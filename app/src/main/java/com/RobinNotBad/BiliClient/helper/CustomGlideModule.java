package com.RobinNotBad.BiliClient.helper;

import android.content.Context;

import androidx.annotation.NonNull;

import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader;
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory;
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
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        //磁盘缓存 64MB 上限（LRU 自动淘汰）：此前全线 NONE 不落盘，内存缓存回收后每图重走网络；
        //上限防低存储手表被缓存占满
        builder.setDiskCache(new InternalCacheDiskCacheFactory(context, 64 * 1024 * 1024));
    }

    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide, @NonNull Registry registry) {
        OkHttpClient.Builder builder = NetWorkUtil.setOkHttpSsl(new OkHttpClient.Builder());
        //凭据最小化（修复 P0：此前图片管线无域名过滤，把全量登录 Cookie 发往任意图片域）：
        //- 应用拦截器：请求发出前按目标域过滤——B 站域带完整请求头，外站图片（动态外链、图片查看器）只留 UA
        //- 网络拦截器：每个连接跳兜底一次——okhttp 3.x 跨主机重定向会保留自定义 Cookie 头，
        //  这里对非 B 站跳强制剥离，对 B 站跳重设为当前全局请求头，堵住重定向把 Cookie 带出域的路径
        builder.addInterceptor(chain -> {
            Request request = chain.request();
            return chain.proceed(applyGlideHeaders(request).build());
        });
        builder.addNetworkInterceptor(chain -> {
            Request request = chain.request();
            return chain.proceed(applyGlideHeaders(request).build());
        });

        registry.replace(GlideUrl.class, InputStream.class, new OkHttpUrlLoader.Factory(builder
                .dns(new NetWorkUtil.Inet4Selector())
                .pingInterval(8, TimeUnit.SECONDS)
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(16, TimeUnit.SECONDS).build()));
    }

    @NonNull
    private static Request.Builder applyGlideHeaders(@NonNull Request request) {
        Request.Builder requestBuilder = request.newBuilder();
        if (NetWorkUtil.isBilibiliHost(request.url().host())) {
            ArrayList<String> headers = NetWorkUtil.webHeaders;
            for (int i = 0; i < headers.size(); i += 2) {
                String key = headers.get(i);
                //set 覆盖而非 add：跨跳重定向残留的旧 Cookie 头会被当前全局值整体替换
                requestBuilder.header(key, headers.get(i + 1));
            }
        } else {
            //外站域名绝不携带 B 站 Cookie 与来源信息，只保留 UA（部分站点拒绝无 UA 的请求）。
            //仅设置 UA 不够：跨主机重定向会把原请求里已有的 Cookie/Referer/Origin 原样带走，
            //必须显式移除（此前注释声称"已剥离"但实际缺少移除动作）
            requestBuilder.header("User-Agent", NetWorkUtil.USER_AGENT_WEB);
            requestBuilder.removeHeader("Cookie");
            requestBuilder.removeHeader("Referer");
            requestBuilder.removeHeader("Origin");
        }
        return requestBuilder;
    }
}
