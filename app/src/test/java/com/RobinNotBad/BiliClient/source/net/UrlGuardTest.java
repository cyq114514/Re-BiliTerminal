package com.RobinNotBad.BiliClient.source.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Locale;

/**
 * {@link UrlGuard} 的纯 JVM 用例：协议白名单、主机名/IP 段黑名单、相对地址解析。
 *
 * <p>全部使用 example.com / example.org 等保留域名与文档给出的私网地址段，
 * 不联网、不出现真实站点。
 */
public class UrlGuardTest {

    // ---------------------------------------------------------------- 空值/协议

    @Test
    public void nullBlankAndWhitespaceAreRejected() {
        assertEquals("空地址", UrlGuard.check(null));
        assertEquals("空地址", UrlGuard.check(""));
        assertEquals("空地址", UrlGuard.check("   "));
        assertEquals("空地址", UrlGuard.check("\t\n"));
        assertFalse(UrlGuard.isAllowed(null));
        assertFalse(UrlGuard.isHttpScheme(null));
    }

    @Test
    public void httpAndHttpsAreAcceptedCaseInsensitively() {
        assertTrue(UrlGuard.isHttpScheme("http://example.com/a"));
        assertTrue(UrlGuard.isHttpScheme("https://example.com/a"));
        assertTrue(UrlGuard.isHttpScheme("HTTP://EXAMPLE.COM/A"));
        assertTrue(UrlGuard.isHttpScheme("HtTpS://example.com"));
        assertFalse(UrlGuard.isHttpScheme("ftp://example.com"));

        assertNull(UrlGuard.check("http://example.com/path"));
        assertNull(UrlGuard.check("https://example.com"));
        // 前后空白会被 trim，大小写混写的协议与主机名都应放行
        assertNull(UrlGuard.check("  HTTP://EXAMPLE.COM/Path?Q=1  "));
        assertTrue(UrlGuard.isAllowed("HTTPS://Example.Com/"));
    }

    @Test
    public void nonHttpSchemesAreRejectedWithSchemeInReason() {
        String[] urls = {
                "ftp://files.example.com/a.zip",
                "file:///etc/hosts",
                "javascript:alert(1)",
                "data:text/plain,hello",
                "ws://example.com/socket",
                "mailto:someone@example.com",
        };
        for (String url : urls) {
            String reason = UrlGuard.check(url);
            assertNotNull(url + " 应被拒绝", reason);
            assertTrue(url + " 的拒绝原因应带协议名", reason.startsWith("协议不允许："));
        }
        assertEquals("协议不允许：file", UrlGuard.check("file:///etc/hosts"));
        assertEquals("协议不允许：javascript", UrlGuard.check("javascript:alert(1)"));
    }

    @Test
    public void malformedHttpUrlsAreRejectedAsUnparsable() {
        assertEquals("地址无法解析", UrlGuard.check("http://"));
        assertEquals("地址无法解析", UrlGuard.check("https://"));
        assertEquals("地址无法解析", UrlGuard.check("https://exa mple.com/x"));
        assertFalse(UrlGuard.isAllowed("http://"));
    }

    // ---------------------------------------------------------------- 内网/本机

    @Test
    public void loopbackAndLocalNamesAreBlocked() {
        String[] blocked = {
                "http://localhost/",
                "http://localhost:8080/admin",
                "http://foo.localhost/",
                "http://printer.local/",
                "http://[::1]/",
                "http://127.0.0.1/",
                "http://127.1.2.3:9000/x",
                "http://LOCALHOST/",
        };
        for (String url : blocked) {
            String reason = UrlGuard.check(url);
            assertNotNull(url + " 应被拒绝", reason);
            assertTrue(url + " 原因应为内网/本机", reason.contains("内网/本机"));
        }
    }

    @Test
    public void privateLinkLocalAndZeroNetworksAreBlocked() {
        String[] blocked = {
                "http://10.0.0.1/",
                "http://192.168.1.1:8080/",
                "http://169.254.1.10/",
                "http://0.0.0.0/",
                "http://0.example-network.test/",
                "http://172.16.0.1/",
                "http://172.20.10.5/",
                "http://172.31.255.254/",
        };
        for (String url : blocked) {
            assertFalse(url + " 应被拒绝", UrlGuard.isAllowed(url));
        }
    }

    @Test
    public void publicHostsAndPublicAddressesAreAllowed() {
        String[] allowed = {
                "https://example.com/path?q=1",
                "http://example.org:8080/a/b",
                "https://media.example.net/video.m3u8",
                "http://8.8.8.8/",
                "https://203.0.113.10/",
                // 172.16~31 之外的两侧边界必须放行（否则会误伤公网地址）
                "https://172.15.0.1/",
                "http://172.32.0.1/",
        };
        for (String url : allowed) {
            assertNull(url + " 应放行，但被 " + UrlGuard.check(url) + " 拒绝", UrlGuard.check(url));
            assertTrue(UrlGuard.isAllowed(url));
        }
    }

    // ---------------------------------------------------------------- resolveFromRoot

    @Test
    public void resolveFromRootHandlesAllHrefForms() {
        String base = "https://example.com/index.php/vod/search.html";
        assertEquals("https://media.example.org/a/b.m3u8",
                UrlGuard.resolveFromRoot(base, "https://media.example.org/a/b.m3u8"));
        assertEquals("https://cdn.example.org/a.ts",
                UrlGuard.resolveFromRoot(base, "//cdn.example.org/a.ts"));
        assertEquals("https://example.com/vod/1.html",
                UrlGuard.resolveFromRoot(base, "/vod/1.html"));
        // 相对链接按站点根解析，而不是按 searchUrl 的目录：这是 Animeko 站点的实测语义
        assertEquals("https://example.com/detail/12.html",
                UrlGuard.resolveFromRoot(base, "detail/12.html"));
    }

