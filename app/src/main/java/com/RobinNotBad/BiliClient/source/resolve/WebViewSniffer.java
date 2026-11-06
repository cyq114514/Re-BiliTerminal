package com.RobinNotBad.BiliClient.source.resolve;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.RobinNotBad.BiliClient.source.net.UrlGuard;
import com.RobinNotBad.BiliClient.util.Logu;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 隐藏 WebView 嗅探：加载播放页，从网络请求里认出视频地址。
 *
 * <p>为什么必须有这一层：Animeko 的 Selector 源本来就是在 WebView 拦截器里跑
 * {@code matchVideoUrl}（{@code WebVideoMatcher.kt:18}）；实测两个国内采集站的播放页
 * HTML 里根本没有 m3u8，地址要等脚本执行后由 XHR 发出。
 *
 * <p><b>安全边界</b>：无 JS 桥、禁止文件访问与 content 访问、只允许 http/https 导航、
 * 请求 URL 一律先过 {@link MediaRule}（内部还有 {@link UrlGuard}）；Cookie 只注入规则
 * 声明的那些，不碰 B 站登录态。命中后立刻销毁 WebView，不留后台页面。
 *
 * <p><b>线程</b>：必须在主线程调用（WebView 只能在创建它的线程使用）。
 */
public final class WebViewSniffer {

    private WebViewSniffer() {
    }

    public interface Callback {
        /** 命中：url 已通过规则校验 */
        void onFound(String url);

        /** 失败：reason 是人类可读原因，可直接上屏 */
        void onFail(String reason);
    }

    /**
     * 设备是否具备 WebView（Wear OS 上常为空）。
     *
     * <p>API 26 起才能查当前 WebView 包名；更低版本无法预判，只能按「可能有」处理，
     * 真正的失败由 {@link #sniff} 里的 try/catch 兜住并回报给用户。
     */
    public static boolean available() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                return WebView.getCurrentWebViewPackage() != null;
            } catch (Throwable t) {
                return false;
            }
        }
        return true;
    }

    @SuppressLint("SetJavaScriptEnabled")
    public static void sniff(final Activity activity, final String pageUrl, final MediaRule rule,
                             final String userAgent, final String cookies,
                             final long timeoutMs, final Callback callback) {
        if (activity == null || pageUrl == null || pageUrl.isEmpty()) {
            failOnce(callback, "缺少播放页地址");
            return;
        }
        if (!UrlGuard.isAllowed(pageUrl)) {
            failOnce(callback, "播放页地址不合法");
            return;
        }
        final Handler handler = new Handler(Looper.getMainLooper());
        final boolean[] finished = new boolean[]{false};
        final WebView[] holder = new WebView[1];

        Runnable timeout = () -> {
            if (finished[0]) return;
            finished[0] = true;
            destroy(holder[0]);
            failOnce(callback, "网页解析超时（未在限定时间内发现视频请求）");
        };

        try {
            WebView webView = new WebView(activity);
            holder[0] = webView;
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setLoadsImagesAutomatically(false);
            settings.setBlockNetworkImage(true);
            settings.setMediaPlaybackRequiresUserGesture(true);
            if (userAgent != null && !userAgent.trim().isEmpty())
                settings.setUserAgentString(userAgent.trim());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN)
                settings.setAllowFileAccessFromFileURLs(false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN)
                settings.setAllowUniversalAccessFromFileURLs(false);

            injectCookies(pageUrl, cookies);

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    //只允许页面内部继续 http/https；intent://、market:// 一律拒绝
                    return !UrlGuard.isHttpScheme(url);
                }

                @Override
                public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                    return check(url);
                }

                @Override
                public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    return request == null ? null : check(request.getUrl() == null ? "" : request.getUrl().toString());
                }

                private android.webkit.WebResourceResponse check(String url) {
                    if (finished[0] || url == null || url.isEmpty()) return null;
                    String hit;
                    try {
                        hit = rule == null ? null : rule.matchVideo(url);
                    } catch (Throwable t) {
                        return null;
                    }
                    if (hit == null) return null;
                    finished[0] = true;
                    handler.removeCallbacks(timeout);
                    destroy(holder[0]);
                    if (callback != null) callback.onFound(hit);
                    return null;
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    //页面加载完不等于流已发出：播放器常在 finished 之后再 XHR，
                    //所以这里只记日志，真正的失败交给超时
                    Logu.d("third", "嗅探页面加载完成，等待视频请求");
                }
            });

            ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(1, 1);
            ViewGroup root = activity.findViewById(android.R.id.content);
            if (root != null) root.addView(webView, params);

            Map<String, String> headers = new LinkedHashMap<>();
            if (userAgent != null && !userAgent.trim().isEmpty())
                headers.put("User-Agent", userAgent.trim());
            String origin = UrlGuard.originOf(pageUrl);
            if (!origin.isEmpty()) headers.put("Referer", origin + "/");
            webView.loadUrl(pageUrl, headers);

            handler.postDelayed(timeout, timeoutMs <= 0 ? 15000 : timeoutMs);
        } catch (Throwable t) {
            //Wear OS 等没有 WebView 实现的设备会在这里抛
            destroy(holder[0]);
            failOnce(callback, "本设备无法使用网页解析（" + t.getClass().getSimpleName() + "）");
        }
    }

    private static void injectCookies(String pageUrl, String cookies) {
        if (cookies == null || cookies.trim().isEmpty()) return;
        try {
            CookieManager manager = CookieManager.getInstance();
            manager.setAcceptCookie(true);
            String[] lines = cookies.trim().split("\n");
            for (String line : lines) {
                String value = line.trim();
                if (value.isEmpty()) continue;
                //只接受 name=value，拒绝把整段 Set-Cookie 塞进来
                if (value.indexOf('=') <= 0) continue;
                manager.setCookie(pageUrl, value);
            }
        } catch (Throwable t) {
            Logu.w("third", "注入 Cookie 失败（忽略）：" + t.getClass().getSimpleName());
        }
    }

    private static void destroy(WebView webView) {
        if (webView == null) return;
        try {
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) parent.removeView(webView);
            webView.stopLoading();
            webView.setWebViewClient(null);
            webView.loadUrl("about:blank");
            webView.destroy();
        } catch (Throwable ignored) {
            //销毁失败不影响结果
        }
    }

    private static void failOnce(Callback callback, String reason) {
        if (callback != null) callback.onFail(reason);
    }
}
