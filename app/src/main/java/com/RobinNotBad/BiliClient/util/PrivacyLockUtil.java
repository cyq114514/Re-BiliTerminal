package com.RobinNotBad.BiliClient.util;

import java.security.MessageDigest;

/**
 * 隐私锁：开启后冷启动需先在计算器页（CalculatorActivity）输入密码。
 *
 * <p>密码只存「固定盐 + 明文」的 SHA-256，不落明文——防的是「直接翻 SharedPreferences 文件」。
 * 盐硬编码在应用里，逆向 APK 可以拿到，所以这只挡君子不挡拆包人：本功能的威胁模型是
 * 防熟人随手翻看，不是防取证，别当成保险箱用。忘了密码没有后门，只能去系统设置清数据。
 */
public class PrivacyLockUtil {
    /** SplashActivity 用它识别「已经验过密码」的回跳（伪装模式下计算器页解锁后跳 Splash）。 */
    public static final String EXTRA_UNLOCKED = "privacy_lock_already_unlocked";

    private static final String KEY_ENABLE = "privacy_lock_enable";
    private static final String KEY_HASH = "privacy_lock_password_hash";
    private static final String SALT = "ReBiliTerminal::privacy_lock::v1";

    /** 锁是否生效：开关打开且已设密码（只开开关没设密码视为未生效）。 */
    public static boolean isEnabled() {
        return SharedPreferencesUtil.getBoolean(KEY_ENABLE, false) && hasPassword();
    }

    public static boolean hasPassword() {
        return !SharedPreferencesUtil.getString(KEY_HASH, "").isEmpty();
    }

    public static void setPassword(String plain) {
        SharedPreferencesUtil.putString(KEY_HASH, sha256(plain));
    }

    public static boolean verify(String plain) {
        return plain != null && hasPassword() && sha256(plain).equals(SharedPreferencesUtil.getString(KEY_HASH, ""));
    }

    public static void setEnabled(boolean enable) {
        SharedPreferencesUtil.putBoolean(KEY_ENABLE, enable);
    }

    /**设置页开关的显示状态（用户拨过的原始开关位；生效与否还要看有没有密码，见 isEnabled）。 */
    public static boolean isSwitchOn() {
        return SharedPreferencesUtil.getBoolean(KEY_ENABLE, false);
    }

    private static String sha256(String plain) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((SALT + plain).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            //SHA-256 在所有 Android 支持的平台上都存在，理论到不了这里；真到了就按无密码处理而不是崩
            return "";
        }
    }
}
