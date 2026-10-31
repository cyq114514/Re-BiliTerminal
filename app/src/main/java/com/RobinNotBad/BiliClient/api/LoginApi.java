package com.RobinNotBad.BiliClient.api;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.activity.SplashActivity;
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity;
import com.RobinNotBad.BiliClient.util.AccountManager;
import com.RobinNotBad.BiliClient.util.AppAuthStore;
import com.RobinNotBad.BiliClient.util.AppSign;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.QRCodeUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.RobinNotBad.BiliClient.util.ToolsUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import okhttp3.Response;

/**
 * Created by liupe on 2018/10/6.
 * 各位大佬好
 * #以下代码修改自腕上哔哩的开源项目，感谢开源者做出的贡献！
 */

public class LoginApi {
    //扫码会话的 qrcode_key：跟二维码一一对应，必须由调用方传递，
    //静态字段在两次并发扫码（多账号切换）时会被后一次覆盖，前一次轮询到错误的 key
    private static volatile String oauthKey;

    public static Bitmap getLoginQR() throws JSONException, IOException {
        String url = "https://passport.bilibili.com/x/passport-login/web/qrcode/generate?source=main-fe-header&go_url=https:%2F%2Fwww.bilibili.com%2F";
        JSONObject loginUrlJson = NetWorkUtil.getJson(url, CookiesApi.genWebHeaders()).getJSONObject("data");
        oauthKey = loginUrlJson.getString("qrcode_key");
        return QRCodeUtil.createQRCodeBitmap(loginUrlJson.getString("url"), 320, 320);
    }

    public static Response getLoginState() throws IOException {
        return NetWorkUtil.get("https://passport.bilibili.com/x/passport-login/web/qrcode/poll?source=main-fe-header&qrcode_key=" + oauthKey, CookiesApi.genWebHeaders());
    }

    public static void requestSSOs() throws JSONException, IOException {
        String listUrl = "https://passport.bilibili.com/x/passport-login/web/sso/list";
        JSONObject listResult = new JSONObject(NetWorkUtil.post(listUrl, new NetWorkUtil.FormData().put("csrf", SharedPreferencesUtil.getString(SharedPreferencesUtil.csrf, "")).toString()).body().string());
        if (listResult.has("data") && !listResult.isNull("data")) {
            JSONArray sso = listResult.getJSONObject("data").getJSONArray("sso");
            for (int i = 0; i < sso.length(); i++) {
                //SSO 通知只需触发请求不读响应，但 body 不关闭连接就一直被占用，积累后拖慢连接池
                try (Response ignored = NetWorkUtil.post(sso.getString(i), "")) {
                }
            }
        }
    }

    /**
     * 极验初始化参数，密码登录与短信登录共用。
     */
    public static class CaptchaParams {
        public String token = "";
        public String gt = "";
        public String challenge = "";
    }

    /**
     * 获取极验初始化参数，返回体形如：{ code, message, data: { token, geetest: { gt, challenge } } }
     */
    public static CaptchaParams getCaptchaParams() throws IOException, JSONException {
        String url = "https://passport.bilibili.com/x/passport-login/captcha?source=main_web";
        JSONObject resp = NetWorkUtil.getJson(url, NetWorkUtil.webHeaders);
        if (resp.optInt("code", -1) != 0)
            throw new JSONException("验证码接口异常：" + resp.optString("message", "未知错误"));

        JSONObject data = resp.optJSONObject("data");
        JSONObject geetest = data != null ? data.optJSONObject("geetest") : null;
        if (geetest == null) throw new JSONException("验证码接口异常：缺少极验参数");

        CaptchaParams params = new CaptchaParams();
        params.token = data.optString("token", "");
        params.gt = geetest.optString("gt", "");
        params.challenge = geetest.optString("challenge", "");
        return params;
    }

    /**
     * 获取密码加密用的RSA公钥，返回体形如：{ code, message, data: { hash, key } }
     */
    public static JSONObject getWebKey() throws IOException, JSONException {
        String url = "https://passport.bilibili.com/x/passport-login/web/key";
        return NetWorkUtil.getJson(url, NetWorkUtil.webHeaders);
    }

    /**
     * 账号密码登录。password 为 {@link com.RobinNotBad.BiliClient.util.PasswordEncryptUtil} 加密后的密文。
     */
    public static JSONObject passwordLogin(String username, String password, String captchaToken, String challenge, String validate, String seccode) throws IOException, JSONException {
        String url = "https://passport.bilibili.com/x/passport-login/web/login";
        String body = new NetWorkUtil.FormData()
                .put("source", "main_web")
                .put("username", username)
                .put("password", password)
                .put("keep", "0")
                .put("token", captchaToken)
                .put("challenge", challenge)
                .put("validate", validate)
                .put("seccode", seccode)
                .toString();
        return new JSONObject(NetWorkUtil.post(url, body, NetWorkUtil.webHeaders).body().string());
    }

