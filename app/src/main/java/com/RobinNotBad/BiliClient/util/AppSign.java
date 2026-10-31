package com.RobinNotBad.BiliClient.util;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Map;
import java.util.TreeMap;

/**
 * App 接口签名（{@code appkey + ts + sign}）。
 *
 * <p>算法与 PiliPlus 的 {@code utils/app_sign.dart} 一致，也与本项目 {@code LoginApi} 里
 * TV 扫码登录用的那套一致：参数按 key 字典序拼接 → 末尾拼 {@code appsec} → md5 → 作为 {@code sign}。
 *
 * <p><b>为什么单独抽出来：</b>常量（appkey/appsec）此前只写在 {@code LoginApi} 里，
 * 现在推荐接口也要用同一对值。常量只留在这里一份，{@code LoginApi} 改为引用本类，
 * 避免"两个地方各写一份 appsec，改了一个忘了另一个"。
 *
 * <p><b>风险提示：</b>这对值是官方 Android HD 客户端的公开内置值，不是通用凭证。
 * 官方调整客户端版本或加新必需参数后可能失效；调用方必须允许"签名失败/被拒时回退到不签名"。
 */
public final class AppSign {

    /** 官方 Android HD 客户端 appkey（公开内置值，与 PiliPlus {@code Constants.appKey} 相同） */
    public static final String APP_KEY = "dfca71928277209b";
    /** 对应 appsec，只参与 md5，不出现在请求里 */
    public static final String APP_SEC = "b5475a8825547a4fc26c7d518eaaa02e";

    private AppSign() {
    }

    /**
     * 就地签名：向 params 写入 {@code appkey} / {@code ts} / {@code sign}，返回按 key 排序并
     * 做过 URL 编码的 query 串（不含前导 {@code ?}）。
     *
     * <p>用 {@link TreeMap} 而不是 HashMap：签名要求参数有序，且这个 query 会直接拼进 URL，
     * 顺序必须可复现，否则排查时两次请求的串对不上。
     */
    public static String sign(TreeMap<String, String> params) {
        params.put("appkey", APP_KEY);
        params.put("ts", String.valueOf(System.currentTimeMillis() / 1000));
        //先把不带 sign 的串算出来，sign 不能参与自己的签名
        String raw = encodeQuery(params);
        params.put("sign", ToolsUtil.md5(raw + APP_SEC));
        return encodeQuery(params);
    }

    /** 只拼接、不签名（未开启签名时用，保证 URL 顺序稳定）。 */
    public static String encodeQuery(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(urlEncode(entry.getKey())).append('=').append(urlEncode(entry.getValue()));
        }
        return sb.toString();
    }

    /** 与 {@code URLEncoder} 的 "+ 表空格" 不同，URL 里空格应编码成 %20，这里统一处理。 */
    private static String urlEncode(String value) {
        if (value == null) return "";
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }
}
