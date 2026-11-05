package com.RobinNotBad.BiliClient.util;

/**
 * 播放清晰度的纯逻辑策略：把「手动选的画质」和「按网络取的默认画质」合成一个最终请求值。
 *
 * <p>约定：
 * <ul>
 *   <li>手动选过就永远优先，不会被网络默认值覆盖；</li>
 *   <li>未选过时按网络取默认；网络识别不了就回退全局默认；</li>
 *   <li>-1 表示“跟随全局”，不是合法画质，只在网络分档里出现。</li>
 * </ul>
 */
public final class PlaybackQualityPolicy {
    public static final int UNKNOWN = 0, MOBILE = 1, WIFI = 2, INHERIT = -1;

    private PlaybackQualityPolicy() {
    }

    /** 当前工程实际支持的清晰度档位；新增档位时要同步这里。 */
    public static boolean valid(int quality) {
        return quality == 16 || quality == 64 || quality == 80;
    }

    public static int resolve(int network, int global, int mobile, int wifi) {
        int fallback = valid(global) ? global : 16;
        int choice = network == MOBILE ? mobile : network == WIFI ? wifi : INHERIT;
        if (choice == INHERIT || !valid(choice)) return fallback;
        return choice;
    }

    public static int request(int manual, int network, int global, int mobile, int wifi) {
        if (valid(manual)) return manual;
        return resolve(network, global, mobile, wifi);
    }
}
