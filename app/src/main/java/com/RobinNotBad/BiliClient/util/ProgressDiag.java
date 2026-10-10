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
 * 只写其中一个（按上面的优先级探测出第一个可用的；运行中写失败自动切到下一个），
 * 文件首行自带自己的绝对路径，取到哪一份都不会搞混。
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
    /** 当前正在写的落点下标：写入失败时在 log() 里顺延切换（见 log 的 failover 逻辑） */
    private static int activeTarget = -1;

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

    /**
     * 番剧进度诊断开关（设置→实验室→调试，默认关闭）。
     *
     * <p>关闭时本类只写 logcat，不再在设备上落任何文件——诊断文件与额外的回读请求都只为排查问题服务，
     * 普通使用没有必要承担这份开销与痕迹。开关打开后文件落在 {@link #path()} 指示的位置。
     */
    private static final String PREF_ENABLE = "diag_readback";

    private static boolean enabled() {
        try {
            //getBoolean 自带空保护（Application.onCreate 之前 sharedPreferences 为 null 时返回默认值）
            return SharedPreferencesUtil.getBoolean(PREF_ENABLE, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 写一条诊断：logcat + 文件（文件仅在"番剧进度诊断"开关打开时写），任何失败都静默忽略。
     *
     * <p>文件侧只写**一个**落点（审计 P3-3）：播放中每 5 秒就有两条日志，原来"每条日志把三个落点
     * 各开-写-关一遍"是三倍的文件 I/O；现在优先写 init() 探测出的第一个可用落点，
     * 写失败（存储被卸载/权限被回收）时自动切换到下一个，并在 logcat 留痕。
     * 首行落点标识按"文件为空"判断（审计 P3-4）：超过上限被删除重建后，新文件照样带首行。
     */
    public static void log(String tag, String msg) {
        try {
            Logu.w("进度诊断>" + tag, msg);
        } catch (Throwable ignored) {
        }
        try {
            if (!enabled()) return;
            init();
            if (targets == null || targets.length == 0) return;
            synchronized (LOCK) {
                String line = FMT.format(new Date()) + " [" + tag + "] " + msg + "\n";
                if (activeTarget < 0 || activeTarget >= targets.length) activeTarget = 0;
                for (int attempt = 0; attempt < targets.length; attempt++) {
                    int idx = (activeTarget + attempt) % targets.length;
                    File file = targets[idx];
                    try {
                        if (file.length() > MAX_BYTES) //noinspection ResultOfMethodCallIgnored
                            file.delete();
                        boolean needHeader = file.length() == 0;
                        FileWriter writer = new FileWriter(file, true);
                        //首行记下落点，用户把文件发出来时能确认取的是哪一份
                        if (needHeader) writer.write("==== 进度诊断开始 文件: " + file.getAbsolutePath() + " ====\n");
                        writer.write(line);
                        writer.close();
                        activeTarget = idx;
                        return;
                    } catch (Throwable t) {
                        //当前落点写不进去（SD 卡卸载/权限回收）：换下一个，下一行也从新的开始写
                        Logu.e("进度诊断", "落点写入失败，切换: " + file.getAbsolutePath());
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
