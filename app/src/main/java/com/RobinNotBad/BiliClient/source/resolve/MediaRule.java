package com.RobinNotBad.BiliClient.source.resolve;

import java.util.Map;

/**
 * 「如何从播放页的请求里认出视频地址」—— 两套规则各有自己的实现。
 *
 * <p>Animeko：`matchVideoUrl` 正则 + `(?<v>...)` 命名组 + `matchNestedUrl` 嵌套页；
 * Kazumi：没有视频正则，靠 WebView 里拦截 m3u8/mp4（含 iframe 解包）。
 * 把两者都收在这个接口后面，解析器（HTML/WebView）就不必区分规则来源。
 */
public interface MediaRule {

    /** 用于日志/错误信息的规则名 */
    String describe();

    /**
     * 判断一个请求 URL 是否是最终媒体。
     *
     * @return 媒体地址（可能从 URL 参数里抽取），不是媒体返回 null
     */
    String matchVideo(String url);

    /** 是否是需要继续加载的嵌套播放页（只在规则显式开启时返回 true） */
    boolean isNestedPage(String url);

    /** 播放请求应带的头（Referer / User-Agent / Cookie …） */
    Map<String, String> videoHeaders();

    /** 加载播放页的 WebView 应使用的 UA；空表示用平台默认 */
    String playPageUserAgent();

    /** 加载播放页前要注入的 Cookie（每行一个 name=value）；空表示不注入 */
    String playPageCookies();
}
