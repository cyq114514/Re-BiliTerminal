package com.RobinNotBad.BiliClient.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.RobinNotBad.BiliClient.BiliTerminal;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;

/**
 * 崩溃链路的同步追踪记录。
 *
 * <p>为什么不用文件：清单里没有 {@code WRITE_EXTERNAL_STORAGE}，公开的 Download 目录写不进去，
 * 只能回落到 {@code Android/media} 或 {@code Android/data}，而这些目录在很多文件管理器里根本看不到，
 * 等于"写了但取不到"。**应用私有的 SharedPreferences 不需要任何权限、一定可写**，
 * 崩溃页所在的新进程也能读同一份，是唯一可靠且不依赖用户操作的通道。
 *
 * <p>每条都走 {@code commit()} 同步落盘：崩溃之后进程随时会被杀，异步 apply 可能还没写完。
 * 只用于崩溃路径上的十几行输出，不做通用日志。
 */
public final class CrashTrace {

    /** 独立的偏好文件，避免和业务设置混在一起 */
    private static final String PREF_NAME = "crash_trace";

    /** 环形缓冲长度：够覆盖一次崩溃过程，又不至于无限增长 */
    private static final int CAPACITY = 60;

    private static final String KEY_COUNT = "count";

    private CrashTrace() {
    }

    /** 尽力而为地追加一行，绝不外抛：它本身不能成为崩溃路径上的新故障点。 */
    public static void log(String message) {
        try {
            Context context = BiliTerminal.context;
            if (context == null) return;
            SharedPreferences preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            int count = preferences.getInt(KEY_COUNT, 0);
            int index = count % CAPACITY;
            //commit 的返回值不看：失败也不重试，崩溃路径上不能出现任何循环
            preferences.edit()
                    .putString("l" + index, new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)
                            .format(new Date()) + " " + message)
                    .putInt(KEY_COUNT, count + 1)
                    .commit();
        } catch (Throwable ignored) {
        }
    }

    /** 读最后若干行，按时间正序。崩溃页直接展示用。 */
    public static String readTail(int maxLines) {
        try {
            Context context = BiliTerminal.context;
            if (context == null) return "";
            SharedPreferences preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            int count = preferences.getInt(KEY_COUNT, 0);
            if (count <= 0) return "";

            int from = Math.max(0, count - Math.min(maxLines, CAPACITY));
            LinkedList<String> lines = new LinkedList<>();
            for (int i = from; i < count; i++) {
                String line = preferences.getString("l" + (i % CAPACITY), null);
                if (line != null) lines.add(line);
            }
            StringBuilder builder = new StringBuilder();
            for (String item : lines) builder.append(item).append('\n');
            return builder.toString().trim();
        } catch (Throwable t) {
            return "";
        }
    }
}
