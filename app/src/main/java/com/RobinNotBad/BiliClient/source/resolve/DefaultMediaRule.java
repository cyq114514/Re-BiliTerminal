package com.RobinNotBad.BiliClient.source.resolve;

import com.RobinNotBad.BiliClient.source.net.UrlGuard;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 按扩展名识别媒体地址（Kazumi 的做法，也是 Animeko 源的兜底）。
 *
 * <p>只认 .m3u8 / .mp4 / .flv / .mkv / .ts 这几种真实媒体后缀，
 * <b>不</b>把「路径里含 m3u8 字样的页面」当成媒体——那是解析失败最常见的误判来源。
 */
public class DefaultMediaRule implements MediaRule {

    private final String referer;
    private final String userAgent;

    public DefaultMediaRule(String referer, String userAgent) {
        this.referer = referer == null ? "" : referer;
        this.userAgent = userAgent == null ? "" : userAgent;
    }

    @Override
    public String describe() {
        return "默认（按扩展名）";
    }

    @Override
    public String matchVideo(String url) {
        if (url == null) return null;
        String lower = url.toLowerCase(Locale.ROOT);
        int query = lower.indexOf('?');
        String path = query >= 0 ? lower.substring(0, query) : lower;
        if (path.endsWith(".m3u8") || path.endsWith(".mp4") || path.endsWith(".flv")
                || path.endsWith(".mkv") || path.endsWith(".ts")) {
            return UrlGuard.isAllowed(url) ? url : null;
        }
        return null;
    }

    @Override
    public boolean isNestedPage(String url) {
        return false;
    }

    @Override
    public Map<String, String> videoHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        if (!referer.isEmpty()) headers.put("Referer", referer);
        if (!userAgent.isEmpty()) headers.put("User-Agent", userAgent);
        return headers;
    }

    @Override
    public String playPageUserAgent() {
        return userAgent;
    }

    @Override
    public String playPageCookies() {
        return "";
    }
}
