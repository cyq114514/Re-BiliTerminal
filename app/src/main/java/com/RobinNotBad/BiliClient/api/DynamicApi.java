package com.RobinNotBad.BiliClient.api;

import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.util.Pair;

import androidx.annotation.NonNull;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.model.ApiResponse;
import com.RobinNotBad.BiliClient.model.ArticleCard;
import com.RobinNotBad.BiliClient.model.At;
import com.RobinNotBad.BiliClient.model.Dynamic;
import com.RobinNotBad.BiliClient.model.DynamicVote;
import com.RobinNotBad.BiliClient.model.Emote;
import com.RobinNotBad.BiliClient.model.LiveRoom;
import com.RobinNotBad.BiliClient.model.Stats;
import com.RobinNotBad.BiliClient.model.UserInfo;
import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.model.VoteInfo;
import com.RobinNotBad.BiliClient.util.DmImgParamUtil;
import com.RobinNotBad.BiliClient.util.EmoteUtil;
import com.RobinNotBad.BiliClient.util.GsonUtil;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.RobinNotBad.BiliClient.util.StringUtil;
import com.google.gson.annotations.SerializedName;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Response;
import okhttp3.ResponseBody;

public class DynamicApi {

    public static class DynamicListData {
        @SerializedName("has_more")
        public boolean has_more;
        @SerializedName("offset")
        public String offset;
        @SerializedName("update_baseline")
        public long update_baseline;
        @SerializedName("items")
        public List<com.google.gson.JsonElement> items;
    }

    public static class DynamicDetailData {
        @SerializedName("item")
        public com.google.gson.JsonElement item;
    }

    public static class UpdateData {
        @SerializedName("update_num")
        public int update_num;
    }

    public static class PortalData {
        @SerializedName("up_list")
        public List<UpInfo> up_list;
    }

    public static long publishTextContent(String content) throws IOException {
        String url = "https://api.vc.bilibili.com/dynamic_svr/v1/dynamic_svr/create";
        Response resp = Objects.requireNonNull(NetWorkUtil.post(url, new NetWorkUtil.FormData()
                .put("dynamic_id", 0).put("type", 4).put("rid", 0).put("content", content)
                .put("csrf", SharedPreferencesUtil.getString("csrf", "")).toString(), NetWorkUtil.webHeaders));
        try {
            ResponseBody body = resp.body();
            if (body == null) return -1;
            ApiResponse<DynamicIdData> r = GsonUtil.fromJson(body.string(), new com.google.gson.reflect.TypeToken<ApiResponse<DynamicIdData>>(){}.getType());
            return (r != null && r.isSuccess() && r.data != null) ? r.data.dynamic_id : -1;
        } catch (Exception ignored) { return -1; }
    }

    public static class DynamicIdData {
        @SerializedName("dynamic_id") public long dynamic_id;
        @SerializedName("dyn_id") public long dyn_id;
    }

