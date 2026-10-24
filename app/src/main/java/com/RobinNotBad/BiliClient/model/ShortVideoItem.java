package com.RobinNotBad.BiliClient.model;

import org.json.JSONObject;

import java.util.ArrayList;

/**
 * 竖屏短视频信息流的一条内容。
 *
 * <p>字段来源是 {@code /x/v2/feed/index/story} 的 {@code data.items[]}：接口同时存在
 * 「卡片形态」（{@code player_args} / {@code args} / {@code owner} / {@code stat}）和
 * 「老竖屏形态」（{@code goto=vertical_av} + 顶层字段）两套结构，这里逐个字段按优先级兜底，
 * 缺字段时退化成空串/0，不抛异常（缺字段在这个接口上是常态，见取流文档）。
 *
 * <p>移植自 Re：哔哩终端（Kotlin 版）的 {@code ShortVideoItem}，字段与过滤规则保持一致，
 * 这样两边的去重、横屏过滤、播放地址缓存的语义才对得上。
 */
public class ShortVideoItem {

    public long aid = 0;
    public String bvid = "";
    public long cid = 0;
    public String title = "";
    public String cover = "";
    /** 时长：接口给的是秒（卡片形态）或毫秒（部分响应），播放进度不依赖它 */
    public long duration = 0;
    public String authorName = "";
    public String authorFace = "";
    public long authorMid = 0;
    public String viewCount = "";
    public String danmakuCount = "";
    public long likeCount = 0;
    /** 收藏数：操作栏「收藏」按钮下面显示的数字（同 stat.favorite） */
    public long favoriteCount = 0;
    /** 评论数：操作栏「评论」按钮下面显示的数字（同 stat.reply） */
    public long replyCount = 0;
    public String desc = "";
    /** 会话内的点赞/收藏态：-1 未知，0 未点，1 已点。存在条目上而不是 ViewHolder 上，
     *  这样滑走再滑回来图标不会被 bind() 复位成"没点过"（看起来像点赞/收藏没生效） */
    public int likeState = -1;
    public int favState = -1;

    // ---- 会话内缓存：取过一次播放地址就不再重复请求 ----
    public String videoUrl = "";
    /**
     * 同一清晰度的多个播放地址（官方 CDN 优先、PCDN 兜底，见
     * {@link com.RobinNotBad.BiliClient.api.PlayerApi#buildMediaCandidates}）。
     * 单个地址连不上时换下一个，比"整条判死"友好得多。
     */
    public ArrayList<String> videoUrlList = new ArrayList<>();
    public String danmakuUrl = "";
    public String[] qnStrList = null;
    public int[] qnValueList = null;
    public int currentQuality = 0;

    /** 只按 aid 判等：同一稿件换清晰度时仍然是同一条，不应该被当成新内容 */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ShortVideoItem)) return false;
        ShortVideoItem other = (ShortVideoItem) o;
        return aid == other.aid && cid == other.cid;
    }

    @Override
    public int hashCode() {
        return (int) (aid ^ (aid >>> 32)) * 31 + (int) (cid ^ (cid >>> 32));
    }

    /**
     * 从信息流卡片解析一条；不是视频卡（横幅卡、广告、直播卡等）返回 null。
     *
     * <p>过滤口径与参考实现一致：只认 {@code goto} 为 {@code av} / {@code vertical_av} 的卡，
     * 并跳过 {@code card_type=banner_v8} 的横幅。
     */
    public static ShortVideoItem fromFeedItem(JSONObject json) {
        if (json == null) return null;
        String gotoValue = json.optString("goto", json.optString("card_goto", ""));
        if (!"av".equals(gotoValue) && !"vertical_av".equals(gotoValue)) return null;
        if ("banner_v8".equals(json.optString("card_type", ""))) return null;

        JSONObject playerArgs = json.optJSONObject("player_args");
        JSONObject args = json.optJSONObject("args");
        JSONObject owner = json.optJSONObject("owner");
        JSONObject stat = json.optJSONObject("stat");

        long aid = 0;
        if (playerArgs != null) aid = playerArgs.optLong("aid", 0);
        else if (args != null) aid = args.optLong("aid", 0);
        else aid = parseLong(json.optString("param", "0"));
        if (aid <= 0) return null;

        ShortVideoItem item = new ShortVideoItem();
        item.aid = aid;
        item.cid = playerArgs != null ? playerArgs.optLong("cid", 0) : 0;
        item.bvid = json.optString("bvid", args != null ? args.optString("bvid", "") : "");
        item.title = json.optString("title", "");
        item.cover = json.optString("cover", "");
        item.desc = json.optString("desc", json.optString("talk_back", ""));

        if (playerArgs != null && playerArgs.has("duration")) item.duration = playerArgs.optLong("duration", 0);
        else item.duration = json.optLong("duration", 0);

        if (owner != null) {
            item.authorName = owner.optString("name", "");
            item.authorMid = owner.optLong("mid", 0);
            item.authorFace = owner.optString("face", "");
        } else if (args != null) {
            item.authorName = args.optString("up_name", "");
            item.authorMid = args.optLong("up_id", 0);
        } else {
            JSONObject descButton = json.optJSONObject("desc_button");
            item.authorName = descButton != null ? descButton.optString("text", "") : "";
        }

        if (stat != null) {
            item.viewCount = formatCount(stat.optLong("view", 0));
            item.danmakuCount = formatCount(stat.optLong("danmaku", 0));
            item.likeCount = stat.optLong("like", 0);
            //B 站 stat 的标准字段：favorite=收藏数、reply=评论数（部分卡片只给 view/like，缺就保持 0）
            item.favoriteCount = stat.optLong("favorite", 0);
            item.replyCount = stat.optLong("reply", 0);
        } else {
            if (json.has("cover_left_text_1")) item.viewCount = json.optString("cover_left_text_1", "");
            else if (json.has("sub_title")) item.viewCount = json.optString("sub_title", "");
            else if (json.has("view_content")) item.viewCount = json.optString("view_content", "");
            if (json.has("cover_left_text_2")) item.danmakuCount = json.optString("cover_left_text_2", "");
        }
        return item;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** 1.3万 / 2.4亿：与列表页 {@link com.RobinNotBad.BiliClient.util.StringUtil#toWan} 的口径一致但保留一位小数 */
    public static String formatCount(long count) {
        if (count <= 0) return "";
        if (count >= 100000000L) return (count / 100000000L) + "." + ((count % 100000000L) / 10000000L) + "亿";
        if (count >= 10000L) return (count / 10000L) + "." + ((count % 10000L) / 1000L) + "万";
        return String.valueOf(count);
    }
}
