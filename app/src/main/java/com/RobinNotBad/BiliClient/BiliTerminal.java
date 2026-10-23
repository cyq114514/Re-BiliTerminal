package com.RobinNotBad.BiliClient;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.multidex.MultiDex;

import com.RobinNotBad.BiliClient.activity.SplashActivity;
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity;
import com.RobinNotBad.BiliClient.activity.user.info.UserInfoActivity;
import com.RobinNotBad.BiliClient.api.DynamicApi;
import com.RobinNotBad.BiliClient.api.MessageApi;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.CookieRefresher;
import com.RobinNotBad.BiliClient.util.CrashReportUtil;
import com.RobinNotBad.BiliClient.util.CrashUploader;
import com.RobinNotBad.BiliClient.util.ExitReasonCollector;
import com.RobinNotBad.BiliClient.util.LaunchPing;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.PlayerSentinel;
import com.RobinNotBad.BiliClient.util.ResumePageUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.RobinNotBad.BiliClient.util.TerminalContext;

import org.json.JSONException;

import java.io.IOException;
import java.lang.ref.WeakReference;

public class BiliTerminal extends Application {

    @SuppressLint("StaticFieldLeak")
    public static Context context;

    /**
     * 进程启动的近似时刻（Application 类加载时取一次），只用于启动耗时埋点：
     * {@code adb logcat -s startup} 能看到「MultiDex / onCreate / 进入首屏」各花了多久。
     */
    public static final long START_UPTIME_MS = SystemClock.uptimeMillis();

    /**
     * 启动瞬间那批网络任务（崩溃补发、哨兵上报、未读数）统一延后这么久。
     *
     * <p>冷启动时它们会和开屏页/首屏的请求抢 DNS、TLS 与带宽，弱网设备上首屏能慢几百毫秒；
     * 这几件事都允许迟到，不影响正确性（补发失败本来就会留到下次启动）。
     */
    private static final long STARTUP_NET_DEFER_MS = 2000;

    /** 首屏（非 Splash）resumed 之后执行一次的回调：需要宿主 Activity 的场景（更新弹窗）用这个 */
    public interface FirstPageCallback {
        void onFirstPage(Activity activity);
    }

    /**
     * 首屏 resumed 后要执行一次的启动回调（免责声明 / 深夜提醒 / 启动检查更新）。
     *
     * <p>用列表而不是单个字段：启动弹窗和启动检查更新是两件独立的事，后注册的不能把前一个挤掉。
     */
    private static final java.util.List<FirstPageCallback> sFirstPageCallbacks = new java.util.ArrayList<>();

    public static boolean DPI_FORCE_CHANGE = false;

