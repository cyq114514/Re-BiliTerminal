package com.RobinNotBad.BiliClient.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 播放器精确跳转与横滑灵敏度的纯逻辑测试。
 *
 * <p>这些值直接决定用户输入会被放大/缩放到哪一帧，边界（≥60 的秒、999999 秒上限、
 * 非法字符）都要按实现契约钉住，避免「输入 1:60 跳到一个奇怪位置」这类问题回归。
 */
@RunWith(RobolectricTestRunner.class)
public class PlayerSeekPoliciesTest {

    private static final float DELTA = 0.0001f;

    @Test
    public void swipeScaleFromStringOnlyAcceptsSupportedLevels() {
        assertEquals(0.25f, PlayerSeekPolicies.swipeScale("0.25"), DELTA);
        assertEquals(0.5f, PlayerSeekPolicies.swipeScale("0.5"), DELTA);
        assertEquals(1.0f, PlayerSeekPolicies.swipeScale("1.0"), DELTA);
        assertEquals(1.5f, PlayerSeekPolicies.swipeScale("1.5"), DELTA);
        assertEquals(2.0f, PlayerSeekPolicies.swipeScale("2.0"), DELTA);
        assertEquals(3.0f, PlayerSeekPolicies.swipeScale("3.0"), DELTA);

        // 前后空白被忽略
        assertEquals(2.0f, PlayerSeekPolicies.swipeScale("  2.0  "), DELTA);
        assertEquals(0.5f, PlayerSeekPolicies.swipeScale("0.5001"), DELTA); // 0.005 容差内归档

        // 空值/非数字/档位外的值退回默认 1:1，绝不能让 NaN/负数进到进度计算里
        assertEquals(PlayerSeekPolicies.DEFAULT_SCALE, PlayerSeekPolicies.swipeScale((String) null), DELTA);
        assertEquals(PlayerSeekPolicies.DEFAULT_SCALE, PlayerSeekPolicies.swipeScale(""), DELTA);
        assertEquals(PlayerSeekPolicies.DEFAULT_SCALE, PlayerSeekPolicies.swipeScale("   "), DELTA);
        assertEquals(PlayerSeekPolicies.DEFAULT_SCALE, PlayerSeekPolicies.swipeScale("abc"), DELTA);
        assertEquals(PlayerSeekPolicies.DEFAULT_SCALE, PlayerSeekPolicies.swipeScale("1.2"), DELTA);
        assertEquals(PlayerSeekPolicies.DEFAULT_SCALE, PlayerSeekPolicies.swipeScale("0"), DELTA);
        assertEquals(PlayerSeekPolicies.DEFAULT_SCALE, PlayerSeekPolicies.swipeScale("-2.0"), DELTA);
        assertEquals(PlayerSeekPolicies.DEFAULT_SCALE, PlayerSeekPolicies.swipeScale("NaN"), DELTA);
        assertEquals(PlayerSeekPolicies.DEFAULT_SCALE, PlayerSeekPolicies.swipeScale("Infinity"), DELTA);
    }

    @Test
    public void swipeScaleFromFloatOnlyAcceptsSupportedLevels() {
        for (float supported : new float[]{0.25f, 0.5f, 1.0f, 1.5f, 2.0f, 3.0f}) {
            assertEquals(supported, PlayerSeekPolicies.swipeScale(supported), DELTA);
        }
        assertEquals(PlayerSeekPolicies.DEFAULT_SCALE, PlayerSeekPolicies.swipeScale(0f), DELTA);
        assertEquals(PlayerSeekPolicies.DEFAULT_SCALE, PlayerSeekPolicies.swipeScale(2.5f), DELTA);
        assertEquals(PlayerSeekPolicies.DEFAULT_SCALE, PlayerSeekPolicies.swipeScale(-1.5f), DELTA);
        assertEquals(PlayerSeekPolicies.DEFAULT_SCALE, PlayerSeekPolicies.swipeScale(Float.NaN), DELTA);
        assertEquals(PlayerSeekPolicies.DEFAULT_SCALE, PlayerSeekPolicies.swipeScale(100f), DELTA);
        assertEquals(1.0f, PlayerSeekPolicies.DEFAULT_SCALE, DELTA);
    }

    @Test
    public void parseJumpSecondsAcceptsSecondsMmSsAndHhMmSs() {
        assertEquals(Long.valueOf(90L), PlayerSeekPolicies.parseJumpSeconds("90"));
        assertEquals(Long.valueOf(0L), PlayerSeekPolicies.parseJumpSeconds("0"));
        assertEquals(Long.valueOf(90L), PlayerSeekPolicies.parseJumpSeconds("01:30"));
        assertEquals(Long.valueOf(3723L), PlayerSeekPolicies.parseJumpSeconds("01:02:03"));
        assertEquals(Long.valueOf(3723L), PlayerSeekPolicies.parseJumpSeconds("1:2:3"));
        assertEquals(Long.valueOf(5400L), PlayerSeekPolicies.parseJumpSeconds("1:30:00"));
        // 两段式里第一段是分钟，可以超过 60
        assertEquals(Long.valueOf(3600L), PlayerSeekPolicies.parseJumpSeconds("60:00"));
        // 纯秒数上限 999999
        assertEquals(Long.valueOf(999_999L), PlayerSeekPolicies.parseJumpSeconds("999999"));
    }

