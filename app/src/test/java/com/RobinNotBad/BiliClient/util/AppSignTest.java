package com.RobinNotBad.BiliClient.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@link AppSign} 的纯 JVM 用例：参数排序、URL 编码（空格为 %20）、签名写入与 MD5 正确性。
 *
 * <p>不联网、不依赖账号：签名是纯字符串运算，这里只断言可复现的结果。
 * 时间戳只断言「存在、10 位、接近当前时间」，不固定具体秒数（避免跨秒抖动）。
 */
public class AppSignTest {

    // ---------------------------------------------------------------- encodeQuery

    @Test
    public void encodeQueryKeepsAlphabeticalOrder() {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("b", "2");
        params.put("a", "1");
        params.put("c", "3");
        assertEquals("a=1&b=2&c=3", AppSign.encodeQuery(params));
    }

    @Test
    public void encodeQueryEncodesSpacesAsPercent20AndSpecials() {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("q", "hello world");
        assertEquals("q=hello%20world", AppSign.encodeQuery(params));

        TreeMap<String, String> special = new TreeMap<>();
        special.put("raw", "a&b=c+d");
        assertEquals("raw=a%26b%3Dc%2Bd", AppSign.encodeQuery(special));

        TreeMap<String, String> chinese = new TreeMap<>();
        chinese.put("wd", "中文");
        assertEquals("wd=%E4%B8%AD%E6%96%87", AppSign.encodeQuery(chinese));
    }

    @Test
    public void encodeQueryHandlesEmptyAndNullValues() {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("empty", "");
        params.put("nil", null);
        assertEquals("empty=&nil=", AppSign.encodeQuery(params));
    }

    // ---------------------------------------------------------------- sign

    @Test
    public void signWritesAppkeyTimestampAndLowercaseMd5() {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("foo", "bar");

        String query = AppSign.sign(params);
        Map<String, String> parsed = parseQuery(query);

        assertEquals("appkey 应是常量", AppSign.APP_KEY, parsed.get("appkey"));
        assertNotNull("ts 必须写入", parsed.get("ts"));
        assertTrue("ts 应是 10 位秒级时间戳：" + parsed.get("ts"),
                parsed.get("ts").matches("\\d{10}"));
        long ts = Long.parseLong(parsed.get("ts"));
        long nowSeconds = System.currentTimeMillis() / 1000L;
        assertTrue("ts 应接近当前时间，实际 " + ts + " 当前 " + nowSeconds,
                Math.abs(nowSeconds - ts) <= 5L);

        String sign = parsed.get("sign");
        assertNotNull("sign 必须写入", sign);
        assertTrue("sign 应是 32 位小写 md5：" + sign, sign.matches("[0-9a-f]{32}"));

        // 业务结果：sign == md5(去掉 sign 后按 key 排序的 query + appsec)
        parsed.remove("sign");
        String raw = AppSign.encodeQuery(parsed);
        assertEquals(ToolsUtil.md5(raw + AppSign.APP_SEC), sign);

        // 就地签名：调用方传入的 TreeMap 里也能看到这三个参数
        assertEquals(AppSign.APP_KEY, params.get("appkey"));
        assertEquals(sign, params.get("sign"));
        assertEquals(parsed.get("ts"), params.get("ts"));
    }

    @Test
    public void signEncodesValuesInReturnedQuery() {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("q", "a b");
        String query = AppSign.sign(params);
        assertTrue("query 里不该出现 '+' 编码：" + query, query.contains("q=a%20b"));
        assertTrue("query 里不该出现原始的 '+'：" + query, !query.contains("q=a+b"));
        Map<String, String> parsed = parseQuery(query);
        assertEquals("a b", parsed.get("q"));
    }

    @Test
    public void signKeepsParameterOrderStableAcrossCalls() {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("zeta", "1");
        params.put("alpha", "2");
        params.put("middle", "3");

        String first = AppSign.sign(params);
        String second = AppSign.sign(params);
        assertEquals("同一参数表两次签名，key 顺序必须一致", keysOf(first), keysOf(second));

        TreeMap<String, String> another = new TreeMap<>();
        another.put("middle", "x");
        another.put("alpha", "y");
        another.put("zeta", "z");
        String third = AppSign.sign(another);
        assertEquals("不同插入顺序、同 key 集合，签名 query 的 key 顺序必须一致",
                keysOf(first), keysOf(third));

        List<String> expected = new ArrayList<>();
        expected.add("alpha");
        expected.add("appkey");
        expected.add("middle");
        expected.add("sign");
        expected.add("ts");
        expected.add("zeta");
        assertEquals(expected, keysOf(first));
    }

    @Test
    public void signIsAlways32HexCharsAcrossManyInputs() {
        // MD5 首字节 <0x10 时 BigInteger.toString(16) 会少一位；批量输入覆盖这条路径，
        // 防止「偶尔少一位 sign」让接口签名随机失效（曾在本机复现 31 位 sign）。
        for (int i = 0; i < 300; i++) {
            TreeMap<String, String> params = new TreeMap<>();
            params.put("probe", "value-" + i);
            Map<String, String> parsed = parseQuery(AppSign.sign(params));
            String sign = parsed.get("sign");
            assertNotNull(sign);
            assertEquals("第 " + i + " 次签名长度异常：" + sign, 32, sign.length());
            assertTrue("第 " + i + " 次签名含非小写十六进制字符：" + sign,
                    sign.matches("[0-9a-f]{32}"));
        }
    }

    // ---------------------------------------------------------------- ToolsUtil.md5

    @Test
    public void md5MatchesKnownVectors() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", ToolsUtil.md5("abc"));
        // 首字节 <0x10 的已知向量：必须补足 32 位，否则 B 站 sign 会少一位
        assertEquals("018541bebe5c87dd495e8ceb894a030c", ToolsUtil.md5("leading-zero-25"));
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", ToolsUtil.md5(""));
        assertEquals("9e107d9d372bb6826bd81d3542a419d6",
                ToolsUtil.md5("The quick brown fox jumps over the lazy dog"));
        // 长度/大小写是接口约定（sign 断言依赖它）
        assertEquals(32, ToolsUtil.md5("abc").length());
        assertTrue(ToolsUtil.md5("abc").matches("[0-9a-f]{32}"));
    }

    // ---------------------------------------------------------------- 辅助

    private static Map<String, String> parseQuery(String query) {
        TreeMap<String, String> parsed = new TreeMap<>();
        if (query == null || query.isEmpty()) return parsed;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            parsed.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
        }
        return parsed;
    }

    private static List<String> keysOf(String query) {
        List<String> keys = new ArrayList<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            keys.add(eq < 0 ? pair : pair.substring(0, eq));
        }
        return keys;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError("测试环境不支持 UTF-8", e);
        }
    }
}