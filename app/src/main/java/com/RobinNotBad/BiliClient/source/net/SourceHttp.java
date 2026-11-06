package com.RobinNotBad.BiliClient.source.net;

import com.RobinNotBad.BiliClient.source.rule.RuleLimits;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 第三方站点专用的 HTTP 客户端。
 *
 * <p><b>为什么不能复用 NetWorkUtil</b>：全局客户端会给每个请求注入 B 站 Cookie /
 * Origin / Referer，还会把响应里的 Set-Cookie 合并进全局登录态。拿它请求第三方站点
 * 等于把 SESSDATA 交给对方，而且对方塞的 Cookie 会污染登录会话。
 *
 * <p>本客户端：无 CookieJar、手动校验重定向（每跳都过 {@link UrlGuard}）、
 * 有超时与体积上限、不在日志里输出 Cookie/Token。
 */
public final class SourceHttp {

    private static volatile OkHttpClient client;

    private SourceHttp() {
    }

    public static final class Response {
        public int code;
        public byte[] body = new byte[0];
        public String finalUrl = "";
        /** Content-Type 原文（可能为空） */
        public String contentType = "";

        public String charset() {
            if (contentType == null) return null;
            String lower = contentType.toLowerCase(Locale.ROOT);
            int index = lower.indexOf("charset=");
            if (index < 0) return null;
            String charset = contentType.substring(index + 8).trim();
            int semi = charset.indexOf(';');
            if (semi >= 0) charset = charset.substring(0, semi);
            charset = charset.trim().replace("\"", "");
            return charset.isEmpty() ? null : charset;
        }

        public boolean isSuccessful() {
            return code >= 200 && code < 300;
        }

        /** 用 jsoup 解析（charset 交给 jsoup 从 meta 嗅探，国内站点常见 GBK） */
        public org.jsoup.nodes.Document parseHtml() throws IOException {
            java.io.ByteArrayInputStream stream = new java.io.ByteArrayInputStream(body);
            String charset = charset();
            // charset 传 null 时 jsoup 会按 meta/BOM 自行嗅探，这是国内 GBK 站点能正常解析的关键
            return org.jsoup.Jsoup.parse(stream, charset, finalUrl == null ? "" : finalUrl);
        }

        public String text() {
            String charset = charset();
            try {
                return new String(body, charset == null || charset.isEmpty() ? "UTF-8" : charset);
            } catch (Exception e) {
                try {
                    return new String(body, "UTF-8");
                } catch (Exception ignored) {
                    return "";
                }
            }
        }
    }

