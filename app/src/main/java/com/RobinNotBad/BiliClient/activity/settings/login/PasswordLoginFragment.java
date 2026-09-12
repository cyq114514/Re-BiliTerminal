package com.RobinNotBad.BiliClient.activity.settings.login;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.viewpager.widget.ViewPager;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.api.CookiesApi;
import com.RobinNotBad.BiliClient.api.LoginApi;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.PasswordEncryptUtil;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONObject;

import java.io.IOException;

//密码登录页面
//流程：校验输入 → 取极验参数 → 人机验证 → 取RSA公钥 → 加密密码 → 提交登录

public class PasswordLoginFragment extends Fragment {

    private static final int REQUEST_CAPTCHA = 1001;

    private EditText usernameInput;
    private EditText passwordInput;
    private TextView statusText;

    //极验参数，点击登录时获取，人机验证完成后使用
    private String captchaToken = "";
    private String captchaChallenge = "";

    //提交瞬间的输入值，人机验证返回后仍要使用，避免用户中途改动输入框
    private String pendingUsername = "";
    private String pendingPassword = "";

    public PasswordLoginFragment() {
    }

    public static PasswordLoginFragment newInstance() {
        return new PasswordLoginFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_password_login, container, false);
        usernameInput = view.findViewById(R.id.usernameInput);
        passwordInput = view.findViewById(R.id.passwordInput);
        statusText = view.findViewById(R.id.statusText);
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        MaterialCardView loginBtn = view.findViewById(R.id.loginBtn);
        loginBtn.setOnClickListener(v -> onLoginClick());

        MaterialCardView switchToSms = view.findViewById(R.id.switchToSms);
        switchToSms.setOnClickListener(v -> {
            ViewPager viewPager = requireActivity().findViewById(R.id.viewPager);
            if (viewPager != null) viewPager.setCurrentItem(2, true);
        });
    }

    private void onLoginClick() {
        String username = usernameInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        if (TextUtils.isEmpty(username) || TextUtils.isEmpty(password)) {
            MsgUtil.showMsg("请输入账号和密码");
            return;
        }
        pendingUsername = username;
        pendingPassword = password;
        startCaptcha();
    }

    //第一步：取极验参数并跳到人机验证页
    private void startCaptcha() {
        setStatus("正在获取验证码...");
        CenterThreadPool.run(() -> {
            try {
                //checkCookies失败不应阻塞登录，buvid等指纹缺失只会降低验证成功率
                try {
                    CookiesApi.checkCookies();
                } catch (Exception e) {
                    e.printStackTrace();
                }

                LoginApi.CaptchaParams params = LoginApi.getCaptchaParams();
                captchaToken = params.token;
                captchaChallenge = params.challenge;

                CenterThreadPool.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    setStatus("");
                    Intent intent = new Intent(requireContext(), CaptchaWebViewActivity.class);
                    intent.putExtra(CaptchaWebViewActivity.EXTRA_GT, params.gt);
                    intent.putExtra(CaptchaWebViewActivity.EXTRA_CHALLENGE, params.challenge);
                    startActivityForResult(intent, REQUEST_CAPTCHA);
                });
            } catch (IOException e) {
                setStatus("网络错误，请重试");
                e.printStackTrace();
            } catch (Exception e) {
                setStatus(e.getMessage() != null ? e.getMessage() : "获取验证码失败，请重试");
                e.printStackTrace();
            }
        });
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTCHA) return;

        if (resultCode != Activity.RESULT_OK || data == null) {
            setStatus("验证已取消");
            return;
        }

        String challenge = data.getStringExtra(CaptchaWebViewActivity.RESULT_CHALLENGE);
        String validate = data.getStringExtra(CaptchaWebViewActivity.RESULT_VALIDATE);
        String seccode = data.getStringExtra(CaptchaWebViewActivity.RESULT_SECCODE);

        if (TextUtils.isEmpty(validate)) {
            setStatus("验证结果异常，请重试");
            return;
        }
        //B站极验要求seccode以"|jordan"收尾，网页端拿不到时由客户端补上
        if (TextUtils.isEmpty(seccode) || !seccode.contains("|jordan"))
            seccode = validate + "|jordan";
        String useChallenge = TextUtils.isEmpty(challenge) ? captchaChallenge : challenge;

        doPasswordLogin(pendingUsername, pendingPassword, useChallenge, validate, seccode);
    }

    //第二步：取公钥、加密密码并提交
    private void doPasswordLogin(String username, String password, String challenge, String validate, String seccode) {
        setStatus("正在获取密钥...");
        CenterThreadPool.run(() -> {
            try {
                JSONObject keyData = LoginApi.getWebKey().optJSONObject("data");
                if (keyData == null) {
                    setStatus("获取密钥失败，请重试");
                    return;
                }

                setStatus("正在加密密码...");
                String encryptedPassword = PasswordEncryptUtil.encryptPassword(password,
                        keyData.optString("hash", ""), keyData.optString("key", ""));

                setStatus("正在登录...");
                JSONObject resp = LoginApi.passwordLogin(username, encryptedPassword, captchaToken, challenge, validate, seccode);
                int code = resp.optInt("code", -1);

                if (code == 0) {
                    JSONObject loginData = resp.optJSONObject("data");
                    //status==2 表示账号密码没错，但登录环境触发风控，需要额外验证
                    if (loginData != null && loginData.optInt("status", 0) == 2) {
                        String riskMsg = loginData.optString("message", "");
                        if (riskMsg.contains("手机号")) setStatus("需要手机号验证，请使用短信验证码登录");
                        else setStatus(TextUtils.isEmpty(riskMsg) ? "登录环境存在风险" : riskMsg);
                        return;
                    }
                    setStatus("正在处理登录……");
                    LoginApi.finishLogin(requireContext(), loginData);
                    CenterThreadPool.runOnUiThread(() -> {
                        if (isAdded()) requireActivity().finish();
                    });
                    return;
                }

                switch (code) {
                    case -629:
                        setStatus("账号或密码错误");
                        break;
                    case -662:
                        setStatus("提交超时，请重试");
                        break;
                    case -105:
                        setStatus("验证码错误，请重试");
                        break;
                    default:
                        setStatus("登录失败：" + resp.optString("message", "未知错误"));
                        break;
                }
            } catch (IOException e) {
                setStatus("网络错误，请重试");
                e.printStackTrace();
            } catch (Exception e) {
                setStatus("登录失败：" + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    @SuppressLint("SetTextI18n")
    private void setStatus(String text) {
        CenterThreadPool.runOnUiThread(() -> {
            if (isAdded()) statusText.setText(text);
        });
    }
}