    @Test
    public void resolveFromRootAppendsQueryAndFragmentToBase() {
        assertEquals("https://example.com/list?page=2",
                UrlGuard.resolveFromRoot("https://example.com/list", "?page=2"));
        assertEquals("https://example.com/list#top",
                UrlGuard.resolveFromRoot("https://example.com/list#old", "#top"));
        assertEquals("https://example.com/detail/12.html?from=list#ep2",
                UrlGuard.resolveFromRoot("https://example.com/index.php/vod/search.html",
                        "detail/12.html?from=list#ep2"));
    }

    @Test
    public void resolveFromRootWithBlankOrInvalidInputs() {
        assertEquals("", UrlGuard.resolveFromRoot("https://example.com", ""));
        assertEquals("", UrlGuard.resolveFromRoot("https://example.com", null));
        assertEquals("", UrlGuard.resolveFromRoot("https://example.com", "   "));
        // 无法解析的 base 没有 origin：只能原样返回 href，不能拼出半截地址
        assertEquals("sub/x", UrlGuard.resolveFromRoot("not a url", "sub/x"));
        assertEquals("/root-like", UrlGuard.resolveFromRoot("", "/root-like"));
        assertEquals("x", UrlGuard.resolveFromRoot(null, "x"));
        // resolveFromRoot 会对 href 做 trim：带前后空白的绝对地址返回的是 trim 后的结果
        assertEquals("https://cdn.example.org/a",
                UrlGuard.resolveFromRoot(null, "  https://cdn.example.org/a  "));
    }

    // ---------------------------------------------------------------- resolveFromDirectory

    @Test
    public void resolveFromDirectoryUsesItemPageDirectory() {
        String pageUrl = "https://example.com/vod/detail/123.html";
        assertEquals("https://example.com/vod/detail/ep1.html",
                UrlGuard.resolveFromDirectory(pageUrl, "ep1.html"));
        assertEquals("https://example.com/vod/cover.jpg",
                UrlGuard.resolveFromDirectory(pageUrl, "../cover.jpg"));
        assertEquals("https://example.com/vod/detail/ep2.html",
                UrlGuard.resolveFromDirectory(pageUrl, "./ep2.html"));
        // 绝对地址与协议相对地址直接返回
        assertEquals("https://media.example.org/ep3.html",
                UrlGuard.resolveFromDirectory(pageUrl, "https://media.example.org/ep3.html"));
        assertEquals("https://cdn.example.org/ep4.html",
                UrlGuard.resolveFromDirectory(pageUrl, "//cdn.example.org/ep4.html"));
    }

    @Test
    public void resolveFromDirectoryWithBlankOrInvalidInputs() {
        assertEquals("", UrlGuard.resolveFromDirectory("https://example.com/vod/1.html", ""));
        assertEquals("", UrlGuard.resolveFromDirectory("https://example.com/vod/1.html", null));
        // base 非法时退回 resolveFromRoot 的语义（按站点根处理）
        assertEquals("sub/x", UrlGuard.resolveFromDirectory("not a url", "sub/x"));
        assertEquals("sub/x", UrlGuard.resolveFromDirectory(null, "sub/x"));
    }

    // ---------------------------------------------------------------- originOf

    @Test
    public void originOfOmitsDefaultPortsAndKeepsOthers() {
        assertEquals("https://example.com", UrlGuard.originOf("https://example.com/a/b"));
        assertEquals("https://example.com", UrlGuard.originOf("https://example.com:443/a"));
        assertEquals("http://example.com", UrlGuard.originOf("http://example.com:80/a"));
        assertEquals("https://example.com:8443", UrlGuard.originOf("https://example.com:8443/a"));
        assertEquals("http://example.com:8080", UrlGuard.originOf("http://example.com:8080/a"));
        assertEquals("", UrlGuard.originOf(null));
        assertEquals("", UrlGuard.originOf(""));
        assertEquals("", UrlGuard.originOf("not a url"));
    }

    // ---------------------------------------------------------------- 其他工具方法

    @Test
    public void withoutQueryStripsQueryAndFragment() {
        assertEquals("https://example.com/a/b",
                UrlGuard.withoutQuery("https://example.com/a/b?x=1&y=2#frag"));
        // 只有 query 没有路径时保留站点根斜杠
        assertEquals("https://example.com/",
                UrlGuard.withoutQuery("https://example.com/?x=1"));
        assertEquals("https://example.com/a/b",
                UrlGuard.withoutQuery("https://example.com/a/b"));
        assertEquals("", UrlGuard.withoutQuery(null));
        assertEquals("not a url", UrlGuard.withoutQuery("not a url"));
    }

    @Test
    public void hostOfReturnsLowercasedHostOrEmpty() {
        assertEquals("example.com", UrlGuard.hostOf("https://example.com:8443/x"));
        assertEquals("example.com", UrlGuard.hostOf("HTTPS://EXAMPLE.COM/Path"));
        assertEquals("::1", UrlGuard.hostOf("http://[::1]/"));
        assertEquals("", UrlGuard.hostOf(null));
        assertEquals("", UrlGuard.hostOf("not a url"));
    }

    @Test
    public void rejectionReasonsAreStableText() {
        // 日志/诊断依赖这些可读原因串，随业务文案修改应同步改这里，避免静默变空
        assertEquals("空地址", UrlGuard.check(""));
        assertEquals("禁止访问内网/本机地址", UrlGuard.check("http://localhost/"));
        assertTrue(UrlGuard.check("ftp://example.com/x")
                .equals("协议不允许：" + "ftp".toLowerCase(Locale.ROOT)));
    }
}