package com.RobinNotBad.BiliClient.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从弹幕文本里解析可跳转的时间点（「空降」类弹幕）。
 *
 * <p>弹幕里常见的写法：{@code 空降05:30}、{@code 空降成功 1:02:33}、{@code 坐标 3：20}（全角冒号）。
 * 这里只负责"这段文本里有没有一个像时间点的 mm:ss / h:mm:ss"，不问它是不是真的在说空降——
 * 用户点了才跳，点错也只是跳一次位置，不需要额外的语义判断。
 *
 * <p>两条边界：
 * <ul>
 *   <li>数字两侧不能紧邻其他数字，避免把 {@code x1:23}（倍速/比例）里的片段当时间；</li>
 *   <li>分与秒都必须 &le; 59，否则按比例/比分处理（{@code 90:00} 这种不走 mm:ss 写法）。</li>
 * </ul>
 */
public class DanmakuTimestampUtil {

    /** 允许的最大跳转目标：24 小时，防脏数据导致 seek 到一个离谱的位置 */
    private static final long MAX_SECONDS = 24 * 3600L;

    //(?<!\d) 与 (?!\d) 保证匹配到的不是更长数字的一部分
    private static final Pattern TIME_PATTERN = Pattern.compile(
            "(?<!\\d)(\\d{1,2})\\s*[:：]\\s*(\\d{1,2})(?:\\s*[:：]\\s*(\\d{1,2}))?(?!\\d)");

    private DanmakuTimestampUtil() {
    }

    /**
     * 解析弹幕文本里的第一个时间点。
     *
     * @return 毫秒位置；文本里没有合法时间点时返回 -1
     */
    public static long parseTimestampMs(String text) {
        if (text == null || text.isEmpty()) return -1;
        Matcher matcher = TIME_PATTERN.matcher(text);
        while (matcher.find()) {
            try {
                int hour = 0;
                int minute;
                int second;
                if (matcher.group(3) != null) {
                    //h:mm:ss
                    hour = Integer.parseInt(matcher.group(1));
                    minute = Integer.parseInt(matcher.group(2));
                    second = Integer.parseInt(matcher.group(3));
                } else {
                    //mm:ss
                    minute = Integer.parseInt(matcher.group(1));
                    second = Integer.parseInt(matcher.group(2));
                }
                if (minute > 59 || second > 59) continue;
                long totalSeconds = hour * 3600L + minute * 60L + second;
                if (totalSeconds <= 0 || totalSeconds > MAX_SECONDS) continue;
                return totalSeconds * 1000L;
            } catch (NumberFormatException ignored) {
                //理论上不会发生（正则已限定 1-2 位数字），保险起见继续找下一个
            }
        }
        return -1;
    }

    /** 文本里是否含可跳转的时间点 */
    public static boolean hasTimestamp(String text) {
        return parseTimestampMs(text) > 0;
    }
}
