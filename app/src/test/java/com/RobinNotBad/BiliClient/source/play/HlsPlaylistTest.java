package com.RobinNotBad.BiliClient.source.play;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link HlsPlaylist} 的纯 JVM 用例：m3u8 里所有资源引用（key / init / 分片 / 子播放列表）
 * 都被改写成 sink 给出的本地地址；非法地址原样保留。
 *
 * <p>用 example.com 等保留域名，不联网。断言的是 rewrite 的确定性文本输出与
 * resolveFromDirectory（相对地址以 m3u8 所在目录为基准）的解析结果。
 */
public class HlsPlaylistTest {

    private static final String BASE_URL = "https://cdn.example.com/live/play.m3u8";

    /** 测试用 sink：把绝对地址包一层，方便断言「传进来的正是解析后的远程地址」。 */
    private static final HlsPlaylist.RouteSink PREFIX_SINK = url -> "LOCAL:" + url;

    // ---------------------------------------------------------------- 分片

    @Test
    public void rewriteRelativeSegmentResolvesAgainstPlaylistDirectory() {
        String body = "#EXTM3U\n#EXTINF:10,\nseg1.ts";
        assertEquals("#EXTM3U\n#EXTINF:10,\nLOCAL:https://cdn.example.com/live/seg1.ts\n",
                HlsPlaylist.rewrite(body, BASE_URL, PREFIX_SINK));
    }

    @Test
    public void rewriteKeepsAbsoluteSegmentUnchanged() {
        String body = "#EXTM3U\nhttps://media.example.org/vod/a/b.ts";
        assertEquals("#EXTM3U\nLOCAL:https://media.example.org/vod/a/b.ts\n",
                HlsPlaylist.rewrite(body, BASE_URL, PREFIX_SINK));
    }

    // ---------------------------------------------------------------- 属性行

    @Test
    public void rewriteKeyUriAttribute() {
        String body = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x0123456789";
        assertEquals("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"LOCAL:https://cdn.example.com/live/key.bin\","
                        + "IV=0x0123456789\n",
                HlsPlaylist.rewrite(body, BASE_URL, PREFIX_SINK));
    }

    @Test
    public void rewriteMapAndSessionKeyUriAttributes() {
        String body = "#EXTM3U\n"
                + "#EXT-X-MAP:URI=\"init.mp4\"\n"
                + "#EXT-X-SESSION-KEY:METHOD=AES-128,URI=\"skey.bin\"";
        assertEquals("#EXTM3U\n"
                        + "#EXT-X-MAP:URI=\"LOCAL:https://cdn.example.com/live/init.mp4\"\n"
                        + "#EXT-X-SESSION-KEY:METHOD=AES-128,URI=\"LOCAL:https://cdn.example.com/live/skey.bin\"\n",
                HlsPlaylist.rewrite(body, BASE_URL, PREFIX_SINK));
    }

    // ---------------------------------------------------------------- 多级播放列表

    @Test
    public void rewriteNestedStreamInfPlaylists() {
        String body = "#EXTM3U\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720\n"
                + "720p/index.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=2000000\n"
                + "1080p/index.m3u8";
        assertEquals("#EXTM3U\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720\n"
                        + "LOCAL:https://cdn.example.com/live/720p/index.m3u8\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=2000000\n"
                        + "LOCAL:https://cdn.example.com/live/1080p/index.m3u8\n",
                HlsPlaylist.rewrite(body, BASE_URL, PREFIX_SINK));
    }

    // ---------------------------------------------------------------- CRLF

    @Test
    public void crlfInputIsParsedLineByLineAndNormalizedToLf() {
        // 上游 m3u8 可能是 CRLF：解析要按行处理且不能把 '\r' 带进 URL。
        // 实际输出统一为 LF（rewrite 只追加 '\n'），这里断言真实行为而不是假设保留 CRLF。
        String body = "#EXTM3U\r\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\r\n"
                + "seg1.ts";
        String rewritten = HlsPlaylist.rewrite(body, BASE_URL, PREFIX_SINK);
        assertEquals("#EXTM3U\n"
                        + "#EXT-X-KEY:METHOD=AES-128,URI=\"LOCAL:https://cdn.example.com/live/key.bin\"\n"
                        + "LOCAL:https://cdn.example.com/live/seg1.ts\n",
                rewritten);
        assertFalse(rewritten.contains("\r"));
    }

