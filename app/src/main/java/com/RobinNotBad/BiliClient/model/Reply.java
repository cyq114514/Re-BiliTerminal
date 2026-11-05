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
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

public class Reply implements Serializable {
    /** 评论排序方式：按时间（发布时间升序，早的在前）。 */
    public static final int SORT_TIME = 0;
    /** 评论排序方式：按点赞数降序。 */
    public static final int SORT_LIKE = 1;

    public long rpid;
    public long oid;
    public long root;
    public long parent;
    public boolean forceDelete;
    public String ofBvid = "";
    /** 发布时间戳（秒，服务端 ctime 原值），只用于排序，不用于展示。 */
    public long ctime;
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
        this.ctime = replyJson.optLong("ctime", 0L);

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

    /**
     * 楼中楼本地排序：按时间或按点赞数重排已加载的子评论。
     *
     * <p>为什么按时间也要真的排：一旦切到热度排序，列表就被本地打乱，再切回时间排序时若只是返回，
     * 顺序就永远停在热度序上。所以两个分支都必须显式重排，才能保证来回切换可逆。
     *
     * @param replies   要排序的列表，允许为 null
     * @param sort      {@link #SORT_TIME} / {@link #SORT_LIKE}
     * @param fromIndex 从这个下标开始排（前面的元素不动）。楼中楼第 0 位是根评论，必须传 1。
     */
    public static void sortReplies(List<Reply> replies, int sort, int fromIndex) {
        if (replies == null) return;
        if (fromIndex < 0) fromIndex = 0;
        if (replies.size() - fromIndex < 2) return;
        Collections.sort(replies.subList(fromIndex, replies.size()), sort == SORT_LIKE ? LIKE_ORDER : TIME_ORDER);
    }

    /** 热度序：点赞数降序。点赞数相同时由稳定排序保住原有（时间）顺序。null 项排到最后。 */
    private static final Comparator<Reply> LIKE_ORDER =
            (a, b) -> {
                if (a == null || b == null) return (a == null ? 1 : 0) - (b == null ? 1 : 0);
                return Integer.compare(b.likeCount, a.likeCount);
            };

    /** 时间序：ctime 升序（早的在前）。键相等时保持输入顺序。null 项排到最后。 */
    private static final Comparator<Reply> TIME_ORDER =
            (a, b) -> {
                if (a == null || b == null) return (a == null ? 1 : 0) - (b == null ? 1 : 0);
                return Long.compare(a.ctime, b.ctime);
            };

    /** 置顶前先把列表里已有的置顶标记清掉：服务端一个评论区只有一个置顶位（再置顶回 12029） */
    public static void clearTopFlags(List<Reply> list) {
        if (list == null) return;
        for (Reply reply : list) {
            if (reply != null && reply.isTop) reply.setTopFlag(false);
        }
    }
}
