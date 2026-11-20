package com.RobinNotBad.BiliClient.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 番剧续播进度规则（{@link PlayerProgressRules}）的纯 JVM 单测。
 *
 * <p>这些断言按「真机诊断得出的当前业务口径」写死，目的是防止有人再引入
 * 「跨集串进度」「毫秒/秒猜测导致续播恒为 0」两类回归（见 PlayerApi.WatchProgress 注释）。
 *
 * <p>1 秒 = 1000 毫秒；24 分钟番剧按 1_440_000ms 计。
 */
public class PlayerProgressRulesTest {

    /** 一集动画的典型总时长：24 分钟。 */
    private static final long DURATION_24MIN = 24L * 60L * 1000L; // 1440000
    /** 本机诊断里 ep28960 的实际位置：11 分 27 秒，毫秒。 */
    private static final long EP_28960_POSITION_MS = 687_000L;
    private static final long EP_28960 = 28960L;
    private static final long EP_28961 = 28961L;
    private static final String SOURCE_CURRENT = "服务端按集进度 current_watch_progress";
    private static final String SOURCE_SEASON = "季级(同响应 last_time)";

    // ================= adoptLastPlayTime =================

    @Test
    public void adoptLastPlayTime_sameCidAndPositive_adoptsValue() {
        assertEquals(421_000, PlayerProgressRules.adoptLastPlayTime(1001L, 1001L, 421_000L));
    }

    @Test
    public void adoptLastPlayTime_differentCid_discards() {
        // last_play_time 只属于 last_play_cid 那一P：换P必须从头播
        assertEquals(0, PlayerProgressRules.adoptLastPlayTime(1001L, 1002L, 421_000L));
    }

    @Test
    public void adoptLastPlayTime_nonPositiveTime_discards() {
        assertEquals(0, PlayerProgressRules.adoptLastPlayTime(1001L, 1001L, 0L));
        assertEquals(0, PlayerProgressRules.adoptLastPlayTime(1001L, 1001L, -5_000L));
    }

    @Test
    public void adoptLastPlayTime_hugeValue_truncatedByIntCast() {
        // 当前实现直接 (int) 强转，没有上限保护（24h 上限只在 normalizeProgress 里生效）。
        // 5_000_000_000L 截断后是 705_032_704；3_000_000_000L 截断后甚至变负。
        // 这里锁定“现有行为”，不是“期望行为”，避免以后悄悄改口径。
        assertEquals(705_032_704, PlayerProgressRules.adoptLastPlayTime(7L, 7L, 5_000_000_000L));
        assertEquals(-1_294_967_296, PlayerProgressRules.adoptLastPlayTime(7L, 7L, 3_000_000_000L));
    }

    // ================= normalizeProgress =================

    @Test
    public void normalizeProgress_normalMilliseconds_returnedAsIs() {
        assertEquals(421_000, PlayerProgressRules.normalizeProgress(421_000L, DURATION_24MIN));
    }

    @Test
    public void normalizeProgress_valueEqualToDuration_adopted() {
        // 边界：只有“超过”总时长才丢弃，恰好等于总时长保留
        assertEquals(600_000, PlayerProgressRules.normalizeProgress(600_000L, 600_000L));
    }

    @Test
    public void normalizeProgress_oneMsOverDuration_discarded() {
        // 毫秒值越界后尝试按秒解释：600_001 * 1000 依然越界 → 丢弃
        assertEquals(0, PlayerProgressRules.normalizeProgress(600_001L, 600_000L));
    }

    @Test
    public void normalizeProgress_secondsLookingValue_isTreatedAsMilliseconds() {
        // 现状：421 这种“看起来是秒”的值小于总时长，走不进换算分支，直接当 421ms 返回。
        // 注：代码里的 *1000 换算分支实际不可达（raw > duration 且 raw*1000 <= duration 不可能同时成立），
        // 这里记录真实行为，便于将来改口径时先看见差异。
        assertEquals(421, PlayerProgressRules.normalizeProgress(421L, 480_000L));
    }

    @Test
    public void normalizeProgress_bothInterpretationsOutOfRange_discarded() {
        // 900_000ms 超过 480_000ms，按秒 900_000_000ms 也超过 → 0
        assertEquals(0, PlayerProgressRules.normalizeProgress(900_000L, 480_000L));
    }

    @Test
    public void normalizeProgress_nonPositiveRaw_zero() {
        assertEquals(0, PlayerProgressRules.normalizeProgress(0L, DURATION_24MIN));
        assertEquals(0, PlayerProgressRules.normalizeProgress(-5_000L, DURATION_24MIN));
    }

    @Test
    public void normalizeProgress_unknownDuration_doesNotConvert() {
        // duration=0 表示未知时长：不做越界判断，也不做单位猜测
        assertEquals(421_000, PlayerProgressRules.normalizeProgress(421_000L, 0L));
    }

    @Test
    public void normalizeProgress_exactly24Hours_adopted() {
        assertEquals(86_400_000, PlayerProgressRules.normalizeProgress(86_400_000L, 0L));
    }

