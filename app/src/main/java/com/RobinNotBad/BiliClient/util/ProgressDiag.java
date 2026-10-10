package com.RobinNotBad.BiliClient.util;

import android.content.Context;
import android.os.Build;
import android.os.Environment;

import com.RobinNotBad.BiliClient.BiliTerminal;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * 观看进度诊断日志。
 *
 * 为什么需要它：番剧进度完全依赖服务端上的账号数据，而"上报静默失效 / 续播串集"这类问题
 * 在设备上只能靠日志定位；本应用的调试日志默认走 logcat（release 包上普通用户拿不到），
 * 所以这里把关键链路落成纯文本文件。
 *
 * 落点（按优先级，{@link #path()} 返回第一个可用的）：
 *  1. {@code Download/ReBiliDiag/progress-diag.txt} —— Android 11 起
 *     {@code Android/data/...} 对文件管理器和第三方工具都是不可见的（连维护者也读不到），
 *     用户根本没有办法把这个文件取出来；放到公开的 Download 目录后，任何文件管理器都能打开。
 *     写不进去（没有存储权限）时自动跳过，不弹权限框、不报错。
 *  2. {@code Android/media/<包名>/progress-diag.txt} —— 应用专属媒体目录，不需要任何权限就能写，
 *     而且对文件管理器与第三方工具可见（App 自己的下载目录就在这一层）。
 *  3. {@code Android/data/<包名>/files/progress-diag.txt} —— 兜底，永远可写但用户通常取不到。
 * 三处都写；每份文件首行自带自己的绝对路径，取哪一份都不会搞混。
 *
 * 记录内容：登录态、三个进度来源的原始返回值、心跳上报的完整参数与服务端返回码、
 * 以及播放 25 秒后的一次"回读"（用于确认上报到底有没有落到服务端）。
 *
 * 约束：任何异常都不能影响播放与上报（全部 try/catch 吞掉），文件大小有上限。
 */
public class ProgressDiag {

    private static final String FILE_NAME = "progress-diag.txt";
    /** 公开目录下的子目录名：用户在 Download 里一眼能找到 */
    private static final String PUBLIC_DIR_NAME = "ReBiliDiag";
    /** 超过这个大小就重建，避免长期使用下无限增长（诊断是临时手段，只保留最近一段） */
    private static final long MAX_BYTES = 256 * 1024;
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA);

    private static File[] targets;
    private static String primaryPath = "";
    private static boolean inited = false;
    private static boolean headerWritten = false;

    private static void init() {
        if (inited) return;
        inited = true;
        ArrayList<File> list = new ArrayList<>();
        //1) 公开 Download 目录优先：用户在任何文件管理器里都能直接打开、直接发出去
        try {
            File pub = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), PUBLIC_DIR_NAME);
            File f = new File(pub, FILE_NAME);
            if (usable(pub, f)) list.add(f);
        } catch (Throwable ignored) {
        }
        //2) 应用专属媒体目录 Android/media/<包名>：不需要任何权限就能写，
        //   而且不像 Android/data 那样对文件管理器隐藏（实测第三方工具能直接读到）
        try {
            Context ctx = BiliTerminal.context;
            File[] mediaDirs = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && ctx != null)
                    ? ctx.getExternalMediaDirs() : null;
            File dir = mediaDirs != null && mediaDirs.length > 0 ? mediaDirs[0] : null;
            if (dir != null) {
                File f = new File(dir, FILE_NAME);
                if (usable(dir, f)) list.add(f);
            }
        } catch (Throwable ignored) {
        }
        //3) 应用外部目录兜底（不需要权限，但 Android 11+ 下用户取不到）
        try {
            Context ctx = BiliTerminal.context;
            File dir = ctx != null ? ctx.getExternalFilesDir(null) : null;
            if (dir == null) dir = new File(Environment.getExternalStorageDirectory(), PUBLIC_DIR_NAME);
            File f = new File(dir, FILE_NAME);
            if (usable(dir, f)) list.add(f);
        } catch (Throwable ignored) {
        }
        targets = list.toArray(new File[0]);
        primaryPath = targets.length > 0 ? targets[0].getAbsolutePath() : "";
    }

    /**
     * 目录是否真的可写：只 mkdirs + canWrite 在部分机型上会骗人（目录建出来了、文件写不进去），
     * 所以这里用一次"建/写/删"探针文件来确认。探针失败只是"不用这个落点"，绝不抛给调用方。
     */
    private static boolean usable(File dir, File file) {
        try {
            if (!dir.exists() && !dir.mkdirs()) return false;
            File probe = new File(dir, FILE_NAME + ".probe");
            if (probe.exists() && !probe.delete()) return false;
            FileWriter w = new FileWriter(probe, false);
            w.write("probe");
            w.close();
            //noinspection ResultOfMethodCallIgnored
            probe.delete();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 同时写 logcat 与文件；任何失败都静默忽略。 */
    public static void log(String tag, String msg) {
        try {
            Logu.w("进度诊断>" + tag, msg);
        } catch (Throwable ignored) {
        }
        try {
            init();
            if (targets == null || targets.length == 0) return;
            synchronized (LOCK) {
                String line = FMT.format(new Date()) + " [" + tag + "] " + msg + "\n";
                boolean needHeader = !headerWritten;
                headerWritten = true;
                for (File file : targets) {
                    try {
                        if (file.length() > MAX_BYTES) //noinspection ResultOfMethodCallIgnored
                            file.delete();
                        FileWriter writer = new FileWriter(file, true);
                        //首行记下落点，用户把文件发出来时能确认取的是哪一份
                        if (needHeader) writer.write("==== 进度诊断开始 文件: " + file.getAbsolutePath() + " ====\n");
                        writer.write(line);
                        writer.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 诊断文件路径（可能为空串，表示不可用）。返回的是"推荐取件位置"：
     * 正常应为 {@code .../Download/ReBiliDiag/progress-diag.txt}。
     */
    public static String path() {
        init();
        return primaryPath;
    }
}
