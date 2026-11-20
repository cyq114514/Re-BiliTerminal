package com.RobinNotBad.BiliClient.source.play;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地 HLS 代理的「会话 / token → 真实地址」路由表（纯 JVM，无网络与 Android 依赖）。
 *
 * <p>安全约束由这张表本身保证：客户端只能按 token 取「本次登记过的 URL」，
 * 不存在按任意地址转发的入口；会话数超上限时淘汰最旧会话并清掉它的全部 token，
 * 避免 token 无限累积。实际转发前的 URL 校验仍由 {@link com.RobinNotBad.BiliClient.source.net.UrlGuard}
 * 在 {@link LocalHlsProxy#forward} 里执行。
 */
public final class HlsRouteTable {

    /** 同时保留的会话上限：播放器切集/重试时旧会话会自然过期。 */
    public static final int MAX_SESSIONS = 8;

    static final class Route {
        final String url;
        final Map<String, String> headers;

        Route(String url, Map<String, String> headers) {
            this.url = url;
            this.headers = headers;
        }
    }

    static final class Session {
        String id;
        String origin;
        Map<String, String> headers;
        byte[] playlist;
        final Set<String> tokens = new HashSet<>();
    }

    private final AtomicLong ids = new AtomicLong();
    private final ConcurrentHashMap<String, Route> routes = new ConcurrentHashMap<>();
    private final LinkedHashMap<String, Session> sessions = new LinkedHashMap<>();

    /** 新建会话（id 唯一，每次播放列表改写复用一个会话）。 */
    public Session newSession(String origin, Map<String, String> headers) {
        return newSessionWithId(Long.toHexString(ids.incrementAndGet()), origin, headers);
    }

    /** 新建指定 id 的会话（多级 m3u8 的子会话用 origin+token 关联，便于日志定位）。 */
    public Session newSessionWithId(String id, String origin, Map<String, String> headers) {
        Session session = new Session();
        session.id = id;
        session.origin = origin;
        session.headers = copyHeaders(headers);
        return session;
    }

    /** 登记「本地 token → 真实 URL」，返回 token；token 只在本会话内有效。 */
    public String routeFor(String remoteUrl, Session session) {
        String token = Long.toHexString(ids.incrementAndGet());
        routes.put(token, new Route(remoteUrl, session.headers));
        session.tokens.add(token);
        return token;
    }

    /** 登记会话并淘汰最旧的，顺手清掉它名下的路由。 */
    public void register(Session session) {
        synchronized (sessions) {
            sessions.put(session.id, session);
            while (sessions.size() > MAX_SESSIONS) {
                String oldest = sessions.keySet().iterator().next();
                Session removed = sessions.remove(oldest);
                if (removed != null) removeRoutes(removed);
            }
        }
    }

    public Session session(String id) {
        if (id == null || id.isEmpty()) return null;
        synchronized (sessions) {
            return sessions.get(id);
        }
    }

    public Route route(String token) {
        if (token == null || token.isEmpty()) return null;
        return routes.get(token);
    }

    /** 当前会话数量（测试/诊断用）。 */
    public int sessionCount() {
        synchronized (sessions) {
            return sessions.size();
        }
    }

    /** 当前 token 数量（测试/诊断用）。 */
    public int routeCount() {
        return routes.size();
    }

    private void removeRoutes(Session session) {
        for (String token : session.tokens) routes.remove(token);
    }

    private static Map<String, String> copyHeaders(Map<String, String> source) {
        Map<String, String> copy = new HashMap<>();
        if (source != null) copy.putAll(source);
        return copy;
    }
}