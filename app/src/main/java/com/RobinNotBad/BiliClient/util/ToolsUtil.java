package com.RobinNotBad.BiliClient.util;

import android.animation.AnimatorInflater;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.view.View;

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

    /**
     * 给自绘卡片补上「按压缩放」反馈（0.9 倍 / 150ms，与 CardStyle 的卡片同一套）。
     *
     * <p><b>为什么必须走代码而不是 XML</b>：{@code android:stateListAnimator} 是 API 21 引入的属性，
     * 本项目 minSdk=19，AAPT2 在 link 阶段会把它从 layout XML 里剥掉——实测 dump APK 后
     * {@code cell_menu_grid} 与 {@code cell_episode_card} 里都没有该属性，而 styles.xml 的
     * CardStyle 里保留了（style 不参与这层剥离）。所以布局里写了也等于没写。
     *
     * @param view 已经可点的卡片根视图
     */
    public static void applyPressScale(View view) {
        if (view == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        try {
            view.setStateListAnimator(AnimatorInflater.loadStateListAnimator(
                    view.getContext(), R.animator.press_scale));
        } catch (Throwable t) {
            //拿不到动画就维持无反馈，绝不影响点击；留一条日志便于真机排查
            Logu.e("press-scale", "加载按压缩放失败：" + t);
        }
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
