package com.RobinNotBad.BiliClient.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

/**
 * 播放会话哨兵：把"点播放就闪退、却不产生任何崩溃记录"的隐形崩溃变成可见数据。
 *
 * <p>原理：进入播放页时同步写一条会话标记（必须 commit——原生崩溃后进程直接消失，
 * apply 的异步落盘会丢），播放过程中不断更新"当前阶段"，正常销毁时清除。
 * 若下次启动时标记仍在，说明上个播放会话没有正常结束：原生崩溃、
 * 被系统低内存回收、被强杀都属于这一类，它们都不会进 Java 的崩溃 handler，
 * 所以在此之前控制台里永远看不到（真实案例：米兔4 每次播放必崩、三条日志零记录）。
 *
 * <p><b>只留本地，不上传。</b>哨兵能看到的信息仅止于"播放页没正常收尾"，
 * 分不清 native 崩溃、被系统回收还是被强杀——这三类在数据上完全同形，拿去做归因只会误导。
 * 因此它只走 logcat + {@link CrashUploader#saveLocal} 本地留档，不进待发队列、不上传；
 * 线上只收有明确 Java 堆栈的崩溃（{@link CrashFilter} 里对哨兵也拦了一道）。
 */
public final class PlayerSentinel {

    private static final String KEY_ACTIVE = "play_sentinel_active";
    private static final String KEY_STAGE = "play_sentinel_stage";
    private static final String KEY_TRAIL = "play_sentinel_trail";
    private static final String KEY_START = "play_sentinel_start";
    private static final String KEY_STREAK = "play_sentinel_streak";
    /** 上次会话结束时页面是否在后台：后台被系统回收属于正常，不应算「播放异常退出」 */
    private static final String KEY_BACKGROUNDED = "play_sentinel_backgrounded";


    /** 轨迹上限，够看清最后几步即可，避免偏好文件无限膨胀 */
    private static final int MAX_TRAIL = 400;

    private PlayerSentinel() {
    }

    private static SharedPreferences sp() {
        return SharedPreferencesUtil.getSharedPreferences();
    }

    /**
     * 开启一个新会话。应当放在整条播放链路的最前端（用户点封面的地方）——
     * "连播放界面都没见到就闪退"说明崩溃可能发生在跳转页甚至 Activity 启动阶段，
     * 起点越早，留得下的线索越多。
     */
    public static void begin(String firstStage) {
        //与 checkAndReport 共用一把锁：冷启动时后者在后台线程清了标记，
        //若用户此刻正好点进播放页，不加锁会把刚写的新会话标记一起清掉（漏报一次）
        synchronized (PlayerSentinel.class) {
            try {
                String stage = firstStage == null || firstStage.isEmpty() ? "进入播放页" : firstStage;
                sp().edit()
                        .putBoolean(KEY_ACTIVE, true)
                        .putLong(KEY_START, System.currentTimeMillis())
                        .putString(KEY_STAGE, stage)
                        .putString(KEY_TRAIL, stage)
                        .commit();
            } catch (Throwable t) {
                Logu.e("player_sentinel", "begin 失败: " + t);
            }
        }
    }

    public static void begin() {
        begin("进入播放页");
    }

    /** 链路中继：已有会话就续写阶段，没有（如被外部拉起/进程重建）就新开一个。 */
    public static void beginOrContinue(String stage) {
        try {
            if (sp().getBoolean(KEY_ACTIVE, false)) stage(stage);
            else begin(stage);
        } catch (Throwable t) {
            Logu.e("player_sentinel", "beginOrContinue 失败: " + t);
        }
    }

    /** 更新当前阶段，调用点必须是播放链路里可能挂掉的位置。 */
    public static void stage(String stage) {
        try {
            SharedPreferences sp = sp();
            if (!sp.getBoolean(KEY_ACTIVE, false)) return;
            String trail = sp.getString(KEY_TRAIL, "");
            if (trail.length() > MAX_TRAIL) trail = trail.substring(trail.length() - MAX_TRAIL);
            sp.edit()
                    .putString(KEY_STAGE, stage)
                    .putString(KEY_TRAIL, trail + "→" + stage)
                    .commit();
            //顺带把崩溃上报的 page 指向当前阶段：崩溃发生在播放页还没来得及 resumed 时，
            //page 字段原本记的是上一个页面，看起来就像"播放页从没崩过"
            CrashUploader.setCurrentPage("播放链路:" + stage);
        } catch (Throwable t) {
            Logu.e("player_sentinel", "stage 失败: " + t);
        }
    }

    /**
     * 标记页面是否退到后台。由播放页 onStop（true）/ onStart（false）调用。
     *
     * <p>用途：用户按 Home 或锁屏后进程被系统回收时 onDestroy 不会执行，标记残留，
     * 下次启动会被当成「播放异常退出」。实测米兔手表上报的 4 条里 3 条都是这种，
     * 停留时间 147s / 1182s，且同时段有 LMK 记录——纯误报。
     */
    public static void markBackgrounded(boolean backgrounded) {
        synchronized (PlayerSentinel.class) {
            try {
                SharedPreferences sp = sp();
                if (!sp.getBoolean(KEY_ACTIVE, false)) return;
                //与 stage 一样必须同步落盘：进程随时可能被回收
                sp.edit().putBoolean(KEY_BACKGROUNDED, backgrounded).commit();
            } catch (Throwable t) {
                Logu.e("player_sentinel", "markBackgrounded 失败: " + t);
            }
        }
    }

