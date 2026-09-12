package com.RobinNotBad.BiliClient.activity.settings.login;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONObject;

/**
 * 极验人机验证页面。
 * 直接加载极验官方JS并在WebView中完成验证，不依赖任何第三方SDK（保持项目"不引依赖"的约定）。
 * 验证结果通过JS桥回传，调用方用 startActivityForResult 接收。
 */
public class CaptchaWebViewActivity extends BaseActivity {

    public static final String EXTRA_GT = "gt";
    public static final String EXTRA_CHALLENGE = "challenge";
    public static final String RESULT_CHALLENGE = "geetest_challenge";
    public static final String RESULT_VALIDATE = "geetest_validate";
    public static final String RESULT_SECCODE = "geetest_seccode";

    private static final int ZOOM_STEP = 25;
    private static final int ZOOM_MIN = 50;
    private static final int ZOOM_MAX = 300;
    private static final int ZOOM_DEFAULT = 100;

    //与网页端保持一致的移动端UA，避免极验按桌面版渲染
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36";

    private WebView webView;
    private int currentZoom = ZOOM_DEFAULT;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_captcha_webview);
        setPageName("人机验证");

        String gt = getIntent().getStringExtra(EXTRA_GT);
        String challenge = getIntent().getStringExtra(EXTRA_CHALLENGE);

        if (gt == null || challenge == null) {
            MsgUtil.showMsg("验证参数错误");
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        initZoomControls();
        initWebView(gt, challenge);
    }

    private void initZoomControls() {
        MaterialCardView btnZoomIn = findViewById(R.id.btnZoomIn);
        MaterialCardView btnZoomOut = findViewById(R.id.btnZoomOut);
        MaterialCardView btnZoomReset = findViewById(R.id.btnZoomReset);

        btnZoomIn.setOnClickListener(v -> {
            if (currentZoom >= ZOOM_MAX) return;
            currentZoom += ZOOM_STEP;
            applyZoom();
        });
        btnZoomOut.setOnClickListener(v -> {
            if (currentZoom <= ZOOM_MIN) return;
            currentZoom -= ZOOM_STEP;
            applyZoom();
        });
        btnZoomReset.setOnClickListener(v -> {
            currentZoom = ZOOM_DEFAULT;
            applyZoom();
        });
    }

    //极验验证码在手表上偏小，通过改写viewport的缩放比例放大，而不是整体缩放WebView
    private void applyZoom() {
        if (webView == null) return;
        double scale = currentZoom / 100.0;
        String js = "document.querySelector('meta[name=\"viewport\"]').setAttribute('content', 'width=device-width, initial-scale="
                + scale + ", maximum-scale=3.0, user-scalable=yes');";
        if (Build.VERSION.SDK_INT >= 19) webView.evaluateJavascript(js, null);
        else webView.loadUrl("javascript:" + js);
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void initWebView(String gt, String challenge) {
        webView = findViewById(R.id.captchaWebView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        //验证码页面无需本地文件访问，关掉危险开关以降低攻击面
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        if (Build.VERSION.SDK_INT >= 16) {
            settings.setAllowUniversalAccessFromFileURLs(false);
            settings.setAllowFileAccessFromFileURLs(false);
        }
        //极验脚本为https，禁止混合内容
        if (Build.VERSION.SDK_INT >= 21)
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setUserAgentString(USER_AGENT);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new CaptchaJsInterface(), "Android");
        //必须设置WebViewClient，否则页面跳转会被系统浏览器接管
        webView.setWebViewClient(new WebViewClient());

        webView.loadDataWithBaseURL("https://www.bilibili.com/", buildCaptchaHtml(gt, challenge), "text/html", "UTF-8", null);
    }

    private String buildCaptchaHtml(String gt, String challenge) {
        return "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head>\n" +
                "<meta charset=\"utf-8\">\n" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=3.0, user-scalable=yes\">\n" +
                "<style>\n" +
                "  * { margin: 0; padding: 0; box-sizing: border-box; }\n" +
                "  body { display: flex; justify-content: center; align-items: center; min-height: 100vh; background: #f5f5f5; padding: 16px; }\n" +
                "  #captcha { width: 100%; max-width: 320px; }\n" +
                "  .loading { text-align: center; color: #666; padding: 40px 0; font-size: 14px; }\n" +
                "  .error { text-align: center; color: #e74c3c; padding: 40px 0; font-size: 14px; }\n" +
                "  .retry-btn { margin-top: 12px; padding: 10px 24px; background: #00a1d6; color: white; border: none; border-radius: 4px; cursor: pointer; font-size: 14px; }\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div id=\"captcha\"><div class=\"loading\">正在加载验证码...</div></div>\n" +
                "<script>\n" +
                //gt/challenge 是服务端返回值，必须转义成合法的 JS 字符串字面量再注入，
                //否则可被构造成闭合单引号注入任意 JS（ 进而调用本页的 JS 桥伪造验证结果）
                "var gt = " + JSONObject.quote(gt) + ";\n" +
                "var challenge = " + JSONObject.quote(challenge) + ";\n" +
                "var captchaEl = document.getElementById('captcha');\n" +
                "\n" +
                "function loadScript(url, callback) {\n" +
                "  var script = document.createElement('script');\n" +
                "  script.type = 'text/javascript';\n" +
                "  script.src = url;\n" +
                "  script.onload = function() { callback(null); };\n" +
                "  script.onerror = function() { callback(new Error('Load failed')); };\n" +
                "  document.body.appendChild(script);\n" +
                "}\n" +
                "\n" +
                "function initCaptcha() {\n" +
                "  captchaEl.innerHTML = '<div class=\"loading\">正在加载验证码...</div>';\n" +
                "  loadScript('https://static.geetest.com/static/js/gt.0.4.9.js', function(err) {\n" +
                "    if (err) {\n" +
                "      captchaEl.innerHTML = '<div class=\"error\">加载核心库失败<br><button class=\"retry-btn\" onclick=\"initCaptcha()\">重试</button></div>';\n" +
                "      return;\n" +
                "    }\n" +
                "    loadScript('https://static.geetest.com/static/js/click.3.1.2.js', function(err2) {\n" +
                "      if (err2) {\n" +
                "        captchaEl.innerHTML = '<div class=\"error\">加载验证库失败<br><button class=\"retry-btn\" onclick=\"initCaptcha()\">重试</button></div>';\n" +
                "        return;\n" +
                "      }\n" +
                "      if (typeof initGeetest === 'undefined') {\n" +
                "        captchaEl.innerHTML = '<div class=\"error\">初始化失败<br><button class=\"retry-btn\" onclick=\"initCaptcha()\">重试</button></div>';\n" +
                "        return;\n" +
                "      }\n" +
                "      initGeetest({\n" +
                "        gt: gt,\n" +
                "        challenge: challenge,\n" +
                "        new_captcha: true,\n" +
                "        product: 'popup',\n" +
                "        offline: false,\n" +
                "        type: 'click',\n" +
                "        https: true\n" +
                "      }, function(captchaObj) {\n" +
                "        captchaEl.innerHTML = '';\n" +
                "        captchaObj.appendTo('#captcha');\n" +
                "        captchaObj.onReady(function() {});\n" +
                "        captchaObj.onSuccess(function() {\n" +
                "          var result = captchaObj.getValidate();\n" +
                "          if (result && result.geetest_validate) {\n" +
                "            var seccode = result.geetest_seccode || (result.geetest_validate + '|jordan');\n" +
                "            Android.onCaptchaResult(\n" +
                "              result.geetest_challenge || challenge,\n" +
                "              result.geetest_validate,\n" +
                "              seccode\n" +
                "            );\n" +
                "          } else {\n" +
                "            Android.onCaptchaError('验证结果为空');\n" +
                "          }\n" +
                "        });\n" +
                "        captchaObj.onError(function(err) {\n" +
                "          Android.onCaptchaError('验证失败: ' + (err && err.msg || '网络错误'));\n" +
                "        });\n" +
                "        captchaObj.verify();\n" +
                "      });\n" +
                "    });\n" +
                "  });\n" +
                "}\n" +
                "\n" +
                "initCaptcha();\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>";
    }

    private class CaptchaJsInterface {
        @JavascriptInterface
        public void onCaptchaResult(String challenge, String validate, String seccode) {
            runOnUiThread(() -> {
                Intent data = new Intent();
                data.putExtra(RESULT_CHALLENGE, challenge);
                data.putExtra(RESULT_VALIDATE, validate);
                data.putExtra(RESULT_SECCODE, seccode);
                setResult(RESULT_OK, data);
                finish();
            });
        }

        @JavascriptInterface
        public void onCaptchaError(String errorMsg) {
            runOnUiThread(() -> {
                MsgUtil.showMsg(errorMsg);
                setResult(RESULT_CANCELED);
                finish();
            });
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