    @Test
    public void normalizeProgress_beyond24Hours_discarded() {
        assertEquals(0, PlayerProgressRules.normalizeProgress(86_400_001L, 0L));
    }

    @Test
    public void normalizeProgress_negativeDuration_treatedAsUnknown() {
        assertEquals(60_000, PlayerProgressRules.normalizeProgress(60_000L, -1L));
    }

    // ================= selectWatchProgress =================

    @Test
    public void selectWatchProgress_currentPositive_adopted() {
        PlayerProgressRules.WatchProgressChoice choice = PlayerProgressRules.selectWatchProgress(
                421_000L, 0L, 0L, EP_28960, DURATION_24MIN);
        assertEquals(421_000, choice.ms);
        assertEquals(SOURCE_CURRENT, choice.source);
        assertFalse(choice.overDurationDiscarded);
        assertFalse(choice.sameValueAsSeason);
    }

    @Test
    public void selectWatchProgress_currentPositive_evenIfLastEpidPointsToOtherEpisode_adopted() {
        // 第 1 层只看 current 本身；last_ep_id 指向别的集不影响本集采用
        PlayerProgressRules.WatchProgressChoice choice = PlayerProgressRules.selectWatchProgress(
                421_000L, EP_28960, 100_000L, EP_28961, DURATION_24MIN);
        assertEquals(421_000, choice.ms);
        assertEquals(SOURCE_CURRENT, choice.source);
    }

    @Test
    public void selectWatchProgress_currentOverDuration_discardedWithFlag() {
        PlayerProgressRules.WatchProgressChoice choice = PlayerProgressRules.selectWatchProgress(
                500_000L, 0L, 0L, EP_28960, 400_000L);
        assertEquals(0, choice.ms);
        assertNull(choice.source);
        assertTrue(choice.overDurationDiscarded);
        assertFalse(choice.sameValueAsSeason);
    }

    @Test
    public void selectWatchProgress_currentOverDuration_keepsSameValueHint() {
        // 当前实现先打 sameValueAsSeason 标记、再因越界清零；标记会保留下来（仅提示用）
        PlayerProgressRules.WatchProgressChoice choice = PlayerProgressRules.selectWatchProgress(
                500_000L, EP_28960, 500_000L, EP_28961, 400_000L);
        assertEquals(0, choice.ms);
        assertNull(choice.source);
        assertTrue(choice.overDurationDiscarded);
        assertTrue(choice.sameValueAsSeason);
    }

    @Test
    public void selectWatchProgress_currentNonPositiveAndPairedLastTime_adopted() {
        PlayerProgressRules.WatchProgressChoice choice = PlayerProgressRules.selectWatchProgress(
                0L, EP_28960, 300_000L, EP_28960, DURATION_24MIN);
        assertEquals(300_000, choice.ms);
        assertEquals(SOURCE_SEASON, choice.source);
        assertFalse(choice.overDurationDiscarded);
    }

    @Test
    public void selectWatchProgress_negativeCurrentAndPairedLastTime_adopted() {
        PlayerProgressRules.WatchProgressChoice choice = PlayerProgressRules.selectWatchProgress(
                -1L, EP_28960, 300_000L, EP_28960, DURATION_24MIN);
        assertEquals(300_000, choice.ms);
        assertEquals(SOURCE_SEASON, choice.source);
    }

    @Test
    public void selectWatchProgress_unpairedLastTime_notAdopted() {
        // last_ep_id 指向别的集：last_time 属于那一集，绝不能拿来当本集位置
        PlayerProgressRules.WatchProgressChoice choice = PlayerProgressRules.selectWatchProgress(
                0L, EP_28960, 300_000L, EP_28961, DURATION_24MIN);
        assertEquals(0, choice.ms);
        assertNull(choice.source);
    }

    @Test
    public void selectWatchProgress_unknownEpisodeEpid_neverAdoptsLastTime() {
        // 非番剧 / epid 缺失时没有集身份，last_time 无从配对
        PlayerProgressRules.WatchProgressChoice choice = PlayerProgressRules.selectWatchProgress(
                0L, EP_28960, 300_000L, 0L, DURATION_24MIN);
        assertEquals(0, choice.ms);
        assertNull(choice.source);
    }

    @Test
    public void selectWatchProgress_pairedButLastTimeZero_notAdopted() {
        PlayerProgressRules.WatchProgressChoice choice = PlayerProgressRules.selectWatchProgress(
                0L, EP_28960, 0L, EP_28960, DURATION_24MIN);
        assertEquals(0, choice.ms);
        assertNull(choice.source);
    }

    @Test
    public void selectWatchProgress_sameValueAsSeason_trueWhenCurrentMatchesPairedLastTime() {
        PlayerProgressRules.WatchProgressChoice choice = PlayerProgressRules.selectWatchProgress(
                421_000L, EP_28960, 421_000L, EP_28961, DURATION_24MIN);
        assertTrue(choice.sameValueAsSeason);
        assertEquals(421_000, choice.ms);
        assertEquals(SOURCE_CURRENT, choice.source);
    }

