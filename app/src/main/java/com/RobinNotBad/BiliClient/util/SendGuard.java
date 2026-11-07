package com.RobinNotBad.BiliClient.util;

import android.os.SystemClock;

import java.util.HashMap;
import java.util.Map;

/**
 * 发送缓冲：同一类发送操作在窗口期内只接受第一次请求，其余直接拒绝并提示。
 *
 * <p>用户反馈（2026-11-07）：评论 / 私信 / 动态在卡顿或手抖连点时会连发好几条，
 * 服务端并不做去重，发出去几条就是几条。原来的 {@link ClickGuard} 只按"同一个控件
 * 250ms"防抖，既没有提示，也拦不住"发送完成后马上又点一次"。
 *
 * <p>这里按"操作类型 + 目标"建窗口（默认 3 秒）：第一次放行，窗口内的其余请求一律拒绝，
 * 并弹「请勿频繁点击」，用户能明确知道自己点多了。
 */
public final class SendGuard {

    /** 窗口期：短到不影响正常连续操作，长到能挡住手抖连点 */
    private static final long WINDOW_MS = 3000;

    private static final Map<String, Long> lastSend = new HashMap<>();

    private SendGuard() {
    }

    /**
     * 尝试占用一次发送机会。
     *
     * @param key 操作类型 + 目标（如 {@code "reply_12345"}），不同目标互不影响
     * @return true = 放行；false = 窗口内重复请求，已弹出提示
     */
    public static boolean tryAcquire(String key) {
        long now = SystemClock.uptimeMillis();
        Long last = lastSend.get(key);
        if (last != null && now - last < WINDOW_MS) {
            MsgUtil.showMsg("请勿频繁点击");
            return false;
        }
        lastSend.put(key, now);
        return true;
    }

    /** 发送失败时调用：允许用户立刻重试，不必干等窗口结束 */
    public static void reset(String key) {
        lastSend.remove(key);
    }
}
