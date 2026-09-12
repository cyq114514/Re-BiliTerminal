package com.RobinNotBad.BiliClient.util;

import android.os.Build;
import android.widget.TextView;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public class CompatUtil {
    @SuppressWarnings("CharsetObjectCanBeUsed")
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    public static Charset getCharsetUTF8() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            return StandardCharsets.UTF_8;
        } else {
            return UTF_8;
        }
    }

    public static void setCompoundDrawablesRelative(TextView tv, int start, int top, int end, int bottom) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            tv.setCompoundDrawablesRelativeWithIntrinsicBounds(start, top, end, bottom);
        } else {
            tv.setCompoundDrawablesWithIntrinsicBounds(start, top, end, bottom);
        }
    }
}