    /**
     * 发送短信验证码，成功时返回体 data 内含 captcha_key，登录时必须回传。
     */
    public static JSONObject smsSend(String phone, String captchaToken, String challenge, String validate, String seccode) throws IOException, JSONException {
        String url = "https://passport.bilibili.com/x/passport-login/web/sms/send";
        String body = new NetWorkUtil.FormData()
                .put("cid", "86")
                .put("tel", phone)
                .put("source", "main_web")
                .put("token", captchaToken)
                .put("challenge", challenge)
                .put("validate", validate)
                .put("seccode", seccode)
                .toString();
        return new JSONObject(NetWorkUtil.post(url, body, NetWorkUtil.webHeaders).body().string());
    }

    /**
     * 短信验证码登录。captchaKey 为 {@link #smsSend} 返回的 captcha_key。
     */
    public static JSONObject smsLogin(String phone, String code, String captchaKey) throws IOException, JSONException {
        String url = "https://passport.bilibili.com/x/passport-login/web/login/sms";
        String body = new NetWorkUtil.FormData()
                .put("cid", "86")
                .put("tel", phone)
                .put("source", "main_web")
                .put("code", code)
                .put("captcha_key", captchaKey != null ? captchaKey : "")
                .put("keep", "1")
                .toString();
        return new JSONObject(NetWorkUtil.post(url, body, NetWorkUtil.webHeaders).body().string());
    }

    /**
     * 登录成功后的统一收尾：从已落盘的Cookies提取身份信息、保存凭证、刷新全局请求头并回到启动页。
     *
     * <p>扫码登录与密码/短信登录都走这一个出口。修复前扫码路径自己写了一整套落盘逻辑，
     * 两边口径不一致：扫码只认 {@code data.refresh_token}、且用 getString（字段缺失会抛异常、
     * 值为 JSON null 时会写入字符串 "null"），一旦在写 refresh_token 那一步失败，
     * 前面写入的 mid/csrf 已经生效，就留下「看着已登录、其实没有续期凭据」的半登录态，
     * 此后每次刷新检查都会因凭据为空而静默跳过，Cookie 到期即掉登录。
     *
     * @param context   跳转用的上下文（可在任意线程调用，跳转会切回主线程）
     * @param loginData 登录接口返回的 data 对象，可为 null
     */
    public static void finishLogin(Context context, JSONObject loginData) {
        long previousMid = SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0);

