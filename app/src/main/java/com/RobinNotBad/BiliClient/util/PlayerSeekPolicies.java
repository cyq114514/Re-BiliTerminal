package com.RobinNotBad.BiliClient.util;

import java.util.Locale;

/**
 * 播放器精确跳转与横滑灵敏度的纯逻辑策略。
 *
 * <p>抽出来是为了：1）不依赖 Android，便于以后补 JVM 单测；2）播放页只负责读事件、画浮层。
 */
public final class PlayerSeekPolicies {

    /** 横滑调进度的存档键，值形如 "1.0"。 */
    public static final String KEY_SWIPE_SEEK_SCALE = "player_swipe_seek_scale";

    /** 默认倍率：改造前就是 1:1，升级后手感不变。 */
    public static final float DEFAULT_SCALE = 1.0f;

    private static final float[] SUPPORTED_SCALES = {0.25f, 0.5f, 1.0f, 1.5f, 2.0f, 3.0f};

    private PlayerSeekPolicies() {
    }

    /** 把存档读成倍率；空值、非数字、档位外的值都退回默认，避免负数/NaN 把进度跑飞。 */
    public static float swipeScale(String raw) {
        if (raw == null) return DEFAULT_SCALE;
        float scale;
        try {
            scale = Float.parseFloat(raw.trim());
        } catch (NumberFormatException ignored) {
            return DEFAULT_SCALE;
        }
        return swipeScale(scale);
    }

    /**
     * 直接按数值取档：0.25/0.5/1.0/1.5/2.0/3.0 之外的值都退回默认。
     *
     * <p>设置页里灵敏度是 input_float（SettingsAdapter 用 putFloat 写入），
     * 播放页必须用 getFloat 读；getString 去读 float 存档时
     * SharedPreferencesImpl 会抛 ClassCastException，横滑一触发就崩（1.2.9 实测）。
     */
    public static float swipeScale(float scale) {
        for (float supported : SUPPORTED_SCALES) {
            if (Math.abs(supported - scale) < 0.005f) return supported;
        }
        return DEFAULT_SCALE;
    }

    /**
     * 解析“跳到指定时间”的输入为秒数；非法返回 null。
     *
     * <p>支持纯秒数、mm:ss、hh:mm:ss；允许前后空白与全角冒号；冒号分段里分/秒必须 < 60。
     */
    public static Long parseJumpSeconds(String input) {
        if (input == null) return null;
        String text = input.trim().replace('：', ':');
        if (text.isEmpty()) return null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!(c >= '0' && c <= '9') && c != ':') return null;
        }
        String[] parts = text.split(":");
        if (parts.length > 3) return null;
        long[] numbers = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                numbers[i] = Long.parseLong(parts[i]);
            } catch (NumberFormatException ignored) {
                return null;
            }
            if (numbers[i] > 999_999L) return null;
        }
        long total;
        if (numbers.length == 1) {
            total = numbers[0];
        } else if (numbers.length == 2) {
            if (numbers[1] >= 60) return null;
            total = numbers[0] * 60L + numbers[1];
        } else {
            if (numbers[1] >= 60 || numbers[2] >= 60) return null;
            total = numbers[0] * 3600L + numbers[1] * 60L + numbers[2];
        }
        return total < 0 ? null : total;
    }

    /** 把目标毫秒限制进 [0, durationMs]；时长无效（直播/未 prepare）返回 null。 */
    public static Long clampJumpMs(long targetMs, long durationMs) {
        if (durationMs <= 0) return null;
        if (targetMs < 0) targetMs = 0;
        if (targetMs > durationMs) targetMs = durationMs;
        return targetMs;
    }

    /** 给“跳到指定时间”按钮用：输入框提示文案。 */
    public static String jumpHint(long durationMs) {
        if (durationMs <= 0) return "直播不支持跳转";
        long totalSec = durationMs / 1000;
        return "输入秒数或 mm:ss / hh:mm:ss，最长 "
                + String.format(Locale.US, "%02d:%02d:%02d", totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60);
    }
}
