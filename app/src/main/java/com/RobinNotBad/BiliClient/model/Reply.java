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
                    pictureList.add(picture.getString("img_src"));
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
}
