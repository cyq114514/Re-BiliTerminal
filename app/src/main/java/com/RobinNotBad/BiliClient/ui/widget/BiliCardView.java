package com.RobinNotBad.BiliClient.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.ui.theme.ThemeManager;
import com.google.android.material.card.MaterialCardView;

/**
 * 高亮卡片（移植自 Re-WearBili 的 Card 选中态）：
 * 选中时描边渐变为强调色 2dp、底色泛强调色（默认 rgb(231,86,136)@10%），带过渡动画；
 * 取消选中时回到普通新样式。仅新版美学下有意义。
 *
 * <p>高亮底色与描边取自 {@link ThemeManager}（随主题色自定义），普通底色/描边/圆角/描边宽度
 * 仍是结构性 token，不随主题变化。主题色变化时按当前选中态重绘，不重放动画。
 */
public class BiliCardView extends MaterialCardView implements ThemeManager.OnThemeChangedListener {

    private static final long ANIM_DURATION = 200L;

    //普通态：卡片灰底与发丝描边，属于结构性颜色，固定不变
    private int normalBg;
    private int normalBorder;
    private final int normalStrokeWidth = Math.max(1, (int) (getResources().getDisplayMetrics().density * 0.5f));
    //高亮态：底与描边跟随主题强调色
    private int highlightBg;
    private int highlightBorder;
    private final int highlightStrokeWidth = (int) (getResources().getDisplayMetrics().density * 2f);

    private ValueAnimator animator;
    private boolean highlighted = false;

    public BiliCardView(@NonNull Context context) {
        super(context);
        init();
    }

    public BiliCardView(@NonNull Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public BiliCardView(@NonNull Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        normalBg = ContextCompat.getColor(getContext(), R.color.card_bg_new);
        normalBorder = ContextCompat.getColor(getContext(), R.color.card_border_new);
        loadHighlightColors();
        setCardBackgroundColor(normalBg);
        setStrokeColor(normalBorder);
        setStrokeWidth(normalStrokeWidth);
        setRadius(getResources().getDisplayMetrics().density * 10f);
    }

    /** 只刷新"跟随主题"的两个色值；默认主题下取到的就是改造前的原值 */
    private void loadHighlightColors() {
        highlightBg = ThemeManager.getPrimarySoft();
        highlightBorder = ThemeManager.getPrimaryBorder();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ThemeManager.addListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        ThemeManager.removeListener(this);
        super.onDetachedFromWindow();
    }

    /** 主题色变化：按当前选中态直接落到新色，不播放过渡动画（换色不是交互） */
    @Override
    public void onThemeChanged() {
        loadHighlightColors();
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        if (highlighted) {
            setCardBackgroundColor(highlightBg);
            setStrokeColor(highlightBorder);
            setStrokeWidth(highlightStrokeWidth);
        } else {
            setCardBackgroundColor(normalBg);
            setStrokeColor(normalBorder);
            setStrokeWidth(normalStrokeWidth);
        }
    }

    /**
     * 仅供「主题配色」预览页使用：临时指定高亮色，不写主题、不影响其他卡片。
     *
     * <p>用户拖动调色盘时预览要立刻跟着变，但那时还没点「应用」，不能改全局主题；
     * 点应用后 {@link ThemeManager} 会回调 {@link #onThemeChanged()} 把颜色拉回真实值。
     *
     * @param bg     高亮底色（含 alpha）
     * @param border 高亮描边色
     */
    public void setHighlightColorsForPreview(int bg, int border) {
        //进页时的 200ms 过渡会逐帧把颜色写回旧值，拖动预览必须把它掐掉
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        highlightBg = bg;
        highlightBorder = border;
        if (highlighted) {
            setCardBackgroundColor(bg);
            setStrokeColor(border);
        }
    }

    public void setHighlighted(boolean highlighted) {
        if (this.highlighted == highlighted) return;
        this.highlighted = highlighted;
        animateTo(highlighted);
    }

    public boolean isHighlighted() {
        return highlighted;
    }

    private void animateTo(boolean toHighlight) {
        if (animator != null) animator.cancel();
        final int fromBg = getCardBackgroundColor().getDefaultColor();
        final int toBg = toHighlight ? highlightBg : normalBg;
        final int fromBorder = getStrokeColor();
        final int toBorder = toHighlight ? highlightBorder : normalBorder;
        final int fromWidth = getStrokeWidth();
        final int toWidth = toHighlight ? highlightStrokeWidth : normalStrokeWidth;

        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(ANIM_DURATION);
        animator.addUpdateListener(anim -> {
            float f = anim.getAnimatedFraction();
            setCardBackgroundColor(lerpColor(fromBg, toBg, f));
            setStrokeColor(ColorStateList.valueOf(lerpColor(fromBorder, toBorder, f)));
            setStrokeWidth(Math.round(fromWidth + (toWidth - fromWidth) * f));
        });
        animator.start();
    }

    private static int lerpColor(int from, int to, float f) {
        int a = Math.round(android.graphics.Color.alpha(from) + (android.graphics.Color.alpha(to) - android.graphics.Color.alpha(from)) * f);
        int r = Math.round(android.graphics.Color.red(from) + (android.graphics.Color.red(to) - android.graphics.Color.red(from)) * f);
        int g = Math.round(android.graphics.Color.green(from) + (android.graphics.Color.green(to) - android.graphics.Color.green(from)) * f);
        int b = Math.round(android.graphics.Color.blue(from) + (android.graphics.Color.blue(to) - android.graphics.Color.blue(from)) * f);
        return android.graphics.Color.argb(a, r, g, b);
    }
}
