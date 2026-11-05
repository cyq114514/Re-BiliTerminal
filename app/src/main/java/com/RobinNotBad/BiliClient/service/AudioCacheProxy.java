package com.RobinNotBad.BiliClient.service;

import android.content.Context;
import android.util.Log;

import com.RobinNotBad.BiliClient.api.PlayerApi;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 音频本地缓存代理（"听歌快速加载"的落盘那一半）。
 *
 * <p>ijkplayer 没有 media3 的 {@code CacheDataSource}：播放远程地址时，起播、拖动进度条
 * 都会重新建连回源。这里在 127.0.0.1 起一个只服务本进程的 HTTP 代理，ijk 播代理地址，
 * 代理按 Range 先读磁盘缓存、缺的部分回源并边下边存。效果等价于 NeriPlayer 的读穿缓存，
 * 但不需要更换播放器内核。
 *
 * <p><b>只缓存从 0 开始的连续前缀</b>：音频几乎总是顺序播放，前缀覆盖了"拖回已播位置"
 * 这个最常用的场景；跳到未播位置时退回透传，行为与直连完全一致，不会更差。
 *
 * <p>任何异常都只影响当次请求：代理启动失败返回 null，调用方回退直连；单条请求失败直接
 * 关闭连接，由播放器的 onError 换线逻辑接管。
 */
public final class AudioCacheProxy {

    private static final String TAG = "audio-proxy";
    /** 缓存上限 80MB，约 12-18 首（192Kbps AAC，4 分钟约 5.8MB） */
    public static final long MAX_CACHE_BYTES = 80L * 1024 * 1024;
    /** 下一首预取 1.5MB ≈ 62 秒音频，足够起播并撑到顺序下载追上 */
    private static final long PREFETCH_BYTES = 1536 * 1024L;
    private static final int MAX_CONNECTIONS = 4;
    private static final int BUFFER_SIZE = 64 * 1024;

    private static volatile AudioCacheProxy instance;

    /** 取进程内单例；已启动失败时返回的实例 {@link #localUrl} 会返回 null，调用方回退直连 */
    public static AudioCacheProxy get(Context context) {
        AudioCacheProxy proxy = instance;
        if (proxy == null) {
            synchronized (AudioCacheProxy.class) {
                proxy = instance;
                if (proxy == null) {
                    proxy = new AudioCacheProxy(context.getApplicationContext());
                    instance = proxy;
                }
            }
        }
        return proxy;
    }

    private static final class Track {
        final long cid;
        final File file;
        volatile String url;
        /** 全程字节数，0 表示未知 */
        volatile long total;
        volatile long lastAccess;
        final Object writeLock = new Object();

        Track(long cid, File file) {
            this.cid = cid;
            this.file = file;
        }

        long prefix() {
            return file.length();
        }
    }

    private final File cacheDir;
    private final Map<Long, Track> tracks = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(MAX_CONNECTIONS);
    private ServerSocket serverSocket;
    private int port = -1;
    private volatile boolean closed;
    /** 正在播放的 cid：LRU 清理时跳过，避免把当前这首删掉 */
    private volatile long playingCid;
    /**
     * 熔断：代理连续失败 3 次就本进程停用，{@link #localUrl} 返回 null 回退直连。
     * 保证"代理自身有缺陷"最坏只影响几首，而不会让整个听歌功能不可用。
     */
    private volatile boolean circuitOpen;
    private int consecutiveFailures;

