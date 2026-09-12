package com.RobinNotBad.BiliClient.activity.user;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.api.UserInfoApi;
import com.RobinNotBad.BiliClient.model.UserInfo;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONObject;

public class EditUserInfoActivity extends BaseActivity {

    private EditText etUsername, etBirthday;
    private RadioGroup rgSex;
    private MaterialCardView submit;
    private boolean isSubmitting = false;
    private String currentUsername = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_user_info);

        if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) == 0) {
            MsgUtil.showMsg("还没有登录喵~");
            finish();
            return;
        }

        etUsername = findViewById(R.id.et_username);
        etBirthday = findViewById(R.id.et_birthday);
        rgSex = findViewById(R.id.rg_sex);
        submit = findViewById(R.id.submit);

        findViewById(R.id.pageName).setOnClickListener(v -> finish());

        CenterThreadPool.run(() -> {
            try {
                UserInfo userInfo = UserInfoApi.getCurrentUserInfo();
                if (userInfo != null && userInfo.name != null) {
                    currentUsername = userInfo.name;
                    runOnUiThread(() -> etUsername.setText(userInfo.name));
                }
            } catch (Exception ignored) {
            }
        });

        submit.setOnClickListener(v -> {
            if (isSubmitting) {
                MsgUtil.showMsg("正在提交中...");
                return;
            }

            if (!SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.cookie_refresh, true)) {
                MsgUtil.showDialog("无法提交", "上一次的Cookie刷新失败了，\n您可能需要重新登录以进行敏感操作", -1);
                return;
            }

            String uname = etUsername.getText().toString().trim();
            String birthday = etBirthday.getText().toString().trim();
            // 验证生日格式为YYYY-MM-DD
            if (!birthday.isEmpty() && !birthday.matches("\\d{4}-\\d{2}-\\d{2}")) {
                runOnUiThread(() -> MsgUtil.showMsg("生日格式错误，请使用YYYY-MM-DD"));
                return;
            }

            int checkedId = rgSex.getCheckedRadioButtonId();
            final String sex;
            if (checkedId == R.id.rb_male) {
                sex = "1";
            } else if (checkedId == R.id.rb_female) {
                sex = "2";
            } else {
                sex = null;
            }

            if (uname.isEmpty() && birthday.isEmpty() && sex == null) {
                MsgUtil.showMsg("请至少填写一项要修改的内容");
                return;
            }

            isSubmitting = true;
            submit.setEnabled(false);

            CenterThreadPool.run(() -> {
                try {
                    String nameToSubmit = uname.isEmpty() ? null : uname;
                    JSONObject result = UserInfoApi.updateUserInfo(
                            nameToSubmit,
                            birthday.isEmpty() ? null : birthday,
                            sex,
                            null
                    );
                    int code = result.getInt("code");
                    String message = result.optString("message", "");

                    runOnUiThread(() -> {
                        isSubmitting = false;
                        submit.setEnabled(true);

                        if (code == 0) {
                            MsgUtil.showMsg("修改成功");
                            finish();
                        } else {
                            String errorMsg;
                            if (code == -101) {
                                errorMsg = "账号未登录";
                            } else if (code == -111) {
                                errorMsg = "CSRF验证失败";
                            } else if (code == 400) {
                                errorMsg = "昵称违规或已被占用";
                            } else if (code == 412) {
                                errorMsg = "修改频率过高，请稍后再试";
                            } else if (code == 2001) {
                                errorMsg = "昵称已存在";
                            } else if (code == 21003) {
                                errorMsg = "生日格式错误";
                            } else if (code == -403) {
                                errorMsg = "权限不足";
                            } else {
                                errorMsg = message.isEmpty() ? "修改失败" : message;
                            }
                            MsgUtil.showMsg(errorMsg);
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        isSubmitting = false;
                        submit.setEnabled(true);
                        MsgUtil.err("修改失败", e);
                    });
                }
            });
        });
    }
}
