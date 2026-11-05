package com.RobinNotBad.BiliClient.api;

import com.RobinNotBad.BiliClient.model.Note;
import com.RobinNotBad.BiliClient.model.NoteBlock;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 视频笔记接口（只读）。
 *
 * <p>稿件私有笔记 id 列表：GET /x/note/list/archive；笔记内容：GET /x/note/info。
 * 正文是 Quill delta 序列的 JSON 字符串，这里自带解析，不复用 opus 段落解析。
 */
public class NoteApi {

    private NoteApi() {
    }

    private static String csrf() {
        return NetWorkUtil.getInfoFromCookie("bili_jct",
                SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, ""));
    }

    public static List<String> getNoteIdsOfVideo(long aid) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/note/list/archive?oid=" + aid + "&oid_type=0&csrf=" + csrf();
        JSONObject all = NetWorkUtil.getJson(url);
        int code = all.optInt("code", -1);
        if (code != 0) {
            throw new JSONException(noteErrorMsg(code));
        }
        List<String> ids = new ArrayList<>();
        JSONObject data = all.optJSONObject("data");
        if (data == null) return ids;
        JSONArray array = data.optJSONArray("noteIds");
        if (array == null) return ids;
        for (int i = 0; i < array.length(); i++) {
            String id = array.optString(i, "");
            if (!id.isEmpty()) ids.add(id);
        }
        return ids;
    }

    public static Note getNoteDetail(String noteId) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/note/info?note_id=" + noteId;
        JSONObject all = NetWorkUtil.getJson(url);
        int code = all.optInt("code", -1);
        if (code != 0) {
            throw new JSONException(noteErrorMsg(code));
        }
        JSONObject data = all.optJSONObject("data");
        if (data == null) return new Note();
        return parseNoteDetail(data);
    }

    public static Note parseNoteDetail(JSONObject data) {
        Note note = new Note();
        if (data == null) return note;
        note.noteId = data.optString("note_id_str", data.optString("note_id", ""));
        note.title = data.optString("title", "");
        note.summary = data.optString("summary", "");
        note.blocks = parseBlocks(data.optString("content", ""));
        JSONObject arc = data.optJSONObject("arc");
        if (arc != null) {
            note.aid = arc.optLong("oid", 0);
            note.bvid = arc.optString("bvid", "");
            note.videoTitle = arc.optString("title", "");
            note.videoDesc = arc.optString("desc", "");
        }
        return note;
    }

    public static List<NoteBlock> parseBlocks(String contentJson) {
        List<NoteBlock> blocks = new ArrayList<>();
        if (contentJson == null || contentJson.isEmpty()) return blocks;
        JSONArray array;
        try {
            array = new JSONArray(contentJson);
        } catch (JSONException e) {
            return blocks;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject node = array.optJSONObject(i);
            if (node == null) continue;
            Object insert = node.opt("insert");
            if (insert instanceof JSONObject) {
                NoteBlock block = parseInsertObject((JSONObject) insert);
                if (block != null) blocks.add(block);
                continue;
            }
            if (!(insert instanceof String)) continue;
            NoteBlock block = new NoteBlock();
            block.text = (String) insert;
            JSONObject attributes = node.optJSONObject("attributes");
            if (attributes != null) {
                block.bold = attributes.optBoolean("bold", false);
                block.underline = attributes.optBoolean("underline", false);
                block.strike = attributes.optBoolean("strike", false);
                block.color = attributes.optString("color", "");
                block.background = attributes.optString("background", "");
                block.list = attributes.optString("list", "");
            }
            if (block.text.isEmpty() && block.list.isEmpty()) continue;
            blocks.add(block);
        }
        return blocks;
    }

    private static NoteBlock parseInsertObject(JSONObject insert) {
        JSONObject image = insert.optJSONObject("imageUpload");
        if (image != null) {
            NoteBlock block = new NoteBlock();
            block.type = NoteBlock.TYPE_IMAGE;
            block.imageUrl = image.optString("url", "");
            block.imageWidth = image.optInt("width", 0);
            return block.imageUrl.isEmpty() ? null : block;
        }
        JSONObject tag = insert.optJSONObject("tag");
        if (tag != null) {
            NoteBlock block = new NoteBlock();
            block.type = NoteBlock.TYPE_TAG;
            block.tagCid = tag.optLong("cid", 0);
            block.tagIndex = tag.optInt("index", -1);
            block.tagSeconds = tag.optLong("seconds", 0);
            block.tagTitle = tag.optString("title", "");
            return block;
        }
        return null;
    }

    public static String noteErrorMsg(int code) {
        switch (code) {
            case 0:
                return "";
            case -101:
                return "需要登录才能查看笔记";
            case -400:
                return "请求参数有误";
            case 79502:
                return "没有找到这篇笔记";
            case 79503:
                return "这篇笔记还没有正文";
            case 79514:
                return "没有找到这篇公开笔记";
            default:
                return "获取笔记失败（错误码 " + code + "）";
        }
    }

    public static String failureText(Throwable e) {
        if (e == null) return "获取笔记失败";
        if (e instanceof IOException) return "网络异常，请检查网络后重试";
        if (e instanceof JSONException) {
            String detail = e.getMessage();
            if (detail == null) return "获取笔记失败";
            detail = detail.replace("org.json.JSONException:", "").trim();
            if (detail.isEmpty() || detail.startsWith("获取笔记失败")) return "获取笔记失败";
            return detail;
        }
        return "获取笔记失败";
    }
}
