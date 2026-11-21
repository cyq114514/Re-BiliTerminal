package com.RobinNotBad.BiliClient.util;

import android.graphics.Rect;

/**
 * 圆屏几何：把"固定百分比"换成按内接圆计算的安全区。
 *
 * <p>384×384 的圆表上，内接正方形只有 271×271；水平 8% 内缩后的 324px 卡片，
 * 顶边落在 y=68 时四角已经在圆外。这里统一按"内容宽度 / 圆心半径"算内缩，
 * 供 BaseActivity、AdaptiveDialog、标题栏动作等共用，避免各页再各拍一个百分比。
 */
public final class RoundGeometry {

    private RoundGeometry() {
    }

    public static int radius(int widthPx, int heightPx) {
        return Math.min(widthPx, heightPx) / 2;
    }

    /**
     * 宽度已定时，居中矩形四角都能落在圆内的最大高度。
     *
     * @return px；宽度已经超过圆直径时返回 0
     */
    public static int maxHeightForWidth(int screenW, int screenH, int width) {
        int r = radius(screenW, screenH);
        int half = Math.min(width, screenW) / 2;
        int inner = r * r - half * half;
        if (inner <= 0) return 0;
        return (int) (2 * Math.sqrt(inner));
    }

    /**
     * 让宽度为 {@code width} 的居中矩形落在圆内，需要的上下内缩（取一侧）。
     *
     * <p>用于滚动列表：给列表底部留出这段 padding，最后一张卡片滚到底时四角仍在圆内。
     */
    public static int verticalInsetForWidth(int screenW, int screenH, int width) {
        int h = maxHeightForWidth(screenW, screenH, width);
        if (h <= 0) return radius(screenW, screenH);
        return Math.max(0, radius(screenW, screenH) - h / 2);
    }

    /**
     * 矩形四角相对圆心的最大出圆深度（px）。大于 0 表示有角在圆外。
     */
    public static float maxCornerDepth(Rect rect, int centerX, int centerY, int radius) {
        if (rect == null || rect.isEmpty()) return 0f;
        float max = 0f;
        max = Math.max(max, (float) Math.hypot(rect.left - centerX, rect.top - centerY));
        max = Math.max(max, (float) Math.hypot(rect.right - centerX, rect.top - centerY));
        max = Math.max(max, (float) Math.hypot(rect.left - centerX, rect.bottom - centerY));
        max = Math.max(max, (float) Math.hypot(rect.right - centerX, rect.bottom - centerY));
        return max - radius;
    }

    /**
     * 居中矩形的四角最大出圆深度（px）。
     */
    public static float maxCornerDepth(int screenW, int screenH, int width, int height) {
        Rect rect = new Rect(
                (screenW - width) / 2, (screenH - height) / 2,
                (screenW + width) / 2, (screenH + height) / 2);
        return maxCornerDepth(rect, screenW / 2, screenH / 2, radius(screenW, screenH));
    }
}