    @Test
    public void parseJumpSecondsAcceptsWhitespaceAndFullWidthColon() {
        assertEquals(Long.valueOf(90L), PlayerSeekPolicies.parseJumpSeconds(" 90 "));
        assertEquals(Long.valueOf(90L), PlayerSeekPolicies.parseJumpSeconds("1：30"));
        assertEquals(Long.valueOf(3723L), PlayerSeekPolicies.parseJumpSeconds(" 01：02：03 "));
    }

    @Test
    public void parseJumpSecondsRejectsInvalidSecondsAndFormats() {
        // 空/非法字符
        assertNull(PlayerSeekPolicies.parseJumpSeconds(null));
        assertNull(PlayerSeekPolicies.parseJumpSeconds(""));
        assertNull(PlayerSeekPolicies.parseJumpSeconds("   "));
        assertNull(PlayerSeekPolicies.parseJumpSeconds("abc"));
        assertNull(PlayerSeekPolicies.parseJumpSeconds("-5"));
        assertNull(PlayerSeekPolicies.parseJumpSeconds("+90"));
        assertNull(PlayerSeekPolicies.parseJumpSeconds("1:2:3:4")); // 四段
        assertNull(PlayerSeekPolicies.parseJumpSeconds("1::30"));   // 空段
        assertNull(PlayerSeekPolicies.parseJumpSeconds("1.5"));     // 小数不支持
        assertNull(PlayerSeekPolicies.parseJumpSeconds("1 2"));     // 内部空格
        // 分/秒必须 < 60
        assertNull(PlayerSeekPolicies.parseJumpSeconds("1:60"));
        assertNull(PlayerSeekPolicies.parseJumpSeconds("1:99"));
        assertNull(PlayerSeekPolicies.parseJumpSeconds("00:01:60"));
        assertNull(PlayerSeekPolicies.parseJumpSeconds("1:60:00"));
        // 单段超长
        assertNull(PlayerSeekPolicies.parseJumpSeconds("1000000"));
    }

    @Test
    public void clampJumpMsBoundsIntoValidDuration() {
        assertEquals(Long.valueOf(5000L), PlayerSeekPolicies.clampJumpMs(5000L, 10000L));
        assertEquals(Long.valueOf(10000L), PlayerSeekPolicies.clampJumpMs(20000L, 10000L));
        assertEquals(Long.valueOf(0L), PlayerSeekPolicies.clampJumpMs(-1L, 10000L));
        assertEquals(Long.valueOf(0L), PlayerSeekPolicies.clampJumpMs(0L, 10000L));
        assertEquals(Long.valueOf(0L), PlayerSeekPolicies.clampJumpMs(Long.MIN_VALUE, 10000L));
        assertEquals(Long.valueOf(10000L), PlayerSeekPolicies.clampJumpMs(Long.MAX_VALUE, 10000L));

        // 时长未知（直播/未 prepare）不能跳
        assertNull(PlayerSeekPolicies.clampJumpMs(1000L, 0L));
        assertNull(PlayerSeekPolicies.clampJumpMs(1000L, -1L));
    }

    @Test
    public void jumpHintDescribesLiveAndFormatsMaxPosition() {
        assertEquals("直播不支持跳转", PlayerSeekPolicies.jumpHint(0L));
        assertEquals("直播不支持跳转", PlayerSeekPolicies.jumpHint(-1000L));

        assertEquals("输入秒数或 mm:ss / hh:mm:ss，最长 00:00:01",
                PlayerSeekPolicies.jumpHint(1000L));
        assertEquals("输入秒数或 mm:ss / hh:mm:ss，最长 00:01:00",
                PlayerSeekPolicies.jumpHint(60000L));
        assertEquals("输入秒数或 mm:ss / hh:mm:ss，最长 01:02:03",
                PlayerSeekPolicies.jumpHint(3723000L));
        assertEquals("输入秒数或 mm:ss / hh:mm:ss，最长 01:00:00",
                PlayerSeekPolicies.jumpHint(3600000L));
        // 不足 1 秒的零头被截掉
        assertEquals("输入秒数或 mm:ss / hh:mm:ss，最长 00:00:59",
                PlayerSeekPolicies.jumpHint(59999L));
    }
}