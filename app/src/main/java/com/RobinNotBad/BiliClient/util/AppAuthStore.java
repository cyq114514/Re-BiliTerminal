package com.RobinNotBad.BiliClient.util;

/**
 * App 侧凭证（access_key）的按账号存储。
 *
 * <p>为什么按 mid 分 key：本项目支持多账号切换，而网页 Cookie 通道本来就按账号隔离；
 * access_key 如果只存全局，切号后会把 A 的卡片发到 B 的账号上。按 mid 命名后，
 * 每个账号的授权互不影响，退出登录也不会误删别的账号。
 *
 * <p>凭证只用于 App gRPC 发私信卡片，不参与网页请求；所有日志/Toast 都不打印它。
 */
public final class AppAuthStore {

    private static final String KEY_ACCESS = "app_access_key_";
    private static final String KEY_REFRESH = "app_refresh_token_";

    private AppAuthStore() {
    }

    public static String getAccessKey(long mid) {
        if (mid <= 0) return "";
        return SharedPreferencesUtil.getString(KEY_ACCESS + mid, "");
    }

    public static String getRefreshToken(long mid) {
        if (mid <= 0) return "";
        return SharedPreferencesUtil.getString(KEY_REFRESH + mid, "");
    }

    public static boolean hasAccessKey(long mid) {
        return !getAccessKey(mid).isEmpty();
    }

    public static void save(long mid, String accessKey, String refreshToken) {
        if (mid <= 0 || accessKey == null || accessKey.isEmpty()) return;
        SharedPreferencesUtil.putString(KEY_ACCESS + mid, accessKey);
        if (refreshToken != null && !refreshToken.isEmpty())
            SharedPreferencesUtil.putString(KEY_REFRESH + mid, refreshToken);
    }

    public static void clear(long mid) {
        if (mid <= 0) return;
        SharedPreferencesUtil.removeValue(KEY_ACCESS + mid);
        SharedPreferencesUtil.removeValue(KEY_REFRESH + mid);
    }
}
