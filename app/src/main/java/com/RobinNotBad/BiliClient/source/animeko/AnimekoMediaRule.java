package com.RobinNotBad.BiliClient.source.animeko;

import com.RobinNotBad.BiliClient.source.net.UrlGuard;
import com.RobinNotBad.BiliClient.source.resolve.MediaRule;
import com.RobinNotBad.BiliClient.source.resolve.MediaUrls;
import com.RobinNotBad.BiliClient.source.rule.SafeRegex;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Animeko 的 matchVideo 规则：先判嵌套页，再按 matchVideoUrl 取视频地址。
 *
 * <p>与上游一致的细节：正则命中且存在命名分组 {@code v} 时取分组值，
 * 否则取<b>整个 URL</b>（不是匹配到的片段）—— 这一点写错会得到半截地址。
 */
public class AnimekoMediaRule implements MediaRule {

    private final AnimekoSelectorConfig config;
    private final SafeRegex nestedRegex;
    private final SafeRegex videoRegex;

    public AnimekoMediaRule(AnimekoSelectorConfig config) {
        this.config = config;
        this.nestedRegex = SafeRegex.compile(config.search.matchVideo.matchNestedUrl);
        this.videoRegex = SafeRegex.compile(config.search.matchVideo.matchVideoUrl);
    }

    @Override
    public String describe() {
        return "Animeko matchVideo";
    }

    @Override
    public String matchVideo(String url) {
        if (url == null || url.isEmpty()) return null;
        if (isNestedPage(url)) return null;
        if (videoRegex == null) return null;
        if (!videoRegex.matches(url)) return null;
        String group = videoRegex.group(url, "v");
        String candidate = group != null && !group.isEmpty() ? group : url;
        if (!UrlGuard.isAllowed(candidate)) return null;
        //正则只要求「URL 里出现 m3u8/mp4 字样」，player.php?v=x.m3u8 也会命中。
        //再按 path 扩展名收一道：不是真媒体地址一律返回 null，交给调用方当嵌套页跟进。
        if (!MediaUrls.isDirectMedia(candidate)) return null;
        return candidate;
    }

    @Override
    public boolean isNestedPage(String url) {
        if (!config.search.matchVideo.enableNestedUrl) return false;
        if (nestedRegex == null || url == null) return false;
        return nestedRegex.matches(url);
    }

    @Override
    public Map<String, String> videoHeaders() {
        Map<String, String> headers = new LinkedHashMap<>(config.videoHeaders());
        // 只有规则显式给了 Cookie 才带，绝不带本机的 B 站登录态
        String cookies = config.search.matchVideo.cookies;
        if (cookies != null && !cookies.trim().isEmpty()) {
            headers.put("Cookie", cookies.trim().replace('\n', ';'));
        }
        return headers;
    }

    @Override
    public String playPageUserAgent() {
        return config.search.userAgent == null ? "" : config.search.userAgent.trim();
    }

    @Override
    public String playPageCookies() {
        return config.search.matchVideo.cookies == null ? "" : config.search.matchVideo.cookies.trim();
    }
}