        String cookies = SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "");
        String midStr = NetWorkUtil.getInfoFromCookie("DedeUserID", cookies);
        long loggedInMid = previousMid;
        if (!TextUtils.isEmpty(midStr)) {
            try {
                loggedInMid = Long.parseLong(midStr);
                SharedPreferencesUtil.putLong(SharedPreferencesUtil.mid, loggedInMid);
            } catch (NumberFormatException ignored) {
                //Cookie里的DedeUserID异常时不覆盖原有mid，避免写入脏数据
            }
        }
        SharedPreferencesUtil.putString(SharedPreferencesUtil.csrf, NetWorkUtil.getInfoFromCookie("bili_jct", cookies));

        //refresh_token 存在新旧两种返回结构，两种都要兜住
        String refreshToken = extractRefreshToken(loginData);
        if (!TextUtils.isEmpty(refreshToken)) {
            SharedPreferencesUtil.putString(SharedPreferencesUtil.refresh_token, refreshToken);
        } else if (previousMid != 0 && loggedInMid != previousMid) {
            //换号登录却没拿到新 token：本地残留的是上一个账号的凭据，
            //留着会拿它去给错误的账号续期，必须清掉
            SharedPreferencesUtil.putString(SharedPreferencesUtil.refresh_token, "");
            Logu.e("login", "换号登录未携带 refresh_token，已清空旧账号残留凭据");
        } else {
            //只记事实不记凭证内容：自动续期能力将不可用，由 CookieRefresher 负责提示用户
            Logu.e("login", "登录响应未携带 refresh_token，自动续期将不可用");
        }

        SharedPreferencesUtil.putBoolean(SharedPreferencesUtil.cookie_refresh, true);
        SharedPreferencesUtil.putBoolean(SharedPreferencesUtil.setup, true);

        InstanceActivity instance = BiliTerminal.getInstanceActivityOnTop();
        if (instance != null && !instance.isDestroyed()) instance.finish();

        NetWorkUtil.refreshHeaders();

        try {
            requestSSOs();
        } catch (Throwable ignored) {
        }

        //返回体里的url需要访问一次才能触发Set-Cookie落盘
        if (loginData != null && loginData.has("url")) {
            try (Response ignored = NetWorkUtil.get(loginData.optString("url"))) {
                //只触发请求，响应无需读取
            } catch (Throwable ignored) {
            }
        }

        //多账号：凭证全部落盘后快照进账号列表（内部有网络请求，丢到线程池避免拖慢跳转）
        CenterThreadPool.run(AccountManager::saveCurrentAccount);

        //「只登录一次」：Web 登录已落 Cookie，后台用当前 Cookie 静默确认一个 TV 二维码，
        //直接把 access_key 也换回来（PiliPlus 的登录本身就是 TV 扫码，故一次扫码两样都有；
        //这里保留 Web 登录/续期的同时补上 access_key）。失败不打扰用户，后续可手动扫码授权。
        final long appAuthMid = loggedInMid;
        CenterThreadPool.run(() -> {
            try {
                acquireAccessKeyFromCookie(appAuthMid);
            } catch (Throwable ignored) {
            }
        });

        startSplash(context);
    }

    // ---------------------------------------------------------------- App/TV 扫码（获取 access_key）

    /**
     * 官方 HD 客户端 appkey/appsec（公开内置值，PiliPlus 同款）。
     * 值统一放在 {@link AppSign}，这里只引用——推荐接口与 TV 扫码登录用的是同一对，
     * 两处各写一份的话改了一个忘了另一个就会出现"一个接口能用一个 403"。
     */
    private static final String APP_KEY = AppSign.APP_KEY;
    private static final String APP_SEC = AppSign.APP_SEC;
    private static final String APP_UA =
            "Mozilla/5.0 BiliDroid/2.0.1 (bbcallen@gmail.com) os/android model/android_hd "
                    + "mobi_app/android_hd build/2001100 channel/master innerVer/2001100 "
                    + "osVer/15 network/2";
    private static final String TV_AUTH_CODE_URL =
            "https://passport.bilibili.com/x/passport-tv-login/qrcode/auth_code";
    private static final String TV_POLL_URL =
            "https://passport.bilibili.com/x/passport-tv-login/qrcode/poll";

    /**
     * 申请 TV/App 扫码二维码。返回完整 JSON（code/data.auth_code/data.url）。
     * 与网页扫码不同，这条链路成功后能拿到 access_key，App gRPC 发卡片需要它。
     */
    public static JSONObject getTVAuthCode() throws IOException, JSONException {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("local_id", "0");
        params.put("platform", "android");
        params.put("mobi_app", "android_hd");
        String query = signAppParams(params);
        Response response = NetWorkUtil.post(TV_AUTH_CODE_URL + "?" + query, "", tvHeaders());
        try {
            return new JSONObject(response.body().string());
        } finally {
            response.close();
        }
    }

    /**
     * 轮询 TV/App 扫码状态。code=0 时 data.token_info.access_token 即 access_key；
     * 86038 过期 / 86090 已扫码待确认 / 86101 未扫码。
     */
    public static JSONObject pollTVAuthCode(String authCode) throws IOException, JSONException {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("auth_code", authCode);
        params.put("local_id", "0");
        String query = signAppParams(params);
        Response response = NetWorkUtil.post(TV_POLL_URL + "?" + query, "", tvHeaders());
        try {
            return new JSONObject(response.body().string());
        } finally {
            response.close();
        }
    }

    /** 用当前 Web Cookie 确认 TV 二维码（等价于用户在手机上点"确认登录"） */
    private static JSONObject confirmTVAuthCode(String authCode) throws IOException, JSONException {
        String cookies = SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "");
        String csrf = NetWorkUtil.getLoginCsrf();
        String form = new NetWorkUtil.FormData()
                .put("auth_code", authCode)
                .put("csrf", csrf)
                .put("scanning_type", "1")
                .toString();
        ArrayList<String> headers = tvHeaders();
        headers.add("Cookie");
        headers.add(cookies);
        headers.add("Origin");
        headers.add("https://passport.bilibili.com");
        headers.add("Referer");
        headers.add("https://passport.bilibili.com/x/passport-tv-login/h5/qrcode/auth?auth_code="
                + authCode + "&mobi_app=android_hd");
        Response response = NetWorkUtil.post(
                "https://passport.bilibili.com/x/passport-tv-login/h5/qrcode/confirm",
                form, headers);
        try {
            return new JSONObject(response.body().string());
        } finally {
            response.close();
        }
    }

    /**
     * 用当前登录 Cookie 静默换取 App access_key（不弹二维码）。
     *
     * <p>流程：申请 TV 二维码 → 用 Web Cookie + csrf 调 H5 confirm（模拟用户点确认）
     * → 轮询拿到 token_info.access_token。T 站 H5 的"确认 TV 登录"就是这个接口，
     * 因此已登录的 Web 会话可以直接完成，不需要第二次扫码。
     *
     * <p><b>必须在后台线程调用</b>（内部有网络请求与短 sleep）。
     *
     * @return true 表示已拿到并保存 access_key（或本来就有）
     */
    public static boolean acquireAccessKeyFromCookie(long mid) {
        if (mid <= 0) return false;
        if (AppAuthStore.hasAccessKey(mid)) return true;
        if (TextUtils.isEmpty(SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "")))
            return false;
        if (TextUtils.isEmpty(NetWorkUtil.getLoginCsrf())) return false;
        try {
            JSONObject qr = getTVAuthCode();
            if (qr.optInt("code", -1) != 0) return false;
            JSONObject qrData = qr.optJSONObject("data");
            String authCode = qrData == null ? "" : qrData.optString("auth_code", "");
            if (authCode.isEmpty()) return false;

            JSONObject confirm = confirmTVAuthCode(authCode);
            if (confirm.optInt("code", -1) != 0) return false;

            for (int i = 0; i < 6; i++) {
                JSONObject poll = pollTVAuthCode(authCode);
                int code = poll.optInt("code", -1);
                if (code == 0) {
                    JSONObject data = poll.optJSONObject("data");
                    JSONObject token = data == null ? null : data.optJSONObject("token_info");
                    String access = token == null ? "" : token.optString("access_token", "");
                    String refresh = token == null ? "" : token.optString("refresh_token", "");
                    if (access.isEmpty() && data != null) access = data.optString("access_token", "");
                    if (refresh.isEmpty() && data != null) refresh = data.optString("refresh_token", "");
                    long pollMid = data == null ? 0 : data.optLong("mid", 0);
                    if (pollMid <= 0 && token != null) pollMid = token.optLong("mid", 0);
                    if (access.isEmpty()) return false;
                    if (pollMid > 0 && pollMid != mid) return false;
                    AppAuthStore.save(mid, access, refresh);
                    return true;
                }
                if (code == 86039 || code == 86090) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                    continue;
                }
                return false;
            }
        } catch (Throwable ignored) {
            //静默失败：不影响登录，用户仍可在分享面板走二维码授权
        }
        return false;
    }

    /** App 接口签名：参数按 key 排序拼接 + appsec 做 MD5；sign 附在最后 */
    private static String signAppParams(LinkedHashMap<String, String> params) throws IOException {
        params.put("appkey", APP_KEY);
        params.put("ts", String.valueOf(System.currentTimeMillis() / 1000));
        List<String> keys = new ArrayList<>(params.keySet());
        Collections.sort(keys);
        StringBuilder raw = new StringBuilder();
        for (String key : keys) {
            String value = params.get(key);
            if (value == null) continue;
            if (raw.length() > 0) raw.append('&');
            raw.append(URLEncoder.encode(key, "UTF-8")).append('=')
                    .append(URLEncoder.encode(value, "UTF-8"));
        }
        return raw + "&sign=" + ToolsUtil.md5(raw + APP_SEC);
    }

    private static ArrayList<String> tvHeaders() {
        String buvid = NetWorkUtil.getInfoFromCookie("buvid3",
                SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, ""));
        if (TextUtils.isEmpty(buvid)) buvid = SharedPreferencesUtil.getString("buvid3", "");
        if (TextUtils.isEmpty(buvid))
            buvid = "XY" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        ArrayList<String> headers = new ArrayList<>();
        headers.add("User-Agent");
        headers.add(APP_UA);
        headers.add("buvid");
        headers.add(buvid);
        headers.add("env");
        headers.add("prod");
        headers.add("app-key");
        headers.add("android_hd");
        return headers;
    }

    /**
     * 取出登录响应里的 refresh_token，兼容 {@code data.refresh_token}（扫码）与
     * {@code data.token_info.refresh_token}（密码/短信）两种结构。
     *
     * <p>不能用 optString 的默认值来兜 JSON null：org.json 的 optString 遇到 JSONObject.NULL
     * 会返回字符串 "null"，会被当成有效凭据写进本地，让后续的 isEmpty() 守卫失效。
     */
    private static String extractRefreshToken(JSONObject loginData) {
        if (loginData == null) return "";
        String token = "";
        JSONObject tokenInfo = loginData.optJSONObject("token_info");
        if (tokenInfo != null) token = tokenInfo.optString("refresh_token", "");
        if (isBlankToken(token)) token = loginData.optString("refresh_token", "");
        return isBlankToken(token) ? "" : token;
    }

    private static boolean isBlankToken(String token) {
        return TextUtils.isEmpty(token) || "null".equals(token);
    }

    /** 跳回启动页。调用方可能在轮询线程等非 UI 线程上，必须切回主线程再 startActivity。 */
    private static void startSplash(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, SplashActivity.class);
        //非 Activity 上下文必须带 NEW_TASK，否则直接抛 AndroidRuntimeException
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            context.startActivity(intent);
        } else {
            new Handler(Looper.getMainLooper()).post(() -> context.startActivity(intent));
        }
    }

}