package com.RobinNotBad.BiliClient.util;

import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/*
 * 未完工的log
 *
 * 日志有两个出口：
 *  1) logcat —— 原有行为，输出格式与开关完全不变；
 *  2) 可选的文件出口（{@link #setFileLoggingEnabled}，设置项 key 见 {@link #KEY_FILE_LOG}）。
 *
 * 为什么要有文件出口：logcat 在 Android 4.1 以上只能用电脑经 adb 取，第三方应用读不到
 * 别的应用的日志（除非设备 root）；而本项目的目标设备是手表与低配手机，用户手上不一定有电脑，
 * "开了调试开关却拿不到日志"是真实发生过的问题。日志文件与番剧进度诊断文件放同一个目录
 * （Download/ReBiliDiag/），用户在文件管理器里取一次就够。
 *
 * 文件出口的三条硬约束：
 *  - 绝不能反过来影响 logcat 与业务：任何异常都在文件出口内部吞掉；
 *  - 绝不能递归（写文件失败时再用 Logu 打日志会无限递归），所以这里一次都不调 Logu；
 *  - 绝不能无限增长：超过 {@link #MAX_FILE_BYTES} 就重建，只保留最近一段。
 */
public class Logu {
    public static boolean LOGV_ENABLED = false;
    public static boolean LOGD_ENABLED = false;
    public static boolean LOGI_ENABLED = false;

    /** 设置项 key：调试日志落文件。改动需冷启动生效（与 dev_logv/d/i 一致） */
    public static final String KEY_FILE_LOG = "dev_log_file";

    /** 日志文件大小上限：超过就重建 */
    private static final long MAX_FILE_BYTES = 1024 * 1024;
    private static final String FILE_NAME = "app-log.txt";

    private static final int LEVEL_V = 0;
    private static final int LEVEL_D = 1;
    private static final int LEVEL_I = 2;
    private static final int LEVEL_W = 3;
    private static final int LEVEL_E = 4;
    private static final int LEVEL_WTF = 5;

    private static volatile boolean fileLoggingEnabled = false;

    /** 落点探测失败后的重试间隔：拿不到目录时不能每行都重探，也不能一次就永久放弃 */
    private static final long RESOLVE_RETRY_MS = 10_000;
    /** V/D/I 级日志的 flush 间隔；W/E/F 级不受它限制，一律立即落盘 */
    private static final long FLUSH_INTERVAL_MS = 1000;

