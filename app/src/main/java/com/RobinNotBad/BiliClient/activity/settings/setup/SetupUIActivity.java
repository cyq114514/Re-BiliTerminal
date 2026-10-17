package com.RobinNotBad.BiliClient.activity.settings.setup;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.activity.settings.UIPreviewActivity;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.google.android.material.switchmaterial.SwitchMaterial;

public class SetupUIActivity extends BaseActivity {

    private EditText uiScaleInput, uiPaddingH, uiPaddingV;

    @SuppressLint({"MissingInflatedId", "SetTextI18n"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup_ui);

        uiScaleInput = findViewById(R.id.ui_scale_input);
        uiScaleInput.setText(String.valueOf(SharedPreferencesUtil.getFloat("dpi", 1.0F)));

        uiPaddingH = findViewById(R.id.ui_padding_horizontal);
        uiPaddingH.setText(String.valueOf(SharedPreferencesUtil.getInt("paddingH_percent", 0)));
        uiPaddingV = findViewById(R.id.ui_padding_vertical);
        uiPaddingV.setText(String.valueOf(SharedPreferencesUtil.getInt("paddingV_percent", 0)));

        SwitchMaterial round = findViewById(R.id.switch_round);
        round.setChecked(SharedPreferencesUtil.getBoolean("player_ui_round", false));
        round.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                uiPaddingH.setText("5");
                uiPaddingV.setText("3");
                SharedPreferencesUtil.putBoolean("player_ui_round", true);
                MsgUtil.showMsg("界面边距已更改\n可以手动微调喵");
            } else {
                uiPaddingH.setText("0");
                uiPaddingV.setText("0");
                SharedPreferencesUtil.putBoolean("player_ui_round", false);
            }
        });

        findViewById(R.id.preview).setOnClickListener(view -> {
            if (!save()) return;   //输入非法时留在本页，让用户改
            Intent intent = new Intent();
            intent.setClass(SetupUIActivity.this, UIPreviewActivity.class);
            startActivity(intent);
        });

        findViewById(R.id.confirm).setOnClickListener(view -> {
            if (!save()) return;
            Intent intent = new Intent();
            intent.setClass(SetupUIActivity.this, IntroductionActivity.class);
            startActivity(intent);
            finish();
        });

        findViewById(R.id.reset).setOnClickListener(view -> {
            SharedPreferencesUtil.putInt("paddingH_percent", 0);
            SharedPreferencesUtil.putInt("paddingV_percent", 0);
            SharedPreferencesUtil.putFloat("dpi", 1.0f);
            SharedPreferencesUtil.putBoolean("player_ui_round", false);
            uiScaleInput.setText("1.0");
            uiPaddingH.setText("0");
            uiPaddingV.setText("0");
            round.setChecked(false);
            MsgUtil.showMsg("恢复完成");
        });
    }

    /**
     * 解析并保存 UI 设置。
     * 先全部解析校验、全部通过后才写入：任何一项非法都只提示并中止，不产生半套配置。
     *
     * @return true 表示已保存（或本就没有需要保存的输入），false 表示输入非法已提示
     */
    private boolean save() {
        String scaleText = uiScaleInput.getText().toString().trim();
        String paddingHText = uiPaddingH.getText().toString().trim();
        String paddingVText = uiPaddingV.getText().toString().trim();

        float dpiTimes = SharedPreferencesUtil.getFloat("dpi", 1.0F);
        int paddingH = SharedPreferencesUtil.getInt("paddingH_percent", 0);
        int paddingV = SharedPreferencesUtil.getInt("paddingV_percent", 0);

        if (!scaleText.isEmpty()) {
            try {
                dpiTimes = Float.parseFloat(scaleText);
            } catch (NumberFormatException e) {
                //粘贴"19:08"这类非数字文本时不能崩，提示后中止
                MsgUtil.showMsg("界面缩放请输入数字\n例如 1.0");
                return false;
            }
            //NaN 也走不进区间判断，一并拦下
            if (!(dpiTimes >= 0.1F && dpiTimes <= 10.0F)) {
                MsgUtil.showMsg("界面缩放需在 0.1 ~ 10.0 之间");
                return false;
            }
        }

        if (!paddingHText.isEmpty()) {
            try {
                //允许输入小数（历史默认值就是 1.0），四舍五入取整而不是崩溃
                paddingH = Math.round(Float.parseFloat(paddingHText));
            } catch (NumberFormatException e) {
                MsgUtil.showMsg("水平边距请输入数字\n例如 5");
                return false;
            }
            if (paddingH < 0 || paddingH > 30) {
                MsgUtil.showMsg("水平边距需在 0 ~ 30 之间");
                return false;
            }
        }

        if (!paddingVText.isEmpty()) {
            try {
                paddingV = Math.round(Float.parseFloat(paddingVText));
            } catch (NumberFormatException e) {
                MsgUtil.showMsg("垂直边距请输入数字\n例如 3");
                return false;
            }
            if (paddingV < 0 || paddingV > 30) {
                MsgUtil.showMsg("垂直边距需在 0 ~ 30 之间");
                return false;
            }
        }

        if (!scaleText.isEmpty()) SharedPreferencesUtil.putFloat("dpi", dpiTimes);
        if (!paddingHText.isEmpty()) SharedPreferencesUtil.putInt("paddingH_percent", paddingH);
        if (!paddingVText.isEmpty()) SharedPreferencesUtil.putInt("paddingV_percent", paddingV);

        Logu.i("dpi", String.valueOf(dpiTimes));
        Logu.i("paddingH", String.valueOf(paddingH));
        Logu.i("paddingV", String.valueOf(paddingV));
        return true;
    }
}