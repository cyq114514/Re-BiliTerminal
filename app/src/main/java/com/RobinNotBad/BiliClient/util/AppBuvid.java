package com.RobinNotBad.BiliClient.util;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * App 端推荐请求头 {@code buvid} 的设备标识。
 *
 * <p><b>为什么单独存一份：</b>Cookie 里的 {@code buvid3} 与 App 接口读的 {@code buvid} 头
 * 不是同一个东西。2026-10-31 匿名实测（同参数、同 IP、各 2~3 次）：
 * <pre>
 * 只带 Cookie buvid3 : 10 条 / 0 条带推荐理由
 * 只带 buvid 头(同值) : 12 条 / 2 条带推荐理由
 * </pre>
 * 也就是说不发这个头时服务端拿不到设备身份，返回条数少且不给推荐理由。
 * 因此这里**不能**复用 Cookie 里的 buvid3，必须独立生成、独立持久化。
 *
 * <p>形态与 PiliPlus 的 {@code LoginUtils.generateBuvid()} 一致：
 * {@code "XY" + md5[2] + md5[12] + md5[22] + md5}，共 37 位大写十六进制（前缀 XY），
 * 字符集 {@code [0-9A-Za-z_-]}，与官方客户端的 {@code XY} 开头设备号同形。
 *
 * <p><b>隐私提示：</b>这是长期设备标识，B 站可用它把同一设备的多个账号关联起来。
 * 所以这里只存储、不在日志里打印完整值（{@link #describe()} 只给前 4 位）。
 */
public final class AppBuvid {

    /** 设置页 input_string 用的键名，与 {@code SettingPrefActivity} 里登记的 id 必须一致 */
    public static final String KEY = "app_buvid";

    private static final String HEX = "0123456789ABCDEF";
    private static final SecureRandom RANDOM = new SecureRandom();

    private AppBuvid() {
    }

    /** 取当前设备标识；尚未生成或本地值非法时生成一份并落库。 */
    public static String get() {
        String cached = SharedPreferencesUtil.getString(KEY, "");
        if (isValid(cached)) return cached;
        String generated = generate();
        SharedPreferencesUtil.putString(KEY, generated);
        return generated;
    }

    /**
     * 写入用户自定义的设备标识。非法值直接忽略（返回 false），不会覆盖已有值。
     * 用 {@code putString} 而非同步写：这个值下次冷启动才需要，不阻塞当前线程。
     */
    public static boolean set(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (!isValid(trimmed)) return false;
        SharedPreferencesUtil.putString(KEY, trimmed);
        return true;
    }

    /** 生成一份新的设备标识（不落库）。 */
    public static String generate() {
        //ToolsUtil.md5 只接受字符串，这里先用 16 字节随机数铺成 32 位十六进制当种子，
        //再做一次 md5 —— 与 PiliPlus 直接对 16 字节随机数求 Digest 的随机性等价
        byte[] seed = new byte[16];
        RANDOM.nextBytes(seed);
        StringBuilder hex = new StringBuilder(32);
        for (byte b : seed) {
            hex.append(HEX.charAt((b >> 4) & 0xF)).append(HEX.charAt(b & 0xF));
        }
        String md5 = ToolsUtil.md5(hex.toString()).toUpperCase(Locale.ROOT);
        return "XY" + md5.charAt(2) + md5.charAt(12) + md5.charAt(22) + md5;
    }

    /** 长度 30~40、字符集 {@code [0-9A-Za-z_-]}：既接受自动生成值，也接受官方客户端的抓包值。 */
    public static boolean isValid(String value) {
        if (value == null) return false;
        int len = value.length();
        if (len < 30 || len > 40) return false;
        for (int i = 0; i < len; i++) {
            char c = value.charAt(i);
            boolean ok = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || c == '_' || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    /** 日志用：只给前缀，不打印完整设备标识。 */
    public static String describe() {
        String v = SharedPreferencesUtil.getString(KEY, "");
        if (v.isEmpty()) return "(未生成)";
        return v.length() <= 4 ? "****" : v.substring(0, 4) + "***(len=" + v.length() + ")";
    }
}