    private static final Object FILE_LOCK = new Object();
    private static final SimpleDateFormat FILE_FMT = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA);
    private static FileWriter fileWriter;
    private static File fileFor;
    private static long nextResolveAt;
    private static long lastFlushAt;
    /** SharedPreferences 只持监听器的**弱引用**，必须在这里留一个强引用，否则它随时会被 GC 掉、开关静默失效 */
    private static SharedPreferences.OnSharedPreferenceChangeListener prefListener;

    public static void v(String s) {
        output(LEVEL_V, s);
    }

    public static void i(String s) {
        output(LEVEL_I, s);
    }

    public static void d(String s) {
        output(LEVEL_D, s);
    }

    public static void w(String s) {
        output(LEVEL_W, s);
    }

    public static void e(String s) {
        output(LEVEL_E, s);
    }

    public static void wtf(String s) {
        output(LEVEL_WTF, s);
    }

    public static void v(String tag, String info) {
        output(LEVEL_V, tag + ">" + info);
    }

    public static void i(String tag, String info) {
        output(LEVEL_I, tag + ">" + info);
    }

    public static void d(String tag, String info) {
        output(LEVEL_D, tag + ">" + info);
    }

    public static void w(String tag, String info) {
        output(LEVEL_W, tag + ">" + info);
    }

    public static void e(String tag, String info) {
        output(LEVEL_E, tag + ">" + info);
    }

    public static void wtf(String tag, String info) {
        output(LEVEL_WTF, tag + ">" + info);
    }

    /**
     * 打开/关闭文件出口。由 {@code BiliTerminal.onCreate} 按设置项初始化，
     * 设置项变化时由 {@link #registerPreferenceListener()} 触发，切换即时生效。
     *
     * <p>关闭时**删除**日志文件：这是「关掉开关即清理本地日志」的约定，
     * 设置页在关闭前会弹确认（提示会删除本地日志）。
     * 因此这里保证一条不变式：<b>开关关闭 ⇒ 不残留日志文件</b>。
     */
    public static void setFileLoggingEnabled(boolean enabled) {
        fileLoggingEnabled = enabled;
        synchronized (FILE_LOCK) {
            closeWriterLocked();
            if (!enabled) deleteLogFileLocked();
            //复位落点缓存与重试节流：否则上一次探测失败（或换了落点）后会一直写不出去
            fileFor = null;
            nextResolveAt = 0;
            lastFlushAt = 0;
        }
    }

    public static boolean isFileLoggingEnabled() {
        return fileLoggingEnabled;
    }

    /**
     * 注册设置项监听，让「调试日志落文件」开关切换立即生效（并触发关闭时的清理）。
     *
     * <p>两个必须注意的点：
     * 1) {@code SharedPreferences} 只持有监听器的**弱引用**，所以强引用留在
     *    {@link #prefListener} 静态字段上，否则监听器会被 GC 掉、开关静默失效；
     * 2) 由 {@code BiliTerminal.onCreate} 在 SP 初始化之后调用一次。
     */
    public static void registerPreferenceListener() {
        if (prefListener != null) return;
        SharedPreferences sp;
        try {
            sp = SharedPreferencesUtil.getSharedPreferences();
        } catch (Throwable t) {
            return;
        }
        if (sp == null) return;
        prefListener = (sharedPreferences, key) -> {
            if (!KEY_FILE_LOG.equals(key)) return;
            setFileLoggingEnabled(sharedPreferences.getBoolean(KEY_FILE_LOG, false));
        };
        sp.registerOnSharedPreferenceChangeListener(prefListener);
    }

    /**
     * 日志文件路径；落点不可用时返回空串。给设置项描述与用户取件用。
     */
    public static String logFilePath() {
        File f = resolveLogFile();
        return f != null ? f.getAbsolutePath() : "";
    }

    /** 删除日志文件（仅在锁内调用）。尽力而为：删不掉也不影响应用，文件仍受 1MB 上限约束 */
    private static void deleteLogFileLocked() {
        try {
            File file = fileFor != null ? fileFor : resolveLogFile();
            if (file != null && file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 所有级别的唯一出口：先判开关（顺带省掉被禁用级别的栈遍历），再按需写文件，最后写 logcat。
     * 单参重载与双参重载只差消息是否带 {@code tag>} 前缀，与重构前的输出完全一致。
     */
    private static void output(int level, String message) {
        if (level == LEVEL_V && !LOGV_ENABLED) return;
        if (level == LEVEL_D && !LOGD_ENABLED) return;
        if (level == LEVEL_I && !LOGI_ENABLED) return;

        //tag 取调用方"类名>方法名"：getCaller 会跳过所有 Logu 自身的栈帧，
        //所以多这一层 output 不会改变 tag
        String tag = getCaller();

        writeToFile(level, tag, message);

        switch (level) {
            case LEVEL_V:
                Log.v(tag, message);
                break;
            case LEVEL_D:
                Log.d(tag, message);
                break;
            case LEVEL_I:
                Log.i(tag, message);
                break;
            case LEVEL_W:
                Log.w(tag, message);
                break;
            case LEVEL_E:
                Log.e(tag, message);
                break;
            default:
                Log.wtf(tag, message);
                break;
        }
    }

    private static void writeToFile(int level, String tag, String message) {
        if (!fileLoggingEnabled) return;
        synchronized (FILE_LOCK) {
            try {
                File file = fileFor;
                if (file == null) {
                    //拿不到落点时不能一次就永久放弃：目录探测在并发首触时可能暂时返回空串，
                    //所以这里改成限流重试（过一会儿还会再试），失败只跳过本次写入
                    long now = System.currentTimeMillis();
                    if (now < nextResolveAt) return;
                    file = resolveLogFile();
                    if (file == null) {
                        nextResolveAt = now + RESOLVE_RETRY_MS;
                        return;
                    }
                    fileFor = file;
                }

                //超上限就重建：先关流，能删掉就用追加模式新建；
                //删不掉（被占用/权限）时改用覆盖模式，否则上限失效、文件会一直涨
                boolean overwrite = false;
                if (fileWriter != null && file.length() > MAX_FILE_BYTES) {
                    closeWriterLocked();
                    if (!file.delete()) overwrite = true;
                }
                if (fileWriter == null) {
                    if (overwrite) {
                        fileWriter = new FileWriter(file, false);
                        writeFileHeaderLocked();
                    } else {
                        boolean fresh = !file.exists() || file.length() == 0;
                        fileWriter = new FileWriter(file, true);
                        if (fresh) writeFileHeaderLocked();
                    }
                }

                fileWriter.write(levelName(level) + " " + FILE_FMT.format(new Date()) + " "
                        + tag + " " + message + "\n");

                //分级 flush：W/E/F 立即落盘（排查崩溃时要看最后几行），V/D/I 按间隔节流。
                //两者都在调用者线程做磁盘 I/O，日志量大时不节流会让 Timer 线程被磁盘拖住
                long now = System.currentTimeMillis();
                if (level >= LEVEL_W || now - lastFlushAt >= FLUSH_INTERVAL_MS) {
                    fileWriter.flush();
                    lastFlushAt = now;
                }
            } catch (Throwable t) {
                //文件出口是尽力而为：写失败绝不外抛、绝不影响 logcat 与业务（更不能在此调 Logu）。
                //同样不永久放弃——复位落点并限流重试，存储短暂不可用时还能恢复
                closeWriterLocked();
                fileFor = null;
                nextResolveAt = System.currentTimeMillis() + RESOLVE_RETRY_MS;
            }
        }
    }

    private static void writeFileHeaderLocked() throws java.io.IOException {
        fileWriter.write("==== 调试日志开始 " + FILE_FMT.format(new Date()) + " ====\n");
    }

    private static void closeWriterLocked() {
        if (fileWriter == null) return;
        try {
            fileWriter.close();
        } catch (Throwable ignored) {
        }
        fileWriter = null;
    }

    /**
     * 落点直接复用番剧进度诊断已经探测好的"用户能直接取到"的目录
     * （Download/ReBiliDiag → Android/media/&lt;包名&gt; → Android/data/&lt;包名&gt;/files），
     * 两个文件放一起，用户取一次就够，也不必重复实现一遍可写探测。
     */
    private static File resolveLogFile() {
        try {
            File dir = null;
            String diagPath = ProgressDiag.path();
            if (diagPath != null && !diagPath.isEmpty()) dir = new File(diagPath).getParentFile();
            if (dir == null) return null;
            return new File(dir, FILE_NAME);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String levelName(int level) {
        switch (level) {
            case LEVEL_V:
                return "V";
            case LEVEL_D:
                return "D";
            case LEVEL_I:
                return "I";
            case LEVEL_W:
                return "W";
            case LEVEL_E:
                return "E";
            default:
                return "F";
        }
    }

    private static String getCaller() {
        //getStackTrace: [0]VM [1]Thread [2]getCaller [3]Logu.x(业务直接调用的重载) [4]…
        //不同重载深度不同，向前找第一个 Logu 之外的帧，避免硬编码下标错位/越界
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        StackTraceElement caller = stack[stack.length - 1];
        for (int i = 3; i < stack.length; i++) {
            if (!stack[i].getClassName().equals(Logu.class.getName())) {
                caller = stack[i];
                break;
            }
        }
        String name = caller.getClassName();
        int index = name.length();
        for (; index > 1; index--) {
            if (name.charAt(index - 1) == '.') break;
        }
        return name.substring(index) + ">" + caller.getMethodName();
    }
}
