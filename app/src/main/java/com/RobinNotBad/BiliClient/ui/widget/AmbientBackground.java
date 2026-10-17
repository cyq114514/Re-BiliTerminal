package com.RobinNotBad.BiliClient.ui.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.core.graphics.ColorUtils;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.util.ToolsUtil;

/**
 * 氛围背景（移植自 Re-WearBili 的黑底氛围视觉）：
 * 纯黑底上铺一层克制的 B 站粉装饰，加载时以 alpha 呼吸（1 ↔ 0.35）。
 * MODE_RADIAL：方屏，顶部一枚柔和的粉色径向光晕；
 * MODE_ROUND：圆屏，右上/左下两枚 75% 宽的装饰圆环（对应 Re-WearBili 的装饰圆图）。
 * 只做静态绘制 + view alpha 动画，不逐帧重绘内容，照顾手表性能。
 */
public class AmbientBackground extends View {

    public static final int MODE_RADIAL = 0;
    public static final int MODE_ROUND = 1;

    private static final int COLOR_PINK = 0xFFFE679A;

    /**
     * 径向光晕的预渲染降采样倍数。
     *
     * <p>为什么需要：径向渐变的半径是 {@code max(w,h)*0.75}，在放大界面后几乎铺满整屏，
     * 每帧都要对全屏像素做一次渐变求值。实测 1.6 倍缩放下「关于」页单帧 GPU 侧耗时 972ms
     * （logcat 里 Davey! duration=2779ms），滑动直接掉到个位数帧。
     *
     * <p>渐变本身是柔和的低频图像，降到 1/4 分辨率再放大肉眼分辨不出，
     * 但每帧从「整屏渐变计算」变成「一次位图拉伸」。缓存图约 w/4×h/4 像素（1080×2160 屏上 0.6MB）。
     */
    private static final int CACHE_SCALE = 4;

    private int mode = MODE_RADIAL;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator breatheAnim;

    /** 径向光晕的预渲染结果；仅 MODE_RADIAL 使用 */
    private Bitmap radialCache;
    private final Paint cachePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Rect cacheDst = new Rect();

    public AmbientBackground(Context context) {
        super(context);
    }

    public AmbientBackground(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setMode(int mode) {
        this.mode = mode;
        //渐变的色带用 dither 缓解
        paint.setDither(true);
        if (mode == MODE_RADIAL && getWidth() > 0) rebuildRadialCache(getWidth(), getHeight());
        invalidate();
    }

    //加载态呼吸；平时静止
    public void setBreathing(boolean breathing) {
        if (breathing) {
            if (breatheAnim != null && breatheAnim.isRunning()) return;
            setAlpha(1f);
            breatheAnim = ValueAnimator.ofFloat(1f, 0.35f);
            breatheAnim.setDuration(1000);
            breatheAnim.setRepeatCount(ValueAnimator.INFINITE);
            breatheAnim.setRepeatMode(ValueAnimator.REVERSE);
            breatheAnim.setInterpolator(new AccelerateDecelerateInterpolator());
            breatheAnim.addUpdateListener(anim -> setAlpha((float) anim.getAnimatedValue()));
            breatheAnim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationCancel(Animator animation) {
                    setAlpha(1f);
                }
            });
            breatheAnim.start();
        } else if (breatheAnim != null) {
            breatheAnim.cancel();
            breatheAnim = null;
            setAlpha(1f);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (breatheAnim != null) {
            breatheAnim.cancel();
            breatheAnim = null;
        }
        releaseRadialCache();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (mode == MODE_RADIAL && w > 0) {
            float cx = w * 0.5f;
            float cy = h * 0.14f;
            float radius = Math.max(w, h) * 0.75f;
            paint.setShader(new RadialGradient(cx, cy, radius,
                    new int[]{ColorUtils.setAlphaComponent(COLOR_PINK, 0x59),
                            ColorUtils.setAlphaComponent(COLOR_PINK, 0x00)},
                    new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            rebuildRadialCache(w, h);
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final int w = getWidth();
        final int h = getHeight();
        if (w == 0 || h == 0) return;
        if (mode == MODE_RADIAL) {
            if (radialCache != null && !radialCache.isRecycled()) {
                cacheDst.set(0, 0, w, h);
                canvas.drawBitmap(radialCache, null, cacheDst, cachePaint);
            } else if (paint.getShader() != null) {
                //缓存建不起来时退回直接绘制：只是慢，不影响正确性
                canvas.drawCircle(w * 0.5f, h * 0.14f, Math.max(w, h) * 0.75f, paint);
            }
        } else {
            drawRing(canvas, w * 0.82f, h * 0.10f, w * 0.375f);
            drawRing(canvas, w * 0.18f, h * 0.90f, w * 0.375f);
        }
    }

    /**
     * 把径向光晕预渲染到一张 1/{@link #CACHE_SCALE} 分辨率的位图。
     * 渐变本身是低频图像，降采样后放大肉眼无差，但省掉了每帧的整屏渐变求值。
     */
    private void rebuildRadialCache(int w, int h) {
        releaseRadialCache();
        if (w <= 0 || h <= 0) return;
        int bw = Math.max(1, w / CACHE_SCALE);
        int bh = Math.max(1, h / CACHE_SCALE);
        Bitmap bitmap;
        try {
            bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        } catch (Throwable t) {
            return;   //内存不足等情况：onDraw 会自动退回直接绘制
        }
        try {
            Canvas cacheCanvas = new Canvas(bitmap);
            //先用原尺寸坐标系画，再整体缩放到缓存分辨率，避免半径/圆心两处换算出错
            cacheCanvas.scale(bw / (float) w, bh / (float) h);
            Paint cacheShaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
            cacheShaderPaint.setShader(new RadialGradient(w * 0.5f, h * 0.14f, Math.max(w, h) * 0.75f,
                    new int[]{ColorUtils.setAlphaComponent(COLOR_PINK, 0x59),
                            ColorUtils.setAlphaComponent(COLOR_PINK, 0x00)},
                    new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            cacheCanvas.drawCircle(w * 0.5f, h * 0.14f, Math.max(w, h) * 0.75f, cacheShaderPaint);
        } catch (Throwable t) {
            bitmap.recycle();
            return;
        }
        radialCache = bitmap;
    }

    private void releaseRadialCache() {
        if (radialCache != null && !radialCache.isRecycled()) radialCache.recycle();
        radialCache = null;
    }

    private void drawRing(Canvas canvas, float cx, float cy, float radius) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(ToolsUtil.dp2px(1.5f));
        paint.setColor(ColorUtils.setAlphaComponent(COLOR_PINK, 0x38));
        canvas.drawCircle(cx, cy, radius, paint);
        //内侧一圈更淡的实心圆增加层次
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ColorUtils.setAlphaComponent(COLOR_PINK, 0x12));
        canvas.drawCircle(cx, cy, radius - ToolsUtil.dp2px(2f), paint);
    }
}
