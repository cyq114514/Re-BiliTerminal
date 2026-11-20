package com.RobinNotBad.BiliClient.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.RobinNotBad.BiliClient.model.ClipInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

/**
 * 番剧片头/片尾跳过状态机测试。
 *
 * <p>重点覆盖节流与防死循环：2 秒 seek 冷却、「越过 end+3s 才重新武装」的闩锁、
 * 「跳过一次」档位手动拖回不再跳，以及片段数据越界/临近结尾等不跳条件。
 *
 * <p>冷却用的是真实 {@code System.currentTimeMillis()}，测试通过反射直接改写私有
 * {@code lastSkipAt} 来模拟「时间已经过去」——比 sleep 2 秒稳定，也不让测试变慢。
 */
@RunWith(RobolectricTestRunner.class)
public class SegmentSkipControllerTest {

    private static final double OP_START = 0;
    private static final double OP_END = 89;
    private static final long OP_END_MS = 89_000L;

    private static ClipInfo op() {
        return new ClipInfo(ClipInfo.TYPE_OP, OP_START, OP_END);
    }

    private static ClipInfo ed() {
        return new ClipInfo(ClipInfo.TYPE_ED, 1300, 1419);
    }

    private static SegmentSkipController controller(SegmentSkipMode mode, ClipInfo... clips) {
        return new SegmentSkipController(new ArrayList<>(Arrays.asList(clips)), mode);
    }

    /** 把「上次跳转时刻」改到过去，等价于冷却时间已过（不 sleep，避免测试变慢/不稳定） */
    private static void cooldownElapsed(SegmentSkipController c) {
        setLastSkipAt(c, System.currentTimeMillis() - 60_000L);
    }

