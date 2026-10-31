package com.RobinNotBad.BiliClient.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 自绘 HSV 调色盘：上半是「饱和度 × 明度」面板，下半是色相条，各带一枚圆形手柄。
 *
 * <p>为什么自绘：minSdk 19 + 不引第三方 UI 框架。只用到 {@link Canvas}/{@link Paint}/
 * {@link LinearGradient}，全部 API 1 起可用，低版本没有兼容分支。
 *
 * <p>刻意不用 {@link android.graphics.ComposeShader}：部分老机型硬件加速下混合模式表现不一致，
 * 这里改成「先横后竖」两层渐变叠加，效果等价且处处一致。
 *
 * <p>事件：按下时调用 {@link #requestDisallowInterceptTouchEvent(boolean)}，
 * 否则外层 {@code RotaryScrollView}（ScrollView 子类）会在竖向拖动时把事件抢走，
 * 表现为「拖一半页面跟着滚」。抬手/取消时释放。
 *
 * <p>拖动只更新自身并回调 {@link OnColorChangedListener}，不写盘、不触发全局刷新，
 * 由调用方决定何时真正应用。
 */
public class ColorPickerView extends View {

    /** 色值变化回调（拖动过程中会持续回调，调用方自己决定要不要落盘） */
    public interface OnColorChangedListener {
        void onColorChanged(int color);
    }

    private static final int DRAG_NONE = 0;
    private static final int DRAG_SV = 1;
    private static final int DRAG_HUE = 2;

    /** 手柄半径 */
    private final float handleRadius = dp(7f);
    /** 色相条高度 */
    private final float hueHeight = dp(24f);
    /** 面板与色相条之间的间距 */
    private final float gap = dp(14f);

    private final Paint svBasePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint svShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint huePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF svRect = new RectF();
    private final RectF hueRect = new RectF();

    private final float[] hsv = new float[]{0f, 1f, 1f};
    private int dragTarget = DRAG_NONE;
    /** 上一次的色相：纯灰/纯黑时 HSV 会丢掉色相，用它兜住，拖回来不会突然变红 */
    private float lastHue = 0f;
    /** 已用于构建渐变色的色相（-1 表示需要重建），只用于避免拖动中每帧重建 shader */
    private float lastHueForShader = -1f;

    private OnColorChangedListener listener;

    public ColorPickerView(Context context) {
        this(context, null);
    }

    public ColorPickerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        handleFillPaint.setStyle(Paint.Style.FILL);
        handleFillPaint.setColor(Color.WHITE);
        handleStrokePaint.setStyle(Paint.Style.STROKE);
        handleStrokePaint.setStrokeWidth(dp(2f));
        handleStrokePaint.setColor(0x66000000);
        huePaint.setStyle(Paint.Style.FILL);
        svShadowPaint.setStyle(Paint.Style.FILL);
        //作为可点按控件的可访问性描述（读屏用）
        setContentDescription("调色盘");
    }

    public void setOnColorChangedListener(OnColorChangedListener listener) {
        this.listener = listener;
    }

    // ------------------------------------------------------------------ 颜色读写

    /** 设置当前颜色（不会回调监听，避免和外部同步形成回环） */
    public void setColor(int color) {
        Color.colorToHSV(color, hsv);
        //纯灰/纯黑时 HSV 的 hue 恒为 0，用上次的色相兜住，拖回来不会突然跳成红色
        if (hsv[1] > 0.0001f) lastHue = hsv[0];
        else hsv[0] = lastHue;
        lastHueForShader = -1f;
        invalidate();
    }

    /** 当前颜色（不透明） */
    public int getColor() {
        return Color.HSVToColor(hsv) | 0xFF000000;
    }

    // ------------------------------------------------------------------ 尺寸与绘制

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        //左右留出手柄半径，手柄不会画到控件外被裁掉
        float left = handleRadius;
        float right = w - handleRadius;
        float bottom = h - handleRadius;
        float hueTop = bottom - hueHeight;
        hueRect.set(left, hueTop, Math.max(left + 1f, right), bottom);
        svRect.set(left, handleRadius, Math.max(left + 1f, right), Math.max(handleRadius + 1f, hueTop - gap));
        buildHueShader();
        lastHueForShader = -1f;
    }

    /** 色相条的 7 段渐变；尺寸变化时重建一次 */
    private void buildHueShader() {
        int[] colors = {
                0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF,
                0xFF0000FF, 0xFFFF00FF, 0xFFFF0000
        };
        huePaint.setShader(new LinearGradient(hueRect.left, 0f, hueRect.right, 0f,
                colors, null, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (svRect.width() <= 1f || svRect.height() <= 1f) return;

        //1. 饱和度：左白 → 右纯色（纯色的色相就是当前 H）
        if (Math.abs(hsv[0] - lastHueForShader) > 0.01f) {
            int pure = Color.HSVToColor(new float[]{hsv[0], 1f, 1f});
            svBasePaint.setShader(new LinearGradient(svRect.left, svRect.top, svRect.right, svRect.top,
                    Color.WHITE, pure, Shader.TileMode.CLAMP));
            lastHueForShader = hsv[0];
        }
        canvas.drawRect(svRect, svBasePaint);

        //2. 明度：上透明 → 下黑（叠在饱和度渐变之上，等价于 ComposeShader 的效果）
        svShadowPaint.setShader(new LinearGradient(svRect.left, svRect.top, svRect.left, svRect.bottom,
                0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
        canvas.drawRect(svRect, svShadowPaint);

        //3. 色相条
        canvas.drawRect(hueRect, huePaint);

        //4. 两枚手柄
        float svX = svRect.left + hsv[1] * svRect.width();
        float svY = svRect.top + (1f - hsv[2]) * svRect.height();
        canvas.drawCircle(svX, svY, handleRadius, handleFillPaint);
        canvas.drawCircle(svX, svY, handleRadius, handleStrokePaint);

        float hueX = hueRect.left + (hsv[0] / 360f) * hueRect.width();
        float hueY = (hueRect.top + hueRect.bottom) * 0.5f;
        canvas.drawCircle(hueX, hueY, handleRadius, handleFillPaint);
        canvas.drawCircle(hueX, hueY, handleRadius, handleStrokePaint);
    }

    // ------------------------------------------------------------------ 触摸

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragTarget = hitTarget(event.getX(), event.getY());
                if (dragTarget == DRAG_NONE) return false;
                //按下就宣告"这次竖向拖动归我"，不让外层 ScrollView 抢走
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                updateFromTouch(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragTarget == DRAG_NONE) return false;
                updateFromTouch(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragTarget == DRAG_NONE) return false;
                dragTarget = DRAG_NONE;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                performClick();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    /** 命中判定：色相条优先（它更窄，被 SV 面板压住的概率低），其余落在 SV 面板 */
    private int hitTarget(float x, float y) {
        if (y >= hueRect.top - gap * 0.5f && y <= hueRect.bottom + handleRadius) return DRAG_HUE;
        if (x >= svRect.left - handleRadius && x <= svRect.right + handleRadius
                && y >= svRect.top - handleRadius && y <= svRect.bottom + handleRadius) return DRAG_SV;
        return DRAG_NONE;
    }

    private void updateFromTouch(float x, float y) {
        if (dragTarget == DRAG_HUE) {
            float ratio = clamp01((x - hueRect.left) / Math.max(1f, hueRect.width()));
            hsv[0] = ratio * 360f;
            lastHue = hsv[0];
        } else if (dragTarget == DRAG_SV) {
            hsv[1] = clamp01((x - svRect.left) / Math.max(1f, svRect.width()));
            hsv[2] = 1f - clamp01((y - svRect.top) / Math.max(1f, svRect.height()));
        } else {
            return;
        }
        invalidate();
        if (listener != null) listener.onColorChanged(getColor());
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private float dp(float value) {
        return getResources().getDisplayMetrics().density * value;
    }
}
