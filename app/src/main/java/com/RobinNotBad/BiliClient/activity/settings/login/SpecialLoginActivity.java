package com.RobinNotBad.BiliClient.activity.settings.login;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.SplashActivity;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.util.AccountManager;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONException;
import org.json.JSONObject;

public class SpecialLoginActivity extends BaseActivity {

    private EditText textInput;

    @SuppressLint({"MissingInflatedId", "SetTextI18n"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login_special);
        Logu.i("debug", "使用特殊登录方式");

        textInput = findViewById(R.id.loginInput);
        MaterialCardView confirm = findViewById(R.id.confirm);
        MaterialCardView refuse = findViewById(R.id.refuse);
        MaterialCardView copy = findViewById(R.id.copy);
        TextView desc = findViewById(R.id.desc);

        Intent intent = getIntent();

        if (intent.getBooleanExtra("login", true)) {
            refuse.setOnClickListener(v -> {
                if (getIntent().getBooleanExtra("from_setup", false))
                    startActivity(new Intent(this, SplashActivity.class));
                else finish();
            });

            confirm.setOnClickListener(view -> {
                String loginInfo = textInput.getText().toString().trim();
                try {
                    JSONObject jsonObject = new JSONObject(loginInfo);
                    String cookies = jsonObject.optString("cookies", "").trim();
                    String refreshToken = jsonObject.optString("refresh_token", "").trim();

                    //逐项校验后才写凭证：getInfoFromCookie 取不到 DedeUserID 时返回空串，
                    //原来的 Long.parseLong 直接抛 NumberFormatException 且 catch 只接 JSONException，必崩
                    if (cookies.isEmpty()) {
                        MsgUtil.showMsg("导入内容缺少 cookies\n请重新从浏览器复制完整登录信息");
                        return;
                    }
                    String midText = NetWorkUtil.getInfoFromCookie("DedeUserID", cookies).trim();
                    if (midText.isEmpty()) {
                        MsgUtil.showMsg("cookies 里没有 DedeUserID\n这份登录信息不完整，请重新复制");
                        return;
                    }
                    long mid;
                    try {
                        mid = Long.parseLong(midText);
                    } catch (NumberFormatException e) {
                        MsgUtil.showMsg("DedeUserID 不是有效数字\n请检查复制的内容是否被改过");
                        return;
                    }
                    if (refreshToken.isEmpty()) {
                        MsgUtil.showMsg("导入内容缺少 refresh_token\n请重新从浏览器复制完整登录信息");
                        return;
                    }

                    SharedPreferencesUtil.putLong(SharedPreferencesUtil.mid, mid);
                    SharedPreferencesUtil.putString(SharedPreferencesUtil.csrf, NetWorkUtil.getInfoFromCookie("bili_jct", cookies));
                    SharedPreferencesUtil.putString(SharedPreferencesUtil.cookies, cookies);
                    SharedPreferencesUtil.putString(SharedPreferencesUtil.refresh_token, refreshToken);
                    MsgUtil.showMsg("登录成功！");
                    SharedPreferencesUtil.putBoolean(SharedPreferencesUtil.setup, true);

                    //refreshHeaders 可能触发 buvid/bili_ticket 的网络请求（带重试），绝不能在主线程做
                    CenterThreadPool.run(NetWorkUtil::refreshHeaders);

                    //多账号：把导入的凭证快照进账号列表
                    CenterThreadPool.run(AccountManager::saveCurrentAccount);

                    Intent intent1 = new Intent();
                    intent1.setClass(SpecialLoginActivity.this, SplashActivity.class);
                    startActivity(intent1);
                    finish();
                } catch (JSONException e) {
                    runOnUiThread(() -> MsgUtil.showMsg("请检查输入的内容，不要有多余空格或字符"));
                }
            });
        } else {
            desc.setText(R.string.special_login_export);

            JSONObject jsonObject = new JSONObject();
            try {
                jsonObject.put("cookies", SharedPreferencesUtil.getString("cookies", ""));
                jsonObject.put("refresh_token", SharedPreferencesUtil.getString(SharedPreferencesUtil.refresh_token, ""));
            } catch (JSONException e) {
                e.printStackTrace();
            }
            textInput.setText(jsonObject.toString());
            textInput.clearFocus();

            refuse.setVisibility(View.GONE);
            if (BiliTerminal.isDebugBuild()) {
                confirm.setOnClickListener(v -> {
                    try {
                        JSONObject input = new JSONObject(textInput.getText().toString());
                        String cookies = input.getString("cookies");
                        SharedPreferencesUtil.putString(SharedPreferencesUtil.cookies, cookies);
                        runOnUiThread(() -> MsgUtil.showMsg("导入cookies成功"));

                        //同上：可能触发网络请求，放后台线程
                        CenterThreadPool.run(NetWorkUtil::refreshHeaders);
                    } catch (JSONException e) {
                        runOnUiThread(() -> MsgUtil.showMsg("请检查输入的内容，不要有多余空格或字符"));
                    }
                });
            } else confirm.setVisibility(View.GONE);
            copy.setOnClickListener((view) -> {
                ClipboardManager clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clipData = ClipData.newPlainText("label", textInput.getText());
                clipboardManager.setPrimaryClip(clipData);
                MsgUtil.showMsg("已复制");
            });
        }
    }

}