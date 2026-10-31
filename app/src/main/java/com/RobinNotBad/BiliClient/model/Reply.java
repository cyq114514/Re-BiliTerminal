package com.RobinNotBad.BiliClient.model;

import static com.RobinNotBad.BiliClient.api.ReplyApi.TOP_TIP;

import android.text.SpannableStringBuilder;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.util.EmoteUtil;
import com.RobinNotBad.BiliClient.util.JsonUtil;
import com.RobinNotBad.BiliClient.util.StringUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

public class Reply implements Serializable {
    public long rpid;
    public long oid;
    public long root;
    public long parent;
    public boolean forceDelete;
    public String ofBvid = "";
    public String pubTime;
    public UserInfo sender;
    public CharSequence message;
    public ArrayList<String> pictureList = new ArrayList<>();
    public int likeCount;
    public boolean upLiked;
    public boolean upReplied;
    public boolean liked;
    public int childCount;
    public boolean isDynamic;
    public ArrayList<Reply> childMsgList = new ArrayList<>();
    public boolean isTop;

    public Reply() {
    }

    /**
     * @param isRoot    是否是根评论
     * @param replyJson 评论json对象
     * @throws JSONException json解析异常
     */
    public Reply(boolean isRoot, JSONObject replyJson) throws JSONException {
        this.rpid = replyJson.optLong("rpid", 0);
        this.oid = replyJson.optLong("oid", 0);
        this.root = replyJson.optLong("root", 0);
        this.parent = replyJson.optLong("parent", 0);

        JSONObject memberJson = replyJson.optJSONObject("member");
        if (memberJson == null) {
            this.sender = new UserInfo();
            this.sender.name = "已注销用户";
        } else {
            this.sender = new UserInfo(memberJson);
        }

        JSONObject content = replyJson.optJSONObject("content");
        if (content == null) throw new JSONException("content is null");

        JSONObject replyCtrl = replyJson.optJSONObject("reply_control");
        long ctime = replyJson.optLong("ctime", 0) * 1000;

        String time;
        if (replyCtrl != null && System.currentTimeMillis() - ctime < 3 * 24 * 60 * 60 * 1000 && replyCtrl.has("time_desc")) {
            time = replyCtrl.optString("time_desc", "");
        } else {
            time = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE).format(ctime);
        }

        if (replyCtrl != null && replyCtrl.has("location")) {
            String loc = replyCtrl.optString("location", "");
            if (loc.length() > 5) time += " | IP:" + loc.substring(5);
        }
        this.pubTime = time;

        if (replyCtrl != null && replyCtrl.optBoolean("is_up_top", false)) {
            this.isTop = true;
        }

        SpannableStringBuilder messageSpannable = new SpannableStringBuilder((isTop ? TOP_TIP : "")
                + StringUtil.htmlToString(content.optString("message", "")));

        if (isTop) StringUtil.setTopSpan(messageSpannable);

        this.likeCount = replyJson.optInt("like", 0);
        this.liked = replyJson.optInt("action", 0) == 1;

        if (content.has("emote") && !content.isNull("emote")) {
            ArrayList<Emote> emoteList = new ArrayList<>();
            JSONObject emoteJson = content.getJSONObject("emote");
            ArrayList<String> emoteKeys = JsonUtil.getJsonKeys(emoteJson);

            for (String emoteKey : emoteKeys) {
                JSONObject key = emoteJson.getJSONObject(emoteKey);
                emoteList.add(new Emote(
                        emoteKey,
                        key.getString("url"),
                        key.getJSONObject("meta").getInt("size")
                ));
            }

            EmoteUtil.textReplaceEmote(messageSpannable.toString(), emoteList, 1.0f, BiliTerminal.context, messageSpannable);
        }

        StringUtil.setLink(messageSpannable);

        if (content.has("at_name_to_mid") && !content.isNull("at_name_to_mid")) {
            JSONObject jsonObject = content.getJSONObject("at_name_to_mid");
            Iterator<String> keys = jsonObject.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                long val = jsonObject.getLong(key);
                StringUtil.setSingleAt(messageSpannable, key, val);
            }
        }

        JSONObject upAction = replyJson.optJSONObject("up_action");
        this.upLiked = upAction != null && upAction.optBoolean("like", false);
        this.upReplied = upAction != null && upAction.optBoolean("reply", false);


        if (isRoot) {
            if (content.has("pictures") && !content.isNull("pictures")) {
                ArrayList<String> pictureList = new ArrayList<>();
                JSONArray pictures = content.getJSONArray("pictures");
                for (int j = 0; j < pictures.length(); j++) {
                    JSONObject picture = pictures.getJSONObject(j);
                    //评论列表接口的图片字段是img_src，评论发布接口(/x/v2/reply/create)返回的是img_url，做兼容
                    String picUrl = picture.optString("img_src", "");
                    if (picUrl.isEmpty()) picUrl = picture.optString("img_url", "");
                    if (!picUrl.isEmpty()) pictureList.add(picUrl);
                }
                this.pictureList = pictureList;
            }

            this.childCount = replyJson.optInt("rcount", 0);

            if (replyJson.has("replies") && !replyJson.isNull("replies")) {
                ArrayList<Reply> childMsgList = new ArrayList<>();
                JSONArray childReplies = replyJson.getJSONArray("replies");
                for (int j = 0; j < childReplies.length(); j++) {
                    JSONObject childReply = childReplies.getJSONObject(j);
                    childMsgList.add(new Reply(false, childReply));
                }
                this.childMsgList = childMsgList;
            }
        }

        this.message = messageSpannable;
    }

    /**
     * 本地切换置顶标记。
     *
     * <p>「[置顶]」前缀是构造时拼进 {@link #message} 的（还带一个粉色 span），只改 {@link #isTop}
     * 界面不会变。这里用 insert/delete 而不是重建字符串：SpannableStringBuilder 插删会自动平移
     * 已有的表情/链接 span，重建则会全部丢掉。
     */
    public void setTopFlag(boolean top) {
        if (this.isTop == top) return;
        if (!(message instanceof SpannableStringBuilder)) return;
        SpannableStringBuilder builder = (SpannableStringBuilder) message;
        if (top) {
            builder.insert(0, TOP_TIP);
            StringUtil.setTopSpan(builder);
        } else if (builder.toString().startsWith(TOP_TIP)) {
            builder.delete(0, TOP_TIP.length());
        }
        this.isTop = top;
    }

    /** 置顶前先把列表里已有的置顶标记清掉：服务端一个评论区只有一个置顶位（再置顶回 12029） */
    public static void clearTopFlags(List<Reply> list) {
        if (list == null) return;
        for (Reply reply : list) {
            if (reply != null && reply.isTop) reply.setTopFlag(false);
        }
    }
}
