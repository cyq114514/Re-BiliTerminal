package com.RobinNotBad.BiliClient.activity.settings.login;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.api.LoginApi;
import com.RobinNotBad.BiliClient.util.AppAuthStore;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.QRCodeUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * App/TV 扫码授权：换取 App gRPC 发视频卡片所需的 access_key。
 *
 * <p>网页 Cookie 登录拿不到 access_key，而 App 的 SendMsg 接口需要
 * {@code authorization: identify_v1 <access_key>}。这里复用官方的 TV 扫码链路：
 * 申请二维码 → 手机客户端扫码确认 → 轮询拿 token_info.access_token → 按当前 mid 保存。
 *
 * <p>授权只用于发私信卡片，不替换网页 Cookie；扫码账号必须与当前登录账号一致。
 */
public class AppAuthActivity extends BaseActivity {

    private ImageView qrImage;
    private TextView statusView;
    private MaterialButton refreshButton;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private String authCode = "";
    private int pollToken = 0;
    private int qrSize = 0;
    private boolean polling = false;
    private boolean destroyed = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_auth);
        setPageName("App 卡片授权");

        qrImage = findViewById(R.id.appAuthQr);
        statusView = findViewById(R.id.appAuthStatus);
        refreshButton = findViewById(R.id.appAuthRefresh);
        refreshButton.setOnClickListener(v -> startAuth());

        //「界面大小」调大后密度变大，固定 240dp 的二维码会把页面撑出屏幕；
        //按可用宽高算出实际像素尺寸，既保证完整显示，也保证扫码清晰度
        applyQrSize();
        startAuth();
    }

    private void applyQrSize() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int preferred = (int) (200 * metrics.density);
        int minSize = (int) (140 * metrics.density);
        int maxWidth = (int) (metrics.widthPixels * 0.68f);
        int maxHeight = (int) (metrics.heightPixels * 0.30f);
        int limit = Math.min(maxWidth, maxHeight);
        qrSize = Math.min(preferred, limit);
        if (qrSize < minSize) qrSize = Math.min(minSize, limit);
        if (qrSize < 1) qrSize = 1;
        ViewGroup.LayoutParams params = qrImage.getLayoutParams();
        params.width = qrSize;
        params.height = qrSize;
        qrImage.setLayoutParams(params);
    }

    /**
     * 先尝试「用当前 Web 登录 Cookie 静默换 access_key」（PiliPlus 一次登录同款效果）；
     * 静默失败才展示 TV 扫码二维码，作为兜底。
     */
    private void startAuth() {
        long mid = SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0);
        if (mid <= 0) {
            MsgUtil.showMsg("请先登录网页账号");
            finish();
            return;
        }
        if (AppAuthStore.hasAccessKey(mid)) {
            MsgUtil.showMsg("卡片权限已开通");
            finish();
            return;
        }

        polling = false;
        pollToken++;
        authCode = "";
        refreshButton.setEnabled(false);
        qrImage.setImageDrawable(null);
        statusView.setText("正在尝试用当前登录账号自动开通…");

        CenterThreadPool.run(() -> {
            final boolean ok = LoginApi.acquireAccessKeyFromCookie(mid);
            handler.post(() -> {
                if (destroyed) return;
                if (ok) {
                    MsgUtil.showMsg("卡片权限已开通");
                    finish();
                } else {
                    showQrCode();
                }
            });
        });
    }

    private void showQrCode() {
        polling = false;
        pollToken++;
        authCode = "";
        refreshButton.setEnabled(false);
        qrImage.setImageDrawable(null);
        statusView.setText("正在获取二维码…");

        CenterThreadPool.run(() -> {
            try {
                JSONObject json = LoginApi.getTVAuthCode();
                int code = json.optInt("code", -1);
                if (code != 0)
                    throw new IllegalStateException("code=" + code + " " + json.optString("message"));
                JSONObject data = json.optJSONObject("data");
                if (data == null) throw new IllegalStateException("接口未返回 data");
                final String auth = data.optString("auth_code", "");
                final String url = data.optString("url", "");
                if (auth.isEmpty() || url.isEmpty())
                    throw new IllegalStateException("二维码数据不完整");
                final Bitmap bitmap = QRCodeUtil.createQRCodeBitmap(url, qrSize, qrSize);
                if (bitmap == null) throw new IllegalStateException("生成二维码失败");

                handler.post(() -> {
                    if (destroyed) return;
                    authCode = auth;
                    qrImage.setImageBitmap(bitmap);
                    statusView.setText("请使用哔哩哔哩官方手机客户端扫码");
                    refreshButton.setEnabled(true);
                    startPolling();
                });
            } catch (Throwable e) {
                handler.post(() -> {
                    if (destroyed) return;
                    refreshButton.setEnabled(true);
                    statusView.setText("获取二维码失败：" + safeMessage(e) + "\n点下方按钮重试");
                });
            }
        });
    }

    private void startPolling() {
        polling = true;
        final int token = ++pollToken;
        handler.postDelayed(() -> poll(token), 800);
    }

    private void poll(int token) {
        if (destroyed || !polling || token != pollToken || TextUtils.isEmpty(authCode)) return;
        final String code = authCode;
        CenterThreadPool.run(() -> {
            try {
                JSONObject json = LoginApi.pollTVAuthCode(code);
                handler.post(() -> handlePoll(token, json, null));
            } catch (Throwable e) {
                handler.post(() -> handlePoll(token, null, e));
            }
        });
    }

    private void handlePoll(int token, @Nullable JSONObject json, @Nullable Throwable error) {
        if (destroyed || !polling || token != pollToken) return;
        if (error != null) {
            statusView.setText("网络异常，正在重试…");
            scheduleNext(token);
            return;
        }
        if (json == null) {
            scheduleNext(token);
            return;
        }
        int code = json.optInt("code", -1);
        if (code == 0) {
            onAuthorized(json);
            return;
        }
        switch (code) {
            case 86038:
                polling = false;
                refreshButton.setEnabled(true);
                statusView.setText("二维码已失效，点下方按钮刷新");
                break;
            case 86090:
                statusView.setText("已扫码，请在手机上点击确认");
                scheduleNext(token);
                break;
            case 86101:
                statusView.setText("请使用哔哩哔哩官方手机客户端扫码");
                scheduleNext(token);
                break;
            default:
                statusView.setText("等待扫码…（" + code + "）");
                scheduleNext(token);
                break;
        }
    }

    private void scheduleNext(int token) {
        handler.postDelayed(() -> poll(token), 2000);
    }

    private void onAuthorized(JSONObject json) {
        try {
            JSONObject data = json.optJSONObject("data");
            if (data == null) {
                statusView.setText("授权失败：接口未返回身份信息");
                polling = false;
                refreshButton.setEnabled(true);
                return;
            }
            JSONObject tokenInfo = data.optJSONObject("token_info");
            String accessKey = tokenInfo == null ? "" : tokenInfo.optString("access_token", "");
            String refreshToken = tokenInfo == null ? "" : tokenInfo.optString("refresh_token", "");
            if (TextUtils.isEmpty(accessKey)) {
                statusView.setText("授权失败：接口未返回 access_key");
                polling = false;
                refreshButton.setEnabled(true);
                return;
            }

            long currentMid = SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0);
            if (currentMid <= 0) {
                statusView.setText("请先登录网页账号，再回来授权");
                polling = false;
                refreshButton.setEnabled(true);
                return;
            }
            long scannedMid = midFromCookies(data.optJSONObject("cookie_info"));
            //必须确认扫码账号 = 当前网页账号：access_key 是账号级凭证，
            //存错账号会把卡片以另一个身份发出去
            if (scannedMid <= 0) {
                statusView.setText("无法确认扫码账号，请重新扫码");
                polling = false;
                refreshButton.setEnabled(true);
                return;
            }
            if (scannedMid != currentMid) {
                statusView.setText("扫码账号与当前账号不一致，请用当前账号重新扫码");
                polling = false;
                refreshButton.setEnabled(true);
                return;
            }

            AppAuthStore.save(currentMid, accessKey, refreshToken);
            polling = false;
            MsgUtil.showMsg("卡片权限已开通");
            finish();
        } catch (Throwable e) {
            statusView.setText("保存授权失败：" + safeMessage(e));
            polling = false;
            refreshButton.setEnabled(true);
        }
    }

    private static long midFromCookies(@Nullable JSONObject cookieInfo) {
        if (cookieInfo == null) return 0;
        JSONArray cookies = cookieInfo.optJSONArray("cookies");
        if (cookies == null) return 0;
        for (int i = 0; i < cookies.length(); i++) {
            JSONObject cookie = cookies.optJSONObject(i);
            if (cookie == null) continue;
            if ("DedeUserID".equals(cookie.optString("name"))) {
                try {
                    return Long.parseLong(cookie.optString("value", "0"));
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static String safeMessage(Throwable e) {
        if (e == null) return "未知错误";
        return TextUtils.isEmpty(e.getMessage()) ? e.toString() : e.getMessage();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        polling = false;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
