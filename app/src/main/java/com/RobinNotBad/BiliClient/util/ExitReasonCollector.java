package com.RobinNotBad.BiliClient.util;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 补齐 Java 异常处理器覆盖不到的三类「进程消失」：原生崩溃、ANR、被系统低内存杀掉。
 *
 * <p>做法：Android 11（API 30）起系统保留进程退出原因历史，
 * 下次冷启动时读出来，把上次没有正常退出的条目补报一条。
 * 这样连「用户看到闪退、但 Java handler 完全没进过」的场景也能留下记录。
 *
 * <p>已知限制：API 30 以下没有这个接口（老手表多为 Android 8.x，拿不到）；
 * 且这里只能拿到系统给出的原因与描述，拿不到原生堆栈（需要 symbols）。
 */
public final class ExitReasonCollector {

    /** 上次采集到的最新时间戳，避免同一条记录每次启动都重报 */
    private static final String KEY_LAST_TS = "exit_reason_last_ts";

    private ExitReasonCollector() {
    }

    /** 冷启动时调用（后台线程）。任何异常都不外抛 */
    public static void collect(Context context) {
        if (Build.VERSION.SDK_INT < 30) {
            CrashTrace.log("退出原因: 系统低于 Android 11，跳过");
            return;
        }
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return;
            List<ApplicationExitInfo> list =
                    manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 8);
            if (list == null || list.isEmpty()) return;

            long lastTs = SharedPreferencesUtil.getLong(KEY_LAST_TS, 0);
            long newest = lastTs;
            for (ApplicationExitInfo info : list) {
                long ts = info.getTimestamp();
                if (ts <= lastTs) continue;
                if (ts > newest) newest = ts;

                int reason = info.getReason();
                //Java 崩溃已经由 ErrorCatch 报过，这里跳过避免双份；
                //用户主动退出/划掉任务、权限变更属于正常行为，不报
                if (reason == ApplicationExitInfo.REASON_CRASH
                        || reason == ApplicationExitInfo.REASON_EXIT_SELF
                        || reason == ApplicationExitInfo.REASON_USER_REQUESTED
                        || reason == ApplicationExitInfo.REASON_PERMISSION_CHANGE) {
                    continue;
                }

                //崩溃页 :crash 进程的退出不报：我们自己 killProcess 结束它，属于正常收尾
                String processName = info.getProcessName();
                if (processName != null && processName.contains(":crash")) continue;

                String type = typeOf(reason);
                //typeOf 返回 null 表示「这一类退出不构成崩溃线索」（含 REASON_OTHER 与 SIGNALED），跳过
                if (type == null) continue;

                String detail = buildDetail(context, info);
                JSONObject report = CrashUploader.buildEvent(context, type, "reason:" + reason,
                        describe(reason), "main", "", detail);
                //用系统记录的真实发生时间，避免历史崩溃被算进"今天"
                CrashStats.record(type, ts);
                CrashTrace.log("退出原因: " + type + " ts=" + ts);
                if (CrashReportUtil.isEnabled()) CrashUploader.submit(context, report);
            }
            if (newest > lastTs) SharedPreferencesUtil.putLong(KEY_LAST_TS, newest);
        } catch (Throwable t) {
            CrashTrace.log("退出原因: 读取失败 " + t.getClass().getSimpleName());
        }
    }

    /**
     * 只保留能当"崩溃线索"的原因，其余返回 null 跳过。
     *
     * <p>为什么跳过 {@code REASON_SIGNALED}：我们自己崩溃后就是 {@code Process.killProcess}（SIGKILL）收尾，
     * 每次 Java 崩溃都会额外产生一条 SIGNALED 记录，噪音大于价值。
     * {@code REASON_OTHER} 覆盖用户划掉任务、系统清理等正常场景，同样不报。
     */
    private static String typeOf(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
                return "native";
            case ApplicationExitInfo.REASON_ANR:
                return "anr";
            case ApplicationExitInfo.REASON_LOW_MEMORY:
                return "lmk";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
                return "init-failure";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
                return "resource";
            case ApplicationExitInfo.REASON_FREEZER:
                return "freezer";
            default:
                return null;
        }
    }

    private static String describe(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
                return "原生崩溃（Java handler 捕获不到，通常是播放器/第三方 .so）";
            case ApplicationExitInfo.REASON_ANR:
                return "应用无响应（ANR）被系统杀死";
            case ApplicationExitInfo.REASON_LOW_MEMORY:
                return "内存不足被系统回收";
            case ApplicationExitInfo.REASON_SIGNALED:
                return "收到致命信号被终止";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
                return "进程初始化失败";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
                return "资源占用过高被系统终止";
            case ApplicationExitInfo.REASON_FREEZER:
                return "被系统冻结（freezer）";
            default:
                return "系统记录的非正常退出，原因码 " + reason;
        }
    }

    private static String buildDetail(Context context, ApplicationExitInfo info) {
        StringBuilder builder = new StringBuilder();
        try {
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                    .format(new Date(info.getTimestamp()));
            builder.append("退出时间: ").append(time).append('\n');
            builder.append("进程: ").append(info.getProcessName()).append('\n');
            builder.append("原因码: ").append(info.getReason()).append('\n');
            builder.append("描述: ").append(info.getDescription()).append('\n');
            builder.append("importance: ").append(info.getImportance()).append('\n');
            builder.append("pss: ").append(info.getPss()).append("KB  rss: ").append(info.getRss()).append("KB\n");
            builder.append("包名: ").append(context.getPackageName()).append('\n');
            builder.append("注: 这一类退出没有 Java 堆栈，只有系统给出的原因。");
        } catch (Throwable t) {
            builder.append("明细拼装失败: ").append(t);
        }
        return builder.toString();
    }
}
