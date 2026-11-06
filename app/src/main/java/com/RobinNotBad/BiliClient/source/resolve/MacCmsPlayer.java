package com.RobinNotBad.BiliClient.source.resolve;

import com.RobinNotBad.BiliClient.source.net.UrlGuard;

import org.json.JSONObject;

/**
 * MacCMS（苹果 CMS / 海洋 CMS 系）播放页的 {@code player_aaaa} 配置解析。
 *
 * <p>这类站点是国内采集站的主流，播放页不直接把 m3u8 写进 HTML，而是留一份配置由 JS 的
 * 播放器再发请求。标准实现的 {@code encrypt} 只有两级：
 * <ul>
 *   <li>{@code 0}：url 就是地址；</li>
 *   <li>{@code 1}：url 做过 {@code escape()}，解一次 {@code unescape}；</li>
 *   <li>{@code 2}：base64 后再 escape。</li>
 * </ul>
 *
 * <p><b>实测存在站点自定义加密</b>（例如 {@code NBY-XMYAES…} 这种解出来不是 URL 的前缀）。
 * 这里的态度是：解不出 URL 就明确失败，<b>不猜算法、不尝试爆破</b>——猜错只会把
 * 一个网页地址当直链塞给播放器，用户看到的是「转圈然后报错」。
 */
public final class MacCmsPlayer {

    private MacCmsPlayer() {
    }

    public static final class Result {
        /** 解出来的候选地址；为空表示没解出可用地址 */
        public String url = "";
        /** 人类可读说明（给「解析失败」文案用，不含敏感内容） */
        public String reason = "";
    }

    public static Result parse(String html) {
        Result result = new Result();
        if (html == null || html.isEmpty()) {
            result.reason = "页面为空";
            return result;
        }
        String block = extractObject(html, "player_aaaa");
        if (block == null) {
            result.reason = "页面没有 player_aaaa 配置";
            return result;
        }
        String rawUrl;
        int encrypt = 0;
        try {
            JSONObject object = new JSONObject(block);
            rawUrl = object.optString("url", "");
            encrypt = object.optInt("encrypt", 0);
        } catch (Exception e) {
            result.reason = "player_aaaa 不是合法 JSON";
            return result;
        }
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            result.reason = "配置里没有 url";
            return result;
        }
        String decoded = rawUrl.trim();
        try {
            if (encrypt == 1) {
                decoded = unescape(decoded);
            } else if (encrypt == 2) {
                decoded = unescape(base64Decode(decoded));
            }
        } catch (Throwable t) {
            result.reason = "配置解密失败（encrypt=" + encrypt + "）";
            return result;
        }
        if (!UrlGuard.isHttpScheme(decoded)) {
            //自定义加密的典型表现：解完还是 NBY-xxx 这种非 URL 文本
            result.reason = "地址被站点自定义加密（encrypt=" + encrypt + "），本端不猜算法";
            return result;
        }
        result.url = decoded;
        return result;
    }

    /**
     * 从脚本里抠出 `player_aaaa = {…}` 的 JSON 块。
     *
     * <p>手写括号配对而不是正则 {@code \\{.*?\\}}：配置里有嵌套对象，非贪婪正则会截断，
     * 贪婪正则遇到后面更长的脚本会吃掉多余内容。
     */
    private static String extractObject(String html, String variable) {
        int index = html.indexOf(variable);
        if (index < 0) return null;
        int start = html.indexOf('{', index);
        if (start < 0) return null;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        int limit = Math.min(html.length(), start + 200 * 1024);
        for (int i = start; i < limit; i++) {
            char c = html.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return html.substring(start, i + 1);
            }
        }
        return null;
    }

    /** JS {@code unescape}：把 %XX 与 %uXXXX 还原成字符，其余原样保留 */
    static String unescape(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '%') {
                builder.append(c);
                continue;
            }
            if (i + 5 < text.length() && (text.charAt(i + 1) == 'u' || text.charAt(i + 1) == 'U')) {
                String hex = text.substring(i + 2, i + 6);
                try {
                    builder.append((char) Integer.parseInt(hex, 16));
                    i += 5;
                    continue;
                } catch (NumberFormatException ignored) {
                    //不是合法的 %uXXXX，按字面量处理
                }
            }
            if (i + 2 < text.length()) {
                String hex = text.substring(i + 1, i + 3);
                try {
                    builder.append((char) Integer.parseInt(hex, 16));
                    i += 2;
                    continue;
                } catch (NumberFormatException ignored) {
                    //同上
                }
            }
            builder.append(c);
        }
        return builder.toString();
    }

    /** 标准 base64（容忍站点省略 padding）；失败抛 IllegalArgumentException */
    static String base64Decode(String text) {
        String clean = text.replace("\r", "").replace("\n", "").replace(" ", "");
        int remainder = clean.length() % 4;
        if (remainder == 2) clean += "==";
        else if (remainder == 3) clean += "=";
        byte[] bytes = android.util.Base64.decode(clean, android.util.Base64.DEFAULT);
        return new String(bytes, java.nio.charset.Charset.forName("UTF-8"));
    }
}
