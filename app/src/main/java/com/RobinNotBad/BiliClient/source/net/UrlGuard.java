package com.RobinNotBad.BiliClient.source.net;

import java.util.Locale;

import okhttp3.HttpUrl;

/**
 * 第三方 URL 的准入校验。
 *
 * <p>远程规则是不可信输入，它可以让 App 请求任意地址：不加限制就等于给了
 * 一条从公网规则打到「内网/本机端口」的通道（例如 192.168.x.x:8080 的管理页）。
 *
 * <p>这里做的是<b>字面量级</b>校验（协议白名单 + 主机名/IP 段黑名单）。
 * 局限必须说清：<b>不解析 DNS</b>，因此「域名指向内网 IP」这种绕过检测不到；
 * 要做完整防护需要在 OkHttp 的 Dns 层做解析后校验，本版本未实现（见交付报告「已知限制」）。
 */
public final class UrlGuard {

    private UrlGuard() {
    }

    public static boolean isHttpScheme(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /**
     * 校验通过返回 null，不通过返回原因（便于日志），不做 DNS 解析。
     */
    public static String check(String url) {
        if (url == null || url.trim().isEmpty()) return "空地址";
        String trimmed = url.trim();
        if (!isHttpScheme(trimmed)) return "协议不允许：" + schemeOf(trimmed);
        HttpUrl parsed = HttpUrl.parse(trimmed);
        if (parsed == null) return "地址无法解析";
        String host = parsed.host() == null ? "" : parsed.host().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) return "缺少主机名";
        if (isBlockedHost(host)) return "禁止访问内网/本机地址";
        return null;
    }

    public static boolean isAllowed(String url) {
        return check(url) == null;
    }

    private static String schemeOf(String url) {
        int index = url.indexOf(':');
        return index > 0 ? url.substring(0, index) : "";
    }

    private static boolean isBlockedHost(String host) {
        if (host.equals("localhost") || host.endsWith(".localhost")) return true;
        if (host.equals("local") || host.endsWith(".local")) return true;
        if (host.equals("[::1]") || host.equals("::1")) return true;
        if (host.startsWith("127.")) return true;
        if (host.startsWith("10.")) return true;
        if (host.startsWith("192.168.")) return true;
        if (host.startsWith("169.254.")) return true;
        if (host.startsWith("0.")) return true;
        // 172.16.0.0 ~ 172.31.255.255
        if (host.startsWith("172.")) {
            int dot = host.indexOf('.', 4);
            if (dot > 4) {
                try {
                    int second = Integer.parseInt(host.substring(4, dot));
                    if (second >= 16 && second <= 31) return true;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return false;
    }

    // ==================== 相对地址解析 ====================

    /**
     * 以「站点根」为基准解析（Animeko 搜索结果链接的语义）。
     *
     * <p>刻意不用 searchUrl 本身当基准：`searchUrl` 常在 `/index.php/vod/search.html`，
     * 相对链接若按它解析会多带两层目录，实测站点给的 `href` 都是相对站点根的。
     */
    public static String resolveFromRoot(String anyUrlOnSite, String href) {
        String clean = href == null ? "" : href.trim();
        if (clean.isEmpty()) return "";
        if (isHttpScheme(clean)) return clean;
        if (clean.startsWith("//")) return "https:" + clean;
        String origin = originOf(anyUrlOnSite);
        if (origin.isEmpty()) return clean;
        if (clean.startsWith("/")) return origin + clean;
        if (clean.startsWith("?") || clean.startsWith("#")) {
            String base = anyUrlOnSite == null ? "" : anyUrlOnSite;
            int hash = base.indexOf('#');
            if (hash >= 0) base = base.substring(0, hash);
            return base + clean;
        }
        HttpUrl resolved = HttpUrl.parse(origin + "/") == null ? null
                : HttpUrl.parse(origin + "/").resolve(clean);
        return resolved == null ? origin + "/" + clean : resolved.toString();
    }

    /**
     * 以「条目页所在目录」为基准解析（Animeko 剧集链接语义）。
     *
     * <p>上游文档明确：剧集相对链接以条目页 URL 去掉最后一段路径为基准，
     * 与搜索结果页用站点根不同。搞错这一条会让部分站点的剧集地址全部 404。
     */
    public static String resolveFromDirectory(String pageUrl, String href) {
        String clean = href == null ? "" : href.trim();
        if (clean.isEmpty()) return "";
        if (isHttpScheme(clean)) return clean;
        if (clean.startsWith("//")) return "https:" + clean;
        HttpUrl base = HttpUrl.parse(pageUrl == null ? "" : pageUrl.trim());
        if (base == null) return resolveFromRoot(pageUrl, clean);
        HttpUrl resolved = base.resolve(clean);
        return resolved == null ? clean : resolved.toString();
    }

    /** scheme://host[:port]（默认端口不显式带出） */
    public static String originOf(String url) {
        HttpUrl parsed = HttpUrl.parse(url == null ? "" : url.trim());
        if (parsed == null) return "";
        String scheme = parsed.scheme();
        int port = parsed.port();
        boolean defaultPort = ("http".equals(scheme) && port == 80)
                || ("https".equals(scheme) && port == 443);
        return scheme + "://" + parsed.host() + (defaultPort ? "" : ":" + port);
    }

    /** 去掉 query 与 fragment 的地址（用作缓存键/作品来源标识） */
    public static String withoutQuery(String url) {
        HttpUrl parsed = HttpUrl.parse(url == null ? "" : url.trim());
        if (parsed == null) return url == null ? "" : url;
        HttpUrl.Builder builder = parsed.newBuilder().query(null).fragment(null);
        return builder.build().toString();
    }

    /** 取 host，异常时返回空串（日志用） */
    public static String hostOf(String url) {
        HttpUrl parsed = HttpUrl.parse(url == null ? "" : url.trim());
        return parsed == null || parsed.host() == null ? "" : parsed.host();
    }
}
