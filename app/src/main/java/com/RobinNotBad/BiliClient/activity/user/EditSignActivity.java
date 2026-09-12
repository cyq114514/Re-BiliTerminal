package com.RobinNotBad.BiliClient.activity.user;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.TextView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.api.UserInfoApi;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONException;

import java.io.IOException;

public class EditSignActivity extends BaseActivity {

    private EditText editText;
    private TextView charCount;
    private MaterialCardView submit;
    private boolean isSubmitting = false;

    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_sign);

        if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) == 0) {
            MsgUtil.showMsg("还没有登录喵~");
            finish();
            return;
        }

        Intent intent = getIntent();
        String currentSign = intent.getStringExtra("currentSign");
        if (currentSign == null) {
            currentSign = "";
        }

        editText = findViewById(R.id.editText);
        charCount = findViewById(R.id.charCount);
        submit = findViewById(R.id.submit);

        editText.setText(currentSign);
        editText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(70)});
        editText.setSelection(editText.getText().length());

        updateCharCount(editText.getText().toString().length());

        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateCharCount(s.length());
            }
        });

        submit.setOnClickListener(view -> {
            if (isSubmitting) {
                MsgUtil.showMsg("正在提交中...");
                return;
            }

            if (!SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.cookie_refresh, true)) {
                MsgUtil.showDialog("无法提交", "上一次的Cookie刷新失败了，\n您可能需要重新登录以进行敏感操作", -1);
                return;
            }

            String newSign = editText.getText().toString();
            isSubmitting = true;
            submit.setEnabled(false);

            CenterThreadPool.run(() -> {
                try {
                    org.json.JSONObject result = UserInfoApi.updateUserSign(newSign);
                    int code = result.getInt("code");
                    String message = result.optString("message", "");

                    if (!this.isDestroyed()) {
                        runOnUiThread(() -> {
                            isSubmitting = false;
                            submit.setEnabled(true);

                            if (code == 0) {
                                MsgUtil.showMsg("修改成功，等待审核");
                                setResult(RESULT_OK);
                                finish();
                            } else {
                                String errorMsg = "修改失败";
                                if (code == -101) {
                                    errorMsg = "账号未登录";
                                } else if (code == -111) {
                                    errorMsg = "CSRF校验失败";
                                } else if (code == 40015) {
                                    errorMsg = "签名包含敏感词";
                                } else if (code == 40021) {
                                    errorMsg = "签名不能包含表情图片";
                                } else if (code == 40022) {
                                    errorMsg = "签名过长";
                                } else if (!message.isEmpty()) {
                                    errorMsg = message;
                                }
                                MsgUtil.showMsg(errorMsg);
                            }
                        });
                    }
                } catch (IOException | JSONException e) {
                    if (!this.isDestroyed()) {
                        runOnUiThread(() -> {
                            isSubmitting = false;
                            submit.setEnabled(true);
                            MsgUtil.err("修改个人描述失败", e);
                        });
                    }
                }
            });
        });
    }

    private void updateCharCount(int count) {
        charCount.setText(count + "/70");
    }
}