    private static WeakReference<InstanceActivity> instance = new WeakReference<>(null);

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        //4.4/5.x 冷启动的一次性开销：dex 只有一份时它会白扫一遍 APK，用日志把它记下来便于定位
        long multidexStart = SystemClock.uptimeMillis();
        MultiDex.install(this);
        android.util.Log.d("startup", "MultiDex.install "
                + (SystemClock.uptimeMillis() - multidexStart) + "ms（sdk=" + Build.VERSION.SDK_INT + "）");
        //最早的一层崩溃兜底：从这一行到 onCreate 装好 ErrorCatch 之间发生的崩溃，
        //以前是完全不留痕的（MultiDex / SP / getFitDisplayContext 都在这个窗口里）
        ErrorCatch.initEarly(this);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (context == null) {
            SharedPreferencesUtil.sharedPreferences = getSharedPreferences("default", MODE_PRIVATE);
            context = getFitDisplayContext(this);

            //崩溃页 CatchActivity 跑在独立进程 :crash（见 AndroidManifest）。那个进程只需渲染崩溃页：
            //准备 SP + context 即可，不做上报初始化、不装 ErrorCatch、不跑登录续期等——
            //既省内存也避免重复初始化与重复上报
            if (!isMainProcess()) {
                //崩溃页自己也会崩（用户已经遇到过"点退出又崩"），这里装上只落盘、不重开页面的处理器
                ErrorCatch.initCrashProcess(context);
                return;
            }

            //崩溃捕获始终由 ErrorCatch 承担；上报开关只决定"传不传"，不影响崩溃页
            ErrorCatch.getInstance().init(context);
            CrashReportUtil.registerPreferenceListener(this);
            //上次没传出去的崩溃（弱网/WiFi 被拦）在这次启动补发；
            //同时补发"启动最早期的崩溃"，并把系统记录的 native 崩溃 / ANR / 被低内存杀掉也补报上来。
            //三个任务必须各自独立提交：原来串在同一个 lambda 里，ExitReasonCollector 排在两次网络补发之后，
            //而单次上传超时 8 秒、队列最多 5 条，弱网下最坏要等 40 秒才轮到它——"上次原生崩溃"的记录因此经常丢
            //启动瞬间的网络任务全部延后：崩溃补发、早期崩溃补发都属于"可以迟到"的活，
            //冷启动时它们会和开屏/首屏抢 DNS/TLS/带宽（弱网上首屏能慢几百毫秒）
            Handler startupHandler = new Handler(Looper.getMainLooper());
            startupHandler.postDelayed(() -> CenterThreadPool.run(() -> CrashUploader.flushPending(this)),
                    STARTUP_NET_DEFER_MS);
            startupHandler.postDelayed(() -> CenterThreadPool.run(() -> CrashUploader.flushEarly(this)),
                    STARTUP_NET_DEFER_MS + 500);
            //系统记录的 native 崩溃/ANR/被回收原因只读本地数据、不占网络，保持立即执行
            CenterThreadPool.run(() -> ExitReasonCollector.collect(this));

            //活跃设备统计：每天一条，随机延迟 3~8 秒再发，避免所有设备在冷启动瞬间同时打点。
            //用 Handler 延迟而不是让线程 sleep：sleep 会白占一个后台线程最长 8 秒
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> CenterThreadPool.run(() -> LaunchPing.reportIfNeeded(this)),
                    3000 + (long) (Math.random() * 5000));

            //播放哨兵：上次播放没有正常收尾（原生崩溃/被系统回收）时补报一条。
            //这类退出进不了 Java 崩溃 handler，只能靠标记残留来发现（内部先清标记再报，不会重复）
            //必须放后台：它要读写偏好并 commit（同步落盘），在冷启动主线程上就是白等几次 fsync
            startupHandler.postDelayed(() -> CenterThreadPool.run(() -> PlayerSentinel.checkAndReport(this)),
                    STARTUP_NET_DEFER_MS + 800);

            //1.2.0-v5 曾在本地保存过"每集看到哪里"(episode_progress)，v6 起改为只信服务端
            //（与 PiliPlus 同口径）。清一次就够，用标记记住：原来每次冷启动都清，等于每次启动
            //多一次偏好文件重写，旧数据也早在第一次就没了
            if (!SharedPreferencesUtil.getBoolean("episode_progress_cleared_v6", false)) {
                try {
                    getSharedPreferences("episode_progress", MODE_PRIVATE).edit().clear().apply();
                    SharedPreferencesUtil.putBoolean("episode_progress_cleared_v6", true);
                } catch (Exception ignored) {
                }
            }

            //冷启动恢复记录的生命周期：所有 Activity 都销毁 = 用户正常退出任务，清除记录；
            //后台被系统杀进程不会走任何回调，记录保留，下次冷启动由 Splash 恢复页面。
            //配置变更重建时新页 onCreate 先于旧页 onDestroy，计数不会跌到 0，不会误清
            registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
                private int liveActivityCount = 0;

