package com.RobinNotBad.BiliClient.ui.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.widget.ImageView;

import androidx.appcompat.widget.AppCompatImageView;

import com.RobinNotBad.BiliClient.R;

/**
 * 固定宽高比的 ImageView（宽/高），用于封面图 16:10 定比裁切（移植自 Re-WearBili 的封面处理）。
 * ratio <= 0 时退回普通行为（adjustViewBounds 自适应），
 * 供关闭新版美学时把比例清零、回到旧版观感。
 */
public class RatioImageView extends AppCompatImageView {

    private float ratio = 0f;

    public RatioImageView(Context context) {
        super(context);
    }

    public RatioImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.RatioImageView);
        ratio = a.getFloat(R.styleable.RatioImageView_coverRatio, 0f);
        a.recycle();
    }

    public void setRatio(float ratio) {
        if (this.ratio == ratio) return;
        this.ratio = ratio;
        requestLayout();
        invalidate();
    }

    public float getRatio() {
        return ratio;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (ratio > 0) {
            int widthSpecMode = MeasureSpec.getMode(widthMeasureSpec);
            if (widthSpecMode == MeasureSpec.EXACTLY || widthSpecMode == MeasureSpec.AT_MOST) {
                int width = MeasureSpec.getSize(widthMeasureSpec);
                int height = Math.round(width / ratio);
                //父布局给了明确高度时尊重父布局
                if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                    return;
                }
                setMeasuredDimension(width, height);
                return;
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
