package com.RobinNotBad.BiliClient.api;

import android.util.Base64;

import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Objects;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

import okhttp3.Response;

/*
这里是Cookie刷新逻辑
https://socialsisteryi.github.io/bilibili-API-collect/docs/login/cookie_refresh.html#%E8%8E%B7%E5%8F%96refresh-csrf
 */
public class CookieRefreshApi {
    public static JSONObject cookieInfo() throws IOException, JSONException {
        String url = "https://passport.bilibili.com/x/passport-login/web/cookie/info";
        JSONObject result = NetWorkUtil.getJson(url);
        return result.getJSONObject("data");
    }

    public static String getCorrespondPath(long timestamp) { //时间戳要精准到毫秒！
        /*
        https://socialsisteryi.github.io/bilibili-API-collect/docs/login/cookie_refresh.html#%E7%94%9F%E6%88%90correspondpath%E7%AE%97%E6%B3%95
         */
        try {
            String publicKeyPEM = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDLgd2OAkcGVtoE3ThUREbio0EgUc/prcajMKXvkCKFCWhJYJcLkcM2DKKcSeFpD/j6Boy538YXnR6VhcuUJOhH2x71nzPjfdTcqMz7djHum0qSZA0AyCBDABUqCrfNgCiJ00Ra7GmRj+YCK1NJEuewlb40JNrRuoEUXpabUzGB8QIDAQAB";
            X509EncodedKeySpec x509EncodedKeySpec = new X509EncodedKeySpec(Base64.decode(publicKeyPEM, Base64.DEFAULT));
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PublicKey publicKey = keyFactory.generatePublic(x509EncodedKeySpec);
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
            OAEPParameterSpec oaepParameterSpec = new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
            cipher.init(Cipher.ENCRYPT_MODE, publicKey, oaepParameterSpec);
            String data = "refresh_" + timestamp;
            return base16Encode(cipher.doFinal(data.getBytes()));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }

    public static String getRefreshCsrf(String CorrespondPath) throws IOException {
        if (Objects.equals(CorrespondPath, "")) return "";
        String url = "https://www.bilibili.com/correspond/1/" + CorrespondPath;
        //try-with-resources：此前响应从不关闭，登录检测高频路径会泄漏连接
        try (Response response = NetWorkUtil.get(url)) {
            if (response.body() != null) {
                Document document = Jsoup.parse(response.body().string());
                if (document.select("#1-name").size() > 0)
                    return document.select("#1-name").get(0).text();
                else return "";
            } else return "";
        }
    }

    /**
     * 刷新 Cookie。
     *
     * <p>返回 {@link CookieRefreshResult} 而不是 boolean：调用方需要区分「瞬时失败」与
     * 「服务端判定失效」——前者必须保留本地凭证等待重试，后者才应该清理登录态。
     *
     * <p>本方法不再向外抛 JSONException/IOException：所有异常都归类为可重试结果，
     * 避免调用方把「网络抖动」误当成「登录失效」（这正是掉登录问题的放大器之一）。
     */
    public static CookieRefreshResult refreshCookie(String RefreshCsrf) {
        //必须的参数不能为空
        if (Objects.equals(RefreshCsrf, "")) return CookieRefreshResult.NO_REFRESH_CSRF;
        String refreshToken = SharedPreferencesUtil.getString(SharedPreferencesUtil.refresh_token, "");
        if (Objects.equals(refreshToken, "")) return CookieRefreshResult.NO_REFRESH_TOKEN;

        String cookies_old = SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "");

        //请求一个新的cookie
        String url = "https://passport.bilibili.com/x/passport-login/web/cookie/refresh";
        String args = "csrf=" + NetWorkUtil.getInfoFromCookie("bili_jct", cookies_old)
                + "&refresh_csrf=" + RefreshCsrf
                + "&source=main_web"
                + "&refresh_token=" + refreshToken;
        JSONObject result;
        try {
            Response response = NetWorkUtil.post(url, args, NetWorkUtil.webHeaders);
            result = new JSONObject(Objects.requireNonNull(response.body()).string());
        } catch (Exception e) {
            //网络层失败属瞬时故障：保留全部凭证，下次再试
            Logu.e("Cookie刷新失败", "刷新请求异常（按可重试处理，保留凭证）");
            return CookieRefreshResult.NETWORK_ERROR;
        }
        int refreshCode = result.optInt("code", -1);
        if (refreshCode != 0) {
            Logu.e("Cookie刷新失败", "刷新时返回:" + refreshCode);
            //只有服务端明确否定凭证本身才判为失效；风控(-412)/限流(-509)/服务端错误等都属于可重试
            if (refreshCode == -101 || refreshCode == -111)
                return CookieRefreshResult.REFRESH_REJECTED;
            return CookieRefreshResult.REFRESH_RETRYABLE;
        }

        JSONObject data = result.optJSONObject("data");
        String refreshToken_new = data != null ? data.optString("refresh_token", "") : "";
        //不打印 refresh_token（长期登录凭证）

        String cookies_new = SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "");
        //不打印新 Cookie（登录凭证）

