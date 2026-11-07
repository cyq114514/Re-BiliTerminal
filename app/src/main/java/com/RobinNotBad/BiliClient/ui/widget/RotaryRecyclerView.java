package com.RobinNotBad.BiliClient.ui.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.os.Build;
import android.util.AttributeSet;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.EditText;

import androidx.core.view.ViewConfigurationCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

public class RotaryRecyclerView extends RecyclerView {
    private float scrollMultiple = 0f;
    private boolean rotaryEnabled = false;
    //XML 的 android:maxHeight（适配审计 20261107 P1-2）：RecyclerView 不解析该属性，
    //选P浮层写 240dp 限高结果运行时完全失效、浮层高度不可控。构造时从 attrs 手动读出并在 onMeasure 收口
    private int maxHeight = Integer.MAX_VALUE;

    public RotaryRecyclerView(Context context) {
        super(context);
    }

    public RotaryRecyclerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initMaxHeight(attrs);
    }

    public RotaryRecyclerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initMaxHeight(attrs);
    }

    private void initMaxHeight(AttributeSet attrs) {
        if (attrs == null) return;
        //缺省给 -1：该属性缺省时 getDimensionPixelSize 会拿到 0，直接采用会把列表压成 0 高
        TypedArray a = getContext().obtainStyledAttributes(attrs, new int[]{android.R.attr.maxHeight});
        int xmlMax = a.getDimensionPixelSize(0, -1);
        a.recycle();
        if (xmlMax > 0) maxHeight = xmlMax;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        if (maxHeight != Integer.MAX_VALUE) {
            int hMode = MeasureSpec.getMode(heightSpec);
            int hSize = MeasureSpec.getSize(heightSpec);
            if (hMode == MeasureSpec.UNSPECIFIED) {
                //ScrollView 等容器给的 UNSPECIFIED（size 常为 0），仍要按上限收口
                heightSpec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST);
            } else if (hSize > maxHeight) {
                heightSpec = MeasureSpec.makeMeasureSpec(maxHeight,
                        hMode == MeasureSpec.EXACTLY ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST);
            }
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        initRotaryScroll();
    }

    private void initRotaryScroll() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            rotaryEnabled = SharedPreferencesUtil.getBoolean("ui_rotatory_enable", false);
            scrollMultiple = SharedPreferencesUtil.getFloat("ui_rotatory_recycler", 0);

            if (rotaryEnabled && scrollMultiple > 0) {
                setOnGenericMotionListener((v, ev) -> {
                    if (ev.getAction() == MotionEvent.ACTION_SCROLL &&
                        ev.getSource() == InputDevice.SOURCE_ROTARY_ENCODER) {
                        float delta = -ev.getAxisValue(MotionEvent.AXIS_SCROLL) *
                            ViewConfigurationCompat.getScaledVerticalScrollFactor(
                                ViewConfiguration.get(getContext()), getContext()) * 2;
                        smoothScrollBy(0, Math.round(delta * scrollMultiple));
                        ensureRotaryFocus();
                        return true;
                    }
                    return false;
                });
            }
        }
    }

    /**
     * 表冠事件路由到持有焦点的视图，所以滚动时焦点要落在本容器上。但正在输入（EditText 持焦）
     * 时不能抢——会把输入法顶掉、丢字（适配审计 P2-9）。其余情况维持原 requestFocus 行为：
     * 触屏模式下按钮通常不持焦点，这里实际是首次聚焦本容器，不构成对按钮的打扰。
     */
    private void ensureRotaryFocus() {
        View focused = getRootView().findFocus();
        if (focused == this || focused instanceof EditText) return;
        requestFocus();
    }
}

