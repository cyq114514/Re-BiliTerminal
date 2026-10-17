package com.RobinNotBad.BiliClient.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 崩溃次数统计：给崩溃页的「按时间筛选」提供数据。
 *
 * <p>为什么不用 SharedPreferences：崩溃页跑在 {@code :crash} 独立进程，
 * SP 的多进程缓存不共享，直接读会拿到上一次崩溃的旧值（同样的坑见 ErrorCatch 里对 trace 的处理）。
 * 这里改成「诊断目录下一个追加写的文本日志」，跨进程读文件即可。
 *
 * <p>每行格式：{@code <epochMillis>\t<类型>}。
 * 同时兼容旧版本留下的 {@code crash-*.txt}（文件名带 yyyyMMdd-HHmmss），
 * 升级前发生过的崩溃也能被算进去；两者按「分钟」去重，避免同一次崩溃算两遍。
 *
 * <p>注意：{@code :crash} 进程崩溃、原生崩溃、ANR 也会调 {@link #record(String)}，
 * 所以这里统计的是「本机记录到的异常次数」，不限于 Java 崩溃。
 */
public final class CrashStats {

    private static final String FILE_NAME = "crash-stats.log";

    /** 日志超过这个行数就裁剪，避免无限增长 */
    private static final int MAX_LINES = 500;

    /** 裁剪后保留的行数 */
    private static final int KEEP_LINES = 400;

    private static final SimpleDateFormat BUCKET_FORMAT = new SimpleDateFormat("yyyyMMddHHmm", Locale.CHINA);
    private static final SimpleDateFormat FILE_FORMAT = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA);

    private CrashStats() {
    }

    /** 记录一次异常。任何异常都不能往外抛——它常在崩溃路径上执行 */
    public static void record(String type) {
        record(type, System.currentTimeMillis());
    }

    /**
     * 按指定时间记录（补报历史时用真实发生时间，而不是补报的这一刻）。
     * 否则「近 7 天」的统计会把上个月的 native 崩溃算到今天。
     */
    public static void record(String type, long timestamp) {
        try {
            File file = statsFile();
            if (file == null) return;
            String line = timestamp + "\t" + (type == null ? "" : type) + "\n";
            FileOutputStream stream = new FileOutputStream(file, true);
            Writer writer = new OutputStreamWriter(stream, StandardCharsets.UTF_8);
            try {
                writer.write(line);
                writer.flush();
            } finally {
                writer.close();
            }
            trimIfNeeded(file);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 统计 sinceMillis 之后发生的异常次数（按分钟去重）。
     *
     * @param sinceMillis 起始时间戳，传 0 表示全部
     */
    public static int countSince(long sinceMillis) {
        Set<String> buckets = new HashSet<>();
        try {
            File file = statsFile();
            if (file != null && file.exists()) {
                BufferedReader reader = null;
                try {
                    reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        int tab = line.indexOf('\t');
                        String tsPart = tab < 0 ? line : line.substring(0, tab);
                        long ts;
                        try {
                            ts = Long.parseLong(tsPart.trim());
                        } catch (NumberFormatException ignored) {
                            continue;
                        }
                        if (ts >= sinceMillis) buckets.add(BUCKET_FORMAT.format(new Date(ts)));
                    }
                } finally {
                    if (reader != null) reader.close();
                }
            }
        } catch (Throwable ignored) {
        }

        //兼容旧记录：没有任何 stats 日志时，用本地 crash-*.txt 的文件名时间戳补上
        try {
            File dir = diagDir();
            if (dir != null) {
                File[] files = dir.listFiles((d, name) -> name.startsWith("crash-")
                        && name.endsWith(".txt") && !name.equals(FILE_NAME));
                if (files != null) {
                    for (File f : files) {
                        String name = f.getName();
                        //crash-yyyyMMdd-HHmmss.txt
                        if (name.length() < 9 + 15) continue;
                        String stamp = name.substring(6, name.length() - 4);
                        long ts;
                        try {
                            Date date = FILE_FORMAT.parse(stamp);
                            if (date == null) continue;
                            ts = date.getTime();
                        } catch (Throwable ignored) {
                            continue;
                        }
                        if (ts < sinceMillis) continue;
                        buckets.add(BUCKET_FORMAT.format(new Date(ts)));
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return buckets.size();
    }

    /** 今天 0 点之后的次数 */
    public static int countToday() {
        return countSince(startOfToday());
    }

    /** 最近 days 天（含今天）的次数 */
    public static int countLastDays(int days) {
        long now = System.currentTimeMillis();
        return countSince(now - Math.max(1, days) * 24L * 60L * 60L * 1000L);
    }

    public static long startOfToday() {
        //用 Calendar 而不是手算偏移：西半球负偏移下「% 取余」会得到负数，把"今天"算错一天
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0);
        calendar.set(java.util.Calendar.MINUTE, 0);
        calendar.set(java.util.Calendar.SECOND, 0);
        calendar.set(java.util.Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    // ---------------------------------------------------------------- 内部实现

    private static File statsFile() {
        File dir = diagDir();
        return dir == null ? null : new File(dir, FILE_NAME);
    }

    private static File diagDir() {
        try {
            String path = ProgressDiag.path();
            if (path == null || path.isEmpty()) return null;
            File dir = new File(path).getParentFile();
            if (dir == null) return null;
            if (!dir.exists() && !dir.mkdirs()) return null;
            return dir;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void trimIfNeeded(File file) {
        try {
            List<String> lines = new ArrayList<>();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = reader.readLine()) != null) lines.add(line);
            } finally {
                reader.close();
            }
            if (lines.size() <= MAX_LINES) return;

            List<String> kept = lines.subList(lines.size() - KEEP_LINES, lines.size());
            StringBuilder builder = new StringBuilder();
            for (String line : kept) builder.append(line).append('\n');
            FileOutputStream stream = new FileOutputStream(file, false);
            Writer writer = new OutputStreamWriter(stream, StandardCharsets.UTF_8);
            try {
                writer.write(builder.toString());
                writer.flush();
            } finally {
                writer.close();
            }
        } catch (Throwable ignored) {
        }
    }
}
