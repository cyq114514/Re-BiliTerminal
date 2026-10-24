package com.RobinNotBad.BiliClient.util;

import java.util.Random;

/**
 * 设备指纹：给竖屏短视频取流用的 {@code Display-ID} 请求头。
 *
 * <p>为什么需要：官方 App 每个请求都带 {@code Buvid} + 稳定的 {@code Display-ID}，服务端据此
 * 判断请求来自「可识别的真实设备」。缺这两个头时，即使已登录，短视频内容也更容易被下发到
 * {@code gateway_fallback_} 降级池（内容明显偏泛）。{@code buvid3} 已由 Cookie 体系保证，
 * 这里只补 {@code Display-ID}。
 *
 * <p>形态是 32 位大写十六进制，生成一次后持久化复用——每次随机等于每次都是「新设备」，
 * 推荐质量会抖。
 */
public final class DeviceIdentity {

    private static final String KEY_DISPLAY_ID = "device_display_id";
    private static final String HEX = "0123456789ABCDEF";

    private DeviceIdentity() {
    }

    public static synchronized String displayId() {
        String cached = SharedPreferencesUtil.getString(KEY_DISPLAY_ID, "");
        if (cached != null && cached.length() == 32) return cached;
        Random random = new Random();
        StringBuilder builder = new StringBuilder(32);
        for (int i = 0; i < 32; i++) builder.append(HEX.charAt(random.nextInt(16)));
        String id = builder.toString();
        SharedPreferencesUtil.putString(KEY_DISPLAY_ID, id);
        return id;
    }
}
