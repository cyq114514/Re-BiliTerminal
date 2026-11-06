package com.RobinNotBad.BiliClient.source.rule;

import com.RobinNotBad.BiliClient.source.model.SourceException;
import com.RobinNotBad.BiliClient.source.net.SourceHttp;
import com.RobinNotBad.BiliClient.util.Logu;

import java.io.IOException;
import java.util.Map;

/**
 * 请求 + 异常归类的公共入口。
 *
 * <p>把「哪个阶段、什么错、算不算可切换」固化成一处，避免每个源各写一遍 try/catch
 * 导致日志口径不一致（出问题时定不到阶段）。日志只打 host 与状态码，
 * 不打完整 URL（query 里可能带 token 类参数）。
 */
public final class WebFetch {

    private WebFetch() {
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
        long start = System.currentTimeMillis();
        try {
            SourceHttp.Response response = timeoutMs > 0
                    ? SourceHttp.get(url, headers, timeoutMs)
                    : SourceHttp.get(url, headers);
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

    public static org.jsoup.nodes.Document getHtml(String url, Map<String, String> headers,
                                                  String sourceName, SourceException.Phase phase)
            throws SourceException {
        return getHtml(url, headers, sourceName, phase, 0);
    }

    public static org.jsoup.nodes.Document getHtml(String url, Map<String, String> headers,
                                                   String sourceName, SourceException.Phase phase,
                                                   long timeoutMs)
            throws SourceException {
        SourceHttp.Response response = get(url, headers, sourceName, phase, timeoutMs);
        try {
            org.jsoup.nodes.Document document = response.parseHtml();
            if (document == null) throw new IOException("空文档");
            return document;
        } catch (Throwable t) {
            throw new SourceException(phase, SourceException.Kind.PARSE, sourceName,
                    "HTML 解析失败：" + t.getMessage(), t);
        }
    }

    private static String describe(IOException e) {
        String message = e.getMessage();
        if (message == null || message.isEmpty()) return e.getClass().getSimpleName();
        if (message.length() > 80) return message.substring(0, 80);
        return message;
    }
}
