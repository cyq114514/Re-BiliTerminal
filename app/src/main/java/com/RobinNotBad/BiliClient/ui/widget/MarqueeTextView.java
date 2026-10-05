package com.RobinNotBad.BiliClient.ui.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.widget.TextView;

import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

@SuppressLint("AppCompatCustomView")
public class MarqueeTextView extends TextView {
    public MarqueeTextView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        setMarquee();
    }

    public MarqueeTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setMarquee();
    }

    public MarqueeTextView(Context context) {
        super(context);
        setMarquee();
    }

    public void setMarquee() {
        if (!isInEditMode()) {
            if (SharedPreferencesUtil.getBoolean("marquee_enable", true)) {
                setSelected(true);
                setEllipsize(TextUtils.TruncateAt.MARQUEE);
                setSingleLine();
                //有限次数而非无限循环：溢出文本此前会永久逐帧重绘，列表页最费
                setMarqueeRepeatLimit(3);
                setFocusable(true);
            } else {
                setEllipsize(TextUtils.TruncateAt.END);
                setSingleLine();
            }
        }
    }
}
