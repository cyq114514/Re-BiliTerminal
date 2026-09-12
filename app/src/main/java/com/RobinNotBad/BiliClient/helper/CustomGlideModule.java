package com.RobinNotBad.BiliClient.helper;

import android.content.Context;

import androidx.annotation.NonNull;

import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.bumptech.glide.Glide;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.module.AppGlideModule;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;

@GlideModule
public class CustomGlideModule extends AppGlideModule {
    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide, @NonNull Registry registry) {
        OkHttpClient.Builder builder = NetWorkUtil.setOkHttpSsl(new OkHttpClient.Builder());
        builder.addInterceptor(chain -> {
            ArrayList<String> headers = NetWorkUtil.webHeaders;
            Request.Builder requestBuilder = chain.request().newBuilder();
            for (int i = 0; i < headers.size(); i += 2)
                requestBuilder.addHeader(headers.get(i), headers.get(i + 1));

            return chain.proceed(requestBuilder.build());
        });

        registry.replace(GlideUrl.class, InputStream.class, new OkHttpUrlLoader.Factory(builder
                .dns(new NetWorkUtil.Inet4Selector())
                .pingInterval(8, TimeUnit.SECONDS)
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(16, TimeUnit.SECONDS).build()));
    }
}
