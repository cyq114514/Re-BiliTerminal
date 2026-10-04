package com.RobinNotBad.BiliClient.ui.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
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

    private int mode = MODE_RADIAL;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator breatheAnim;

    public AmbientBackground(Context context) {
        super(context);
    }

    public AmbientBackground(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setMode(int mode) {
        this.mode = mode;
        //不做软件层/位图缓存：硬件路径下绘制内容记录一次进 display list，
        //呼吸动画只改 view alpha（合成参数），不逐帧重绘，对低配手表最省
        //渐变的色带用 dither 缓解
        paint.setDither(true);
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
            if (paint.getShader() != null) {
                canvas.drawCircle(w * 0.5f, h * 0.14f, Math.max(w, h) * 0.75f, paint);
            }
        } else {
            drawRing(canvas, w * 0.82f, h * 0.10f, w * 0.375f);
            drawRing(canvas, w * 0.18f, h * 0.90f, w * 0.375f);
        }
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
