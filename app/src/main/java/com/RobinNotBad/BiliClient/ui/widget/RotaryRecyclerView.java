package com.RobinNotBad.BiliClient.ui.widget;

import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import androidx.core.view.ViewConfigurationCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

public class RotaryRecyclerView extends RecyclerView {
    private float scrollMultiple = 0f;
    private boolean rotaryEnabled = false;

    public RotaryRecyclerView(Context context) {
        super(context);
    }

    public RotaryRecyclerView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public RotaryRecyclerView(Context context, AttributeSet attrs, int defStyleAttr) {
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
            scrollMultiple = SharedPreferencesUtil.getFloat("ui_rotatory_recycler", 0);
            
            if (rotaryEnabled && scrollMultiple > 0) {
                setOnGenericMotionListener((v, ev) -> {
                    if (ev.getAction() == MotionEvent.ACTION_SCROLL && 
                        ev.getSource() == InputDevice.SOURCE_ROTARY_ENCODER) {
                        float delta = -ev.getAxisValue(MotionEvent.AXIS_SCROLL) * 
                            ViewConfigurationCompat.getScaledVerticalScrollFactor(
                                ViewConfiguration.get(getContext()), getContext()) * 2;
                        smoothScrollBy(0, Math.round(delta * scrollMultiple));
                        requestFocus();
                        return true;
                    }
                    return false;
                });
            }
        }
    }
}

