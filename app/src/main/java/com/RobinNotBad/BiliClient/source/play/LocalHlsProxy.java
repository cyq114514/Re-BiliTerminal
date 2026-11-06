package com.RobinNotBad.BiliClient.source.play;

import com.RobinNotBad.BiliClient.source.net.UrlGuard;
import com.RobinNotBad.BiliClient.source.resolve.MediaUrls;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.CookieJar;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 第三方加密 HLS 的 loopback 代理。
 *
 * <p><b>解决什么问题</b>：ijk（k0.8.8 / FFmpeg 3.4）打不开
 * {@code crypto+https://cdn:9999/...ts} 这种「AES-128 加密 + 非 443 端口」的分片——
 * 真机日志里 {@code Opening 'crypto+https://…'} 与 {@code Failed to open segment}
 * 出现在同一毫秒，连 DNS 都没发起；而同一 URL 用 curl 是 200 / 1.88MB / TLS1.2 正常。
 * 这是播放器内核的协议层能力边界，不是地址失效也不是防盗链。
 *
 * <p><b>怎么做</b>：把 m3u8 里的 key URI 与所有分片 URL 改写成
 * {@code http://127.0.0.1:<port>/f/<token>}，ijk 改播本地 m3u8；
 * 代理收到 {@code /f/<token>} 时用 OkHttp 带原始 UA/Referer/Cookie 转发真实资源。
 * 这样分片变成 {@code crypto+http://127.0.0.1}，绕开内核不支持的组合。
 * 多级 m3u8（{@code #EXT-X-STREAM-INF}）在转发时递归改写，同样能走。
 *
 * <p><b>安全边界</b>：只绑 127.0.0.1；客户端只能按 token 取「本次注册过的 URL」，
 * 不接受任意地址（防 SSRF / 内网探测）；转发前仍过 {@link UrlGuard}；
 * 日志只打 host+path，不输出 query 里的 token。
 *
 * <p>只在第三方 HLS 播放时启用；B 站链路与普通 mp4 直链零改动。启动失败一律回退原地址。
 */
public final class LocalHlsProxy {

    private static final String TAG = "third-hls";
    private static final int MAX_SESSIONS = 8;
    private static final long MAX_FORWARD_BYTES = 16L * 1024 * 1024;

    private static final LocalHlsProxy INSTANCE = new LocalHlsProxy();

    public static LocalHlsProxy get() {
        return INSTANCE;
    }

    private final OkHttpClient client = NetWorkUtil.setOkHttpSsl(new OkHttpClient.Builder())
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .dns(new NetWorkUtil.Inet4Selector())
            .build();

    private static final long PLAYLIST_CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final int PLAYLIST_CACHE_MAX = 16;

    private final AtomicLong ids = new AtomicLong();
    private final ConcurrentHashMap<String, Route> routes = new ConcurrentHashMap<>();
    private final LinkedHashMap<String, Session> sessions = new LinkedHashMap<>();
    /** 同一集重看：省掉一次 m3u8 网络往返；改写仍每次重做，保证 token 绑定当前会话 */
    private final LinkedHashMap<String, CachedPlaylist> playlistCache = new LinkedHashMap<>();

    private ServerSocket server;
    private int port = -1;
    private ExecutorService workers;

    private LocalHlsProxy() {
    }

    private static final class Route {
        final String url;
        final Map<String, String> headers;

        Route(String url, Map<String, String> headers) {
            this.url = url;
            this.headers = headers;
        }
    }

    private static final class Session {
        String id;
        String origin;
        Map<String, String> headers;
        byte[] playlist;
        final Set<String> tokens = new HashSet<>();
    }

    private static final class CachedPlaylist {
        final String body;
        final long at;

        CachedPlaylist(String body, long at) {
            this.body = body;
            this.at = at;
        }
    }

    /**
     * 把第三方 m3u8 变成本地可播地址。
     *
     * @return 本地 URL；非 HLS / 任何一步失败时原样返回 {@code mediaUrl}（调用方无需分支处理）
     */
    public String rewrite(String mediaUrl, Map<String, String> headers) {
        if (mediaUrl == null || !mediaUrl.toLowerCase(Locale.ROOT).contains(".m3u8")) {
            return mediaUrl;
        }
        try {
            ensureServer();
            String body;
            synchronized (playlistCache) {
                CachedPlaylist cached = playlistCache.get(mediaUrl);
                if (cached != null && System.currentTimeMillis() - cached.at < PLAYLIST_CACHE_TTL_MS) {
                    body = cached.body;
                } else {
                    playlistCache.remove(mediaUrl);
                    body = null;
                }
            }
            if (body == null) {
                body = fetchText(mediaUrl, headers);
                if (body != null && body.contains("#EXTM3U")) {
                    synchronized (playlistCache) {
                        playlistCache.put(mediaUrl, new CachedPlaylist(body, System.currentTimeMillis()));
                        while (playlistCache.size() > PLAYLIST_CACHE_MAX) {
                            String oldest = playlistCache.keySet().iterator().next();
                            playlistCache.remove(oldest);
                        }
                    }
                }
            }
            if (body == null || !body.contains("#EXTM3U")) return mediaUrl;

            Session session = new Session();
            session.id = Long.toHexString(ids.incrementAndGet());
            session.origin = mediaUrl;
            session.headers = copyHeaders(headers);
            String rewritten = HlsPlaylist.rewrite(body, mediaUrl,
                    absolute -> routeFor(absolute, session));
            session.playlist = rewritten.getBytes("UTF-8");

            registerSession(session);
            String local = "http://127.0.0.1:" + port + "/p/" + session.id + "/index.m3u8";
            Logu.w(TAG, "HLS 代理就绪 原=" + MediaUrls.summarize(mediaUrl)
                    + " 映射=" + session.tokens.size() + " 本地=" + local);
            return local;
        } catch (Throwable t) {
            //代理是增强能力，起不来就退回原来的直连，绝不让「播不了」变成「解析失败」
            Logu.w(TAG, "HLS 代理启动失败，回退直连：" + t.getClass().getSimpleName()
                    + " " + (t.getMessage() == null ? "" : t.getMessage()));
            return mediaUrl;
        }
    }

    // ---------------------------------------------------------------- 路由登记
    // 播放列表改写本身在 HlsPlaylist（纯 Java，可单测）里，这里只负责把 token 落到路由表。

    /** 登记会话并淘汰最旧的，顺手清掉它名下的路由，避免 token 无限累积 */
    private void registerSession(Session session) {
        synchronized (sessions) {
            sessions.put(session.id, session);
            while (sessions.size() > MAX_SESSIONS) {
                String oldest = sessions.keySet().iterator().next();
                Session removed = sessions.remove(oldest);
                if (removed != null) {
                    for (String token : removed.tokens) routes.remove(token);
                }
            }
        }
    }

    /** 登记一个「本地 token → 真实 URL」，返回本地路径 */
    private String routeFor(String remoteUrl, Session session) {
        String token = Long.toHexString(ids.incrementAndGet());
        routes.put(token, new Route(remoteUrl, session.headers));
        session.tokens.add(token);
        return "http://127.0.0.1:" + port + "/f/" + token;
    }

    // ---------------------------------------------------------------- HTTP 服务

    private synchronized void ensureServer() throws IOException {
        if (server != null && !server.isClosed()) return;
        server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        port = server.getLocalPort();
        workers = Executors.newFixedThreadPool(4, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "hls-proxy-worker");
                thread.setDaemon(true);
                return thread;
            }
        });
        Thread accept = new Thread(() -> {
            while (server != null && !server.isClosed()) {
                try {
                    final Socket socket = server.accept();
                    socket.setSoTimeout(30_000);
                    workers.execute(() -> handle(socket));
                } catch (IOException e) {
                    if (server == null || server.isClosed()) return;
                }
            }
        }, "hls-proxy-accept");
        accept.setDaemon(true);
        accept.start();
    }

    private void handle(Socket socket) {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), "ISO-8859-1"));
            String requestLine = reader.readLine();
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String path = parts[1];

            if (path.startsWith("/p/")) {
                String id = path.substring(3);
                int slash = id.indexOf('/');
                if (slash >= 0) id = id.substring(0, slash);
                Session session;
                synchronized (sessions) {
                    session = sessions.get(id);
                }
                if (session == null) {
                    writeError(socket.getOutputStream(), 404, "session gone");
                } else {
                    writeOk(socket.getOutputStream(), "application/vnd.apple.mpegurl",
                            session.playlist);
                }
            } else if (path.startsWith("/f/")) {
                forward(path.substring(3), socket.getOutputStream());
            } else {
                writeError(socket.getOutputStream(), 404, "not found");
            }
        } catch (Throwable t) {
            Logu.w(TAG, "本地请求处理失败：" + t.getClass().getSimpleName());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 转发真实资源；若返回的是另一级 m3u8，继续改写，支持多级播放列表 */
    private void forward(String token, OutputStream out) throws IOException {
        Route route = routes.get(token);
        if (route == null || UrlGuard.check(route.url) != null) {
            writeError(out, 403, "unknown route");
            return;
        }
        Request.Builder builder = new Request.Builder().url(route.url);
        for (Map.Entry<String, String> entry : route.headers.entrySet()) {
            if (entry.getValue() != null) builder.header(entry.getKey(), entry.getValue());
        }
        Response response = null;
        try {
            response = client.newCall(builder.build()).execute();
            if (!response.isSuccessful()) {
                writeError(out, response.code(), "upstream " + response.code());
                return;
            }
            ResponseBody body = response.body();
            if (body == null) {
                writeError(out, 502, "empty body");
                return;
            }
            if (body.contentLength() > MAX_FORWARD_BYTES) {
                writeError(out, 502, "segment too large");
                return;
            }
            byte[] bytes = body.bytes();
            String contentType = response.header("Content-Type", "application/octet-stream");
            if (HlsPlaylist.isPlaylist(contentType, bytes)) {
                Session session = new Session();
                session.id = "sub" + token;
                session.headers = route.headers;
                String rewritten = HlsPlaylist.rewrite(new String(bytes, "UTF-8"), route.url,
                        absolute -> routeFor(absolute, session));
                registerSession(session);
                writeOk(out, "application/vnd.apple.mpegurl", rewritten.getBytes("UTF-8"));
            } else {
                writeOk(out, contentType, bytes);
            }
        } finally {
            if (response != null) response.close();
        }
    }

    // ---------------------------------------------------------------- 工具

    private String fetchText(String url, Map<String, String> headers) throws IOException {
        Request.Builder builder = new Request.Builder().url(url);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getValue() != null) builder.header(entry.getKey(), entry.getValue());
        }
        Response response = client.newCall(builder.build()).execute();
        try {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) return null;
            return new String(body.bytes(), "UTF-8");
        } finally {
            response.close();
        }
    }

    private static Map<String, String> copyHeaders(Map<String, String> source) {
        Map<String, String> copy = new HashMap<>();
        if (source != null) copy.putAll(source);
        return copy;
    }

    private static void writeOk(OutputStream out, String contentType, byte[] bytes)
            throws IOException {
        StringBuilder head = new StringBuilder(128);
        head.append("HTTP/1.1 200 OK\r\n")
                .append("Content-Type: ").append(contentType).append("\r\n")
                .append("Content-Length: ").append(bytes.length).append("\r\n")
                .append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes("US-ASCII"));
        out.write(bytes);
        out.flush();
    }

    private static void writeError(OutputStream out, int code, String message) throws IOException {
        byte[] bytes = message.getBytes("US-ASCII");
        out.write(("HTTP/1.1 " + code + " proxy\r\nContent-Length: " + bytes.length
                + "\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
        out.write(bytes);
        out.flush();
    }
}
