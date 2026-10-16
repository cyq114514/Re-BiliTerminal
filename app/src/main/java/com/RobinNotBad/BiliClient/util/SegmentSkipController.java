package com.RobinNotBad.BiliClient.util;

import com.RobinNotBad.BiliClient.model.ClipInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 番剧片头/片尾片段跳过的分档状态机。
 *
 * <p>由播放器既有的 250ms 进度 tick 驱动，<b>不新增任何定时器</b>；
 * 片段数据随取流响应一次返回，也<b>不新增任何网络请求</b>。
 *
 * <p>各档位语义：
 * <ul>
 *   <li>总是跳过：进入片段区间立即跳到片段结束；</li>
 *   <li>跳过一次：同一片段只自动跳一次，用户手动拖回去后不再跳；</li>
 *   <li>手动跳过：不自动跳，区间内由播放器持续显示可点击的跳过按钮；</li>
 *   <li>仅显示：只提示不跳；</li>
 *   <li>禁用：完全不处理。</li>
 * </ul>
 */
public class SegmentSkipController {

    /** 设置项 key：片头/片尾跳过档位（存 {@link SegmentSkipMode#value}） */
    public static final String KEY_MODE = "player_clip_skip_mode";

    /**
     * 发起一次跳转后的冷却时间。seek 需要时间才真正到位，期间 tick 仍会读到区间内的旧位置，
     * 不冷却会在每个 tick 上重复 seek，表现为播放不断抖动。
     */
    private static final long SEEK_COOLDOWN_MS = 2000;

    /**
     * 同一个片段在本次播放里最多自动跳几次，超过就永久放弃该片段。
     * 正常情况下一次穿越只会跳 1 次（闩锁保证），所以这个值只会在异常路径上被触达。
     */
    private static final int MAX_AUTO_SKIP_PER_SEGMENT = 3;

    /**
     * 「重新武装」需要越过结束位置这么多毫秒。
     *
     * <p>真机日志：片段 end=89s，seek 到 89s 后位置短暂到达过 89s，播放器随即因缓冲不足退回到
     * 85.9s。若只要求"到达过 end"就重新武装，这种"到位又退回"会被当成新的一次穿越，于是反复 seek。
     * 要求多前进 3 秒，退回的假象就不再满足条件。这是本次「反复加载」的直接修复点。
     */
    private static final long REARM_MARGIN_MS = 3000;

    /**
     * 当前位置距离片段结束不足这么多毫秒时不跳。
     *
     * <p>同上日志：用户从 84s 续播进入 0–89s 的片头，剩下 5 秒——跳这 5 秒没有任何价值，
     * 却会打断刚起播的缓冲、把播放推入"seek 失败→回退→再 seek"的抖动。
     * 让用户自然播完这 5 秒才是对的。
     */
    private static final long MIN_REMAINING_MS = 15000;

    private final List<ClipInfo> clips = new ArrayList<>();
    private final SegmentSkipMode mode;
    /**
     * 「本次穿越已经自动跳过」的片段序号。这是防死循环的核心闩锁：
     * seek 期间播放器可能仍在报区间内的旧位置（甚至因重新缓冲而停滞），
     * 只靠时间冷却的话冷却一过就会再跳一次，表现为"反复跳过、一直加载、无法继续观看"。
     * 有了闩锁，同一个片段在一次穿越里最多只跳一次。
     */
    private final Set<Integer> handled = new HashSet<>();
    /** 「跳过一次」档位在本集内已经跳过的片段（穿越结束后也不会重新武装） */
    private final Set<Integer> skippedOnce = new HashSet<>();
    /** 每个片段已经自动跳过的次数，用于硬上限兜底 */
    private final java.util.Map<Integer, Integer> autoSkipCount = new java.util.HashMap<>();
    private long lastSkipAt;
    /** 视频总时长（毫秒），用于剔除越界的脏片段数据；未知时传 0 */
    private long durationMs;

    public SegmentSkipController(List<ClipInfo> clips, SegmentSkipMode mode) {
        this.mode = mode == null ? SegmentSkipMode.ALWAYS : mode;
        if (clips != null) {
            for (ClipInfo c : clips) {
                //过滤脏数据：区间反了或时长为 0 的片段没有跳过意义，留着只会误跳
                if (c != null && c.duration() > 0) this.clips.add(c);
            }
        }
    }

