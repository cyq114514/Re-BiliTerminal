package com.RobinNotBad.BiliClient.source.play;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link HlsRouteTable} 的纯 JVM 用例：会话 id 唯一、token 路由、会话淘汰。
 *
 * <p>安全语义是这张表的全部价值：客户端只能按 token 取回「本次登记过的地址」，
 * 会话超上限时最旧会话的 token 必须清掉（否则 token 会无限累积）。
 */
public class HlsRouteTableTest {

    private static final String ORIGIN = "https://cdn.example.com/live/play.m3u8";

    @Test
    public void newSessionGeneratesUniqueNonEmptyIds() {
        HlsRouteTable table = new HlsRouteTable();
        HlsRouteTable.Session first = table.newSession(ORIGIN, null);
        HlsRouteTable.Session second = table.newSession(ORIGIN, null);

        assertNotNull(first.id);
        assertFalse(first.id.isEmpty());
        assertNotNull(second.id);
        assertFalse(second.id.isEmpty());
        assertFalse("会话 id 不能重复", first.id.equals(second.id));
        assertEquals(ORIGIN, first.origin);
    }

    @Test
    public void sessionsAreInvisibleUntilRegistered() {
        HlsRouteTable table = new HlsRouteTable();
        HlsRouteTable.Session session = table.newSession(ORIGIN, null);
        assertEquals(0, table.sessionCount());
        assertNull(table.session(session.id));

        table.register(session);
        assertEquals(1, table.sessionCount());
        assertSame(session, table.session(session.id));
    }

    @Test
    public void routeForStoresUrlAndCopiedHeaders() {
        HlsRouteTable table = new HlsRouteTable();
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "ua-test");
        headers.put("Referer", "https://example.com/");
        HlsRouteTable.Session session = table.newSession(ORIGIN, headers);
        table.register(session);

        String token = table.routeFor("https://cdn.example.com/live/seg1.ts", session);
        assertNotNull(token);
        assertFalse(token.isEmpty());

        HlsRouteTable.Route route = table.route(token);
        assertNotNull(route);
        assertEquals("https://cdn.example.com/live/seg1.ts", route.url);
        assertEquals("ua-test", route.headers.get("User-Agent"));
        assertEquals("https://example.com/", route.headers.get("Referer"));

        assertEquals(1, table.routeCount());
        assertTrue("session 应记录自己的 token", session.tokens.contains(token));
    }

    @Test
    public void unknownOrBlankTokenReturnsNull() {
        HlsRouteTable table = new HlsRouteTable();
        assertNull(table.route(null));
        assertNull(table.route(""));
        assertNull(table.route("no-such-token"));
    }

    @Test
    public void sessionLookupRejectsBlankAndUnknownIds() {
        HlsRouteTable table = new HlsRouteTable();
        HlsRouteTable.Session session = table.newSession(ORIGIN, null);
        table.register(session);

        assertSame(session, table.session(session.id));
        assertNull(table.session(null));
        assertNull(table.session(""));
        assertNull(table.session("no-such-session"));
    }

    @Test
    public void registerEvictsOldestSessionAndItsTokens() {
        HlsRouteTable table = new HlsRouteTable();
        assertEquals(8, HlsRouteTable.MAX_SESSIONS);

        // 每会话 1 个 token，注册 9 个：最旧的 s1 被淘汰，routeCount 随之降到 8
        for (int i = 1; i <= HlsRouteTable.MAX_SESSIONS + 1; i++) {
            HlsRouteTable.Session session = table.newSessionWithId(
                    "s" + i, "https://example.com/p" + i + ".m3u8", null);
            table.routeFor("https://cdn.example.com/s" + i + ".ts", session);
            table.register(session);
        }

        assertEquals(HlsRouteTable.MAX_SESSIONS, table.sessionCount());
        assertEquals(HlsRouteTable.MAX_SESSIONS, table.routeCount());
        assertNull("最旧会话应被淘汰", table.session("s1"));
        assertNotNull(table.session("s2"));
        assertEquals("https://example.com/p9.m3u8", table.session("s9").origin);
        // s1 的 token 已清掉，访问应返回 null（不能留下可转发入口）
        assertNull(table.route("1"));
    }

    @Test
    public void evictionRemovesEveryTokenOfTheOldestSession() {
        HlsRouteTable table = new HlsRouteTable();

        HlsRouteTable.Session oldest = table.newSessionWithId("oldest", ORIGIN, null);
        String[] oldestTokens = {
                table.routeFor("https://cdn.example.com/a.ts", oldest),
                table.routeFor("https://cdn.example.com/b.ts", oldest),
                table.routeFor("https://cdn.example.com/c.ts", oldest),
        };
        table.register(oldest);

        // 再注册 8 个会话（总数 9），最旧的 oldest 应连同 3 个 token 一起消失
        for (int i = 1; i <= HlsRouteTable.MAX_SESSIONS; i++) {
            HlsRouteTable.Session session = table.newSessionWithId("new" + i, ORIGIN, null);
            table.routeFor("https://cdn.example.com/new" + i + ".ts", session);
            table.register(session);
        }

        assertEquals(HlsRouteTable.MAX_SESSIONS, table.sessionCount());
        assertEquals(HlsRouteTable.MAX_SESSIONS, table.routeCount());
        assertNull(table.session("oldest"));
        for (String token : oldestTokens) {
            assertNull("淘汰后 token 必须失效：" + token, table.route(token));
        }
        assertNotNull(table.session("new1"));
        assertNotNull(table.session("new8"));
    }

    @Test
    public void newSessionWithIdKeepsGivenChildIdAndCopiesHeaders() {
        HlsRouteTable table = new HlsRouteTable();
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "r1");

        HlsRouteTable.Session child = table.newSessionWithId("sub-token-1", ORIGIN, headers);
        assertEquals("sub-token-1", child.id);
        assertEquals(ORIGIN, child.origin);
        // headers 是防御性拷贝：调用方事后改原 map 不应影响已建会话
        headers.put("Referer", "r2");
        headers.put("X-Extra", "v");
        assertEquals("r1", child.headers.get("Referer"));
        assertEquals(1, child.headers.size());

        // null headers 归一为空表，避免下游 NPE
        HlsRouteTable.Session noHeaders = table.newSessionWithId("sub-2", ORIGIN, null);
        assertNotNull(noHeaders.headers);
        assertTrue(noHeaders.headers.isEmpty());
    }

    @Test
    public void sessionAndRouteCountsTrackRegistrations() {
        HlsRouteTable table = new HlsRouteTable();
        assertEquals(0, table.sessionCount());
        assertEquals(0, table.routeCount());

        HlsRouteTable.Session session = table.newSession(ORIGIN, null);
        table.routeFor("https://cdn.example.com/a.ts", session);
        table.routeFor("https://cdn.example.com/b.ts", session);
        // 未注册前只登记了 token，不算会话
        assertEquals(0, table.sessionCount());
        assertEquals(2, table.routeCount());

        table.register(session);
        assertEquals(1, table.sessionCount());
        assertEquals(2, table.routeCount());
    }

    @Test
    public void registeringSameSessionIdTwiceDoesNotDuplicateSessionCount() {
        HlsRouteTable table = new HlsRouteTable();
        HlsRouteTable.Session session = table.newSessionWithId("same", ORIGIN, null);
        table.register(session);
        table.register(session);

        assertEquals(1, table.sessionCount());
        assertSame(session, table.session("same"));
    }
}