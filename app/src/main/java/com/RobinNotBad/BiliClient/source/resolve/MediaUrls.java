package com.RobinNotBad.BiliClient.source.resolve;

import java.util.Locale;

/**
 * 播放地址判定与日志脱敏。
 *
 * <p>为什么需要单独一层：Animeko 的 {@code matchVideoUrl} 正则只要求「URL 里任意位置
 * 出现 m3u8/mp4 字样」，实测会把 {@code player.php?v=x.m3u8}、{@code player.js?need=m3u8}
 * 这类**网页地址**也判成视频直链。播放器拿到 HTML 页面只会转圈然后报错——
 * 这正是 {@code SourceMedia} 类注释里明令禁止的「假成功」。
 *
 * <p>判定口径：去掉 query/fragment 后，path 必须以媒体扩展名结尾。
 */
public final class MediaUrls {

    private static final String[] MEDIA_EXTENSIONS = {".m3u8", ".mp4", ".mkv", ".flv"};

    private MediaUrls() {
    }

    /** 只有这类地址能进 {@code SourceMedia.mediaUrl}；query 里带 token 不影响判定 */
    public static boolean isDirectMedia(String url) {
        if (url == null || url.isEmpty()) return false;
        String path = pathOf(url);
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        String lower = path.toLowerCase(Locale.ROOT);
        for (String extension : MEDIA_EXTENSIONS) {
            if (lower.endsWith(extension)) return true;
        }
        return false;
    }

    /** 去掉 query 与 fragment 的 path（不解析 URL，避免引入依赖） */
    private static String pathOf(String url) {
        int end = url.length();
        int query = url.indexOf('?');
        int fragment = url.indexOf('#');
        if (query >= 0) end = query;
        if (fragment >= 0 && fragment < end) end = fragment;
        return url.substring(0, end);
    }

    /** 真直链之间的优先级：HLS 通常才是主线路 */
    public static int rank(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (lower.contains(".m3u8")) return 0;
        if (lower.contains(".mp4")) return 1;
        if (lower.contains(".mkv")) return 2;
        if (lower.contains(".flv")) return 3;
        return 4;
    }

    /**
     * 「URL 里提到媒体、但 path 明显是网页」→ 应该跟进的嵌套播放页，不是直链。
     */
    public static boolean looksLikePlaybackPage(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.contains("m3u8") && !lower.contains(".mp4")
                && !lower.contains(".mkv") && !lower.contains(".flv")) return false;
        String path = pathOf(lower);
        return path.endsWith(".php") || path.endsWith(".html") || path.endsWith(".htm")
                || path.endsWith(".js") || path.endsWith(".shtml")
                || path.endsWith(".asp") || path.endsWith(".jsp")
                || path.contains("/play") || path.contains("player");
    }

    /**
     * 日志用的 URL 摘要：只留 host + path 前缀，**绝不输出 query**
     * （第三方流地址常带 token/签名，写进 logcat 等于泄露）。
     */
    public static String summarize(String url) {
        if (url == null || url.isEmpty()) return "(empty)";
        try {
            okhttp3.HttpUrl parsed = okhttp3.HttpUrl.parse(url);
            if (parsed == null) return "(invalid)";
            String path = parsed.encodedPath();
            if (path.length() > 72) path = path.substring(0, 72) + "…";
            return parsed.host() + path;
        } catch (Throwable t) {
            return "(bad-url)";
        }
    }
}
