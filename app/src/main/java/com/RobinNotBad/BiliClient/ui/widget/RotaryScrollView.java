package com.RobinNotBad.BiliClient.ui.widget;

import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.EditText;
import android.widget.ScrollView;

import androidx.core.view.ViewConfigurationCompat;

import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

public class RotaryScrollView extends ScrollView {
    private float scrollMultiple = 0f;
    private boolean rotaryEnabled = false;

    public RotaryScrollView(Context context) {
        super(context);
    }

    public RotaryScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public RotaryScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        initRotaryScroll();
    }

    private void initRotaryScroll() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            rotaryEnabled = SharedPreferencesUtil.getBoolean("ui_rotatory_enable", false);
            scrollMultiple = SharedPreferencesUtil.getFloat("ui_rotatory_scroll", 0);
            
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
     * 表冠事件路由到持有焦点的视图，滚动时焦点要落在本容器上；但正在输入（EditText 持焦）
     * 时不能抢——会把输入法顶掉、丢字（适配审计 P2-9）。
     */
    private void ensureRotaryFocus() {
        android.view.View focused = getRootView().findFocus();
        if (focused == this || focused instanceof EditText) return;
        requestFocus();
    }
}