    // ---------------------------------------------------------------- 非法地址

    @Test
    public void illegalAddressesAreLeftVerbatim() {
        String body = "#EXTM3U\n"
                + "http://192.168.1.5/secret.ts\n"
                + "ftp://files.example.com/x.ts\n"
                + "http://localhost/loopback.ts\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"http://10.0.0.1/key.bin\"";
        List<String> routed = new ArrayList<>();
        String rewritten = HlsPlaylist.rewrite(body, BASE_URL, url -> {
            routed.add(url);
            return "LOCAL:" + url;
        });

        assertEquals("#EXTM3U\n"
                        + "http://192.168.1.5/secret.ts\n"
                        + "ftp://files.example.com/x.ts\n"
                        + "http://localhost/loopback.ts\n"
                        + "#EXT-X-KEY:METHOD=AES-128,URI=\"http://10.0.0.1/key.bin\"\n",
                rewritten);
        // 非法地址一次都不应交给 sink 登记，否则本地代理会拿到内网 token
        assertTrue("非法地址不应被登记：" + routed, routed.isEmpty());
    }

    // ---------------------------------------------------------------- 空值/结构

    @Test
    public void nullBodyOrNullSinkReturnsInputUnchanged() {
        String body = "#EXTM3U\nseg1.ts";
        assertNull(HlsPlaylist.rewrite(null, BASE_URL, PREFIX_SINK));
        assertEquals(body, HlsPlaylist.rewrite(body, BASE_URL, null));
    }

    @Test
    public void commentsAndBlankLinesArePreserved() {
        String body = "#EXTM3U\n\n# a comment line\nseg1.ts";
        assertEquals("#EXTM3U\n\n# a comment line\nLOCAL:https://cdn.example.com/live/seg1.ts\n",
                HlsPlaylist.rewrite(body, BASE_URL, PREFIX_SINK));
    }

    @Test
    public void sinkReceivesResolvedAbsoluteUrlsInSourceOrder() {
        List<String> routed = new ArrayList<>();
        String body = "#EXTM3U\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n"
                + "#EXT-X-MAP:URI=\"init.mp4\"\n"
                + "seg1.ts\n"
                + "https://media.example.org/abs/seg2.ts";
        HlsPlaylist.rewrite(body, BASE_URL, url -> {
            routed.add(url);
            return "LOCAL:" + url;
        });

        assertEquals(4, routed.size());
        assertEquals("https://cdn.example.com/live/key.bin", routed.get(0));
        assertEquals("https://cdn.example.com/live/init.mp4", routed.get(1));
        assertEquals("https://cdn.example.com/live/seg1.ts", routed.get(2));
        assertEquals("https://media.example.org/abs/seg2.ts", routed.get(3));
    }

    // ---------------------------------------------------------------- isPlaylist

    @Test
    public void isPlaylistDetectsContentTypeAndByteHeader() {
        byte[] header = "#EXTM3U\n#EXT-X-VERSION:3".getBytes(StandardCharsets.UTF_8);

        // contentType 优先：mpegurl / m3u8 关键字大小写不敏感
        assertTrue(HlsPlaylist.isPlaylist("application/vnd.apple.mpegurl", null));
        assertTrue(HlsPlaylist.isPlaylist("application/x-mpegURL", null));
        assertTrue(HlsPlaylist.isPlaylist("Application/X-M3U8", null));
        assertTrue(HlsPlaylist.isPlaylist("audio/mpegurl", null));
        // contentType 不匹配时看字节头
        assertTrue(HlsPlaylist.isPlaylist(null, header));
        assertTrue(HlsPlaylist.isPlaylist("text/html", "#EXTM3U".getBytes(StandardCharsets.UTF_8)));
        // 两者都不匹配 / 空内容时不是播放列表
        assertFalse(HlsPlaylist.isPlaylist(null, null));
        assertFalse(HlsPlaylist.isPlaylist("video/mp2t", new byte[0]));
        assertFalse(HlsPlaylist.isPlaylist("text/html",
                "hello".getBytes(StandardCharsets.UTF_8)));
    }
}