    private AudioCacheProxy(Context context) {
        cacheDir = new File(context.getCacheDir(), "audio_cache_v2");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            Log.w(TAG, "缓存目录创建失败: " + cacheDir);
        }
        startServer();
        //索引重建要扫缓存目录，放到后台：本类可能在主线程 get()，不能让磁盘 IO 卡住起播
        Thread indexer = new Thread(() -> {
            rebuildIndex();
            enforceLimit();
        }, "audio-proxy-index");
        indexer.setDaemon(true);
        indexer.start();
    }

    // ==================== 对外接口 ====================

    /**
     * 把远程地址换成代理地址；代理不可用时返回 null。
     *
     * @param cid       稿件分P 的 cid，用作缓存键（URL 会过期，cid 不会）
     * @param sourceUrl 原始播放地址
     */
    public String localUrl(long cid, String sourceUrl) {
        if (circuitOpen || port <= 0 || cid <= 0 || sourceUrl == null || sourceUrl.isEmpty()) return null;
        track(cid, sourceUrl);
        return "http://127.0.0.1:" + port + "/a?cid=" + cid;
    }

    /** 预取下一首的开头 1.5MB，让切歌时第一段直接从磁盘出 */
    public void prefetch(long cid, String sourceUrl) {
        if (port <= 0 || cid <= 0 || sourceUrl == null) return;
        final Track track = track(cid, sourceUrl);
        pool.execute(() -> {
            try {
                long prefix = track.prefix();
                if (prefix >= PREFETCH_BYTES) return;
                appendAndCopy(track, prefix, PREFETCH_BYTES - 1, null);
                diag("预取完成 cid=" + cid + " 已缓存=" + track.prefix() + " 总长=" + track.total);
            } catch (Throwable e) {
                diag("预取失败 cid=" + cid + " : " + e);
            }
        });
    }

    /** 当前正在播的 cid：LRU 清理时保护它 */
    public void setPlayingCid(long cid) {
        playingCid = cid;
    }

    /** 已缓存的总字节数（用于设置页展示/诊断） */
    public long cachedBytes() {
        long total = 0;
        for (Track track : tracks.values()) total += track.file.length();
        return total;
    }

    public void clear() {
        for (Track track : tracks.values()) {
            //noinspection ResultOfMethodCallIgnored
            track.file.delete();
        }
        tracks.clear();
    }

    public void release() {
        closed = true;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Throwable ignored) {
        }
        pool.shutdownNow();
        instance = null;
    }

    // ==================== 服务端 ====================

    private void startServer() {
        try {
            ServerSocket socket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            serverSocket = socket;
            port = socket.getLocalPort();
            Thread acceptor = new Thread(() -> {
                while (!closed) {
                    try {
                        final Socket client = socket.accept();
                        pool.execute(() -> handle(client));
                    } catch (Throwable e) {
                        if (!closed) diag("accept 失败: " + e);
                        break;
                    }
                }
            }, "audio-proxy-accept");
            acceptor.setDaemon(true);
            acceptor.start();
            diag("已启动 127.0.0.1:" + port);
        } catch (Throwable e) {
            diag("启动失败，回退直连: " + e);
            serverSocket = null;
            port = -1;
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(20000);
            InputStream in = socket.getInputStream();
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE);

            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                writeError(out, 400);
                return;
            }

            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    headers.put(line.substring(0, idx).trim().toLowerCase(Locale.US),
                            line.substring(idx + 1).trim());
                }
            }

            long cid = parseCid(parts[1]);
            Track track = cid > 0 ? tracks.get(cid) : null;
            if (track == null || track.url == null) {
                writeError(out, 404);
                return;
            }
            track.lastAccess = System.currentTimeMillis();

            long[] range = parseRange(headers.get("range"));
            serve(track, range[0], range[1], out);
            out.flush();
            noteSuccess();
        } catch (Throwable e) {
            diag("请求失败: " + e);
        } finally {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private void serve(Track track, long start, long end, OutputStream out) throws IOException {
        long prefix = track.prefix();
        diag("请求 cid=" + track.cid + " start=" + start + " end=" + end
                + " prefix=" + prefix + " total=" + track.total);

        //起点不在已缓存前缀里（含空缓存）：透传源站，行为与直连一致
        if (prefix <= 0 || start >= prefix) {
            proxyUpstream(track, start, end, out);
            return;
        }

        long total = track.total;
        if (total <= 0) {
            total = probeTotal(track);
            if (total <= 0) {
                proxyUpstream(track, start, end, out);
                return;
            }
            track.total = total;
        }

        long last = (end < 0 || end >= total) ? total - 1 : end;
        if (last < start) last = start;
        long cachedEnd = Math.min(last, prefix - 1);

        writeHead(out, 206, "Partial Content", last - start + 1,
                "bytes " + start + "-" + last + "/" + total, "audio/mp4", "bytes");

        //① 先吐缓存里的那段
        copyFromFile(track.file, start, cachedEnd, out);

        //② 缓存没覆盖的部分回源续传（同时把数据补进缓存）
        if (last > cachedEnd) {
            appendAndCopy(track, cachedEnd + 1, last, out);
        }
    }

    /** 透传源站：不缓存乱序区间，只在前缀刚好连续时顺手补进缓存 */
    private void proxyUpstream(Track track, long start, long end, OutputStream out) throws IOException {
        Response response = null;
        try {
            response = NetWorkUtil.getOkHttpInstance().newCall(buildRequest(track.url, start, end)).execute();
            ResponseBody body = response.body();
            if (!response.isSuccessful() && response.code() != 206) {
                noteFailure("upstream " + response.code());
                writeError(out, 502);
                return;
            }
            if (track.total <= 0) {
                long total = parseTotal(response.header("Content-Range"));
                if (total > 0) track.total = total;
            }
            String contentType = response.header("Content-Type", "audio/mp4");

            //源站忽略了 Range（回 200 全量）而客户端要的是从 start 开始的一段：
            //若原样透传 200，ffmpeg 会从头解码，但播放器仍按 start 之后的时间轴走
            //——表现就是"声音和进度条不同步"。这里自己跳过前 start 字节，按 206 返回。
            if (response.code() == 200 && start > 0 && body != null) {
                long fullLength = body.contentLength();
                if (fullLength > 0) {
                    track.total = fullLength;
                    long last = end >= 0 ? Math.min(end, fullLength - 1) : fullLength - 1;
                    if (last < start) last = start;
                    diag("源站忽略 Range，按 206 裁剪 start=" + start + " last=" + last
                            + " total=" + fullLength);
                    writeHead(out, 206, "Partial Content", last - start + 1,
                            "bytes " + start + "-" + last + "/" + fullLength, contentType, "bytes");
                    InputStream in = body.byteStream();
                    skipFully(in, start);
                    copyStream(in, out, null, 0, last - start + 1);
                    return;
                }
                noteFailure("upstream 200 without length");
                writeError(out, 502);
                return;
            }

            long contentLength = body != null ? body.contentLength() : -1;
            writeHead(out, response.code() == 206 ? 206 : 200, response.message(),
                    contentLength, response.header("Content-Range"), contentType, "bytes");
            if (body != null) {
                copyStream(body.byteStream(), out, start == track.prefix() ? track : null, start, -1);
            }
            enforceLimit();
        } finally {
            if (response != null) response.close();
        }
    }

    // ==================== 缓存读写 ====================

    /** 从 from 开始拉取到 to（含），可同时写给客户端；用于预取与续传 */
    private void appendAndCopy(Track track, long from, long to, OutputStream out) throws IOException {
        if (from > to) return;
        Response response = null;
        try {
            response = NetWorkUtil.getOkHttpInstance().newCall(buildRequest(track.url, from, to)).execute();
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new IOException("upstream " + response.code());
            }
            if (track.total <= 0) {
                long total = parseTotal(response.header("Content-Range"));
                if (total > 0) track.total = total;
            }
            copyStream(body.byteStream(), out, track, from, to - from + 1);
            enforceLimit();
        } finally {
            if (response != null) response.close();
        }
    }

    /**
     * 把输入流复制到客户端，并在 {@code cacheTrack != null} 时从 {@code cacheOffset} 起顺序落盘。
     *
     * <p>{@code maxBytes > 0} 时最多读这么多（预取只下 1.5MB）：源站忽略 Range 回 200 全量时，
     * 没有这个上限就会把整首 5-10MB 都拉下来，长时间占满带宽——真机日志里预取线程卡 22 秒、
     * 播放同时卡顿就是它。
     */
    private void copyStream(InputStream in, OutputStream out, Track cacheTrack, long cacheOffset,
                            long maxBytes) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long remaining = maxBytes > 0 ? maxBytes : Long.MAX_VALUE;
        if (cacheTrack == null) {
            int read;
            while (remaining > 0
                    && (read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining))) > 0) {
                if (out != null) out.write(buffer, 0, read);
                remaining -= read;
            }
            return;
        }
        synchronized (cacheTrack.writeLock) {
            //偏移必须还等于当前文件长度（前缀仍然连续），否则说明另一个连接已经写到更后面：
            //这次只转发、不落盘，避免把已缓存的前缀覆盖成乱序数据
            if (cacheOffset != cacheTrack.file.length()) {
                int read;
                while (remaining > 0
                        && (read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining))) > 0) {
                    if (out != null) out.write(buffer, 0, read);
                    remaining -= read;
                }
                return;
            }
            RandomAccessFile raf = new RandomAccessFile(cacheTrack.file, "rw");
            try {
                raf.seek(cacheOffset);
                int read;
                while (remaining > 0
                        && (read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining))) > 0) {
                    raf.write(buffer, 0, read);
                    if (out != null) out.write(buffer, 0, read);
                    remaining -= read;
                }
            } finally {
                raf.close();
            }
        }
    }

    private void copyFromFile(File file, long from, long to, OutputStream out) throws IOException {
        FileInputStream fis = new FileInputStream(file);
        try {
            skipFully(fis, from);
            byte[] buffer = new byte[BUFFER_SIZE];
            long remaining = to - from + 1;
            while (remaining > 0) {
                int want = (int) Math.min(buffer.length, remaining);
                int read = fis.read(buffer, 0, want);
                if (read <= 0) break;
                out.write(buffer, 0, read);
                remaining -= read;
            }
        } finally {
            fis.close();
        }
    }

    // ==================== 索引与 LRU ====================

    private Track track(long cid, String url) {
        Track track = tracks.get(cid);
        if (track == null) {
            Track created = new Track(cid, new File(cacheDir, cid + ".m4s"));
            Track old = tracks.putIfAbsent(cid, created);
            track = old != null ? old : created;
        }
        track.url = url;
        track.lastAccess = System.currentTimeMillis();
        return track;
    }

    /** 重启后把已有缓存文件重新挂到索引上（url 为空，等播放时补） */
    private void rebuildIndex() {
        File[] files = cacheDir.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (!name.endsWith(".m4s")) continue;
            try {
                long cid = Long.parseLong(name.substring(0, name.length() - 4));
                Track track = new Track(cid, file);
                track.lastAccess = file.lastModified();
                tracks.put(cid, track);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 超过上限时按"最久未访问"删除，正在播的那首不动 */
    private void enforceLimit() {
        long totalSize = cachedBytes();
        if (totalSize <= MAX_CACHE_BYTES) return;
        List<Track> all = new ArrayList<>(tracks.values());
        Collections.sort(all, (left, right) -> Long.compare(left.lastAccess, right.lastAccess));
        for (Track track : all) {
            if (totalSize <= MAX_CACHE_BYTES) break;
            if (track.cid == playingCid) continue;
            long length = track.file.length();
            //noinspection ResultOfMethodCallIgnored
            if (track.file.delete()) {
                tracks.remove(track.cid);
                totalSize -= length;
                diag("LRU 淘汰 cid=" + track.cid + " 释放=" + length + "B");
            }
        }
    }

    // ==================== HTTP 细节 ====================

    private Request buildRequest(String url, long start, long end) {
        Request.Builder builder = new Request.Builder().url(url);
        builder.header("Range", end >= 0 ? "bytes=" + start + "-" + end : "bytes=" + start + "-");
        for (Map.Entry<String, String> entry : PlayerApi.mediaHeadersFor(url).entrySet()) {
            if ("Range".equalsIgnoreCase(entry.getKey())) continue;
            builder.header(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    /** 用 0-0 探测总长（只在缓存命中但 total 未知时发生，正常预取路径不会走到） */
    private long probeTotal(Track track) {
        Response response = null;
        try {
            response = NetWorkUtil.getOkHttpInstance().newCall(buildRequest(track.url, 0, 0)).execute();
            return parseTotal(response.header("Content-Range"));
        } catch (Throwable e) {
            return 0;
        } finally {
            if (response != null) response.close();
        }
    }

    private static long parseTotal(String contentRange) {
        if (contentRange == null) return 0;
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0) return 0;
        try {
            return Long.parseLong(contentRange.substring(slash + 1).trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 只取第一个区间；后缀范围（bytes=-500）退化为整段，播放器极少用 */
    private static long[] parseRange(String header) {
        if (header == null) return new long[]{0, -1};
        try {
            String value = header.trim();
            if (!value.startsWith("bytes=")) return new long[]{0, -1};
            value = value.substring(6).trim();
            int comma = value.indexOf(',');
            if (comma > 0) value = value.substring(0, comma);
            int dash = value.indexOf('-');
            if (dash <= 0) return new long[]{0, -1};
            long start = Long.parseLong(value.substring(0, dash).trim());
            String endPart = value.substring(dash + 1).trim();
            long end = endPart.isEmpty() ? -1 : Long.parseLong(endPart);
            return new long[]{Math.max(0, start), end};
        } catch (Throwable t) {
            return new long[]{0, -1};
        }
    }

    private static long parseCid(String path) {
        int idx = path.indexOf("cid=");
        if (idx < 0) return -1;
        int end = path.indexOf('&', idx);
        String value = end < 0 ? path.substring(idx + 4) : path.substring(idx + 4, end);
        try {
            return Long.parseLong(value);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                int length = sb.length();
                if (length > 0 && sb.charAt(length - 1) == '\r') sb.setLength(length - 1);
                return sb.toString();
            }
            sb.append((char) c);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private void writeHead(OutputStream out, int code, String reason, long contentLength,
                           String contentRange, String contentType, String acceptRanges) throws IOException {
        StringBuilder sb = new StringBuilder(256);
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason == null ? "OK" : reason).append("\r\n");
        if (contentType != null) sb.append("Content-Type: ").append(contentType).append("\r\n");
        if (acceptRanges != null) sb.append("Accept-Ranges: ").append(acceptRanges).append("\r\n");
        if (contentLength >= 0) sb.append("Content-Length: ").append(contentLength).append("\r\n");
        if (contentRange != null) sb.append("Content-Range: ").append(contentRange).append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes("UTF-8"));
    }

    private void writeError(OutputStream out, int code) {
        try {
            byte[] body = ("proxy error " + code).getBytes("UTF-8");
            writeHead(out, code, "Error", body.length, null, "text/plain", null);
            out.write(body);
            out.flush();
        } catch (Throwable ignored) {
        }
    }

    private static void skipFully(InputStream in, long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) throw new IOException("skip 越界");
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private void noteSuccess() {
        if (consecutiveFailures != 0) consecutiveFailures = 0;
    }

    private synchronized void noteFailure(String reason) {
        consecutiveFailures++;
        diag("代理失败 " + consecutiveFailures + "/3: " + reason);
        if (consecutiveFailures >= 3) {
            circuitOpen = true;
            diag("连续失败 3 次，本进程停用缓存代理（回退直连）");
        }
    }

    private static void diag(String message) {
        Log.i(TAG, message);
    }
}
