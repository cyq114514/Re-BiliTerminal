package com.RobinNotBad.BiliClient.source.resolve;

import com.RobinNotBad.BiliClient.source.ThirdPartySource;
import com.RobinNotBad.BiliClient.source.model.SourceChannel;
import com.RobinNotBad.BiliClient.source.model.SourceEpisode;
import com.RobinNotBad.BiliClient.source.model.SourceException;
import com.RobinNotBad.BiliClient.source.model.SourceInfo;
import com.RobinNotBad.BiliClient.source.model.SourceMedia;
import com.RobinNotBad.BiliClient.source.model.SourceSubject;
import com.RobinNotBad.BiliClient.source.net.SourceHttp;
import com.RobinNotBad.BiliClient.source.net.UrlGuard;
import com.RobinNotBad.BiliClient.source.rule.RuleLimits;
import com.RobinNotBad.BiliClient.source.rule.WebFetch;
import com.RobinNotBad.BiliClient.util.Logu;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把「一集」解析成可播放的 {@link SourceMedia}。
 *
 * <p>只做不需要 WebView 的部分：抓剧集页 HTML → 扫地址 → 命中即返回；命不中再跟进嵌套页。
 * 需要 WebView 的情况返回 {@link Outcome#needWebView}，由调用方决定要不要走
 * {@link WebViewSniffer}（默认关，见方案 D1）。
 *
 * <p><b>阻塞调用</b>：必须在后台线程执行。
 */
public final class MediaResolver {

    private MediaResolver() {
    }

    public static final class Outcome {
        public SourceMedia media;
        /** 失败原因（人类可读，不含完整 URL） */
        public String reason = "";
        /** true 表示「纯 HTTP 这一级确实找不到，需要网页嗅探才有机会」 */
        public boolean needWebView;
        /** 扫到的嵌套播放页，供 WebView 直接加载 */
        public final List<String> nestedUrls = new java.util.ArrayList<>();
    }

    public static Outcome resolve(SourceInfo info, ThirdPartySource source, SourceSubject subject,
                                  SourceChannel channel, SourceEpisode episode) {
        Outcome outcome = new Outcome();
        if (source == null || episode == null) {
            outcome.reason = "参数不完整";
            return outcome;
        }
        MediaRule rule = source.mediaRule();
        String name = info == null ? "" : info.displayName();

        //剧集页里直接给了媒体地址：先验一遍规则，避免把网页当直链
        if (episode.directMediaUrl != null && !episode.directMediaUrl.isEmpty()) {
            String hit = rule.matchVideo(episode.directMediaUrl);
            if (hit != null) {
                outcome.media = build(info, subject, channel, episode, hit, rule);
                reportSuccess(name, outcome.media, "剧集自带直链");
                return outcome;
            }
        }

        String pageUrl = episode.pageUrl == null ? "" : episode.pageUrl.trim();
        if (pageUrl.isEmpty()) {
            outcome.reason = "该集没有页面地址";
            return outcome;
        }
        if (pageUrl.toLowerCase(Locale.ROOT).startsWith("http://")) {
            outcome.reason = "该集是明文 http 地址，本端只支持 https 源";
            return outcome;
        }

        Scanned scanned;
        try {
            scanned = scanPage(pageUrl, rule, name);
        } catch (SourceException e) {
            outcome.reason = e.userMessage();
            Logu.w("third-resolve", "取播放页失败 源=" + name
                    + " 原因=" + outcome.reason + " 页面=" + MediaUrls.summarize(pageUrl));
            return outcome;
        }
        if (scanned.result.mediaUrl != null && !scanned.result.mediaUrl.isEmpty()) {
            outcome.media = build(info, subject, channel, episode, scanned.result.mediaUrl, rule);
            reportSuccess(name, outcome.media, "HTML:" + scanned.result.via);
            return outcome;
        }
        outcome.nestedUrls.addAll(scanned.result.nestedUrls);

        //嵌套播放页：只在规则显式开启时跟进，最多一层。
        //上游允许两层，但每多一层就多一次整页请求（1~3s），用户感知是「点了一直转」，
        //所以这里收紧到一层：一层拿不到就交给网页嗅探，比串行等两层更快。
        int followed = 0;
        for (String nested : scanned.result.nestedUrls) {
            if (followed >= 1) break;
            followed++;
            try {
                Scanned inner = scanPage(nested, rule, name);
                if (inner.result.mediaUrl != null && !inner.result.mediaUrl.isEmpty()) {
                    outcome.media = build(info, subject, channel, episode, inner.result.mediaUrl, rule);
                    reportSuccess(name, outcome.media, "嵌套页:" + inner.result.via);
                    return outcome;
                }
                for (String deeper : inner.result.nestedUrls) {
                    if (!outcome.nestedUrls.contains(deeper)) outcome.nestedUrls.add(deeper);
                }
            } catch (SourceException ignored) {
                //一层失败继续下一层，不把整次解析判死
            }
        }

        //404/410 是「这一集确实没了」，开 WebView 只会白等 15 秒；其余（含 403/5xx）都值得再试
        outcome.needWebView = scanned.code != 404 && scanned.code != 410;
        //非 2xx 也允许走到这里（WebFetch 不再按状态码判死）：但既然没扫出地址，
        //上屏原因要带上状态码，否则「页面里没有地址」会把 403 掩盖成「站点改版」
        outcome.reason = scanned.httpOk()
                ? "页面里没有可直接播放的地址（多数站点要等脚本执行后才有流）"
                : "页面返回 HTTP " + scanned.code + "，且没有可直接播放的地址";
        Logu.w("third-resolve", "解析无结果 源=" + name
                + " HTTP=" + scanned.code
                + " 页面=" + MediaUrls.summarize(pageUrl)
                + " 嵌套候选=" + outcome.nestedUrls.size() + " 需嗅探=" + outcome.needWebView);
        return outcome;
    }

    /** 一次播放页抓取的产物：扫描结果 + HTTP 状态码（用于区分「站点拒绝」与「页面里真的没有流」） */
    private static final class Scanned {
        final HtmlVideoScanner.Result result;
        final int code;

        Scanned(HtmlVideoScanner.Result result, int code) {
            this.result = result;
            this.code = code;
        }

        boolean httpOk() {
            return code >= 200 && code < 300;
        }
    }

    /**
     * 解析结果落 W 级日志：正式包 {@code Logu.LOGD_ENABLED=false}，V/D/I 一律不进 logcat，
     * 之前「解析成功但播不了」在真机上没有任何一行可查的证据。
     * 只输出 host + path 前缀，不输出 query（第三方流地址常带 token）。
     */
    private static void reportSuccess(String sourceName, SourceMedia media, String via) {
        if (media == null) return;
        Logu.w("third-resolve", "解析成功 源=" + sourceName
                + " via=" + via
                + " url=" + MediaUrls.summarize(media.mediaUrl)
                + " 头=" + media.headers.keySet()
                + " mime=" + media.mimeType);
    }

    private static Scanned scanPage(String url, MediaRule rule, String sourceName)
            throws SourceException {
        Map<String, String> headers = new LinkedHashMap<>();
        String ua = rule.playPageUserAgent();
        if (ua != null && !ua.isEmpty()) headers.put("User-Agent", ua);
        String origin = UrlGuard.originOf(url);
        if (!origin.isEmpty()) headers.put("Referer", origin + "/");
        String cookies = rule.playPageCookies();
        if (cookies != null && !cookies.trim().isEmpty())
            headers.put("Cookie", cookies.trim().replace('\n', ';'));
        //刻意拿原始文本而不是 jsoup 重新序列化后的 DOM：脚本里的 `\/`、未闭合标签
        //会在序列化时被改写，正则反而抓不到原样的地址
        SourceHttp.Response response = WebFetch.get(url, headers, sourceName,
                SourceException.Phase.RESOLVE, RuleLimits.RESOLVE_PAGE_TIMEOUT_MS);
        return new Scanned(HtmlVideoScanner.scan(response.text(), url, rule), response.code);
    }

    /**
     * 把「已确认可播的地址」包装成 {@link SourceMedia}（给 WebView 嗅探用）。
     *
     * @return 地址未通过规则校验时返回 null
     */
    public static SourceMedia fromUrl(SourceInfo info, SourceSubject subject, SourceEpisode episode,
                                      String mediaUrl, MediaRule rule) {
        if (rule == null) return null;
        String hit = rule.matchVideo(mediaUrl);
        if (hit == null) return null;
        SourceMedia media = build(info, subject, null, episode, hit, rule);
        reportSuccess(info == null ? "" : info.displayName(), media, "网页嗅探");
        return media;
    }

    private static SourceMedia build(SourceInfo info, SourceSubject subject, SourceChannel channel,
                                     SourceEpisode episode, String mediaUrl, MediaRule rule) {
        SourceMedia media = new SourceMedia();
        media.mediaUrl = mediaUrl;
        media.mimeType = mimeOf(mediaUrl);
        media.pageUrl = episode.pageUrl;
        media.sourceId = info == null ? "" : info.id;
        media.subjectKey = subject == null ? "" : subject.key();
        media.episodeId = episode.id();
        if (rule != null) {
            Map<String, String> headers = rule.videoHeaders();
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet())
                    media.addHeader(entry.getKey(), entry.getValue());
            }
        }
        //播放页地址本身是最可靠的 Referer：很多站点只校验「同站」
        if (!media.headers.containsKey("Referer") && episode.pageUrl != null && !episode.pageUrl.isEmpty()) {
            String origin = UrlGuard.originOf(episode.pageUrl);
            if (!origin.isEmpty()) media.addHeader("Referer", origin + "/");
        }
        return media;
    }

    private static String mimeOf(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (lower.contains(".m3u8")) return "application/vnd.apple.mpegurl";
        if (lower.contains(".mp4")) return "video/mp4";
        if (lower.contains(".flv")) return "video/x-flv";
        if (lower.contains(".mkv")) return "video/x-matroska";
        return "";
    }
}
