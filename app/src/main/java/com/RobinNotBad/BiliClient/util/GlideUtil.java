package com.RobinNotBad.BiliClient.util;


import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.TransitionOptions;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.transition.DrawableCrossFadeFactory;
import com.RobinNotBad.BiliClient.ui.widget.RatioImageView;

public class GlideUtil {
    public static final int QUALITY_HIGH = 80;
    public static final int QUALITY_LOW = 25;
    public static final int MAX_W_HIGH = 1024;
    public static final int MAX_W_LOW = 512;
    //新版美学封面：16:10 裁切 + 6dp 圆角
    public static final float COVER_RATIO = 1.6f;
    public static final int COVER_ROUND_DP = 6;

    public static String url(String url) {
        //接口字段缺失（Reply.sender.avatar、VideoCard.cover 等为 null）是常态而非异常，
        //调用方遍布各 Adapter 且大多没有 try 包裹：null 直接 NPE 崩列表
        if (url == null || url.isEmpty()) return "";
        if (!url.startsWith("http") || url.endsWith("gif") || url.contains("@") || url.contains("afdian"))
            return url;
        if (SharedPreferencesUtil.getBoolean("image_request_jpg", false)) {
            if (url.endsWith("jpeg") || url.endsWith("jpg")) return url;
            return url + "@0e_"
                    + QUALITY_LOW + "q_"
                    //+ MAX_H_LOW + "h_"
                    + MAX_W_LOW + "w.jpeg";
        } else {
            if (url.endsWith("webp")) return url;
            return url + "@0e_"
                    + QUALITY_LOW + "q_"
                    //+ MAX_H_LOW + "h_"
                    + MAX_W_LOW + "w.webp";
        }
    }

    public static String url_hq(String url) {
        if (url == null || url.isEmpty()) return "";
        if (!url.startsWith("http") || url.endsWith("gif") || url.contains("@") || url.contains("afdiancdn.com"))
            return url;
        if (SharedPreferencesUtil.getBoolean("image_request_jpg", false)) {
            if (url.endsWith("jpeg") || url.endsWith("jpg")) return url;
            return url + "@0e_"
                    + QUALITY_HIGH + "q_"
                    //+ MAX_H_HIGH + "h_"
                    + MAX_W_HIGH + "w.jpeg";
        } else {
            if (url.endsWith("webp")) return url;
            return url + "@0e_"
                    + QUALITY_HIGH + "q_"
                    //+ MAX_H_HIGH + "h_"
                    + MAX_W_HIGH + "w.webp";
        }
    }

    public static void request(ImageView view, String url, int placeholder) {
        Glide.with(view).asDrawable().load(url(url))
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .format(DecodeFormat.PREFER_RGB_565)
                .transition(GlideUtil.getTransitionOptions())
                .placeholder(placeholder)
                .into(view);
    }

    public static void requestRound(ImageView view, String url, int placeholder) {
        Glide.with(view).asDrawable().load(url(url))
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .format(DecodeFormat.PREFER_RGB_565)
                .transition(GlideUtil.getTransitionOptions())
                .placeholder(placeholder)
                .apply(RequestOptions.circleCropTransform())
                .into(view);
    }

    //封面 RequestOptions 按模式缓存：每次绑定都 new 太浪费（Glide 官方建议复用）。
    //volatile + synchronized 双检：万一未来从池线程调用也不会读到半初始化状态
    private static volatile RequestOptions coverOptionsNew;
    private static volatile RequestOptions coverOptionsClassic;

    private static RequestOptions coverOptions(boolean newUi) {
        if (newUi) {
            RequestOptions options = coverOptionsNew;
            if (options == null) {
                synchronized (GlideUtil.class) {
                    if (coverOptionsNew == null)
                        coverOptionsNew = new RequestOptions().transforms(
                                new CenterCrop(), new RoundedCorners(ToolsUtil.dp2px(COVER_ROUND_DP)));
                    options = coverOptionsNew;
                }
            }
            return options;
        }
        RequestOptions options = coverOptionsClassic;
        if (options == null) {
            synchronized (GlideUtil.class) {
                if (coverOptionsClassic == null)
                    coverOptionsClassic = RequestOptions.bitmapTransform(new RoundedCorners(ToolsUtil.dp2px(5)));
                options = coverOptionsClassic;
            }
        }
        return options;
    }

    //列表封面统一入口：新版美学下 16:10 CenterCrop + 6dp 圆角（Re-WearBili 语言）；
    //关闭新版时恢复旧版 fitCenter 自适应 + 5dp 圆角
    public static void requestCover(ImageView view, String url, int placeholder) {
        boolean newUi = SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.NEW_UI_DESIGN, true);
        if (view instanceof RatioImageView) {
            ((RatioImageView) view).setRatio(newUi ? COVER_RATIO : 0f);
        }
        if (newUi) {
            view.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Glide.with(view).asDrawable().load(url(url))
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .transition(GlideUtil.getTransitionOptions())
                    .placeholder(placeholder)
                    .apply(coverOptions(true))
                    .into(view);
        } else {
            //旧版观感：fitCenter 自适应 + 5dp 圆角（与历史版本各封面调用点的 RoundedCorners(5) 一致）
            view.setScaleType(ImageView.ScaleType.FIT_CENTER);
            Glide.with(view).asDrawable().load(url(url))
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .transition(GlideUtil.getTransitionOptions())
                    .placeholder(placeholder)
                    .apply(coverOptions(false))
                    .into(view);
        }
    }

    public static void request(ImageView view, String url, int roundCorners, int placeholder) {
        Glide.with(view).asDrawable().load(url(url))
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .format(DecodeFormat.PREFER_RGB_565)
                .transition(GlideUtil.getTransitionOptions())
                .placeholder(placeholder)
                .apply(RequestOptions.bitmapTransform(new RoundedCorners(ToolsUtil.dp2px(roundCorners))))
                .into(view);
    }

    //淡入工厂复用（Glide 官方建议）：每图 new 一个此前是纯浪费；开关两态懒初始化
    private static volatile DrawableCrossFadeFactory crossFadeFactory;

    public static TransitionOptions<?, ? super Drawable> getTransitionOptions() {
        if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.LOAD_TRANSITION, true)) {
            DrawableCrossFadeFactory factory = crossFadeFactory;
            if (factory == null) {
                synchronized (GlideUtil.class) {
                    if (crossFadeFactory == null)
                        crossFadeFactory = new DrawableCrossFadeFactory.Builder(300).setCrossFadeEnabled(true).build();
                    factory = crossFadeFactory;
                }
            }
            return DrawableTransitionOptions.with(factory);
        } else {
            return new DrawableTransitionOptions();
        }
    }
}
