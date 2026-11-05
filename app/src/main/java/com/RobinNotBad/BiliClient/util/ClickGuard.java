package com.RobinNotBad.BiliClient.util;

import android.os.SystemClock;
import android.view.View;

import java.util.WeakHashMap;

/**
 * 点击防抖：同一控件在窗口期内只放行一次点击。
 *
 * <p>为什么要有它：全站列表点击此前零防抖（批次2/X-01），手表卡顿下双击会连开两个页面、
 * 发送类按钮会重复发请求。各 Adapter 的 onClick 包一层 {@link #protect(View, Runnable)} 即可。
 *
 * <p>时间戳按 View 记（WeakHashMap，不阻止回收）：全局单时间戳会误伤"250ms 内先后点不同按钮"的正常操作。
 * 线程安全：只要求在主线程调用（View 点击回调本来就在主线程）。
 */
public final class ClickGuard {

    /** 同一控件的两次有效点击最小间隔 */
    private static final long INTERVAL_MS = 250;

    private static final WeakHashMap<View, Long> lastClicks = new WeakHashMap<>();

    private ClickGuard() {
    }

    /** @return true = 处于防抖窗口内，本次点击应忽略 */
    public static boolean isBlocked(View view) {
        long now = SystemClock.uptimeMillis();
        Long last = lastClicks.get(view);
        if (last != null && now - last < INTERVAL_MS) return true;
        lastClicks.put(view, now);
        return false;
    }

    /** 最省事的接入方式：把原 onClick 逻辑包进来 */
    public static void protect(View view, Runnable action) {
        if (isBlocked(view)) return;
        action.run();
    }
}