                @Override
                public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
                    liveActivityCount++;
                }

                @Override
                public void onActivityDestroyed(@NonNull Activity activity) {
                    liveActivityCount--;
                    if (liveActivityCount <= 0) ResumePageUtil.clear();
                }

                @Override
                public void onActivityStarted(@NonNull Activity activity) {
                }

                @Override
                public void onActivityResumed(@NonNull Activity activity) {
                    //崩溃上报里的 page 字段：记录崩溃时用户在哪一页，便于复现
                    CrashUploader.setCurrentPage(activity.getClass().getSimpleName());

                    //首屏之后的启动弹窗：此刻首屏已在栈顶，弹窗才会盖在它上面。
                    //延迟 250ms 是为"恢复链"场景留出余量——连续 startActivity 时第一条 resumed
                    //的可能只是栈底页，紧接着还有几条要入栈，太早弹会被后入栈的页面盖住
                    final java.util.List<FirstPageCallback> pending;
                    synchronized (BiliTerminal.class) {
                        if (sFirstPageCallbacks.isEmpty() || activity instanceof SplashActivity) return;
                        pending = new java.util.ArrayList<>(sFirstPageCallbacks);
                        sFirstPageCallbacks.clear();
                    }
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        //回调执行时首选"当前栈顶页"：恢复链/转场期间首个 resumed 的页可能已被盖住，
                        //而更新弹窗需要一个还活着的宿主 Activity
                        Activity host = activity;
                        InstanceActivity top = instance.get();
                        if (top != null && !top.isDestroyed()) host = top;
                        for (FirstPageCallback callback : pending) {
                            try {
                                callback.onFirstPage(host);
                            } catch (Throwable t) {
                                Logu.e("startup", "启动回调执行失败: " + t);
                            }
                        }
                    }, 250);
                }

                @Override
                public void onActivityPaused(@NonNull Activity activity) {
                }

                @Override
                public void onActivityStopped(@NonNull Activity activity) {
                }

                @Override
                public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
                }
            });

            //调试日志一律默认关闭，且与是否 debug 构建无关：它们会改变应用行为或产生额外落盘
            Logu.LOGV_ENABLED = SharedPreferencesUtil.getBoolean("dev_logv", false);
            Logu.LOGD_ENABLED = SharedPreferencesUtil.getBoolean("dev_logd", false);
            Logu.LOGI_ENABLED = SharedPreferencesUtil.getBoolean("dev_logi", false);

            //日志落文件：开上面三个开关的用户多半没有 adb（logcat 只能用电脑取），
            //所以额外给一个直接落在 Download/ReBiliDiag/ 的日志文件。
            //开关为关闭时这里会顺手清掉历史日志文件（"关掉开关即清理"的不变式由 Logu 保证）
            Logu.setFileLoggingEnabled(SharedPreferencesUtil.getBoolean(Logu.KEY_FILE_LOG, false));
            //注册设置项监听，让开关切换立即生效（SharedPreferences 只持弱引用，强引用在 Logu 内部）
            Logu.registerPreferenceListener();

            //登录续期：进程存活期间按小时检查一次 Cookie 是否需要刷新。
            //修复前刷新只在启动页触发一次，App 常驻不重启就会错过续期窗口，Cookie 到期即掉登录。
            //未登录时内部会直接返回，开在这里是安全的。
            CookieRefresher.startPeriodicCheck();

            if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.DYNAMIC_UPDATE_CHECK_ENABLE, true) && SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) != 0) {
                startupHandler.postDelayed(() -> CenterThreadPool.run(() -> {
                    try {
                        long updateBaseline = SharedPreferencesUtil.getLong("dynamic_update_baseline", 0);
                        int updateNum = DynamicApi.checkDynamicUpdate("all", updateBaseline);
                        SharedPreferencesUtil.putInt(SharedPreferencesUtil.DYNAMIC_UPDATE_NUM, updateNum);
                    } catch (IOException | JSONException e) {
                        SharedPreferencesUtil.putInt(SharedPreferencesUtil.DYNAMIC_UPDATE_NUM, 0);
                    }
                }), STARTUP_NET_DEFER_MS + 700);
            }

            if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.MESSAGE_UPDATE_CHECK_ENABLE, true) && SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) != 0) {
                startupHandler.postDelayed(() -> CenterThreadPool.run(() -> {
                    try {
                        int messageUnread = MessageApi.checkMessageUnread();
                        int privateMsgUnread = MessageApi.checkPrivateMsgUnread();
                        int totalUnread = messageUnread + privateMsgUnread;
                        SharedPreferencesUtil.putInt(SharedPreferencesUtil.MESSAGE_UPDATE_NUM, totalUnread);
                    } catch (Exception e) {
                        SharedPreferencesUtil.putInt(SharedPreferencesUtil.MESSAGE_UPDATE_NUM, 0);
                    }
                }), STARTUP_NET_DEFER_MS + 900);
            }

            android.util.Log.d("startup", "Application.onCreate 完成，距进程启动 "
                    + (SystemClock.uptimeMillis() - START_UPTIME_MS) + "ms");
        }
    }

    /**
     * 安排一个"首屏 Activity 显示之后"执行一次的回调（主线程执行）。
     *
     * <p>为什么需要它：启动弹窗（免责声明 / 深夜提醒）是 {@code startActivity(DialogActivity)}，
     * 如果从开屏页的后台线程直接拉起，会和首屏的 startActivity 竞争——弹窗被压在首屏下面，
     * 用户要再点一下菜单才看到（实测反馈）。挂到这里，等首屏真的 resumed 了再弹，顺序就稳定了。
     */
    public static void runAfterFirstPageResumed(Runnable callback) {
        if (callback == null) return;
        runAfterFirstPageResumed(activity -> callback.run());
    }

    public static void runAfterFirstPageResumed(FirstPageCallback callback) {
        if (callback == null) return;
        synchronized (BiliTerminal.class) {
            sFirstPageCallbacks.add(callback);
        }
    }

    public static void setInstance(InstanceActivity instanceActivity) {
        instance = new WeakReference<>(instanceActivity);
    }

    @Nullable
    public static InstanceActivity getInstanceActivityOnTop() {
        return instance.get();
    }

    /**
     * 重写attachBaseContext方法，用于调整应用内dpi
     * 尝试下这种风格代码是否会导致低版本设备异常
     *
     * @param old The origin context.
     */
    public static Context getFitDisplayContext(Context old) {
        float dpiTimes = SharedPreferencesUtil.getFloat("dpi", 1.0F);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) return old;
        if (!DPI_FORCE_CHANGE && dpiTimes == 1.0F) return old;
        try {
            DisplayMetrics displayMetrics = old.getResources().getDisplayMetrics();
            Configuration configuration = old.getResources().getConfiguration();
            configuration.densityDpi = (int) (displayMetrics.densityDpi * dpiTimes);
            return old.createConfigurationContext(configuration);
        } catch (Exception e) {
            //MsgUtil.err(e,old);
            return old;
        }
    }

    public static int getVersion() throws PackageManager.NameNotFoundException {
        return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
    }

    public static boolean isDebugBuild() {
        return "debug".equals(BuildConfig.BUILD_TYPE);
    }

    public static void jumpToVideo(Context context, long aid) {
        TerminalContext.getInstance().enterVideoDetailPage(context, aid);
    }

    public static void jumpToVideo(Context context, String bvid) {
        TerminalContext.getInstance().enterVideoDetailPage(context, bvid);
    }

    public static void jumpToArticle(Context context, long cvid) {
        TerminalContext.getInstance().enterArticleDetailPage(context, cvid);
    }

    public static void jumpToUser(Context context, long mid) {
        Intent intent = new Intent();
        intent.setClass(context, UserInfoActivity.class);
        intent.putExtra("mid", mid);
        context.startActivity(intent);
    }

    /**
     * 当前是否为主进程。崩溃页 CatchActivity 跑在 :crash 进程，那里要跳过 Bugly/ErrorCatch/登录续期等初始化。
     *
     * <p>读 /proc/self/cmdline：NUL 分隔，第一段就是进程名。主进程名等于包名；私有进程名形如
     * {@code 包名:crash}。读不到时按主进程处理（宁可多初始化也不能让主进程不初始化）。
     */
    private boolean isMainProcess() {
        try (java.io.FileInputStream in = new java.io.FileInputStream("/proc/self/cmdline")) {
            byte[] buf = new byte[256];
            int len = in.read(buf);
            if (len <= 0) return true;
            int end = 0;
            while (end < len && buf[end] != 0) end++;
            String name = new String(buf, 0, end);
            return name.equals(getPackageName());
        } catch (Throwable e) {
            return true;
        }
    }

}
