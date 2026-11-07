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
        applyTo(this);
    }

    /**跑马灯口径（1.3.3 反馈：私信页效果好评，全局接入顶栏标题）：吃实验室
     * 「文字跑马灯」开关，开=溢出滚动（3 次循环防逐帧耗电），关=末尾截断 */
    public static void applyTo(TextView view) {
        if (view.isInEditMode()) return;
        if (SharedPreferencesUtil.getBoolean("marquee_enable", true)) {
            view.setSelected(true);
            view.setEllipsize(TextUtils.TruncateAt.MARQUEE);
            view.setSingleLine();
            view.setMarqueeRepeatLimit(3);
            view.setFocusable(true);
        } else {
            view.setEllipsize(TextUtils.TruncateAt.END);
            view.setSingleLine();
        }
    }
}
