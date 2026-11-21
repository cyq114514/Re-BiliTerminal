package com.RobinNotBad.BiliClient.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 自动检查更新的节流策略测试。
 *
 * <p>2026-11-21 用户反馈「开关开了仍收不到更新」：原实现一个进程只试一次、失败静默吞掉。
 * 这里把「什么时候该检查」钉死：成功 6 小时后才再查；失败 30 分钟后重试；
 * 开关关闭或已有请求在飞时不查。
 */
@RunWith(RobolectricTestRunner.class)
public class UpdateCheckPolicyTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;

    @Test
    public void disabledNeverChecks() {
        assertFalse(UpdateManager.shouldCheck(false, false, NOW, 0L, 0L));
        assertFalse(UpdateManager.shouldCheck(false, false, NOW, NOW - 24 * HOUR, 0L));
    }

    @Test
    public void inFlightNeverChecks() {
        assertFalse(UpdateManager.shouldCheck(true, true, NOW, 0L, 0L));
    }

    @Test
    public void firstRunChecks() {
        assertTrue(UpdateManager.shouldCheck(true, false, NOW, 0L, 0L));
    }

    @Test
    public void recentSuccessSkips() {
        assertFalse(UpdateManager.shouldCheck(true, false, NOW, NOW - 6 * HOUR + MINUTE, 0L));
    }

    @Test
    public void successOlderThanSixHoursChecks() {
        assertTrue(UpdateManager.shouldCheck(true, false, NOW, NOW - 6 * HOUR, 0L));
        assertTrue(UpdateManager.shouldCheck(true, false, NOW, NOW - 24 * HOUR, 0L));
    }

    @Test
    public void failureRetriesAfterCooldown() {
        // 失败后 30 分钟内不重试，超过后立刻重试（lastSuccess 仍为 0）
        assertFalse(UpdateManager.shouldCheck(true, false, NOW, 0L, NOW - 10 * MINUTE));
        assertTrue(UpdateManager.shouldCheck(true, false, NOW, 0L, NOW - 31 * MINUTE));
    }

    @Test
    public void recentSuccessBeatsOldAttempt() {
        // 成功检查过一次后，即便上一条失败尝试已过冷却，也不该再联网
        assertFalse(UpdateManager.shouldCheck(true, false, NOW, NOW - 1 * HOUR, NOW - 31 * MINUTE));
    }

    @Test
    public void clockRollbackChecks() {
        assertTrue(UpdateManager.shouldCheck(true, false, NOW, NOW + 1 * HOUR, 0L));
    }
}