    /** 播放页正常销毁时调用，清除标记并复位连续异常计数。 */
    public static void finish() {
        synchronized (PlayerSentinel.class) {
            try {
                sp().edit()
                        .remove(KEY_ACTIVE)
                        .remove(KEY_STAGE)
                        .remove(KEY_TRAIL)
                        .remove(KEY_START)
                        .putInt(KEY_STREAK, 0)
                        .commit();
            } catch (Throwable t) {
                Logu.e("player_sentinel", "finish 失败: " + t);
            }
        }
    }

    /**
     * 启动时调用：发现上次播放没有正常收尾就上报一条。
     * 标记先清后报——一次异常只报一次，不会每次启动都重复上报。
     */
    public static void checkAndReport(Context context) {
        final String stage;
        final String trail;
        final long start;
        final int streak;
        final boolean backgrounded;
        //读标记 + 清标记必须原子：本方法现在跑在后台线程，而 begin() 在主线程，
        //中间被插入一次 begin 会让刚写的新会话被误清（漏报一次）
        synchronized (PlayerSentinel.class) {
            final SharedPreferences sp = sp();
            final boolean active;
            try {
                active = sp.getBoolean(KEY_ACTIVE, false);
            } catch (Throwable t) {
                return;
            }
            if (!active) return;

            stage = sp.getString(KEY_STAGE, "");
            trail = sp.getString(KEY_TRAIL, "");
            start = sp.getLong(KEY_START, 0);
            streak = sp.getInt(KEY_STREAK, 0) + 1;
            backgrounded = sp.getBoolean(KEY_BACKGROUNDED, false);
            try {
                sp.edit()
                        .remove(KEY_ACTIVE)
                        .remove(KEY_STAGE)
                        .remove(KEY_TRAIL)
                        .remove(KEY_START)
                        .remove(KEY_BACKGROUNDED)
                        .putInt(KEY_STREAK, streak)
                        .commit();
            } catch (Throwable ignored) {
            }
        }

        //退到后台后进程被回收不是「播放异常退出」：用户按 Home / 锁屏后系统清后台是常态。
        //只有「听视频」（后台播放是预期行为）才继续当作异常上报
        if (backgrounded && !SharedPreferencesUtil.getBoolean("player_audio_only", false)) {
            Logu.e("player_sentinel", "上次会话在后台被回收，按正常退出处理（不上报）最后阶段=" + stage);
            return;
        }

        //尊重用户的崩溃上报开关：关闭时只留一条 logcat，不落文件也不上传
        if (!CrashReportUtil.isEnabled()) {
            Logu.e("player_sentinel", "上次播放异常结束（上报已关闭）最后阶段=" + stage + " 轨迹=" + trail);
            return;
        }

        final String detail = describe(stage, trail, start, streak);
        Logu.e("player_sentinel", "上次播放异常结束: " + detail);
        CenterThreadPool.run(() -> {
            try {
                Throwable synthetic = new AbnormalExit(detail);
                JSONObject report = CrashUploader.buildReport(context, synthetic, "player-sentinel", trail);
                //此刻还停在启动页，真实位置写死在播放页
                report.put("page", "PlayerActivity");
                //只留本地：不 enqueue、不上传。需要排查时由用户从本地目录导出。
                CrashUploader.saveLocal(report);
                Logu.e("player_sentinel", "仅本地留档，不进待发队列");
            } catch (Throwable t) {
                Logu.e("player_sentinel", "本地留档失败: " + t);
            }
        });
    }

    /** 组装上报消息：把判断根因需要的现场一次带全，免得再来回问用户。 */
    private static String describe(String stage, String trail, long start, int streak) {
        String abi = "";
        try {
            if (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0) abi = Build.SUPPORTED_ABIS[0];
        } catch (Throwable ignored) {
        }
        long seconds = start > 0 ? (System.currentTimeMillis() - start) / 1000 : -1;
        return "[播放异常退出·无Java堆栈]" +
                " 最后阶段=" + stage +
                " 停留=" + seconds + "s" +
                " 连续=" + streak + "次" +
                " 硬解=" + SharedPreferencesUtil.getBoolean("player_codec", true) +
                " Texture=" + SharedPreferencesUtil.getBoolean("player_display", Build.VERSION.SDK_INT < 26) +
                " 听视频=" + SharedPreferencesUtil.getBoolean("player_audio_only", false) +
                " ABI=" + abi +
                " sdk=" + Build.VERSION.SDK_INT;
    }

    /** 用异常类型区分来源：控制台里按 exception_class 过滤即可只看播放类异常退出 */
    public static final class AbnormalExit extends RuntimeException {
        AbnormalExit(String message) {
            super(message);
        }
    }
}
