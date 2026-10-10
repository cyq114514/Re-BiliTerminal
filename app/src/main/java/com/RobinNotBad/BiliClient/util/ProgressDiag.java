package com.RobinNotBad.BiliClient.util;

import android.content.Context;
import android.os.Environment;

import com.RobinNotBad.BiliClient.BiliTerminal;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 观看进度诊断日志。
 *
 * 为什么需要它：番剧进度完全依赖服务端上的账号数据，而"上报静默失效"这类问题在设备上
 * 只能靠日志定位；本应用的调试日志默认走 logcat（release 包上普通用户拿不到），
 * 所以这里把关键链路落一份纯文本到应用外部目录，用户可自行取出，
 * 或由维护者通过设备工具读取：
 *  {@code Android/data/com.RobinNotBad.BiliClient.re/files/progress-diag.txt}
 *
 * 记录内容：登录态、三个进度来源的原始返回值、心跳上报的完整参数与服务端返回码、
 * 以及播放 25 秒后的一次"回读"（用于确认上报到底有没有落到服务端）。
 *
 * 约束：任何异常都不能影响播放与上报（全部 try/catch 吞掉），文件大小有上限。
 */
public class ProgressDiag {

    private static final String FILE_NAME = "progress-diag.txt";
    /** 超过这个大小就重建，避免长期使用下无限增长（诊断是临时手段，只保留最近一段） */
    private static final long MAX_BYTES = 256 * 1024;
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA);

    private static File file;
    private static boolean inited = false;

    private static void init() {
        if (inited) return;
        inited = true;
        try {
            Context ctx = BiliTerminal.context;
            File dir = ctx != null ? ctx.getExternalFilesDir(null) : null;
            if (dir == null) dir = new File(Environment.getExternalStorageDirectory(), "ReBiliDiag");
            if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            file = new File(dir, FILE_NAME);
        } catch (Throwable t) {
            file = null;
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
            if (file == null) return;
            synchronized (LOCK) {
                if (file.exists() && file.length() > MAX_BYTES) //noinspection ResultOfMethodCallIgnored
                    file.delete();
                FileWriter writer = new FileWriter(file, true);
                writer.write(FMT.format(new Date()) + " [" + tag + "] " + msg + "\n");
                writer.close();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 诊断文件路径（可能为空串，表示不可用）。 */
    public static String path() {
        init();
        return file != null ? file.getAbsolutePath() : "";
    }
}