        //使老的Cookie失效。此处必须用「新的 bili_jct + 旧的 refresh_token」：
        //新 token 要到下面 putString 才写入，所以这里读到的仍是旧值，与官方流程一致
        int confirmCode;
        try {
            confirmCode = new JSONObject(Objects.requireNonNull(NetWorkUtil.post(
                    "https://passport.bilibili.com/x/passport-login/web/confirm/refresh",
                    "csrf=" + NetWorkUtil.getInfoFromCookie("bili_jct", cookies_new)
                            + "&refresh_token=" + refreshToken,
                    NetWorkUtil.webHeaders).body()).string()).optInt("code", -1);
        } catch (Exception e) {
            //确认请求本身失败：旧凭证很可能仍然有效，按可重试处理并回滚，不当成失效
            Logu.e("Cookie刷新失败", "确认刷新请求异常（按可重试处理）");
            rollbackCookies(cookies_old);
            return CookieRefreshResult.CONFIRM_FAILED;
        }
        if (confirmCode != 0) { //必须要等确认更新Cookie成功，不然就无法完成Cookie的刷新
            Logu.e("Cookie刷新失败", "确认刷新时返回:" + confirmCode);
            rollbackCookies(cookies_old);
            return CookieRefreshResult.CONFIRM_FAILED;
        }
        if (!refreshToken_new.isEmpty())
            SharedPreferencesUtil.putString(SharedPreferencesUtil.refresh_token, refreshToken_new);
        //DedeUserID 理论上不因刷新而变；新 Cookie 缺失该字段时 parseLong 会抛 NFE 并中断刷新（此时 refresh_token 已更新，状态不一致），改为沿用本地已知的 mid
        long mid_new;
        try {
            mid_new = Long.parseLong(NetWorkUtil.getInfoFromCookie("DedeUserID", cookies_new));
        } catch (NumberFormatException e) {
            mid_new = SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0L);
        }
        SharedPreferencesUtil.putLong(SharedPreferencesUtil.mid, mid_new);
        SharedPreferencesUtil.putString(SharedPreferencesUtil.csrf, NetWorkUtil.getInfoFromCookie("bili_jct", cookies_new));
        Logu.v("Cookie刷新成功");
        NetWorkUtil.refreshHeaders();
        return CookieRefreshResult.OK;
    }

    /**
     * 把 Cookie 回滚到刷新前的串。
     * 内存请求头必须一起回滚：只改持久层会让 webHeaders 里的 Cookie 仍是新值，
     * 之后任何 Set-Cookie 合并都会基于错误的基线，登出/切号时状态更乱。
     */
    private static void rollbackCookies(String cookiesOld) {
        synchronized (CookieRefreshApi.class) {
            SharedPreferencesUtil.putString(SharedPreferencesUtil.cookies, cookiesOld);
        }
        NetWorkUtil.updateWebHeaders();
    }

    public static String base16Encode(byte[] src) {
        StringBuilder strbuf = new StringBuilder(src.length * 2);
        int i;

        for (i = 0; i < src.length; i++) {
            if (((int) src[i] & 0xff) < 0x10)
                strbuf.append("0");

            strbuf.append(Long.toString((int) src[i] & 0xff, 16));
        }

        return strbuf.toString();
    }
}
