package com.RobinNotBad.BiliClient.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.RobinNotBad.BiliClient.R;
import com.google.android.material.card.MaterialCardView;

/**
 * 高亮卡片（移植自 Re-WearBili 的 Card 选中态）：
 * 选中时描边渐变为 B 站粉 2dp、底色泛粉（rgb(231,86,136)@10%），带过渡动画；
 * 取消选中时回到普通新样式。仅新版美学下有意义。
 */
public class BiliCardView extends MaterialCardView {

    private static final long ANIM_DURATION = 200L;

    private final int normalBg = ContextCompat.getColor(getContext(), R.color.card_bg_new);
    private final int normalBorder = ContextCompat.getColor(getContext(), R.color.card_border_new);
    private final int normalStrokeWidth = Math.max(1, (int) (getResources().getDisplayMetrics().density * 0.5f));
    private final int highlightBg = ContextCompat.getColor(getContext(), R.color.card_bg_highlight);
    private final int highlightBorder = ContextCompat.getColor(getContext(), R.color.bili_pink);
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
        setCardBackgroundColor(normalBg);
        setStrokeColor(normalBorder);
        setStrokeWidth(normalStrokeWidth);
        setRadius(getResources().getDisplayMetrics().density * 10f);
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