    private static OkHttpClient client() {
        if (client != null) return client;
        synchronized (SourceHttp.class) {
            if (client == null) {
                client = NetWorkUtil.setOkHttpSsl(new OkHttpClient.Builder())
                        //重定向手动跟：OkHttp 自动跟跳不做协议/内网校验，会被 302 绕到内网
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .retryOnConnectionFailure(true)
                        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
                        .connectTimeout(RuleLimits.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .readTimeout(RuleLimits.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .writeTimeout(RuleLimits.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .dns(new NetWorkUtil.Inet4Selector())
                        .build();
            }
            return client;
        }
    }

    public static Response get(String url, Map<String, String> headers)
            throws IOException, SecurityException {
        return execute("GET", url, headers, null, null, 0);
    }

    /**
     * 带「整次调用」超时的 GET。
     *
     * <p>为什么需要：readTimeout 是「两次读之间的间隔」，站点慢吞吞吐一个字节就能把
     * 单源拖到 12s 以上；批量搜索 60 个源时这类长尾会把整页拖到一分钟。
     * {@code Call.timeout} 才是「从发起到读完」的总闸。
     */
    public static Response get(String url, Map<String, String> headers, long timeoutMs)
            throws IOException, SecurityException {
        return execute("GET", url, headers, null, null, timeoutMs);
    }

    public static Response post(String url, Map<String, String> headers, String contentType, String body)
            throws IOException, SecurityException {
        return execute("POST", url, headers, contentType, body, 0);
    }

    /**
     * @param timeoutMs 整次调用超时；<=0 表示用客户端默认
     * @throws SecurityException URL 或重定向目标未通过 {@link UrlGuard}
     */
    private static Response execute(String method, String url, Map<String, String> headers,
                                    String contentType, String body, long timeoutMs)
            throws IOException, SecurityException {
        String current = url;
        String currentMethod = method;
        String currentBody = body;

        for (int hop = 0; hop <= 5; hop++) {
            String rejection = UrlGuard.check(current);
            if (rejection != null) throw new SecurityException(rejection + "（" + UrlGuard.hostOf(current) + "）");

            Request.Builder builder = new Request.Builder().url(current);
            Map<String, String> merged = defaultHeaders(headers);
            for (Map.Entry<String, String> entry : merged.entrySet()) {
                if (entry.getValue() == null) continue;
                builder.header(entry.getKey(), entry.getValue());
            }

            if ("POST".equals(currentMethod)) {
                RequestBody requestBody = RequestBody.create(
                        MediaType.parse(contentType == null
                                ? "application/x-www-form-urlencoded; charset=utf-8" : contentType),
                        currentBody == null ? "" : currentBody);
                builder.post(requestBody);
            } else {
                builder.get();
            }

            okhttp3.Response response = null;
            try {
                okhttp3.Call call = client().newCall(builder.build());
                if (timeoutMs > 0) call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS);
                response = call.execute();
                int code = response.code();
                if (code >= 300 && code < 400) {
                    String location = response.header("Location");
                    if (location == null)
                        throw new IOException("HTTP " + code + "（重定向缺少 Location）");
                    String next = UrlGuard.resolveFromDirectory(current, location);
                    //301/302/303 浏览器会降级为 GET；307/308 保留方法与请求体
                    if (code == 301 || code == 302 || code == 303) {
                        currentMethod = "GET";
                        currentBody = null;
                    }
                    Logu.d("third", "重定向 " + code + " → " + UrlGuard.hostOf(next));
                    current = next;
                    continue;
                }
                return readBody(response, current);
            } finally {
                if (response != null) response.close();
            }
        }
        throw new IOException("重定向层数过多");
    }

    private static Response readBody(okhttp3.Response response, String url) throws IOException {
        Response result = new Response();
        result.code = response.code();
        result.finalUrl = url;
        result.contentType = response.header("Content-Type", "");

        ResponseBody body = response.body();
        if (body == null) return result;
        long declared = body.contentLength();
        if (declared > RuleLimits.MAX_PAGE_BYTES)
            throw new IOException("响应过大 " + declared + " 字节");

        InputStream input = body.byteStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                declared > 0 ? (int) Math.min(declared, 64 * 1024) : 8192);
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > RuleLimits.MAX_PAGE_BYTES)
                throw new IOException("响应超过上限 " + RuleLimits.MAX_PAGE_BYTES + " 字节");
            output.write(buffer, 0, read);
        }
        result.body = output.toByteArray();
        return result;
    }

    /**
     * 默认请求头：只补规则没给的部分。
     *
     * <p>刻意不带 Cookie —— 第三方站点没有我们的登录态，
     * 需要 Cookie 的站点由规则自己声明（Kazumi 的 plugin cookie、Animeko 的 matchVideo.cookies）。
     */
    private static Map<String, String> defaultHeaders(Map<String, String> headers) {
        Map<String, String> merged = new LinkedHashMap<>();
        merged.put("User-Agent", NetWorkUtil.USER_AGENT_WEB);
        merged.put("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8");
        merged.put("Accept-Language", "zh-CN,zh;q=0.9,ja;q=0.8");
        merged.put("Cache-Control", "no-store");
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isEmpty()) continue;
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        return merged;
    }
}
