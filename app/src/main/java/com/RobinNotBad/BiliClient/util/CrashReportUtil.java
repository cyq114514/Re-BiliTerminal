package com.RobinNotBad.BiliClient.util;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AlertDialog;

import com.RobinNotBad.BiliClient.R;

/**
 * 崩溃上报的「开关 + 说明」入口。
 *
 * <p>1.2.1 起策略变化：**默认开启**，首次安装或升级到 1.2.1 及以上时弹一次说明框，
 * 告知收集什么、不收集什么、以及如何关闭；用户仍可在「设置-偏好设置」随时关闭。
 *
 * <p>唯一例外：如果用户在更早的版本里**明确点过"不同意"**，本次不强行打开，
 * 弹窗改为告知"当前为关闭状态、可自行开启"——尊重已经表达过的选择。
 *
 * <p>真正的捕获在 {@link com.RobinNotBad.BiliClient.ErrorCatch}，组装与发送在 {@link CrashUploader}。
 */
public final class CrashReportUtil {

    /**
     * 「实验室」里的主动崩溃开关：一次性触发器，置 true 后立刻复位并抛一次真实未捕获异常。
     */
    public static final String KEY_DEBUG_CRASH = "debug_crash_test";

    /** 1.2.1 说明弹窗是否已展示过（与旧版的"是否已询问"分开记，便于后续版本再次调整策略） */
    private static final String KEY_NOTICE_SHOWN = "crash_report_notice_121";

    /**
     * 偏好监听器必须保持强引用：SharedPreferences 只持弱引用，局部变量注册完就会被回收。
     */
    private static SharedPreferences.OnSharedPreferenceChangeListener listener;

    /** 说明弹窗的宿主 Activity 弱引用：页面在弹出前被销毁时「已弹」标志会永久卡住。 */
    private static java.lang.ref.WeakReference<Activity> hostRef;

    private CrashReportUtil() {
    }

    /** 是否上报。默认开启；用户关闭后写入 false。 */
    public static boolean isEnabled() {
        return SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.CRASH_REPORT_ENABLE, true);
    }

    /** 设置页开关：只写偏好，捕获侧在每次崩溃时实时读取。 */
    public static void setEnabled(Context context, boolean enabled) {
        SharedPreferencesUtil.putBoolean(SharedPreferencesUtil.CRASH_REPORT_ENABLE, enabled);
    }

    /**
     * 注册偏好监听，只为「主动触发崩溃」这个一次性开关服务。
     */
    public static void registerPreferenceListener(Context context) {
        SharedPreferences preferences = SharedPreferencesUtil.getSharedPreferences();
        if (listener != null || preferences == null) return;
        listener = (sharedPreferences, key) -> {
            if (KEY_DEBUG_CRASH.equals(key) && sharedPreferences.getBoolean(key, false)) {
                triggerTestCrash();
            }
        };
        preferences.registerOnSharedPreferenceChangeListener(listener);
    }

    /**
     * 首次安装或升级到 1.2.1+ 时弹一次说明框。
     *
     * <p>挂在 {@code BaseActivity} 上：引导页只有新用户经过，老用户升级后走不到。
     * 宿主 Activity 在弹出前被销毁时，用弱引用判重，避免"标志位卡死"。
     */
    public static void askNoticeIfNeeded(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (SharedPreferencesUtil.getBoolean(KEY_NOTICE_SHOWN, false)) return;

        Activity host = hostRef == null ? null : hostRef.get();
        if (host != null && !host.isFinishing() && !host.isDestroyed()) return;

        //更早版本里明确拒绝过的用户：不强行打开，只做告知
        boolean optedOut = SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.CRASH_REPORT_ASKED, false)
                && !SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.CRASH_REPORT_ENABLE, false);
        if (!optedOut) {
            SharedPreferencesUtil.putBoolean(SharedPreferencesUtil.CRASH_REPORT_ENABLE, true);
        }

        hostRef = new java.lang.ref.WeakReference<>(activity);
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                    .setTitle(R.string.crash_notice_title)
                    .setMessage(activity.getString(optedOut
                            ? R.string.crash_notice_message_off
                            : R.string.crash_notice_message_on))
                    .setCancelable(false)
                    .setPositiveButton(R.string.crash_notice_ok, (dialog, which) -> markNoticeShown());
            if (!optedOut) {
                builder.setNegativeButton(R.string.crash_notice_disable, (dialog, which) -> {
                    setEnabled(activity.getApplicationContext(), false);
                    markNoticeShown();
                });
            }
            builder.setOnDismissListener(dialog -> hostRef = null).show();
        } catch (Throwable t) {
            hostRef = null;
            CrashTrace.log("说明弹窗失败 " + t);
        }
    }

    private static void markNoticeShown() {
        SharedPreferencesUtil.putBoolean(KEY_NOTICE_SHOWN, true);
        //兼容旧标记：避免历史版本遗留的"未询问"状态再次触发其它逻辑
        SharedPreferencesUtil.putBoolean(SharedPreferencesUtil.CRASH_REPORT_ASKED, true);
    }

    /**
     * 「实验室 → 主动触发崩溃」：制造一次真实的未捕获异常，走与线上崩溃完全相同的链路。
     * 开关在触发瞬间复位，避免重启后再次崩溃形成循环；延迟 800ms 让开关先回位。
     */
    public static void triggerTestCrash() {
        SharedPreferencesUtil.putBoolean(KEY_DEBUG_CRASH, false);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            throw new RuntimeException("调试开关：用户主动触发的测试崩溃");
        }, 800);
    }
}
