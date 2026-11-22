package com.RobinNotBad.BiliClient.util;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;

import java.lang.ref.WeakReference;

/**
 * 主线程掉帧监控：把一段段卡顿写进现有的文件日志（{@link Logu} 的 app-log.txt）。
 *
 * <p><b>为什么需要它</b>：手表与低配手机用户多半没有 adb，卡顿只能靠“感觉”，
 * 无法像 {@code dumpsys gfxinfo} 那样取到逐帧数据。文件日志已经是用户能自己导出的通道
 * （Download/ReBiliDiag/app-log.txt），把掉帧也写进去，复现完导一次就能定位。
 *
 * <p><b>只在「调试日志落文件」开启时工作</b>：该开关默认关闭，此处跟随它启停；
 * 关闭时只移除 Choreographer 回调，不注册任何监听以外的开销。
 *
 * <p><b>记录口径</b>：以相邻两帧的 vsync 时间差为准，超过 {@link #SLOW_FRAME_MS} 视为一次慢帧；
 * 连续慢帧合并成一段，段结束时只写一条日志（持续时长 / 慢帧数 / 最长单帧 / 当前页面），
 * 避免持续卡顿时每帧都落盘、反过来加重卡顿。
 *
 * <p>日志形如：{@code 卡顿 250ms/12 帧，最长 87ms，页面=SearchActivity}
 */
public final class FrameMonitor {

    /** 单帧超过这个值算一次慢帧：60Hz 下约 2 帧预算，低配设备的明显掉帧都能抓到 */
    private static final long SLOW_FRAME_MS = 32L;

    /** 一段卡顿最长记 5 秒就强制落盘一次：卡死时也要留下证据 */
    private static final long MAX_SEGMENT_MS = 5000L;

    /** 距上一帧超过 1 秒视为息屏/静止后恢复，重置基线，不能算成掉帧 */
    private static final long IDLE_RESET_MS = 1000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static boolean installed = false;
    private static boolean running = false;

    private static WeakReference<Activity> topActivity = new WeakReference<>(null);

    private static long lastFrameNanos = 0;
    private static long segmentStartNanos = 0;
    private static long segmentMaxDeltaMs = 0;
    private static int segmentSlowFrames = 0;

    private static final Choreographer.FrameCallback CALLBACK = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!running) return;
            Choreographer.getInstance().postFrameCallback(this);
            sample(frameTimeNanos);
        }
    };

    /**
     * SharedPreferences 只持监听器的弱引用，必须留一个强引用，否则开关切换会静默失效
     * （与 {@link Logu#registerPreferenceListener()} 是同一个坑）。
     */
    private static final SharedPreferences.OnSharedPreferenceChangeListener PREF_LISTENER =
            (sharedPreferences, key) -> {
                if (!Logu.KEY_FILE_LOG.equals(key)) return;
                applyState(sharedPreferences.getBoolean(Logu.KEY_FILE_LOG, false));
            };

    private FrameMonitor() {
    }

    /** 由 {@code BiliTerminal.onCreate} 在日志开关初始化之后调用一次（主线程） */
    public static void install() {
        if (installed) return;
        installed = true;
        applyState(Logu.isFileLoggingEnabled());
        try {
            SharedPreferences sp = SharedPreferencesUtil.getSharedPreferences();
            if (sp != null) sp.registerOnSharedPreferenceChangeListener(PREF_LISTENER);
        } catch (Throwable ignored) {
            //监控是尽力而为：注册不上就退化成“只能靠 logcat”，绝不影响业务
        }
    }

    /** 记录当前前台页面，供卡顿日志标注（在 Activity 的 onActivityResumed 里调用） */
    public static void notePage(Activity activity) {
        topActivity = new WeakReference<>(activity);
    }

    private static void applyState(boolean enabled) {
        //Choreographer.getInstance() 要求当前线程有 Looper；开关监听可能在后台线程触发，
        //统一扔回主线程执行，start/stop 都是幂等的
        MAIN.post(enabled ? FrameMonitor::startInternal : FrameMonitor::stopInternal);
    }

    private static void startInternal() {
        if (running) return;
        running = true;
        lastFrameNanos = 0;
        resetSegment();
        Choreographer.getInstance().postFrameCallback(CALLBACK);
    }

    private static void stopInternal() {
        if (!running) return;
        running = false;
        resetSegment();
        try {
            Choreographer.getInstance().removeFrameCallback(CALLBACK);
        } catch (Throwable ignored) {
        }
    }

    private static void sample(long frameTimeNanos) {
        long last = lastFrameNanos;
        lastFrameNanos = frameTimeNanos;
        if (last == 0) return;

        long deltaMs = (frameTimeNanos - last) / 1_000_000L;
        if (deltaMs >= IDLE_RESET_MS) {
            //息屏/长时间静止后的第一帧：间隔再大也不是“卡顿”，只重置基线
            resetSegment();
            return;
        }

        if (deltaMs >= SLOW_FRAME_MS) {
            if (segmentSlowFrames == 0) segmentStartNanos = last;
            segmentSlowFrames++;
            if (deltaMs > segmentMaxDeltaMs) segmentMaxDeltaMs = deltaMs;
            if ((frameTimeNanos - segmentStartNanos) / 1_000_000L >= MAX_SEGMENT_MS) flushSegment();
        } else if (segmentSlowFrames > 0) {
            flushSegment();
        }
    }

    private static void flushSegment() {
        int frames = segmentSlowFrames;
        long maxDelta = segmentMaxDeltaMs;
        long start = segmentStartNanos;
        long end = lastFrameNanos;
        resetSegment();
        if (frames <= 0) return;

        long durationMs = Math.max(0, (end - start) / 1_000_000L);
        //W 级别会立即 flush 落盘：卡顿段本来就不多，保证用户复现完立刻能取到
        Logu.w("卡顿 " + durationMs + "ms/" + frames + " 帧，最长 " + maxDelta
                + "ms，页面=" + pageName());
    }

    private static void resetSegment() {
        segmentStartNanos = 0;
        segmentMaxDeltaMs = 0;
        segmentSlowFrames = 0;
    }

    private static String pageName() {
        Activity activity = topActivity.get();
        if (activity == null) return "?";
        return activity.getClass().getSimpleName();
    }
}