    /** 告知视频总时长，用于剔除"结束位置已经在片尾之外"的脏片段 */
    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public boolean isEnabled() {
        return mode != SegmentSkipMode.DISABLED && !clips.isEmpty();
    }

    public SegmentSkipMode getMode() {
        return mode;
    }

    /** 位置（毫秒）落在哪个片段内；不在任何片段内返回 null */
    public ClipInfo segmentAt(long positionMs) {
        for (ClipInfo c : clips) {
            if (c.contains(positionMs)) return c;
        }
        return null;
    }

    /**
     * 由进度 tick 调用，决定是否需要自动跳转。
     *
     * @param positionMs 当前播放位置（毫秒）
     * @return 需要跳转到的目标位置（毫秒）；不需要动作时返回 -1
     */
    public long onTick(long positionMs) {
        if (!isEnabled()) return -1;
        //手动与仅显示都不自动跳，交给播放器 UI 处理
        if (mode == SegmentSkipMode.MANUAL || mode == SegmentSkipMode.SHOW_ONLY) return -1;

        //先处理"重新武装"：只有观察到位置已经真正越过片段结束，才算这一次穿越完成。
        //必须用"越过结束位置"而不是"位置不在区间内"来判断——seek 之后播放器可能短暂回到
        //很靠前的位置（甚至 0），用后者会在缓冲恢复的瞬间重新武装，再跳一次，形成死循环。
        for (int i = 0; i < clips.size(); i++) {
            if (!handled.contains(i)) continue;
            if (positionMs >= clips.get(i).endMs() + REARM_MARGIN_MS) {
                handled.remove(i);
                //跳过一次：本集内不再重新武装，用户手动拖回去也不跳
                if (mode == SegmentSkipMode.ONCE) skippedOnce.add(i);
            }
        }

        if (System.currentTimeMillis() - lastSkipAt < SEEK_COOLDOWN_MS) return -1;

        for (int i = 0; i < clips.size(); i++) {
            ClipInfo c = clips.get(i);
            if (!c.contains(positionMs)) continue;
            //已经跳过且尚未确认越过：绝不再跳（防死循环的关键）
            if (handled.contains(i)) return -1;
            if (mode == SegmentSkipMode.ONCE && skippedOnce.contains(i)) return -1;
            //越界脏数据不自动跳：结束位置超出视频总长的片段，跳过去只会让播放直接结束或卡住
            if (durationMs > 0 && c.endMs() > durationMs) return -1;
            //离片段结束太近就不跳：跳不到十几秒的位移没有价值，却会打断刚起播的缓冲
            if (c.endMs() - positionMs < MIN_REMAINING_MS) return -1;
            //硬上限兜底：同一个片段自动跳够次数就彻底放弃它。
            //这是"不可能无限循环"的最后一道保险——即使还存在没预见到的位置回退路径
            //（seek 失败后播放器重新起播、缓冲期间位置乱跳等），也最多多跳几次就永久停下，
            //不会出现"一直在加载、永远走不出这一段"。用 w 级日志，普通用户不开调试开关也能抓到。
            int done = autoSkipCount.containsKey(i) ? autoSkipCount.get(i) : 0;
            if (done >= MAX_AUTO_SKIP_PER_SEGMENT) return -1;
            autoSkipCount.put(i, done + 1);
            handled.add(i);
            lastSkipAt = System.currentTimeMillis();
            Logu.w("片头片尾跳过", "自动跳过" + c.displayName() + "：第 " + (done + 1) + "/"
                    + MAX_AUTO_SKIP_PER_SEGMENT + " 次，" + positionMs + "ms → " + c.endMs() + "ms");
            return c.endMs();
        }
        return -1;
    }

    /**
     * 手动档位的「跳过」按钮点击。
     *
     * @return 该片段的结束位置（毫秒）；当前不在片段内时返回 -1
     */
    public long skipNow(long positionMs) {
        ClipInfo c = segmentAt(positionMs);
        if (c == null) return -1;
        lastSkipAt = System.currentTimeMillis();
        return c.endMs();
    }

    /** 手动档位：当前是否应显示跳过按钮 */
    public ClipInfo manualButtonSegment(long positionMs) {
        return mode == SegmentSkipMode.MANUAL ? segmentAt(positionMs) : null;
    }

    /** 仅显示档位：当前是否应提示片段存在 */
    public ClipInfo showOnlyHint(long positionMs) {
        return mode == SegmentSkipMode.SHOW_ONLY ? segmentAt(positionMs) : null;
    }
}