    private static void setLastSkipAt(SegmentSkipController c, long value) {
        try {
            Field field = SegmentSkipController.class.getDeclaredField("lastSkipAt");
            field.setAccessible(true);
            field.setLong(c, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("无法改写 lastSkipAt: " + e);
        }
    }

    @Test
    public void alwaysSkipsOnEnteringSegmentAndLatchesWithinOnePass() {
        SegmentSkipController c = controller(SegmentSkipMode.ALWAYS, op());
        assertTrue(c.isEnabled());

        assertEquals(OP_END_MS, c.onTick(5_000L));
        // 同一次穿越内不再重复跳（seek 期间 tick 仍可能读到区间内的旧位置）
        assertEquals(-1L, c.onTick(10_000L));
        cooldownElapsed(c);
        assertEquals(-1L, c.onTick(30_000L));
        // 片段之外本来就不跳
        assertEquals(-1L, c.onTick(120_000L));
    }

    @Test
    public void rearmRequiresPassingSegmentEndPlusMargin() {
        SegmentSkipController c = controller(SegmentSkipMode.ALWAYS, op());
        assertEquals(OP_END_MS, c.onTick(5_000L));

        // 只到 end+2.9s 还没有越过 3 秒余量：仍然保持闩锁
        assertEquals(-1L, c.onTick(OP_END_MS + 2_900L));
        cooldownElapsed(c);
        assertEquals("未越过 end+3s 前不能重新武装", -1L, c.onTick(60_000L));

        // 越过 end+3s 完成这次穿越，重新武装
        assertEquals(-1L, c.onTick(OP_END_MS + 3_000L));
        cooldownElapsed(c);
        assertEquals(OP_END_MS, c.onTick(60_000L));
    }

    @Test
    public void onceModeSkipsOnlyOnceEvenWhenUserDragsBack() {
        SegmentSkipController c = controller(SegmentSkipMode.ONCE, op());
        assertEquals(OP_END_MS, c.onTick(5_000L));

        // 用户被跳到 89s，继续播过 end+3s 后本次穿越结束，但 ONCE 不会重新武装
        assertEquals(-1L, c.onTick(OP_END_MS + 3_000L));
        cooldownElapsed(c);
        assertEquals("跳过一次：手动拖回片段内不能再跳", -1L, c.onTick(5_000L));
        assertEquals(-1L, c.onTick(80_000L));

        // 其它片段仍然可以跳一次
        SegmentSkipController twoClips = controller(SegmentSkipMode.ONCE, op(), ed());
        assertEquals(OP_END_MS, twoClips.onTick(5_000L));
        assertEquals(-1L, twoClips.onTick(1_300_000L)); // 冷却中
        cooldownElapsed(twoClips);
        assertEquals(1_419_000L, twoClips.onTick(1_300_000L));
    }

    @Test
    public void autoSkipHasHardLimitPerSegment() {
        SegmentSkipController c = controller(SegmentSkipMode.ALWAYS, op());

        // 反复「跳一次 → 越过 end+3s 重新武装」，第 4 次必须彻底放弃该片段
        for (int expected = 1; expected <= 3; expected++) {
            cooldownElapsed(c);
            assertEquals("第 " + expected + " 次自动跳", OP_END_MS, c.onTick(5_000L));
            assertEquals(-1L, c.onTick(OP_END_MS + 3_000L));
        }
        cooldownElapsed(c);
        assertEquals("同一片段自动跳满 3 次后不再跳", -1L, c.onTick(5_000L));
    }

    @Test
    public void manualAndShowOnlyNeverSkipAutomatically() {
        SegmentSkipController manual = controller(SegmentSkipMode.MANUAL, op());
        assertTrue(manual.isEnabled());
        assertEquals(-1L, manual.onTick(5_000L));
        cooldownElapsed(manual);
        assertEquals(-1L, manual.onTick(5_000L));
        // 手动档位由播放器 UI 显示按钮，点击后跳到片段尾
        ClipInfo segment = manual.manualButtonSegment(5_000L);
        assertNotNull(segment);
        assertEquals(OP_END_MS, segment.endMs());
        assertEquals(OP_END_MS, manual.skipNow(5_000L));
        assertEquals(-1L, manual.skipNow(120_000L)); // 片段外没有可跳的
        assertNull(manual.manualButtonSegment(120_000L));
        assertNull(manual.showOnlyHint(5_000L));

        SegmentSkipController showOnly = controller(SegmentSkipMode.SHOW_ONLY, op());
        assertEquals(-1L, showOnly.onTick(5_000L));
        cooldownElapsed(showOnly);
        assertEquals(-1L, showOnly.onTick(5_000L));
        // 仅显示档位只给提示，不提供手动按钮
        assertNotNull(showOnly.showOnlyHint(5_000L));
        assertNull(showOnly.manualButtonSegment(5_000L));
        assertNull(showOnly.showOnlyHint(120_000L));
    }

    @Test
    public void disabledModeAndEmptyOrDirtyClipsDoNothing() {
        SegmentSkipController disabled = controller(SegmentSkipMode.DISABLED, op());
        assertFalse(disabled.isEnabled());
        assertEquals(SegmentSkipMode.DISABLED, disabled.getMode());
        assertEquals(-1L, disabled.onTick(5_000L));
        // segmentAt 只查片段表、与档位无关：禁用档也要能查到，UI 才不会误显示
        assertEquals(ClipInfo.TYPE_OP, disabled.segmentAt(5_000L).clipType);

        // 区间反了 / 时长为 0 的脏片段在构造时被剔除
        SegmentSkipController dirty = controller(SegmentSkipMode.ALWAYS,
                new ClipInfo(ClipInfo.TYPE_OP, 100, 50),
                new ClipInfo(ClipInfo.TYPE_ED, 200, 200),
                null);
        assertFalse(dirty.isEnabled());
        assertEquals(-1L, dirty.onTick(5_000L));
        assertNull(dirty.segmentAt(5_000L));

        // clips=null 与 mode=null：不崩，模式按默认 ALWAYS 处理
        SegmentSkipController empty = new SegmentSkipController(null, null);
        assertFalse(empty.isEnabled());
        assertEquals(SegmentSkipMode.ALWAYS, empty.getMode());
        assertEquals(-1L, empty.onTick(5_000L));

        SegmentSkipController nullMode = controller(null, op());
        assertEquals(SegmentSkipMode.ALWAYS, nullMode.getMode());
        assertEquals(OP_END_MS, nullMode.onTick(5_000L));
    }

    @Test
    public void outOfDurationOrTooCloseToEndIsNotSkipped() {
        // 片段结束位置超过视频总时长：脏数据，跳过去会让播放直接结束
        SegmentSkipController beyondDuration = controller(SegmentSkipMode.ALWAYS, op());
        beyondDuration.setDurationMs(60_000L);
        assertEquals(-1L, beyondDuration.onTick(5_000L));
        cooldownElapsed(beyondDuration);
        assertEquals(-1L, beyondDuration.onTick(5_000L));

        // 距片段结束不足 15s：跳没有价值，还会打断刚起播的缓冲
        SegmentSkipController close = controller(SegmentSkipMode.ALWAYS, op());
        close.setDurationMs(300_000L);
        assertEquals(-1L, close.onTick(80_000L));  // 剩 9s
        assertEquals(-1L, close.onTick(74_100L));  // 剩 14.9s
        cooldownElapsed(close);
        assertEquals(OP_END_MS, close.onTick(74_000L)); // 恰好剩 15s：可以跳

        // 时长未知（0）时不做越界判断，正常跳
        SegmentSkipController unknown = controller(SegmentSkipMode.ALWAYS, op());
        assertEquals(OP_END_MS, unknown.onTick(5_000L));
    }

    @Test
    public void cooldownBlocksSeeksForTwoSeconds() {
        SegmentSkipController c = controller(SegmentSkipMode.ALWAYS, op());

        // 用一次手动跳（skipNow）把 lastSkipAt 置为「刚刚」
        assertEquals(OP_END_MS, c.skipNow(5_000L));
        assertEquals("2 秒冷却内不能再 seek", -1L, c.onTick(10_000L));

        setLastSkipAt(c, System.currentTimeMillis() - 1_000L);
        assertEquals(-1L, c.onTick(10_000L));

        setLastSkipAt(c, System.currentTimeMillis() - 5_000L);
        assertEquals("冷却结束后恢复自动跳", OP_END_MS, c.onTick(10_000L));
    }

    @Test
    public void segmentAtAndBasicQueries() {
        SegmentSkipController c = controller(SegmentSkipMode.MANUAL, op(), ed());
        // 区间是 [start, end)，单位秒→毫秒
        assertEquals(ClipInfo.TYPE_OP, c.segmentAt(0L).clipType);
        assertEquals(ClipInfo.TYPE_OP, c.segmentAt(88_999L).clipType);
        assertNull(c.segmentAt(OP_END_MS));
        assertNull(c.segmentAt(-1L));
        assertEquals(ClipInfo.TYPE_ED, c.segmentAt(1_300_000L).clipType);
        assertEquals(1_419_000L, c.segmentAt(1_300_000L).endMs());
        assertEquals("片头", c.segmentAt(1_000L).displayName());
        assertEquals("片尾", c.segmentAt(1_310_000L).displayName());

        SegmentSkipController empty = new SegmentSkipController(Collections.emptyList(),
                SegmentSkipMode.MANUAL);
        assertFalse(empty.isEnabled());
        assertNull(empty.segmentAt(1_000L));
        assertNull(empty.manualButtonSegment(1_000L));
        assertEquals(-1L, empty.skipNow(1_000L));
    }
}