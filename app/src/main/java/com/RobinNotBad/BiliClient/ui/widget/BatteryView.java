package com.RobinNotBad.BiliClient.ui.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

public class BatteryView extends View {
    private int mPower = 100;
    private boolean mCharging = false;

    //该控件常驻播放器顶栏，onDraw 每帧都会走，Paint/Rect 提升为字段复用，避免每帧分配
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect stroke = new Rect();
    private final Rect head = new Rect();
    private final Rect fill = new Rect();

    public BatteryView(Context context) {
        super(context);
    }

    public BatteryView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public BatteryView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @SuppressLint("DrawAllocation")
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float power_percent = mPower / 100.0f;

        int padding = (int) (getWidth() * 0.05f);

        int battery_width = (int) (getWidth() * 0.9f);
        int battery_height = getHeight() - padding;

        int stroke_width = (int) (getWidth() * 0.05f);

        int head_width = (int) (getWidth() * 0.08f);
        int head_height = (int) (getHeight() * 0.4f);
        int head_top = (battery_height - head_height + padding) / 2;
        int head_right = battery_width + head_width;
        int head_bottom = head_top + head_height;

        int inside_padding = (int) (getWidth() * 0.08f);

        int fill_left = padding + inside_padding;
        int fill_top = padding + inside_padding;
        int fill_right = (int) ((battery_width - inside_padding) * power_percent);
        int fill_bottom = battery_height - inside_padding;

        //边框
        paint.setColor(Color.WHITE);
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke_width);

        stroke.set(padding, padding,
                battery_width, battery_height);
        canvas.drawRect(stroke, paint);

        //电池头
        paint.setStyle(Paint.Style.FILL);
        head.set(battery_width, head_top, head_right, head_bottom);
        canvas.drawRect(head, paint);

        //画电量
        paint.setStrokeWidth(0f);
        paint.setColor(mCharging ? Color.GREEN :
                (mPower <= 20 ? Color.RED : Color.WHITE));

        if (power_percent != 0) {
            fill.set(fill_left, fill_top, fill_right, fill_bottom);
            canvas.drawRect(fill, paint);
        }
    }

    public void setPower(int power) {
        mPower = power;
        if (mPower < 0) {
            mPower = 0;
        }
        invalidate();
    }

    public void setCharging(boolean charging) {
        mCharging = charging;
        invalidate();
    }
}
