package com.RobinNotBad.BiliClient.source.rule;

import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 两套规则语言的模板/变量替换。
 *
 * <p>为什么不能只写一个 replace：
 * <ul>
 *   <li>Kazumi 用 `@keyword` / `@source` / `@roadIndex` …，替换时做 URL 编码，
 *       且变量前必须是分隔符（`(?<![A-Za-z0-9_])`），所以 `/GV@source` 这种
 *       「变量紧跟字母」的写法是允许的，而 `fooBar@x` 不会被误替换；</li>
 *   <li>Animeko 用 `{keyword}` / `{value}`，<b>不做</b>编码（`linkTemplate`
 *       是直接拼字符串）。</li>
 * </ul>
 * 两套语法混用就会得到「规则能解析但地址拼错」的哑巴故障。
 */
public final class Templates {

    private Templates() {
    }

    private static final Pattern AT_VAR =
            Pattern.compile("(?<![A-Za-z0-9_])@([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern EXACT_AT_VAR =
            Pattern.compile("^@([A-Za-z_][A-Za-z0-9_]*)$");

    // ==================== 编码 ====================

    /**
     * RFC 3986 unreserved 之外的字符全部百分号编码（等价 Dart 的 Uri.encodeComponent）。
     *
     * <p>与 {@code URLEncoder.encode} 的差别：后者把空格变成 {@code +}、还不转义 {@code *}，
     * 拼进 path 段会出错。
     */
    public static String encodeComponent(String value) {
        if (value == null) return "";
        StringBuilder builder = new StringBuilder(value.length() + 16);
        byte[] bytes;
        try {
            bytes = value.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            bytes = value.getBytes();
        }
        for (byte raw : bytes) {
            int b = raw & 0xFF;
            if ((b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') || (b >= '0' && b <= '9')
                    || b == '-' || b == '_' || b == '.' || b == '~') {
                builder.append((char) b);
            } else {
                builder.append('%');
                builder.append(HEX[b >> 4]);
                builder.append(HEX[b & 0x0F]);
            }
        }
        return builder.toString();
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /** 查询串里的值：空格编码为 %20（不产生 +，避免某些站点二次解码后变加号） */
    public static String encodeQueryValue(String value) {
        return encodeComponent(value).replace("+", "%20");
    }

    // ==================== Animeko ====================

    /** Animeko 的 `{keyword}` 占位符替换（规则里 {keyword} 可能出现在 path 或 query） */
    public static String replaceKeyword(String template, String keyword) {
        if (template == null) return "";
        return template.replace("{keyword}", encodeQueryValue(keyword == null ? "" : keyword));
    }

    /**
     * Animeko 的 `{value}`（linkTemplate）：直接替换，不编码。
     *
     * <p><b>空模板必须原样返回 value</b>（与上游 {@code applyLinkTemplate} 的
     * {@code isNullOrBlank() → rawHref} 一致）。这里踩过一次真实事故：只判了 {@code null}
     * 没判空串，而 {@code "".replace("{value}", v)} 仍然是空串 —— 实测订阅里 63/63 条源的
     * linkTemplate 都是空，于是所有搜索结果都被当成「链接非法」丢掉，表现就是
     * 「每个源都 200，但一条结果都没有」。
     */
    public static String replaceValue(String template, String value) {
        if (template == null || template.trim().isEmpty()) return value == null ? "" : value;
        String raw = value == null ? "" : value;
        //模板里没有占位符时按「整段就是地址」处理，避免把用户配置的固定地址换成空串
        if (!template.contains("{value}")) return template;
        return template.replace("{value}", raw);
    }

    // ==================== Kazumi ====================

    /**
     * Kazumi 的 `@var` 模板渲染。
     *
     * @param encode 是否对变量值做 URL 编码（URL 模板 true，header/body 值 false）
     * @throws IllegalArgumentException 变量缺失（对齐 Kazumi 的「缺少模板变量」错误）
     */
    public static String renderAtTemplate(String template, Map<String, ?> variables, boolean encode) {
        if (template == null) return "";
        Matcher matcher = AT_VAR.matcher(template);
        StringBuffer buffer = new StringBuffer(template.length() + 16);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (variables == null || !variables.containsKey(name))
                throw new IllegalArgumentException("缺少模板变量 @" + name);
            Object value = variables.get(name);
            String text = value == null ? "" : String.valueOf(value);
            matcher.appendReplacement(buffer,
                    Matcher.quoteReplacement(encode ? encodeComponent(text) : text));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /**
     * 查询参数/请求体里的值：整个值就是一个 `@var` 时保留原始类型语义（这里统一成字符串），
     * 否则按模板渲染。
     */
    public static String renderAtValue(String value, Map<String, ?> variables) {
        if (value == null) return "";
        Matcher exact = EXACT_AT_VAR.matcher(value.trim());
        if (exact.matches()) {
            String name = exact.group(1);
            if (variables == null || !variables.containsKey(name))
                throw new IllegalArgumentException("缺少模板变量 @" + name);
            Object data = variables.get(name);
            return data == null ? "" : String.valueOf(data);
        }
        return renderAtTemplate(value, variables, false);
    }

    // ==================== 查询串拼接 ====================

    /**
     * 把查询参数追加到已有 URL 上（覆盖同名参数）。
     *
     * <p>注意不能简单 `url + "?" + query`：原本已有 `?` 时要变 `&`，
     * fragment 也必须保留在最后，否则 `#playlist` 之类会被参数吞掉导致线路丢失。
     */
    public static String appendQuery(String url, Map<String, String> params) {
        if (url == null) url = "";
        if (params == null || params.isEmpty()) return url;

        String hash = "";
        int hashIndex = url.indexOf('#');
        if (hashIndex >= 0) {
            hash = url.substring(hashIndex);
            url = url.substring(0, hashIndex);
        }
        StringBuilder query = new StringBuilder();
        int queryIndex = url.indexOf('?');
        String base = queryIndex >= 0 ? url.substring(0, queryIndex) : url;
        String existing = queryIndex >= 0 ? url.substring(queryIndex + 1) : "";
        if (!existing.isEmpty()) query.append(existing);

        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey();
            String value = entry.getValue() == null ? "" : entry.getValue();
            String pair = encodeQueryValue(key) + "=" + encodeQueryValue(value);
            if (query.indexOf(encodeQueryValue(key) + "=") >= 0) continue; // 已存在的同名参数不重复追加
            if (query.length() > 0) query.append('&');
            query.append(pair);
        }
        String result = query.length() > 0 ? base + "?" + query : base;
        return result + hash;
    }

    /** 解析已有的查询串为「保持顺序」的 key=value 列表（Kazumi POST 搜索要用） */
    public static void parseQuery(String url, Map<String, String> out) {
        if (url == null || out == null) return;
        int queryIndex = url.indexOf('?');
        if (queryIndex < 0) return;
        String query = url.substring(queryIndex + 1);
        int hashIndex = query.indexOf('#');
        if (hashIndex >= 0) query = query.substring(0, hashIndex);
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            out.put(decode(key), decode(value));
        }
    }

    public static String decode(String value) {
        if (value == null) return "";
        try {
            return java.net.URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    /** 去掉 URL 的查询部分（Kazumi 的 POST 搜索：查询参数搬到表单里） */
    public static String stripQuery(String url) {
        if (url == null) return "";
        int queryIndex = url.indexOf('?');
        String hash = "";
        int hashIndex = url.indexOf('#');
        if (hashIndex >= 0) hash = url.substring(hashIndex);
        String base = queryIndex >= 0 ? url.substring(0, queryIndex) : url;
        if (base.endsWith("#") ) base = base.substring(0, base.length() - 1);
        return base + hash;
    }

    /** 表单编码（application/x-www-form-urlencoded）：空格用 %20，避免与站点二次解码冲突 */
    public static String formEncode(Map<String, String> params) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (builder.length() > 0) builder.append('&');
            builder.append(encodeQueryValue(entry.getKey() == null ? "" : entry.getKey()));
            builder.append('=');
            builder.append(encodeQueryValue(entry.getValue() == null ? "" : entry.getValue()));
        }
        return builder.toString();
    }

    /** 顺序无关的集合转数组（避免调用方到处 new） */
    public static String[] toArray(List<String> list) {
        return list == null ? new String[0] : list.toArray(new String[0]);
    }
}
