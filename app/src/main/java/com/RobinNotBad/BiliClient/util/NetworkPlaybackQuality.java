package com.RobinNotBad.BiliClient.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;

/**
 * 按网络类型（Wi-Fi / 移动数据）取播放默认清晰度。
 *
 * <p>设置键：
 * <ul>
 *   <li>{@link #DEFAULT}：全局默认（原有行为，不动）；</li>
 *   <li>{@link #MOBILE}：移动数据下的默认，-1 表示跟随全局；</li>
 *   <li>{@link #WIFI}：Wi-Fi 下的默认，-1 表示跟随全局。</li>
 * </ul>
 * 手动选过清晰度时不会走这里，避免被网络默认值覆盖。
 */
public final class NetworkPlaybackQuality {
    public static final String DEFAULT = "play_qn", MOBILE = "play_qn_mobile", WIFI = "play_qn_wifi";
    public static final String EXTRA_KEY = "quality_preference_key";

    private NetworkPlaybackQuality() {
    }

    public static String preferenceKey(String key) {
        if (MOBILE.equals(key)) return MOBILE;
        if (WIFI.equals(key)) return WIFI;
        return DEFAULT;
    }

    public static int defaultQuality(Context context) {
        return requestedQuality(context, -1);
    }

    public static int requestedQuality(Context context, int manual) {
        if (SharedPreferencesUtil.getSharedPreferences() == null) {
            return PlaybackQualityPolicy.request(manual, PlaybackQualityPolicy.UNKNOWN, 16, -1, -1);
        }
        return PlaybackQualityPolicy.request(manual, network(context),
                SharedPreferencesUtil.getInt(DEFAULT, 16),
                SharedPreferencesUtil.getInt(MOBILE, -1),
                SharedPreferencesUtil.getInt(WIFI, -1));
    }

    public static int network(Context context) {
        if (context == null) return PlaybackQualityPolicy.UNKNOWN;
        try {
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return PlaybackQualityPolicy.UNKNOWN;
            NetworkInfo active = manager.getActiveNetworkInfo();
            if (active == null || !active.isConnected()) return PlaybackQualityPolicy.UNKNOWN;
            int type = active.getType();
            if (type == ConnectivityManager.TYPE_WIFI || type == ConnectivityManager.TYPE_ETHERNET) {
                return PlaybackQualityPolicy.WIFI;
            }
            if (type == ConnectivityManager.TYPE_MOBILE
                    || type == ConnectivityManager.TYPE_MOBILE_DUN
                    || type == ConnectivityManager.TYPE_MOBILE_HIPRI
                    || type == ConnectivityManager.TYPE_MOBILE_MMS
                    || type == ConnectivityManager.TYPE_MOBILE_SUPL
                    || type == ConnectivityManager.TYPE_WIMAX) {
                return PlaybackQualityPolicy.MOBILE;
            }
            if (type == ConnectivityManager.TYPE_VPN) {
                if (Build.VERSION.SDK_INT >= 23) {
                    int underlying = Api23.network(manager);
                    if (underlying != PlaybackQualityPolicy.UNKNOWN) return underlying;
                }
                return manager.isActiveNetworkMetered() ? PlaybackQualityPolicy.MOBILE : PlaybackQualityPolicy.WIFI;
            }
        } catch (RuntimeException ignored) {
        }
        return PlaybackQualityPolicy.UNKNOWN;
    }

    /** API 23+ 才有的 NetworkCapabilities 检测，单独包一层，避免 4.x 上触发 StrictMode。 */
    @android.annotation.TargetApi(23)
    private static final class Api23 {
        static int network(ConnectivityManager manager) {
            android.net.NetworkCapabilities caps = manager.getNetworkCapabilities(manager.getActiveNetwork());
            if (caps != null) {
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) return PlaybackQualityPolicy.MOBILE;
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                        || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    return PlaybackQualityPolicy.WIFI;
                }
            }
            return PlaybackQualityPolicy.UNKNOWN;
        }
    }
}
