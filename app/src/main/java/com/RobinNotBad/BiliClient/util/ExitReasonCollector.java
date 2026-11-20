package com.RobinNotBad.BiliClient.util;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPInputStream;

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

    /** trace 读取上限：原始最多 128KB，写进上报再截到 12000 字符（stacktrace 列上限 32000） */
    private static final int MAX_TRACE_BYTES = 128 * 1024;
    private static final int MAX_TRACE_CHARS = 12000;
    private static final long TRACE_BUDGET_MS = 2000L;

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
            //同一次采集里系统历史偶发重复条目：按 (时间, 原因, 进程) 去重，避免同一事件报两遍
            Set<String> seen = new HashSet<>();
            for (ApplicationExitInfo info : list) {
                long ts = info.getTimestamp();
                if (ts <= lastTs) continue;

                int reason = info.getReason();
                String processName = info.getProcessName();
                if (!seen.add(ts + "|" + reason + "|" + processName)) continue;
                if (ts > newest) newest = ts;

                //Java 崩溃已经由 ErrorCatch 报过，这里跳过避免双份；
                //用户主动退出/划掉任务、权限变更属于正常行为，不报
                if (reason == ApplicationExitInfo.REASON_CRASH
                        || reason == ApplicationExitInfo.REASON_EXIT_SELF
                        || reason == ApplicationExitInfo.REASON_USER_REQUESTED
                        || reason == ApplicationExitInfo.REASON_PERMISSION_CHANGE) {
                    continue;
                }

                //崩溃页 :crash 进程的退出不报：我们自己 killProcess 结束它，属于正常收尾
                if (processName != null && processName.contains(":crash")) continue;

                //只保留真正能当"崩溃线索"的原因：native 崩溃、ANR、初始化失败。
                //内存回收（lmk）、冻结（freezer）、资源占用过高（resource）都是系统在内存压力下的
                //常规操作，没有代码线索——按"只报最重要的崩溃"的口径，它们连本地记录都不再生成
                //（真 OOM 会以 java.lang.OutOfMemoryError 走 Java 崩溃链路，那条仍然上报）
                String type = typeOf(reason);
                if (type == null) {
                    CrashTrace.log("退出原因: 跳过非崩溃类 reason=" + reason);
                    continue;
                }

                String detail = buildDetail(context, info);
                JSONObject report = CrashUploader.buildEvent(context, type, "reason:" + reason,
                        describe(reason), "main", "", detail);
                //用系统记录的真实发生时间，避免历史崩溃被算进"今天"
                CrashStats.record(type, ts);
                CrashTrace.log("退出原因: " + type + " ts=" + ts);
                CrashFilter.Decision decision = CrashFilter.decide(report);
                if (!decision.upload) {
                    CrashTrace.log("退出原因: 仅本地（" + decision.reason + "）");
                    continue;
                }
                if (CrashReportUtil.isEnabled()) CrashUploader.submit(context, report);
            }
            //水位用 commit 同步落盘：进程可能在启动早期被杀，apply 丢失会让同一批退出记录
            //下次冷启动再报一遍（apply 的异步落盘是设计隐患）。
            if (newest > lastTs) {
                try {
                    SharedPreferencesUtil.sharedPreferences.edit().putLong(KEY_LAST_TS, newest).commit();
                } catch (Throwable t) {
                    SharedPreferencesUtil.putLong(KEY_LAST_TS, newest);
                }
            }
        } catch (Throwable t) {
            CrashTrace.log("退出原因: 读取失败 " + t.getClass().getSimpleName());
        }
    }

    /**
     * 只保留能当"崩溃线索"的原因，其余返回 null 跳过。
     *
     * <p>保留：原生崩溃、ANR、进程初始化失败——这三类要么有代码关联，要么用户能直接感知。
     * <p>跳过：
     * <ul>
     *   <li>{@code REASON_SIGNALED}：我们自己崩溃后就是 killProcess(SIGKILL) 收尾，每崩一次就会多一条；</li>
     *   <li>{@code REASON_OTHER}：用户划掉任务、系统清理等正常场景；</li>
     *   <li>{@code REASON_LOW_MEMORY} / {@code REASON_FREEZER} / {@code REASON_EXCESSIVE_RESOURCE_USAGE}：
     *       系统内存压力下的常规回收，没有代码线索（真 OOM 会走 Java 崩溃链路）。</li>
     * </ul>
     */
    private static String typeOf(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
                return "native";
            case ApplicationExitInfo.REASON_ANR:
                return "anr";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
                return "init-failure";
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
            //API30+ 的 app trace / API31+ 的 native tombstone 是定位 ANR/native 的唯一线索。
            //读不到（API<30、全局缓冲被覆盖、ROM 不提供）时保持原文案，不为空而失败。
            String trace = readTrace(info);
            if (trace != null && !trace.isEmpty()) {
                builder.append("\n\n").append(sanitizeTrace(trace, context));
            }
        } catch (Throwable t) {
            builder.append("明细拼装失败: ").append(t);
        }
        return builder.toString();
    }

    /**
     * 读取系统保存的退出 trace。
     *
     * <p>API30 一般是 ANR 的 app trace（GZIP 包裹的文本）；API31+ 的 native 崩溃
     * 可能返回 tombstone（裸 protobuf）。先嗅 gzip magic 再决定解压；读取限 128KB / 2s，
     * 超限截断。文本直接上报，二进制 tombstone 转 base64，避免污染文本字段。
     */
    private static String readTrace(ApplicationExitInfo info) {
        if (Build.VERSION.SDK_INT < 30) return null;
        try (InputStream raw = info.getTraceInputStream()) {
            if (raw == null) return null;
            BufferedInputStream in = new BufferedInputStream(raw, 8192);
            in.mark(2);
            int b0 = in.read();
            int b1 = in.read();
            in.reset();
            InputStream data = (b0 == 0x1f && b1 == 0x8b) ? new GZIPInputStream(in) : in;

            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[4096];
            long deadline = SystemClock.uptimeMillis() + TRACE_BUDGET_MS;
            int n;
            while ((n = data.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() >= MAX_TRACE_BYTES) break;
                if (SystemClock.uptimeMillis() > deadline) break;
            }
            byte[] bytes = out.toByteArray();
            if (bytes.length == 0) return null;
            if (isMostlyText(bytes)) {
                return "--- exit-trace(API30+) ---\n" + cut(bytesToString(bytes), MAX_TRACE_CHARS);
            }
            String base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
            return "--- exit-tombstone-base64(API31+) ---\n" + cut(base64, MAX_TRACE_CHARS);
        } catch (Throwable t) {
            CrashTrace.log("退出原因: trace 读取失败 " + t.getClass().getSimpleName());
            return null;
        }
    }

    private static String bytesToString(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** 粗略判断可读文本：可打印 ASCII/空白占比 >=85% 才按文本上报 */
    private static boolean isMostlyText(byte[] bytes) {
        if (bytes.length == 0) return false;
        int printable = 0;
        for (byte b : bytes) {
            int c = b & 0xff;
            if ((c >= 0x20 && c <= 0x7e) || c == '\n' || c == '\r' || c == '\t') printable++;
        }
        return printable * 100L >= bytes.length * 85L;
    }

    private static String cut(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "\n...[trace 已截断]";
    }

    /** trace 里可能带绝对路径/用户目录：上报前做最小脱敏，保留库名、偏移、signal 这些定位必需信息 */
    private static String sanitizeTrace(String trace, Context context) {
        String pkg = context.getPackageName();
        return trace
                .replace("/data/user/0/" + pkg, "<pkg>")
                .replace("/data/data/" + pkg, "<pkg>")
                .replace("/storage/emulated/0", "<ext>")
                .replace("/sdcard", "<ext>");
    }
}