    @Test
    public void selectWatchProgress_sameValueAsSeason_falseWhenValuesDiffer() {
        PlayerProgressRules.WatchProgressChoice choice = PlayerProgressRules.selectWatchProgress(
                421_000L, EP_28960, 400_000L, EP_28961, DURATION_24MIN);
        assertFalse(choice.sameValueAsSeason);
    }

    @Test
    public void selectWatchProgress_allFieldsMissing_returnsZeroAndNullSource() {
        PlayerProgressRules.WatchProgressChoice choice = PlayerProgressRules.selectWatchProgress(
                0L, 0L, 0L, EP_28960, DURATION_24MIN);
        assertEquals(0, choice.ms);
        assertNull(choice.source);
        assertFalse(choice.overDurationDiscarded);
        assertFalse(choice.sameValueAsSeason);
    }

    @Test
    public void selectWatchProgress_currentEqualToDuration_adopted() {
        assertEquals(600_000, PlayerProgressRules.selectWatchProgress(
                600_000L, 0L, 0L, EP_28960, 600_000L).ms);
    }

    @Test
    public void selectWatchProgress_unknownDuration_acceptsCurrentWithoutBounds() {
        assertEquals(421_000, PlayerProgressRules.selectWatchProgress(
                421_000L, 0L, 0L, EP_28960, 0L).ms);
    }

    // ================= 多集/跨集场景（回归重点） =================

    @Test
    public void sharedAid_switchingEpisode_neverLeaksPreviousEpisodePosition() {
        // season 1564（攻壳 SAC）整季共享 aid=135433，epid 28960/28961 是同一季的两集。
        // 步骤一：看 e28960，服务端 current=687000。
        assertEquals(EP_28960_POSITION_MS, PlayerProgressRules.selectWatchProgress(
                EP_28960_POSITION_MS, 0L, 0L, EP_28960, DURATION_24MIN).ms);

        // 步骤二：切到 e28961，服务端把 e28960 的记录清掉：
        // current=0、last_ep_id=28960、last_time=687000（属于 e28960，不是本集）。
        PlayerProgressRules.WatchProgressChoice ep2 = PlayerProgressRules.selectWatchProgress(
                0L, EP_28960, EP_28960_POSITION_MS, EP_28961, DURATION_24MIN);
        assertEquals("上一集的位置绝不能串到下一集", 0, ep2.ms);
        assertNull(ep2.source);
        assertFalse("last_ep_id 不等本集，不能当成“本集无记录”，必须继续扫观看记录",
                PlayerProgressRules.serverConfirmsNoRecord(0L, EP_28960, EP_28961));

        // 步骤三：再切回 e28960，这次 last_ep_id 反过来指向 28961：
        PlayerProgressRules.WatchProgressChoice back = PlayerProgressRules.selectWatchProgress(
                0L, EP_28961, 300_000L, EP_28960, DURATION_24MIN);
        assertEquals("切回去也不能拿 e28961 的位置", 0, back.ms);
        assertFalse(PlayerProgressRules.serverConfirmsNoRecord(0L, EP_28961, EP_28960));
    }

    @Test
    public void sharedAid_pairedLastTimeOnlyUsedByItsOwnEpisode() {
        // 同一份 watch_progress 数据：配对成功的那一集拿到位置，另一集必须拿 0
        long current = 0L;
        long lastEpid = EP_28961;
        long lastTime = 300_000L;

        assertEquals(300_000, PlayerProgressRules.selectWatchProgress(
                current, lastEpid, lastTime, EP_28961, DURATION_24MIN).ms);
        assertEquals(0, PlayerProgressRules.selectWatchProgress(
                current, lastEpid, lastTime, EP_28960, DURATION_24MIN).ms);
    }

    // ================= serverConfirmsNoRecord =================

    @Test
    public void serverConfirmsNoRecord_zeroCurrentPairedWithEpisode_returnsTrue() {
        assertTrue(PlayerProgressRules.serverConfirmsNoRecord(0L, EP_28960, EP_28960));
    }

    @Test
    public void serverConfirmsNoRecord_negativeCurrentPairedWithEpisode_returnsTrue() {
        assertTrue(PlayerProgressRules.serverConfirmsNoRecord(-1L, EP_28960, EP_28960));
    }

    @Test
    public void serverConfirmsNoRecord_positiveCurrent_returnsFalse() {
        assertFalse(PlayerProgressRules.serverConfirmsNoRecord(421_000L, EP_28960, EP_28960));
    }

    @Test
    public void serverConfirmsNoRecord_unpairedLastEpid_returnsFalse() {
        assertFalse(PlayerProgressRules.serverConfirmsNoRecord(0L, EP_28961, EP_28960));
    }

    @Test
    public void serverConfirmsNoRecord_unknownEpisodeEpid_returnsFalse() {
        assertFalse(PlayerProgressRules.serverConfirmsNoRecord(0L, EP_28960, 0L));
    }
}