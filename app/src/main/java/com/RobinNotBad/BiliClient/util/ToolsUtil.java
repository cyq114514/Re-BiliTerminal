package com.RobinNotBad.BiliClient.util;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.BuildConfig;
import com.RobinNotBad.BiliClient.R;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

//2023-07-25

public class ToolsUtil {

    public static int dp2px(float dpValue) {
        final float scale = BiliTerminal.context.getResources().getDisplayMetrics().density;
        return (int) (dpValue * scale + 0.5f);
    }

    public static int sp2px(float spValue) {
        final float fontScale = BiliTerminal.context.getResources()
                .getDisplayMetrics().scaledDensity;
        return (int) (spValue * fontScale + 0.5f);
    }

    public static String md5(String plainText) {
        byte[] secretBytes;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(plainText.getBytes(java.nio.charset.StandardCharsets.UTF_8));   //平台默认字符集下不同设备签名结果可能不同
            secretBytes = md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("没有md5这个算法！");
        }
        StringBuilder md5code = new StringBuilder(new BigInteger(1, secretBytes).toString(16));
        for (int i = 0; i < 32 - md5code.length(); i++) {
            md5code.insert(0, "0");
        }
        return md5code.toString();
    }


    /**关于页"更新细节"：显示当前版本（日志数据第一条）的更新条目，数据源见 {@link UpdateLog}。*/
    public static String getUpdateLog(Context context) {
        StringBuilder str = new StringBuilder();
        String[] logItems = UpdateLog.currentItems();
        for (int i = 0; i < logItems.length; i++)
            str.append("\n").append((i + 1)).append(".").append(logItems[i]);
        return str.toString();
    }

    public static boolean isDebugBuild() {
        return BuildConfig.BETA;
    }

    /**
     * 宿主 Activity 是否还活着（未 finish、未 destroy）。
     *
     * <p>用来在异步回调里做最后一次判活：Glide 的 {@code with(view)} 一旦拿到已销毁的 Activity
     * 会直接抛 {@code IllegalArgumentException}（"You cannot start a load for a destroyed activity"），
     * 而这类崩溃全部来自「网络回来了、页面已经没了」的回调。
     *
     * <p>沿 {@link ContextWrapper} 链往上找 Activity（View 的 context 常常是包了 Activity 的
     * ContextThemeWrapper，直接 instanceof 判断会漏）；找不到 Activity（Application 上下文、
     * ApplicationContext 包装等）一律视为可用，交给 Glide 自己处理。
     */
    public static boolean isHostAlive(Context context) {
        Context current = context;
        //防御性上限：正常的包装层级不会超过 3~4 层，防止异常的自引用 Context 死循环
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof Activity) {
                Activity activity = (Activity) current;
                if (activity.isDestroyed()) return false;
                return !activity.isFinishing();
            }
            if (!(current instanceof ContextWrapper)) return true;
            current = ((ContextWrapper) current).getBaseContext();
        }
        return true;
    }

    /**把 ARGB 颜色转成弹幕协议的 RGB888 整数（白色应为 16777215）。*/
    public static int getRgb888(int color) {
        return ((color >> 16) & 0xff) << 16 | ((color >> 8) & 0xff) << 8 | (color & 0xff);
    }

}
