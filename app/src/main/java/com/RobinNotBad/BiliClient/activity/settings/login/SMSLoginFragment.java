package com.RobinNotBad.BiliClient.activity.settings.login;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
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
import com.google.android.material.card.MaterialCardView;

import org.json.JSONObject;

import java.io.IOException;

//短信验证码登录页面
//流程：校验手机号 → 取极验参数 → 人机验证 → 发送验证码 → 输入验证码 → 提交登录

public class SMSLoginFragment extends Fragment {

    private static final int REQUEST_CAPTCHA = 1002;
    private static final long COUNTDOWN_MILLIS = 60000;

    private EditText phoneInput;
    private EditText smsCodeInput;
    private TextView sendSmsText;
    private TextView statusText;
    private MaterialCardView sendSmsBtn;

    //极验参数，发送验证码前获取
    private String captchaToken = "";
    private String captchaChallenge = "";
    //发送验证码成功后返回，登录时必须回传
    private String captchaKey = "";
    //是否已完成人机验证，未完成不允许点登录
    private boolean captchaReady = false;
    private String pendingPhone = "";

    private CountDownTimer countDownTimer;

    public SMSLoginFragment() {
    }

    public static SMSLoginFragment newInstance() {
        return new SMSLoginFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_sms_login, container, false);
        phoneInput = view.findViewById(R.id.phoneInput);
        smsCodeInput = view.findViewById(R.id.smsCodeInput);
        sendSmsText = view.findViewById(R.id.sendSmsText);
        statusText = view.findViewById(R.id.statusText);
        sendSmsBtn = view.findViewById(R.id.sendSmsBtn);
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        sendSmsBtn.setOnClickListener(v -> onSendSmsClick());

        MaterialCardView confirmBtn = view.findViewById(R.id.confirmBtn);
        confirmBtn.setOnClickListener(v -> onLoginClick());

        MaterialCardView switchToPwd = view.findViewById(R.id.switchToPwd);
        switchToPwd.setOnClickListener(v -> {
            ViewPager viewPager = requireActivity().findViewById(R.id.viewPager);
            if (viewPager != null) viewPager.setCurrentItem(1, true);
        });
    }

    private void onSendSmsClick() {
        String phone = phoneInput.getText().toString().trim();
        if (TextUtils.isEmpty(phone)) {
            MsgUtil.showMsg("请输入手机号");
            return;
        }
        pendingPhone = phone;
        startCaptcha();
    }

    private void onLoginClick() {
        String phone = phoneInput.getText().toString().trim();
        String code = smsCodeInput.getText().toString().trim();
        if (TextUtils.isEmpty(phone) || TextUtils.isEmpty(code)) {
            MsgUtil.showMsg("请输入手机号和验证码");
            return;
        }
        if (!captchaReady) {
            MsgUtil.showMsg("请先完成人机验证");
            return;
        }
        doSmsLogin(phone, code);
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

        doSendSms(pendingPhone, useChallenge, validate, seccode);
    }

    //第二步：发送短信验证码
    private void doSendSms(String phone, String challenge, String validate, String seccode) {
        setStatus("正在发送验证码...");
        CenterThreadPool.run(() -> {
            try {
                JSONObject resp = LoginApi.smsSend(phone, captchaToken, challenge, validate, seccode);
                int code = resp.optInt("code", -1);

                if (code == 0) {
                    JSONObject data = resp.optJSONObject("data");
                    captchaKey = data != null ? data.optString("captcha_key", "") : "";
                    captchaReady = true;
                    setStatus("验证码已发送，请查收短信");
                    CenterThreadPool.runOnUiThread(() -> {
                        if (isAdded()) startCountDown();
                    });
                    return;
                }

                captchaReady = false;
                if (code == -105) setStatus("验证码错误，请重试");
                else setStatus("发送失败：" + resp.optString("message", "未知错误"));
            } catch (IOException e) {
                captchaReady = false;
                setStatus("网络错误，请重试");
                e.printStackTrace();
            } catch (Exception e) {
                captchaReady = false;
                setStatus("发送失败：" + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private void startCountDown() {
        if (countDownTimer != null) countDownTimer.cancel();
        countDownTimer = new CountDownTimer(COUNTDOWN_MILLIS, 1000) {
            @SuppressLint("SetTextI18n")
            @Override
            public void onTick(long millisUntilFinished) {
                if (!isAdded()) return;
                sendSmsText.setText((millisUntilFinished / 1000) + "s后重发");
                sendSmsBtn.setEnabled(false);
            }

            @Override
            public void onFinish() {
                if (!isAdded()) return;
                sendSmsText.setText("获取验证码");
                sendSmsBtn.setEnabled(true);
            }
        };
        countDownTimer.start();
    }

    //第三步：提交验证码登录
    private void doSmsLogin(String phone, String code) {
        setStatus("正在登录...");
        CenterThreadPool.run(() -> {
            try {
                JSONObject resp = LoginApi.smsLogin(phone, code, captchaKey);
                int respCode = resp.optInt("code", -1);

                if (respCode == 0) {
                    setStatus("正在处理登录……");
                    LoginApi.finishLogin(requireContext(), resp.optJSONObject("data"));
                    CenterThreadPool.runOnUiThread(() -> {
                        if (isAdded()) requireActivity().finish();
                    });
                    return;
                }

                //验证码/极验凭据失效，需要重新走一遍人机验证
                captchaReady = false;
                if (respCode == 1006) setStatus("请输入正确的短信验证码");
                else if (respCode == 1007) setStatus("短信验证码已过期");
                else setStatus("登录失败：" + resp.optString("message", "未知错误"));
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

    @Override
    public void onDestroy() {
        if (countDownTimer != null) countDownTimer.cancel();
        super.onDestroy();
    }
}
