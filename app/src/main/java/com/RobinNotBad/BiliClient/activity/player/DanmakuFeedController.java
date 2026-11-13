package com.RobinNotBad.BiliClient.activity.player;

import com.RobinNotBad.BiliClient.model.DanmakuElem;
import com.RobinNotBad.BiliClient.model.DmSegMobileReply;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 位置驱动的弹幕台账：把整场弹幕按 progress 排好序，由<b>播放位置</b>决定"这一刻该投哪几条"。
 *
 * <p><b>为什么不再让 DFM 自己走时间轴</b>：DFM 的时钟是墙钟驱动的，和播放器位置是两套基准。
 * 缓冲、掉帧、倍速都会让两者分叉，而唯一能纠偏的手段 {@code seekTo} 会清屏
 * （{@code DrawHandler SEEK_POS → DrawTask.seek → reset() → mRenderer.clear()}），
 * 用户看到的就是"整屏弹幕消失、过一会儿又冒出来"。
 *
 * <p>这里把"什么时候出现"完全交给播放位置，DFM 只负责"从右往左飘"，
 * 两边不再需要对时钟，也就不需要任何纠偏性 seek。
 *
 * <p>线程模型：本类<b>不做同步</b>，约定只在一个线程里调用（本应用是 250ms 进度定时器所在线程
 * 与主线程各走一条路径，因此由调用方保证串行——见 PlayerActivity#feedDanmaku）。
 */
public class DanmakuFeedController {

    /** 起播/跳转时的补投窗口：把"过去这么久"的弹幕一起投出去，屏幕立刻有内容而不是空等几秒 */
    public static final long PREHEAT_MS = 8000;

    /** 单次投放上限：跳到弹幕密集处时，防止一次性灌上百条把主线程顶住 */
    public static final int MAX_PER_FEED = 60;

    /**
     * 播放位置一次跳变超过这个值就判定为"跳转/追帧"，重整游标。
     * 阈值要大于一个 tick 的正常位移（250ms），又要小于用户拖动进度条的量级。
     */
    private static final long JUMP_THRESHOLD_MS = 3000;

    private final List<DanmakuElem> ledger;
    private int cursor = 0;
    private long lastPosition = -1;

    public DanmakuFeedController(List<DanmakuElem> ledger) {
        this.ledger = ledger == null ? new ArrayList<DanmakuElem>() : ledger;
    }

    /**
     * 把分段响应摊平成一条按时间升序的台账。
     *
     * <p>用稳定排序：同一毫秒的多条弹幕保持服务端下发顺序（同屏多条的先后观感与之一致）。
     */
    public static List<DanmakuElem> flatten(List<DmSegMobileReply> segments) {
        List<DanmakuElem> out = new ArrayList<>();
        if (segments == null) return out;
        for (DmSegMobileReply seg : segments) {
            if (seg == null || seg.elems == null || seg.elems.isEmpty()) continue;
            out.addAll(seg.elems);
        }
        //Collections.sort 是稳定排序；这里也只是把分段拼接后的乱序理回时间序
        Collections.sort(out, new Comparator<DanmakuElem>() {
            @Override
            public int compare(DanmakuElem a, DanmakuElem b) {
                if (a == null || b == null) return 0;
                return Integer.compare(a.progress, b.progress);
            }
        });
        return out;
    }

    public synchronized int size() {
        return ledger.size();
    }

    /** 只读台账（供高能进度条分桶统计）。调用方不得修改返回的列表 */
    public List<DanmakuElem> ledger() {
        return ledger;
    }

    public synchronized boolean isEmpty() {
        return ledger.isEmpty();
    }

    /**
     * 把游标放到 {@code positionMs} 之前 {@link #PREHEAT_MS} 处。
     *
     * <p>调用后应立刻用 {@link #take(long, boolean)} 传 {@code warmup=true} 补投一次，
     * 否则这一段要等下一轮 tick 才会出现。
     */
    public synchronized void reset(long positionMs) {
        long from = positionMs - PREHEAT_MS;
        if (from < 0) from = 0;
        cursor = lowerBound(from);
        lastPosition = positionMs;
    }

    /**
     * 取"截至 {@code positionMs} 应该出现"的弹幕。
     *
     * @param positionMs 当前播放位置（毫秒）
     * @param warmup     true=起播/跳转后的补投，允许取 {@link #PREHEAT_MS} 窗口内的历史弹幕；
     *                   false=常规增量投放
     * @return 本次应投放的弹幕，可能为空（不返回 null）
     */
    public synchronized List<DanmakuElem> take(long positionMs, boolean warmup) {
        if (ledger.isEmpty()) return Collections.emptyList();
        if (positionMs < 0) positionMs = 0;

        if (lastPosition < 0) {
            //第一次：游标就位，按补投处理
            reset(positionMs);
            warmup = true;
        } else if (!warmup) {
            if (positionMs < lastPosition) {
                //位置倒退（用户拖回去但没走 seek 入口）：重整游标，本 tick 不投
                reset(positionMs);
                return Collections.emptyList();
            }
            if (positionMs - lastPosition > JUMP_THRESHOLD_MS) {
                //播放位置一次跳太远（倍速叠加 tick 被拖慢，或跳过片头）：
                //只把游标跟到新位置，**绝不补投历史**——补投会把已经投过的弹幕再投一遍，
                //屏幕上就出现"重复的弹幕从头开始跑"（长按 3x 时真机实测到过）
                cursor = lowerBound(positionMs);
                lastPosition = positionMs;
                return Collections.emptyList();
            }
            if (positionMs <= lastPosition) {
                //位置没前进（暂停、同一毫秒的重复 tick）：无事可做
                return Collections.emptyList();
            }
        }

        List<DanmakuElem> out = new ArrayList<>(16);
        while (cursor < ledger.size() && out.size() < MAX_PER_FEED) {
            DanmakuElem e = ledger.get(cursor);
            if (e.progress > positionMs) break;
            cursor++;
            out.add(e);
        }
        lastPosition = positionMs;
        return out;
    }

    /** 当前游标位置，仅用于日志/诊断 */
    public synchronized int cursor() {
        return cursor;
    }

    /** 二分查找第一条 {@code progress >= fromMs} 的下标 */
    private int lowerBound(long fromMs) {
        int lo = 0;
        int hi = ledger.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            DanmakuElem e = ledger.get(mid);
            if (e != null && e.progress < fromMs) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }
}
