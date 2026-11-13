package com.RobinNotBad.BiliClient.source.rule;

import com.RobinNotBad.BiliClient.source.model.SourceException;
import com.RobinNotBad.BiliClient.source.net.SourceHttp;
import com.RobinNotBad.BiliClient.util.Logu;

import org.jsoup.nodes.Document;

import java.io.IOException;
import java.util.Map;

/**
 * 请求 + 异常归类的公共入口。
 *
 * <p>把「哪个阶段、什么错、算不算可切换」固化成一处，避免每个源各写一遍 try/catch
 * 导致日志口径不一致（出问题时定不到阶段）。日志只打 host 与状态码，
 * 不打完整 URL（query 里可能带 token 类参数）。
 *
 * <p><b>非 2xx 不直接判死</b>：不少站点在 403/404/5xx 上仍返回结构完整的页面
 * （CDN 错误页、反爬壳页之后才是真页面、临时 5xx 但内容已渲染）。Animeko 的
 * {@code PageEvaluator} 把这条写成硬规则——先按 selector 解析，解析出内容就算成功，
 * 即使状态码是 4xx。这里照同一口径：HTTP 状态只当诊断信息，成败由调用方解析结果决定。
 *
 * <p><b>429 退避一次</b>：站点限流时立刻换源只会把所有源都撞一遍；先按
 * {@code Retry-After}（没有就 800ms）等一次再重试，第二次失败才交给上层。
 */
public final class WebFetch {

    /** 429 没给 Retry-After 时的默认等待 */
    private static final long DEFAULT_RETRY_WAIT_MS = 800L;
    /** Retry-After 可能大到几十分钟（有站点直接回 3600），本地退避必须封顶，否则用户以为卡死 */
    private static final long MAX_RETRY_WAIT_MS = 5_000L;

    private WebFetch() {
    }

    /**
     * 页面抓取结果：状态码 + 解析出的 DOM。
     *
     * <p>为什么要把状态码带回来：非 2xx 也允许解析，但最后什么都没解析到时，
     * 上屏原因必须能区分「站点拒绝(403)」和「站点改版(选择器对不上)」。
     */
    public static final class Html {
        public final int code;
        /** 解析失败时为 null */
        public final Document document;
        private final String error;

        Html(int code, Document document, String error) {
            this.code = code;
            this.document = document;
            this.error = error == null ? "" : error;
        }

        public boolean hasDocument() {
            return document != null;
        }

        public boolean httpOk() {
            return code >= 200 && code < 300;
        }

        /** 解析不出内容时可上屏的原因：先报状态码，再报解析错误 */
        public String failureReason() {
            if (code > 0 && !httpOk()) return "HTTP " + code;
            return error.isEmpty() ? "页面解析失败" : error;
        }
    }

    public static SourceHttp.Response get(String url, Map<String, String> headers,
                                          String sourceName, SourceException.Phase phase)
            throws SourceException {
        return get(url, headers, sourceName, phase, 0);
    }

    /**
     * @param timeoutMs 整次调用超时；0 表示用客户端默认（解析阶段用默认，搜索阶段用短超时）
     */
    public static SourceHttp.Response get(String url, Map<String, String> headers,
                                          String sourceName, SourceException.Phase phase, long timeoutMs)
            throws SourceException {
        SourceHttp.Response response = executeGet(url, headers, sourceName, phase, timeoutMs);
        if (response.code == 429) {
            long wait = response.retryAfterMs > 0
                    ? Math.min(response.retryAfterMs, MAX_RETRY_WAIT_MS)
                    : DEFAULT_RETRY_WAIT_MS;
            Logu.w("third", sourceName + " " + phase.label + " 429 限流，退避 " + wait + "ms 后重试一次");
            if (!sleep(wait)) {
                throw new SourceException(phase, SourceException.Kind.NETWORK, sourceName, "已取消");
            }
            response = executeGet(url, headers, sourceName, phase, timeoutMs);
        }
        if (!response.isSuccessful()) {
            //只留证据、不抛：调用方先解析，解析不出再按这个状态码报错
            Logu.w("third", sourceName + " " + phase.label + " HTTP " + response.code + "，继续尝试解析");
        }
        return response;
    }

