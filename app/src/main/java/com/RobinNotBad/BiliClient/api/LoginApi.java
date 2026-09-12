package com.RobinNotBad.BiliClient.api;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.text.TextUtils;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.activity.SplashActivity;
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.QRCodeUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Response;

/**
 * Created by liupe on 2018/10/6.
 * 各位大佬好
 * #以下代码修改自腕上哔哩的开源项目，感谢开源者做出的贡献！
 */

public class LoginApi {
    private static String oauthKey;

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
                NetWorkUtil.post(sso.getString(i), "");
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
     * 与扫码登录(QRLoginFragment)使用同一套落盘逻辑。
     *
     * @param context   跳转用的上下文
     * @param loginData 登录接口返回的 data 对象，可为 null
     */
    public static void finishLogin(Context context, JSONObject loginData) {
        String cookies = SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "");

        String midStr = NetWorkUtil.getInfoFromCookie("DedeUserID", cookies);
        if (!TextUtils.isEmpty(midStr)) {
            try {
                SharedPreferencesUtil.putLong(SharedPreferencesUtil.mid, Long.parseLong(midStr));
            } catch (NumberFormatException ignored) {
                //Cookie里的DedeUserID异常时不覆盖原有mid，避免写入脏数据
            }
        }
        SharedPreferencesUtil.putString(SharedPreferencesUtil.csrf, NetWorkUtil.getInfoFromCookie("bili_jct", cookies));

        //refresh_token 存在新旧两种返回结构，两种都要兜住
        String refreshToken = "";
        if (loginData != null) {
            JSONObject tokenInfo = loginData.optJSONObject("token_info");
            if (tokenInfo != null) refreshToken = tokenInfo.optString("refresh_token", "");
            if (TextUtils.isEmpty(refreshToken)) refreshToken = loginData.optString("refresh_token", "");
        }
        if (!TextUtils.isEmpty(refreshToken))
            SharedPreferencesUtil.putString(SharedPreferencesUtil.refresh_token, refreshToken);

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
            try {
                NetWorkUtil.get(loginData.optString("url"));
            } catch (Throwable ignored) {
            }
        }

        context.startActivity(new Intent(context, SplashActivity.class));
    }

}