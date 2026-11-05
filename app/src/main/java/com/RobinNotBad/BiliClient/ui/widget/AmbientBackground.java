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

import com.RobinNotBad.BiliClient.ui.theme.ThemeManager;

/**
 * 氛围背景（移植自 Re-WearBili 的黑底氛围视觉）：
 * 纯黑底上铺一层克制的强调色装饰，加载时以 alpha 呼吸（1 ↔ 0.35）。
 * 全局只有一种画法：顶部一枚柔和的径向光晕，铺满整屏。
 * 开「圆屏适配」时也用这一套（只改页面布局，不换背景风格）——
 * 原来的圆屏专属画法（黑底 + 右上/左下两枚装饰圆环）会让整页发黑，与直屏观感割裂，已移除。
 * 只做静态绘制 + view alpha 动画，不逐帧重绘内容，照顾手表性能。
 *
 * <p>光晕颜色取自 {@link ThemeManager#getAmbient()}（主题色自定义），渐变圆心、半径、透明度、
 * 缓存策略全部保持原样；主题色变化时只重建缓存图，不改变绘制方式。
 */
public class AmbientBackground extends View implements ThemeManager.OnThemeChangedListener {

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

    //DITHER：粉色渐变的色带用抖动缓解
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private ValueAnimator breatheAnim;

    /** 径向光晕的预渲染结果（见 onDraw） */
    private Bitmap radialCache;
    private final Paint cachePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Rect cacheDst = new Rect();

    public AmbientBackground(Context context) {
        super(context);
    }

    public AmbientBackground(Context context, AttributeSet attrs) {
        super(context, attrs);
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
        //主题色变化监听跟着视图生命周期走，不持有已销毁的 View
        ThemeManager.removeListener(this);
        //只丢引用、不 recycle：硬件加速下这张图还被渲染线程的 DisplayList 拿着，
        //回收之后那一层会静默停止绘制（观感就是"返回页面后氛围背景消失、只剩纯黑"）。
        //丢掉引用后它本来就没有强引用了，交给 GC 回收即可。
        dropRadialCache();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ThemeManager.addListener(this);
        //重新挂回窗口时尺寸没变，onSizeChanged 不会再触发，缓存被丢掉就永远不重建，
        //只能走"每帧现算渐变"的兜底路径——这里自己补一次
        final int w = getWidth();
        final int h = getHeight();
        if (radialCache == null && w > 0 && h > 0) {
            ensureRadialShader(w, h);
            rebuildRadialCache(w, h);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0) {
            ensureRadialShader(w, h);
            rebuildRadialCache(w, h);
        }
        invalidate();
    }

    /**
     * 主题色变化：只重建渐变与缓存图，绘制结构、圆心、半径、透明度一律不动。
     * 尺寸还没确定时什么都不做——onSizeChanged 会带着新颜色重建。
     */
    @Override
    public void onThemeChanged() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        ensureRadialShader(w, h);
        rebuildRadialCache(w, h);
        invalidate();
    }

    /**
     * 光晕圆心相对高度的位置：**负数 = 落在屏幕上方之外**。
     *
     * <p>径向渐变在圆心是一个数学奇点，个别 GPU/Skia 实现会在那一像素上算出异常色值；
     * 再经过 1/4 分辨率缓存放大，就变成屏幕上的一个小黑点（用户实测：只在手表上出现、
     * 会被上层 UI 盖住、位置正好在光晕最亮处）。把圆心挪到屏幕外，可见区域内不再有任何
     * 像素落在奇点上，观感仍是"从顶部洒下来的光"。
     */
    private static final float CENTER_Y_RATIO = -0.04f;
    /** 光晕半径相对屏幕长边的比例 */
    private static final float RADIUS_RATIO = 0.72f;

    private RadialGradient buildShader(int w, int h) {
        int ambient = ThemeManager.getAmbient();
        return new RadialGradient(w * 0.5f, h * CENTER_Y_RATIO, Math.max(w, h) * RADIUS_RATIO,
                new int[]{ColorUtils.setAlphaComponent(ambient, 0x59),
                        ColorUtils.setAlphaComponent(ambient, 0x00)},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP);
    }

    /** 光晕的渐变着色器（缓存图建不起来时直接绘制用） */
    private void ensureRadialShader(int w, int h) {
        paint.setShader(buildShader(w, h));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final int w = getWidth();
        final int h = getHeight();
        if (w == 0 || h == 0) return;
        if (radialCache != null && !radialCache.isRecycled()) {
            cacheDst.set(0, 0, w, h);
            canvas.drawBitmap(radialCache, null, cacheDst, cachePaint);
        } else if (paint.getShader() != null) {
            //缓存建不起来时退回直接绘制：只是慢，不影响正确性。
            //用矩形铺满 + CLAMP 着色器，而不是画圆：半径外的像素由 CLAMP 取渐变的末端色（全透明），
            //效果与画圆一致，但不会在圆心/圆周留下一像素级的接缝（见 rebuildRadialCache 注释）
            canvas.drawRect(0, 0, w, h, paint);
        }
    }

    /**
     * 把径向光晕预渲染到一张 1/{@link #CACHE_SCALE} 分辨率的位图。
     * 渐变本身是低频图像，降采样后放大肉眼无差，但省掉了每帧的整屏渐变求值。
     */
    private void rebuildRadialCache(int w, int h) {
        dropRadialCache();
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
            cacheShaderPaint.setShader(buildShader(w, h));
            //用矩形铺满整块缓存图 + CLAMP 着色器（半径外取末端色=全透明），不再 drawCircle：
            //圆形的抗锯齿边缘有时会在"圆心"或"圆周"留下 1px 的异常像素，
            //降采样后放大到全屏，就表现为光晕正中/边缘上的一个小黑点
            cacheCanvas.drawRect(0, 0, w, h, cacheShaderPaint);
        } catch (Throwable t) {
            bitmap.recycle();
            return;
        }
        radialCache = bitmap;
    }

    /**
     * 只丢引用、<b>不 recycle</b>：这张位图会被硬件加速的 DisplayList 继续引用，
     * 回收它是"某一层突然不画了"的典型成因（见 onDetachedFromWindow 的注释）。
     */
    private void dropRadialCache() {
        radialCache = null;
    }

}
