package com.RobinNotBad.BiliClient.source.resolve;

import com.RobinNotBad.BiliClient.source.net.UrlGuard;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从播放页 HTML 里找视频地址（不需要 WebView 的那一级）。
 *
 * <p>三层来源，按「可信度」顺序试：
 * <ol>
 *   <li>MacCMS 的 {@code player_aaaa} 配置（国内采集站最常见）；</li>
 *   <li>{@code <video src>} / {@code data-*} 这类属性；</li>
 *   <li>内联脚本里的 `url:"..."` 与裸的绝对地址。</li>
 * </ol>
 *
 * <p>只返回通过 {@link MediaRule#matchVideo(String)} 且 {@link MediaUrls#isDirectMedia}
 * 判定为媒体地址的结果；网页地址改投 {@code nestedUrls}。不再「第一条命中即返回」——
 * 页面里排在前面的常常是脚本/播放器链接，那是最常见的「看着像成功」的假阳性。
 */
public final class HtmlVideoScanner {

    private static final Pattern ATTR = Pattern.compile(
            "(?i)(?:src|data-src|data-video|data-url|data-play|data-original|href)\\s*=\\s*[\"']([^\"']{1,2048})[\"']");
    private static final Pattern SCRIPT = Pattern.compile(
            "(?i)(?:url|source|src|file|video|play|m3u8|mp4|hls)\\s*[:=]\\s*[\"']([^\"']{1,2048})[\"']");
    //必须有一个捕获组：collect() 统一取 group(1)，没有组时 matcher.group(1) 会抛
    //IndexOutOfBoundsException: No group 1（这里踩过一次，真机直接崩）
    private static final Pattern ABSOLUTE = Pattern.compile("(?i)(https?://[^\\s\"'<>\\\\]{6,2048})");

    /** 单页最多检查多少个候选，防止畸形页面把低端机拖死 */
    private static final int MAX_CANDIDATES = 2000;

    private HtmlVideoScanner() {
    }

    public static final class Result {
        /** 命中的媒体地址；空表示没找到 */
        public String mediaUrl = "";
        /** 命中来源（日志用，不上屏） */
        public String via = "";
        /** 需要继续跟进的嵌套播放页（只在规则开启 enableNestedUrl 时有值） */
        public final List<String> nestedUrls = new ArrayList<>();
    }

    public static Result scan(String html, String pageUrl, MediaRule rule) {
        Result result = new Result();
        if (html == null || html.isEmpty() || rule == null) return result;

        //① MacCMS 配置：先解出来，命中就直接返回
        MacCmsPlayer.Result macCms = MacCmsPlayer.parse(html);
        if (macCms.url != null && !macCms.url.isEmpty()) {
            String normalized = normalize(macCms.url, pageUrl);
            String hit = rule.matchVideo(normalized);
            if (hit != null) {
                result.mediaUrl = hit;
                result.via = "player_aaaa";
                return result;
            }
            addNested(result, rule, normalized);
        }

        Set<String> candidates = new LinkedHashSet<>();
        collect(ATTR, html, candidates);
        collect(SCRIPT, html, candidates);
        collect(ABSOLUTE, html, candidates);

        //② 先把全部真直链收齐再挑最好的一条，而不是「第一条命中即返回」。
        //   采集站 HTML 里 <script src>/播放器链接通常排在真流前面，按出现顺序取会稳定拿到网页地址。
        String best = "";
        int bestRank = Integer.MAX_VALUE;
        int checked = 0;
        for (String raw : candidates) {
            if (++checked > MAX_CANDIDATES) break;
            String normalized = normalize(raw, pageUrl);
            if (normalized.isEmpty()) continue;
            String hit = rule.matchVideo(normalized);
            if (hit != null) {
                int rank = MediaUrls.rank(hit);
                if (rank < bestRank) {
                    bestRank = rank;
                    best = hit;
                }
                continue;
            }
            addNested(result, rule, normalized);
        }
        if (!best.isEmpty()) {
            result.mediaUrl = best;
            result.via = "html";
        }
        return result;
    }

    private static void collect(Pattern pattern, String html, Set<String> out) {
        Matcher matcher = pattern.matcher(html);
        int count = 0;
        while (matcher.find() && count++ < MAX_CANDIDATES) {
            //防御：正则没有捕获组时退回整段匹配，绝不让一条正则写错把整次解析炸掉
            String value = matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
            if (value != null && !value.isEmpty()) out.add(value);
        }
    }

    private static void addNested(Result result, MediaRule rule, String url) {
        if (result.nestedUrls.size() >= 6) return;
        if (url == null || url.isEmpty()) return;
        //规则判定的嵌套页继续收；另外把「URL 里提到 m3u8 但 path 是网页」的也收进来跟进，
        //否则它们既当不了直链、又被丢掉，本来能跟进的播放页就彻底没了
        if (!rule.isNestedPage(url) && !MediaUrls.looksLikePlaybackPage(url)) return;
        if (!result.nestedUrls.contains(url)) result.nestedUrls.add(url);
    }

    /**
     * 把页面里抠出来的字符串变成可请求的绝对地址。
     *
     * <p>要处理的脏数据比想象多：JS 里的 `\/`、JSON 转义的 `\u002F`、HTML 实体 `&amp;`、
     * 协议相对 `//host/path`。任何一条没处理，得到的都是 404 或「URL 非法」。
     */
    static String normalize(String raw, String pageUrl) {
        if (raw == null) return "";
        String text = raw.trim();
        if (text.isEmpty()) return "";
        text = text.replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("\\u002f", "/")
                .replace("&amp;", "&")
                .replace("&#38;", "&");
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("javascript:") || lower.startsWith("data:")
                || lower.startsWith("blob:") || lower.startsWith("about:")) {
            return "";
        }
        if (lower.startsWith("//")) return "https:" + text;
        if (lower.startsWith("http://") || lower.startsWith("https://")) return text;
        if (text.startsWith("/")) return UrlGuard.resolveFromRoot(pageUrl, text);
        return "";
    }
}