    public static long publishComplex(@NonNull JSONArray contents, JSONArray pics, JSONObject option, JSONObject topic, int scene, Map<String, Object> otherArgs) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/dynamic/feed/create/dyn?csrf=" + SharedPreferencesUtil.getString("csrf", "");
        JSONObject reqBody = new JSONObject()
                .put("content", new JSONObject().put("contents", contents))
                .put("scene", scene)
                .put("meta", new JSONObject().put("app_meta", new JSONObject().put("from", "create.dynamic.web").put("mobi_app", "web")));
        if (pics != null && pics.length() > 0) reqBody.put("pics", pics);
        if (option != null && option.length() > 0) reqBody.put("option", option);
        if (topic != null) reqBody.put("topic", topic);
        reqBody = new JSONObject().put("dyn_req", reqBody);
        if (otherArgs != null) for (Map.Entry<String, Object> e : otherArgs.entrySet()) reqBody.put(e.getKey(), e.getValue());
        Logu.v("publishComplex reqBody=" + reqBody);
        Response resp = Objects.requireNonNull(NetWorkUtil.postJson(url, reqBody.toString()));
        try {
            ResponseBody body = resp.body();
            if (body == null) return -1;
            ApiResponse<DynamicIdData> r = GsonUtil.fromJson(body.string(), new com.google.gson.reflect.TypeToken<ApiResponse<DynamicIdData>>(){}.getType());
            return (r != null && r.isSuccess() && r.data != null) ? r.data.dyn_id : -1;
        } catch (Exception e) { MsgUtil.err("发送动态", e); return -1; }
    }

    /**
     * 构造发布contents：先按@拆节点，再把文本节点里的表情拆成type 9节点。
     * emoteTexts为空时表情保持纯文本（与旧版行为一致）。
     */
    public static JSONArray buildContents(String content, Map<String, Long> atUserUid, Set<String> emoteTexts) throws JSONException {
        //转发引用等场景不解析@，允许传null（parseAtContent要求非null）
        JSONArray contents = parseAtContent(content, atUserUid != null ? atUserUid : new HashMap<>());
        if (emoteTexts == null || emoteTexts.isEmpty()) return contents;
        JSONArray result = new JSONArray();
        Pattern emotePattern = Pattern.compile("\\[[^\\[\\]]{1,32}\\]");
        for (int i = 0; i < contents.length(); i++) {
            JSONObject node = contents.optJSONObject(i);
            if (node == null || node.optInt("type", 1) != 1) {
                result.put(node);
                continue;
            }
            String raw = node.optString("raw_text", "");
            Matcher matcher = emotePattern.matcher(raw);
            int pos = 0;
            while (matcher.find()) {
                if (!emoteTexts.contains(matcher.group())) continue;
                if (matcher.start() > pos) result.put(Content.create(raw.substring(pos, matcher.start()), 1, null));
                result.put(Content.create(matcher.group(), 9, null));
                pos = matcher.end();
            }
            if (pos < raw.length()) result.put(Content.create(raw.substring(pos), 1, null));
            else if (pos == 0 && raw.isEmpty()) result.put(node);
        }
        return result;
    }

    /**发布选项：private_pub仅自己可见、close_comment关闭评论、up_choose_comment评论精选、timer_pub_time定时发布(yyyy-MM-dd HH:mm)*/
    public static JSONObject buildPublishOption(boolean privatePub, Integer closeComment, Integer upChooseComment, String timerPubTime) throws JSONException {
        JSONObject option = new JSONObject();
        if (privatePub) option.put("private_pub", true);
        if (closeComment != null) option.put("close_comment", closeComment);
        if (upChooseComment != null) option.put("up_choose_comment", upChooseComment);
        if (timerPubTime != null && !timerPubTime.isEmpty()) option.put("timer_pub_time", timerPubTime);
        return option;
    }

    public static long publishTextContent(String content, Map<String, Long> atUserUid) throws JSONException, IOException {
        return publishComplex(parseAtContent(content, atUserUid), null, null, null, 1, null);
    }

    public static long publishTextContent(String content, Map<String, Long> atUserUid, JSONObject option, Set<String> emoteTexts) throws JSONException, IOException {
        return publishComplex(buildContents(content, atUserUid, emoteTexts), null, option, null, 1, null);
    }

    /**发布带图动态（图片需先经ImageApi.uploadImage上传图床；带图scene=2，参考B站web端行为）*/
    public static long publishImageContent(String content, Map<String, Long> atUserUid, JSONArray pics) throws JSONException, IOException {
        return publishImageContent(content, atUserUid, pics, null, null);
    }

    public static long publishImageContent(String content, Map<String, Long> atUserUid, JSONArray pics, JSONObject option, Set<String> emoteTexts) throws JSONException, IOException {
        return publishComplex(buildContents(content, atUserUid, emoteTexts), (pics != null && pics.length() > 0) ? pics : null, option, null, (pics != null && pics.length() > 0) ? 2 : 1, null);
    }

    /**
     * 构造转发时的自动引用内容：//@原作者: 原动态内容
     * 参考 web 端行为：原作者用type 2节点，原内容里的表情转type 9、@保留type 2。
     * authorContent为原动态的纯文本（表情已是[xxx]形式）。
     */
    private static JSONArray appendRepostQuote(JSONArray userNodes, String authorName, long authorMid, String authorContent, Set<String> emoteTexts) throws JSONException {
        if (authorName == null || authorName.isEmpty()) return userNodes;
        userNodes.put(Content.create("//", 1, null));
        userNodes.put(Content.create("@" + authorName, 2, String.valueOf(authorMid)));
        userNodes.put(Content.create(":", 1, null));
        if (authorContent != null && !authorContent.isEmpty()) {
            JSONArray origNodes = buildContents(authorContent, null, emoteTexts);
            for (int i = 0; i < origNodes.length(); i++) {
                JSONObject node = origNodes.optJSONObject(i);
                if (node != null) userNodes.put(node);
            }
        }
        return userNodes;
    }

    /**转发视频到动态（scene=5）；authorName/authorContent非空时自动构造 //@UP: 标题 的引用*/
    public static long relayVideo(String text, Map<String, Long> atUserUid, long aid) throws JSONException, IOException {
        return relayVideo(text, atUserUid, aid, null, 0, null, null);
    }

    public static long relayVideo(String text, Map<String, Long> atUserUid, long aid, String authorName, long authorMid, String authorContent, Set<String> emoteTexts) throws JSONException, IOException {
        JSONArray contents = text == null ? new JSONArray().put(Content.create("", 1, null)) : buildContents(text, atUserUid, emoteTexts);
        appendRepostQuote(contents, authorName, authorMid, authorContent, emoteTexts);
        return publishComplex(contents, null, null, null, 5, Map.of("web_repost_src", new JSONObject().put("revs_id", new JSONObject().put("dyn_type", 8).put("rid", aid))));
    }

    public static long relayDynamic(String text, long dyid) throws IOException {
        String url = "https://api.vc.bilibili.com/dynamic_repost/v1/dynamic_repost/repost";
        Response resp = Objects.requireNonNull(NetWorkUtil.post(url, new NetWorkUtil.FormData()
                .put("dynamic_id", dyid).put("content", text).put("csrf_token", SharedPreferencesUtil.getString("csrf", "")).toString(), NetWorkUtil.webHeaders));
        try {
            ResponseBody body = resp.body();
            if (body == null) return -1;
            ApiResponse<DynamicIdData> r = GsonUtil.fromJson(body.string(), new com.google.gson.reflect.TypeToken<ApiResponse<DynamicIdData>>(){}.getType());
            return (r != null && r.isSuccess() && r.data != null) ? r.data.dynamic_id : -1;
        } catch (Exception ignored) { return -1; }
    }

    /**转发动态（scene=4）；authorName/authorContent非空时自动构造 //@原作者: 原内容 的引用*/
    public static long relayDynamic(String text, Map<String, Long> atUserUid, long dyid) throws JSONException, IOException {
        return relayDynamic(text, atUserUid, dyid, null, 0, null, null);
    }

    public static long relayDynamic(String text, Map<String, Long> atUserUid, long dyid, String authorName, long authorMid, String authorContent, Set<String> emoteTexts) throws JSONException, IOException {
        JSONArray contents = text == null ? new JSONArray().put(Content.create("", 1, null)) : buildContents(text, atUserUid, emoteTexts);
        appendRepostQuote(contents, authorName, authorMid, authorContent, emoteTexts);
        return publishComplex(contents, null, null, null, 4, Map.of("web_repost_src", new JSONObject().put("dyn_id_str", String.valueOf(dyid))));
    }

    public static JSONArray parseAtContent(String content, Map<String, Long> atUserUid) throws JSONException {
        JSONArray contentJSONArray = new JSONArray();
        Set<Pair<Integer, Integer>> indexes = new HashSet<>();
        Map<Pair<Integer, Integer>, Long> uidIndexes = new HashMap<>();
        for (Map.Entry<String, Long> entry : atUserUid.entrySet()) {
            Pattern pattern = Pattern.compile("@" + entry.getKey() + " ");
            Matcher matcher = pattern.matcher(content);
            List<Pair<Integer, Integer>> mIndex = new ArrayList<>();
            while (matcher.find()) {
                Pair<Integer, Integer> pair = new Pair<>(matcher.start(), matcher.end());
                mIndex.add(pair);
                uidIndexes.put(pair, entry.getValue());
            }
            indexes.addAll(mIndex);
        }
        ArrayList<Pair<Integer, Integer>> indexesList = new ArrayList<>(indexes);
        int pos = 0;
        for (Pair<Integer, Integer> index : indexesList) {
            String sub = content.substring(pos, index.first);
            if (!sub.isEmpty()) contentJSONArray.put(Content.create(sub, 1, null));
            contentJSONArray.put(Content.create(content.substring(index.first, index.second), 2, String.valueOf(uidIndexes.get(index))));
            pos = index.second;
        }
        String sub = content.substring(pos);
        if (!sub.isEmpty()) contentJSONArray.put(Content.create(sub, 1, null));
        if (indexesList.isEmpty()) contentJSONArray.put(Content.create(content, 1, null));
        return contentJSONArray;
    }

    public static int likeDynamic(long dyid, boolean up) throws IOException {
        String url = "https://api.vc.bilibili.com/dynamic_like/v1/dynamic_like/thumb";
        Response resp = Objects.requireNonNull(NetWorkUtil.post(url, new NetWorkUtil.FormData()
                .put("dynamic_id", dyid).put("up", up ? 1 : 2).put("csrf_token", SharedPreferencesUtil.getString("csrf", "")).toString(), NetWorkUtil.webHeaders));
        try {
            ResponseBody body = resp.body();
            if (body == null) return -1;
            return GsonUtil.fromJson(body.string(), ApiResponse.class).code;
        } catch (Exception e) { return -1; }
    }

    public static int deleteDynamic(long dyid) throws IOException {
        String url = "https://api.vc.bilibili.com/dynamic_svr/v1/dynamic_svr/rm_dynamic";
        Response resp = Objects.requireNonNull(NetWorkUtil.post(url, new NetWorkUtil.FormData()
                .put("dynamic_id", dyid).put("csrf_token", SharedPreferencesUtil.getString("csrf", "")).toString(), NetWorkUtil.webHeaders));
        try {
            ResponseBody body = resp.body();
            if (body == null) return -1;
            return GsonUtil.fromJson(body.string(), ApiResponse.class).code;
        } catch (Exception ignored) { return -1; }
    }

    /**置顶/取消置顶自己的动态（新版 /x/dynamic/feed/space 接口，表单字段为dyn_str）*/
    public static int setTop(long dyid, boolean top) throws IOException {
        String url = "https://api.bilibili.com/x/dynamic/feed/space/" + (top ? "set_top" : "rm_top")
                + "?csrf=" + SharedPreferencesUtil.getString("csrf", "");
        Response resp = Objects.requireNonNull(NetWorkUtil.post(url, new NetWorkUtil.FormData()
                .put("dyn_str", String.valueOf(dyid)).toString(), NetWorkUtil.webHeaders));
        try {
            ResponseBody body = resp.body();
            if (body == null) return -1;
            return GsonUtil.fromJson(body.string(), ApiResponse.class).code;
        } catch (Exception ignored) { return -1; }
    }

    /**切换动态可见范围：privatePub=true 仅自己可见，false 所有人可见*/
    public static int setPrivatePub(long dyid, boolean privatePub) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/dynamic/feed/dyn/private_pub_setting?platform=web&csrf=" + SharedPreferencesUtil.getString("csrf", "");
        JSONObject body = new JSONObject()
                .put("object_id", new JSONObject().put("dyn_id", String.valueOf(dyid)).toString())
                .put("action", privatePub ? "private_pub" : "public_pub");
        Response resp = Objects.requireNonNull(NetWorkUtil.postJson(url, body.toString()));
        try {
            ResponseBody respBody = resp.body();
            if (respBody == null) return -1;
            return GsonUtil.fromJson(respBody.string(), ApiResponse.class).code;
        } catch (Exception ignored) { return -1; }
    }

    /**获取投票详情（data根级含vote_info与my_votes）*/
    public static VoteInfo getVoteInfo(long voteId) throws IOException, JSONException {
        JSONObject root = NetWorkUtil.getJson("https://api.bilibili.com/x/vote/vote_info?vote_id=" + voteId);
        int code = root.optInt("code", -1);
        if (code != 0) throw new JSONException("获取投票失败：" + root.optString("message", String.valueOf(code)));
        return VoteInfo.fromSeparatedJson(root.optJSONObject("data"));
    }

    /**参与投票，votes为选项下标(0起)列表，返回刷新后的投票信息*/
    public static VoteInfo doVote(long voteId, List<Integer> votes, boolean anonymous, long dynamicId) throws IOException, JSONException {
        String csrf = SharedPreferencesUtil.getString("csrf", "");
        String url = "https://api.bilibili.com/x/vote/do_vote?csrf=" + csrf;
        JSONArray voteArr = new JSONArray();
        for (int v : votes) voteArr.put(v);
        JSONObject body = new JSONObject()
                .put("vote_id", voteId)
                .put("votes", voteArr)
                .put("voter_uid", SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0))
                .put("status", anonymous ? 1 : 0)
                .put("op_bit", 0)
                .put("dynamic_id", dynamicId)
                .put("csrf_token", csrf)
                .put("csrf", csrf);
        JSONObject root = new JSONObject(Objects.requireNonNull(NetWorkUtil.postJson(url, body.toString()).body()).string());
        int code = root.optInt("code", -1);
        if (code != 0) throw new JSONException("投票失败：" + root.optString("message", String.valueOf(code)));
        JSONObject data = root.optJSONObject("data");
        return VoteInfo.fromJson(data != null ? data.optJSONObject("vote_info") : null);
    }

    /**
     * 话题动态流（/x/polymer/web-dynamic/v1/feed/topic）。
     * 响应结构与全站流不同：data.topic_card_list[].dynamic_card_item 才是动态本体，
     * topic_type 为 fold 的折叠卡无 dynamic_card_item，跳过。
     */
    public static long getTopicDynamicList(List<Dynamic> dynamicList, long offset, long topicId) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/topic?topic_id=" + topicId
                + "&page_size=20&source=Web"
                + (offset == 0 ? "" : "&offset=" + offset);
        String json = NetWorkUtil.getJson(ConfInfoApi.signWBI(DmImgParamUtil.getDmImgParamsUrl(url))).toString();
        ApiResponse<TopicFeedData> resp = GsonUtil.fromJson(json, new com.google.gson.reflect.TypeToken<ApiResponse<TopicFeedData>>(){}.getType());
        if (resp == null || !resp.isSuccess() || resp.data == null) throw new JSONException("获取话题动态失败");
        long offset_new;
        try {
            offset_new = resp.data.has_more && resp.data.offset != null ? Long.parseLong(resp.data.offset) : -1;
        } catch (NumberFormatException e) {
            offset_new = -1;
        }
        if (resp.data.topic_card_list != null) {
            for (com.google.gson.JsonElement card : resp.data.topic_card_list) {
                if (card == null) continue;
                try {
                    JSONObject cardJson = new JSONObject(card.toString());
                    JSONObject dynItem = cardJson.optJSONObject("dynamic_card_item");
                    if (dynItem != null) dynamicList.add(analyzeDynamic(dynItem));
                } catch (Exception ignored) {}
            }
        }
        return offset_new;
    }

    public static class TopicFeedData {
        @SerializedName("has_more")
        public boolean has_more;
        @SerializedName("offset")
        public String offset;
        @SerializedName("topic_card_list")
        public List<com.google.gson.JsonElement> topic_card_list;
    }

    /**编辑自己的文字动态（不支持图片/转发的编辑）；走新版 /x/dynamic/feed/edit/dyn，需WBI签名*/
    public static long editDynamic(long dyid, String content, Set<String> emoteTexts) throws IOException, JSONException {
        String uploadId = SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) + "_"
                + (System.currentTimeMillis() / 1000) + "_" + (int) (Math.random() * 9000 + 1000);
        //query参数与web端一致（w_开头的是参与WBI签名的请求元信息）
        String url = "https://api.bilibili.com/x/dynamic/feed/edit/dyn?platform=web&csrf=" + SharedPreferencesUtil.getString("csrf", "")
                + "&x-bili-device-req-json=" + java.net.URLEncoder.encode("{\"platform\":\"web\",\"device\":\"pc\",\"spmid\":\"333.1368\"}", "UTF-8")
                + "&w_dyn_req.upload_id=" + java.net.URLEncoder.encode(uploadId, "UTF-8")
                + "&w_dyn_req.meta=" + java.net.URLEncoder.encode("{\"app_meta\":{\"from\":\"create.dynamic.web\",\"mobi_app\":\"web\"}}", "UTF-8");
        JSONObject reqBody = new JSONObject()
                .put("content", new JSONObject().put("contents", buildContents(content, null, emoteTexts)))
                .put("scene", 1)
                .put("upload_id", uploadId)
                .put("meta", new JSONObject().put("app_meta", new JSONObject().put("from", "create.dynamic.web").put("mobi_app", "web")));
        JSONObject body = new JSONObject()
                .put("dyn_req", reqBody)
                .put("dyn_id_str", String.valueOf(dyid));
        Logu.v("editDynamic body=" + body);
        Response resp = Objects.requireNonNull(NetWorkUtil.postJson(ConfInfoApi.signWBI(url), body.toString()));
        try {
            ResponseBody respBody = resp.body();
            if (respBody == null) return -1;
            ApiResponse<DynamicIdData> r = GsonUtil.fromJson(respBody.string(), new com.google.gson.reflect.TypeToken<ApiResponse<DynamicIdData>>(){}.getType());
            return (r != null && r.isSuccess()) ? dyid : -1;
        } catch (Exception e) { MsgUtil.err("编辑动态", e); return -1; }
    }

    public static long mentionAtFindUser(String name) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/polymer/web-dynamic/v1/mention/search?keyword=" + name;
        String json = NetWorkUtil.getJson(url, NetWorkUtil.webHeaders).toString();
        MentionData data = GsonUtil.fromJson(json, MentionData.class);
        if (data == null || data.data == null || data.data.groups == null) return -1;
        for (MentionGroup group : data.data.groups) {
            if (group == null || group.items == null) continue;
            for (MentionItem item : group.items) {
                if (item != null && name.equals(item.name)) {
                    try { return Long.parseLong(item.uid); } catch (Exception ignored) {}
                }
            }
        }
        return -1;
    }

    public static class MentionData { @SerializedName("data") public MentionDataInner data; }
    public static class MentionDataInner { @SerializedName("groups") public List<MentionGroup> groups; }
    public static class MentionGroup { @SerializedName("items") public List<MentionItem> items; }
    public static class MentionItem { @SerializedName("name") public String name; @SerializedName("uid") public String uid; }

    public static long getDynamicList(List<Dynamic> dynamicList, long offset, long mid, String type) throws IOException, JSONException {
        String url;
        if (mid == 0) {
            url = "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all?type=" + type + (offset == 0 ? "" : "&offset=" + offset) + "&features=" + DYN_FEATURES;
        } else {
            url = "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/space?host_mid=" + mid + "&platform=web&web_location=333.1387&timezone_offset=-480" + (offset == 0 ? "" : "&offset=" + offset) + "&features=" + DYN_FEATURES;
        }
        String json = NetWorkUtil.getJson(ConfInfoApi.signWBI(DmImgParamUtil.getDmImgParamsUrl(url))).toString();
        ApiResponse<DynamicListData> resp = GsonUtil.fromJson(json, new com.google.gson.reflect.TypeToken<ApiResponse<DynamicListData>>(){}.getType());
        if (resp == null || !resp.isSuccess() || resp.data == null) throw new JSONException("获取动态列表失败");

        DynamicListData data = resp.data;
        long offset_new;
        try {
            offset_new = data.has_more && data.offset != null ? Long.parseLong(data.offset) : -1;
        } catch (NumberFormatException e) {
            offset_new = -1;
        }

        if (mid == 0) {
            if (data.update_baseline > -1) SharedPreferencesUtil.putLong("dynamic_update_baseline", data.update_baseline);
            else if (offset_new != -1) SharedPreferencesUtil.putLong("dynamic_update_baseline", offset_new);
        }

        if (data.items != null) {
            for (com.google.gson.JsonElement item : data.items) {
                if (item == null) continue;
                try {
                    dynamicList.add(analyzeDynamic(new JSONObject(item.toString())));
                } catch (Exception ignored) {}
            }
        }
        return offset_new;
    }

    /**动态接口通用的features声明（opus样式渲染等），与web端保持一致可减少风控与兼容问题*/
    public static final String DYN_FEATURES = "itemOpusStyle,listOnlyfans,opusBigCover,onlyfansVote,forwardListHidden,decorationCard,commentsNewVersion,onlyfansAssetsV2,ugcDelete,onlyfansQaCard,avatarAutoTheme,sunflowerStyle,eva3CardOpus,eva3CardVideo,eva3CardComment";

    private static String dynDetailParams() {
        try {
            return "&features=" + DYN_FEATURES + "&timezone_offset=-480&gaia_source=Athena&web_location=333.1330"
                    + "&x-bili-device-req-json=" + java.net.URLEncoder.encode("{\"platform\":\"web\",\"device\":\"pc\",\"spmid\":\"333.1330\"}", "UTF-8");
        } catch (Exception e) {
            return "&features=" + DYN_FEATURES;
        }
    }

    public static Dynamic getDynamic(long id) throws JSONException, IOException {
        String url = "https://api.bilibili.com/x/polymer/web-dynamic/v1/detail?id=" + id + dynDetailParams();
        String json = NetWorkUtil.getJson(url).toString();
        ApiResponse<DynamicDetailData> resp = GsonUtil.fromJson(json, new com.google.gson.reflect.TypeToken<ApiResponse<DynamicDetailData>>(){}.getType());
        if (resp == null || !resp.isSuccess()) {
            String fallbackUrl = "https://api.bilibili.com/x/polymer/web-dynamic/v1/detail?rid=" + id + "&type=2" + dynDetailParams();
            String fallbackJson = NetWorkUtil.getJson(fallbackUrl).toString();
            ApiResponse<DynamicDetailData> fallback = GsonUtil.fromJson(fallbackJson, new com.google.gson.reflect.TypeToken<ApiResponse<DynamicDetailData>>(){}.getType());
            if (fallback == null || !fallback.isSuccess() || fallback.data == null || fallback.data.item == null)
                throw new JSONException("获取动态详情失败");
            return analyzeDynamic(new JSONObject(fallback.data.item.toString()));
        }
        if (resp.data == null || resp.data.item == null) throw new JSONException("data is null");
        return analyzeDynamic(new JSONObject(resp.data.item.toString()));
    }

    public static int checkDynamicUpdate(String type, long updateBaseline) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all/update?type=" + type + "&update_baseline=" + updateBaseline + "&web_location=333.1365";
        String json = NetWorkUtil.getJson(url, NetWorkUtil.webHeaders).toString();
        ApiResponse<UpdateData> resp = GsonUtil.fromJson(json, new com.google.gson.reflect.TypeToken<ApiResponse<UpdateData>>(){}.getType());
        if (resp == null || !resp.isSuccess()) throw new JSONException("检查动态更新失败");
        return resp.data != null ? resp.data.update_num : 0;
    }

    public static Dynamic analyzeDynamic(JSONObject dynamic_json) {
        Dynamic dynamic = new Dynamic();
        try { dynamic.dynamicId = Long.parseLong(dynamic_json.optString("id_str", "0")); } catch (Exception ignored) {}
        dynamic.type = dynamic_json.optString("type", "");

        JSONObject basic = dynamic_json.optJSONObject("basic");
        if (basic != null) {
            try { dynamic.comment_id = Long.parseLong(basic.optString("comment_id_str", "0")); } catch (Exception ignored) {}
            dynamic.comment_type = basic.optInt("comment_type", 0);
        }
        if (dynamic.comment_id == 0) dynamic.comment_id = dynamic.dynamicId;
        if (dynamic.comment_type == 0) dynamic.comment_type = 17;

        JSONObject modules = dynamic_json.optJSONObject("modules");
        if (modules == null) return dynamic;

        UserInfo userInfo = new UserInfo();
        JSONObject module_author = modules.optJSONObject("module_author");
        if (module_author != null) {
            userInfo.mid = module_author.optLong("mid", 0);
            userInfo.name = module_author.optString("name", "");
            userInfo.followed = module_author.optBoolean("following", false) || module_author.optInt("following", 0) == 1;
            userInfo.avatar = module_author.optString("face", "");
            JSONObject vipJson = module_author.optJSONObject("vip");
            if (vipJson != null) userInfo.vip_nickname_color = vipJson.optString("nickname_color", "");
            dynamic.pubTime = module_author.optString("pub_time", "");
            dynamic.pubAction = module_author.optString("pub_action", "");
            dynamic.isTop = module_author.optBoolean("is_top", false) || module_author.optInt("is_top", 0) == 1;
            JSONObject iconBadge = module_author.optJSONObject("icon_badge");
            if (iconBadge != null) dynamic.badgeText = iconBadge.optString("text", "");
        }
        dynamic.userInfo = userInfo;

        if ("DYNAMIC_TYPE_NONE".equals(dynamic.type)) { dynamic.content = "[动态不存在]"; return dynamic; }

        JSONObject module_dynamic = modules.optJSONObject("module_dynamic");
        if (module_dynamic != null) {
            JSONObject desc = module_dynamic.optJSONObject("desc");
            dynamic.content = desc != null ? analyzeTextContent(desc.optJSONArray("rich_text_nodes")) : "";

            JSONObject major = module_dynamic.optJSONObject("major");
            if (major != null) {
                String major_type = major.optString("type", "");
                dynamic.major_type = major_type;
                switch (major_type) {
                    case "MAJOR_TYPE_ARCHIVE": { JSONObject a = major.optJSONObject("archive"); if (a != null) dynamic.major_object = analyzeVideoCard(a); break; }
                    case "MAJOR_TYPE_UGC_SEASON": { JSONObject u = major.optJSONObject("ugc_season"); if (u != null) dynamic.major_object = analyzeVideoCard(u); break; }
                    case "MAJOR_TYPE_PGC": {
                        JSONObject pgc = major.optJSONObject("pgc");
                        if (pgc != null) {
                            VideoCard card = new VideoCard();
                            card.type = "media_bangumi";
                            card.aid = BangumiApi.getMdidFromEpid(pgc.optLong("epid", 0));
                            card.title = pgc.optString("title", "");
                            card.cover = pgc.optString("cover", "");
                            JSONObject stat = pgc.optJSONObject("stat");
                            card.view = stat != null ? stat.optString("play", "0") : "0";
                            dynamic.major_object = card;
                        }
                        break;
                    }
                    case "MAJOR_TYPE_ARTICLE": {
                        JSONObject art = major.optJSONObject("article");
                        if (art != null) {
                            JSONArray covers = art.optJSONArray("covers");
                            dynamic.major_object = new ArticleCard(art.optString("title", ""), art.optLong("id", 0),
                                    (covers != null && covers.length() > 0) ? covers.optString(0, "") : "", "投稿文章", art.optString("label", ""));
                        }
                        break;
                    }
                    case "MAJOR_TYPE_DRAW": {
                        JSONObject draw = major.optJSONObject("draw");
                        if (draw != null) {
                            JSONArray items = draw.optJSONArray("items");
                            ArrayList<String> pics = new ArrayList<>();
                            if (items != null) for (int i = 0; i < items.length(); i++) { JSONObject it = items.optJSONObject(i); if (it != null) pics.add(it.optString("src", "")); }
                            dynamic.major_object = pics;
                        }
                        break;
                    }
                    case "MAJOR_TYPE_COMMON": dynamic.content += "\n[无法显示活动类动态的附加内容]"; break;
                    case "MAJOR_TYPE_LIVE_RCMD": {
                        JSONObject lrc = major.optJSONObject("live_rcmd");
                        if (lrc != null) try {
                            JSONObject lpi = new JSONObject(lrc.optString("content", "{}")).optJSONObject("live_play_info");
                            if (lpi != null) { LiveRoom room = new LiveRoom(); room.roomid = lpi.optLong("room_id", 0); room.title = lpi.optString("title", ""); room.cover = lpi.optString("cover", ""); room.online = lpi.optInt("online", 0); dynamic.major_object = room; }
                        } catch (Exception ignored) {}
                        dynamic.content = (TextUtils.isEmpty(dynamic.content) ? "" : dynamic.content + "\n"); break;
                    }
                    case "MAJOR_TYPE_LIVE": {
                        JSONObject live = major.optJSONObject("live");
                        if (live != null) { LiveRoom room = new LiveRoom(); room.roomid = live.optLong("id", 0); room.title = live.optString("title", ""); room.cover = live.optString("cover", ""); dynamic.major_object = room; }
                        dynamic.content = (TextUtils.isEmpty(dynamic.content) ? "" : dynamic.content + "\n"); break;
                    }
                    case "MAJOR_TYPE_OPUS": {
                        JSONObject opus = major.optJSONObject("opus");
                        if (opus != null) {
                            String title = opus.optString("title", "");
                            if (!TextUtils.isEmpty(title) && !"null".equals(title)) dynamic.title = title;
                            JSONArray pics = opus.optJSONArray("pics");
                            if (pics != null) { ArrayList<String> pl = new ArrayList<>(); for (int i = 0; i < pics.length(); i++) { JSONObject p = pics.optJSONObject(i); if (p != null) pl.add(p.optString("url", "")); } dynamic.major_object = pl; }
                            JSONObject summary = opus.optJSONObject("summary");
                            dynamic.content = summary != null ? analyzeTextContent(summary.optJSONArray("rich_text_nodes")) : "";
                        }
                        break;
                    }
                    default: dynamic.content += "\n[*Re：哔哩终端暂时无法查看此动态的附加内容QwQ|类型：" + major_type + "]"; break;
                }
            }

            JSONObject module_additional = modules.optJSONObject("module_additional");
            if (module_additional != null) {
                String additionalType = module_additional.optString("type", "");
                if ("ADDITIONAL_TYPE_UGC".equals(additionalType)) {
                    dynamic.major_type = "MAJOR_TYPE_ARCHIVE";
                    JSONObject ugc = module_additional.optJSONObject("ugc");
                    if (ugc != null) dynamic.major_object = analyzeVideoCard(ugc);
                } else if ("ADDITIONAL_TYPE_VOTE".equals(additionalType)) {
                    JSONObject voteJson = module_additional.optJSONObject("vote");
                    if (voteJson != null) {
                        String voteTitle = voteJson.optString("title", "");
                        if (voteTitle.isEmpty()) voteTitle = voteJson.optString("desc", "");
                        dynamic.vote = new DynamicVote(voteJson.optLong("vote_id", 0), voteTitle, voteJson.optLong("join_num", 0));
                    }
                }
            }
        }

        JSONObject module_stat = modules.optJSONObject("module_stat");
        if (module_stat != null) {
            //comment/forward/favorite/like一起解析（Stats.fromOpus），点赞状态兼容布尔与STATE_LIKE字符串
            dynamic.stats = Stats.fromOpus(module_stat);
        }

        JSONObject module_more = modules.optJSONObject("module_more");
        if (module_more != null) {
            JSONArray tpi = module_more.optJSONArray("three_point_items");
            if (tpi != null) {
                List<String> types = new ArrayList<>();
                for (int i = 0; i < tpi.length(); i++) { JSONObject it = tpi.optJSONObject(i); if (it != null) types.add(it.optString("type", "")); }
                dynamic.canDelete = types.contains("THREE_POINT_DELETE");
            }
        }

        JSONObject orig = dynamic_json.optJSONObject("orig");
        if (orig != null) dynamic.dynamic_forward = analyzeDynamic(orig);

        return dynamic;
    }

    private static VideoCard analyzeVideoCard(JSONObject json) {
        JSONObject stat = json.optJSONObject("stat");
        return new VideoCard(json.optString("title", ""), "投稿视频", stat != null ? stat.optString("play", "0") : "0",
                json.optString("cover", ""), json.optLong("aid", 0), json.optString("bvid", ""));
    }

    private static SpannableStringBuilder analyzeTextContent(JSONArray rich_text_nodes) {
        if (rich_text_nodes == null) return new SpannableStringBuilder("[动态内容解析异常]");
        ArrayList<Emote> emoteList = new ArrayList<>();
        ArrayList<At> atList = new ArrayList<>();
        //节点自带的可点击区域：{LinkClickableSpan, start, end}，需在EmoteUtil重建文本后统一设置
        ArrayList<Object[]> pendingSpans = new ArrayList<>();
        SpannableStringBuilder content = new SpannableStringBuilder();
        for (int i = 0; i < rich_text_nodes.length(); i++) {
            JSONObject node = rich_text_nodes.optJSONObject(i);
            if (node == null) continue;
            String type = node.optString("type", "");
            String jumpUrl = node.optString("jump_url", "");
            String rid = node.optString("rid", "");
            switch (type) {
                case "RICH_TEXT_NODE_TYPE_EMOJI":
                    content.append(node.optString("text", ""));
                    JSONObject emoji = node.optJSONObject("emoji");
                    if (emoji != null) {
                        //webp_url/gis_url/gif_url依次回退，部分表情包没有icon_url
                        String emojiUrl = emoji.optString("webp_url", "");
                        if (emojiUrl.isEmpty()) emojiUrl = emoji.optString("gif_url", "");
                        if (emojiUrl.isEmpty()) emojiUrl = emoji.optString("icon_url", "");
                        emoteList.add(new Emote(emoji.optString("text", ""), emojiUrl, emoji.optInt("size", 0)));
                    }
                    break;
                case "RICH_TEXT_NODE_TYPE_AT":
                    Pair<Integer, Integer> idx = StringUtil.appendString(content, node.optString("text", ""));
                    atList.add(new At(node.optLong("rid", 0), idx.first, idx.second));
                    break;
                case "RICH_TEXT_NODE_TYPE_TOPIC": {
                    Pair<Integer, Integer> range = StringUtil.appendString(content, node.optString("text", ""));
                    String topicName = node.optString("orig_text", node.optString("text", "")).replaceAll("^#+|#+$", "");
                    if (!topicName.isEmpty())
                        pendingSpans.add(new Object[]{new StringUtil.LinkClickableSpan(node.optString("text", ""), com.RobinNotBad.BiliClient.util.LinkUrlUtil.TYPE_TOPIC, topicName), range.first, range.second});
                    break;
                }
                case "RICH_TEXT_NODE_TYPE_BV": {
                    Pair<Integer, Integer> range = StringUtil.appendString(content, node.optString("text", ""));
                    if (!rid.isEmpty())
                        pendingSpans.add(new Object[]{new StringUtil.LinkClickableSpan(node.optString("text", ""), com.RobinNotBad.BiliClient.util.LinkUrlUtil.TYPE_BVID, rid), range.first, range.second});
                    break;
                }
                case "RICH_TEXT_NODE_TYPE_VOTE": {
                    Pair<Integer, Integer> range = StringUtil.appendString(content, node.optString("text", ""));
                    if (!rid.isEmpty())
                        pendingSpans.add(new Object[]{new StringUtil.LinkClickableSpan(node.optString("text", ""), com.RobinNotBad.BiliClient.util.LinkUrlUtil.TYPE_VOTE, rid), range.first, range.second});
                    break;
                }
                case "RICH_TEXT_NODE_TYPE_WEB":
                case "RICH_TEXT_NODE_TYPE_GOODS":
                case "RICH_TEXT_NODE_TYPE_LOTTERY": {
                    String text = node.optString("orig_text", "");
                    if (text.isEmpty()) text = node.optString("text", "");
                    Pair<Integer, Integer> range = StringUtil.appendString(content, text);
                    if (!jumpUrl.isEmpty())
                        pendingSpans.add(new Object[]{new StringUtil.LinkClickableSpan(text, com.RobinNotBad.BiliClient.util.LinkUrlUtil.TYPE_WEB_URL, jumpUrl), range.first, range.second});
                    break;
                }
                default: content.append(node.optString("text", "")); break;
            }
        }
        EmoteUtil.textReplaceEmote(content.toString(), emoteList, 1.0f, BiliTerminal.context, content);
        for (At at : atList) StringUtil.setSingleAt(content, at);
        for (Object[] pending : pendingSpans)
            content.setSpan((StringUtil.LinkClickableSpan) pending[0], (int) pending[1], (int) pending[2], android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return content;
    }

    public static class Content {
        public static JSONObject create(@NonNull String raw_text, int type, String biz_id) throws JSONException {
            return new JSONObject().put("raw_text", raw_text).put("type", type).put("biz_id", biz_id == null ? "" : biz_id);
        }
    }

    public static List<UpInfo> getRecentUpList() throws IOException, JSONException {
        String json = NetWorkUtil.getJson("https://api.bilibili.com/x/polymer/web-dynamic/v1/portal", NetWorkUtil.webHeaders).toString();
        ApiResponse<PortalData> resp = GsonUtil.fromJson(json, new com.google.gson.reflect.TypeToken<ApiResponse<PortalData>>(){}.getType());
        if (resp == null || !resp.isSuccess()) throw new JSONException("获取最近UP主列表失败");
        List<UpInfo> upList = new ArrayList<>();
        if (resp.data != null && resp.data.up_list != null) upList.addAll(resp.data.up_list);
        return upList;
    }

    public static class UpInfo {
        @SerializedName("mid") public long mid;
        @SerializedName("uname") public String uname;
        @SerializedName("face") public String face;
        @SerializedName("has_update") public boolean has_update;
    }
}
