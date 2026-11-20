package com.RobinNotBad.BiliClient.util;

/**
 * 番剧续播进度的纯逻辑规则（不依赖 Android / 网络，可直接 JVM 单测）。
 *
 * <p>本类只回答「服务端返回的哪一条数据可以当作本集续播位置」这一个问题，
 * 请求顺序、季级接口回退与观看记录扫描仍由 {@code PlayerApi.getEpisodeProgressMs} 负责。
 *
 * <p>成立依据（真机诊断结论，改动前请先读 {@code PlayerApi.WatchProgress} 的注释）：
 * <ul>
 *   <li>{@code current_watch_progress} 只有在「本集自己的记录」时才可信，但它恒为毫秒；</li>
 *   <li>{@code last_time} 与 {@code last_ep_id} 严格配对，只有 last_ep_id 等于本集 epid 时才能用；</li>
 *   <li>绝不做「毫秒/秒」单位猜测：实测两个字段本来就是同一个毫秒值，猜错会让每次续播都变 0；</li>
 *   <li>位置超过总时长只可能是脏数据，宁可从头播。</li>
 * </ul>
 */
public final class PlayerProgressRules {

    /** 没有任何时长参照时的兜底上限（24 小时），防止异常值经 int 截断后变成离谱跳转。 */
    public static final long MAX_PROGRESS_MS = 24L * 60 * 60 * 1000L;

    private PlayerProgressRules() {
    }

    /**
     * {@code last_play_time} 只属于 {@code last_play_cid} 那一P。
     * 换P播放必须从头开始，否则会出现「选了P3却从P2的位置开始播」。
     */
    public static int adoptLastPlayTime(long lastPlayCid, long requestCid, long lastPlayTime) {
        if (lastPlayCid != requestCid || lastPlayTime <= 0) return 0;
        return (int) lastPlayTime;
    }

    /**
     * 归一化 last_play_time（毫秒）。
     *
     * <p>官方文档标注为毫秒，但该字段在 {@code x/player/wbi/v2} 中未被文档确证：
     * 仅当毫秒值超出视频总时长、而换算成秒后落在时长内时按秒处理。
     * 仍越界或超过 24 小时上限则丢弃为 0。
     */
    public static int normalizeProgress(long raw, long durationMs) {
        if (raw <= 0) return 0;
        long ms = raw;
        if (durationMs > 0 && ms > durationMs) {
            long sec = raw * 1000L;
            if (sec <= durationMs) {
                ms = sec;
            } else {
                ms = 0;
            }
        }
        if (ms > MAX_PROGRESS_MS) ms = 0;
        return (int) ms;
    }

    /** 服务端 watch_progress 的选取结果。{@code ms == 0} 表示前两层都没命中。 */
    public static final class WatchProgressChoice {
        public final long ms;
        /** 命中来源（与历史诊断日志的 source 文案一致）；未命中为 null。 */
        public final String source;
        /** current_watch_progress 因超过总时长被丢弃（调用方据此打诊断日志）。 */
        public final boolean overDurationDiscarded;
        /** 按集进度与季级位置数值相同（仅提示，不改变采用结果）。 */
        public final boolean sameValueAsSeason;

        WatchProgressChoice(long ms, String source, boolean overDurationDiscarded,
                            boolean sameValueAsSeason) {
            this.ms = ms;
            this.source = source;
            this.overDurationDiscarded = overDurationDiscarded;
            this.sameValueAsSeason = sameValueAsSeason;
        }
    }

    /**
     * 第 1、2 层：从 {@code watch_progress} 结构里选本集续播位置。
     *
     * @param currentMs     current_watch_progress（毫秒；0/负数视为无）
     * @param lastEpid      服务端记录的本季最近观看 epid（0 视为无）
     * @param lastTimeMs    与 lastEpid 配对的 last_time（毫秒）
     * @param episodeEpid   本次要播的集 epid（0 表示不是番剧/无 epid）
     * @param durationMs    本集总时长（毫秒；0 表示未知，不做越界判断）
     */
    public static WatchProgressChoice selectWatchProgress(long currentMs, long lastEpid,
                                                          long lastTimeMs, long episodeEpid,
                                                          long durationMs) {
        long adopted = 0;
        String source = null;
        boolean overDuration = false;
        boolean sameValueAsSeason = false;

        //1. 服务端按集保存的本集进度（PiliPlus 口径，毫秒）
        if (currentMs > 0) {
            adopted = currentMs;
            source = "服务端按集进度 current_watch_progress";
            if (lastEpid != 0 && lastEpid != episodeEpid && adopted == lastTimeMs) {
                //仅提示：两者数值相同只是巧合，仍按集级采用
                sameValueAsSeason = true;
            }
            if (durationMs > 0 && adopted > durationMs) {
                //位置不可能超过总时长：只可能是异常数据，宁可从头播
                adopted = 0;
                source = null;
                overDuration = true;
            }
        }

        //2. 本集就是「本季最近观看」那一集，但集级字段为空：同响应的 last_time 就是本集位置
        if (adopted <= 0 && episodeEpid != 0 && lastEpid == episodeEpid && lastTimeMs > 0) {
            adopted = lastTimeMs;
            source = "季级(同响应 last_time)";
        }

        return new WatchProgressChoice(adopted, source, overDuration, sameValueAsSeason);
    }

    /**
     * 「服务端确认本集没有记录」：本集就是最近一集，但 current 位置为 0/缺失。
     *
     * <p>只有这种情况才允许跳过观看记录扫描；不能把「返回了 watch_progress 对象」本身当作
     * 本集已有记录的证据（多集共享 aid 的番剧切集后 current 会被清零）。
     */
    public static boolean serverConfirmsNoRecord(long currentMs, long lastEpid, long episodeEpid) {
        return currentMs <= 0 && episodeEpid != 0 && lastEpid == episodeEpid;
    }
}