    private static SourceHttp.Response executeGet(String url, Map<String, String> headers,
                                                  String sourceName, SourceException.Phase phase,
                                                  long timeoutMs) throws SourceException {
        long start = System.currentTimeMillis();
        try {
            SourceHttp.Response response = timeoutMs > 0
                    ? SourceHttp.get(url, headers, timeoutMs)
                    : SourceHttp.get(url, headers);
            long cost = System.currentTimeMillis() - start;
            Logu.d("third", sourceName + " " + phase.label + " " + response.code
                    + " " + cost + "ms " + com.RobinNotBad.BiliClient.source.net.UrlGuard.hostOf(url));
            return response;
        } catch (SecurityException e) {
            Logu.e("third", sourceName + " " + phase.label + " 地址被拒绝：" + e.getMessage());
            throw new SourceException(phase, SourceException.Kind.CONFIG, sourceName,
                    "地址不合法：" + e.getMessage(), e);
        } catch (IOException e) {
            Logu.w("third", sourceName + " " + phase.label + " 网络失败：" + e.getClass().getSimpleName()
                    + " " + e.getMessage());
            throw new SourceException(phase, SourceException.Kind.NETWORK, sourceName,
                    "网络错误：" + describe(e), e);
        }
    }

    /**
     * POST 保持严格语义：调用方（目前只有订阅/接口类）拿的是结构化数据，
     * 非 2xx 的响应体不可信，直接抛比让上层解析一段错误页更清楚。
     */
    public static SourceHttp.Response post(String url, Map<String, String> headers,
                                           String contentType, String body,
                                           String sourceName, SourceException.Phase phase)
            throws SourceException {
        long start = System.currentTimeMillis();
        try {
            SourceHttp.Response response = SourceHttp.post(url, headers, contentType, body);
            long cost = System.currentTimeMillis() - start;
            Logu.d("third", sourceName + " " + phase.label + " " + response.code
                    + " " + cost + "ms " + com.RobinNotBad.BiliClient.source.net.UrlGuard.hostOf(url));
            if (!response.isSuccessful())
                throw SourceException.http(sourceName, phase, response.code);
            return response;
        } catch (SecurityException e) {
            Logu.e("third", sourceName + " " + phase.label + " 地址被拒绝：" + e.getMessage());
            throw new SourceException(phase, SourceException.Kind.CONFIG, sourceName,
                    "地址不合法：" + e.getMessage(), e);
        } catch (IOException e) {
            Logu.w("third", sourceName + " " + phase.label + " 网络失败：" + e.getClass().getSimpleName()
                    + " " + e.getMessage());
            throw new SourceException(phase, SourceException.Kind.NETWORK, sourceName,
                    "网络错误：" + describe(e), e);
        }
    }

    /**
     * 抓页面并解析成 DOM，<b>不因非 2xx 抛异常</b>；解析失败时返回的 {@link Html#document} 为 null。
     *
     * <p>调用方拿到结果后自己决定：解析出内容就照常走；什么都没解析到再用
     * {@link Html#failureReason()} 报错（非 2xx 会报状态码，否则报解析错误）。
     */
    public static Html getHtml(String url, Map<String, String> headers,
                               String sourceName, SourceException.Phase phase, long timeoutMs)
            throws SourceException {
        SourceHttp.Response response = get(url, headers, sourceName, phase, timeoutMs);
        try {
            Document document = response.parseHtml();
            if (document == null) return new Html(response.code, null, "空文档");
            return new Html(response.code, document, "");
        } catch (Throwable t) {
            //jsoup 解析异常不是网络问题：把状态码一起带回去，便于判断是不是站点回了错误页
            return new Html(response.code, null, "HTML 解析失败：" + t.getMessage());
        }
    }

    /** @return false 表示等待被中断（页面已退出），调用方应尽快结束 */
    private static boolean sleep(long ms) {
        if (ms <= 0) return true;
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String describe(IOException e) {
        String message = e.getMessage();
        if (message == null || message.isEmpty()) return e.getClass().getSimpleName();
        if (message.length() > 80) return message.substring(0, 80);
        return message;
    }
}
