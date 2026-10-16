package com.RobinNotBad.BiliClient.activity.settings;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.util.BackgroundManager;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.google.android.material.switchmaterial.SwitchMaterial;

public class SettingUIActivity extends BaseActivity {

    private EditText uiScaleInput, uiPaddingH, uiPaddingV, density_input;
    private EditText bgDimInput;
    private SwitchMaterial bgSwitch;
    private TextView bgImageState;

    //相册选图：minSdk 19 下 GetContent 契约内部走 ACTION_GET_CONTENT，
    //不做 Uri 持久化授权（老设备上不可靠），拿到后立刻复制并压缩进私有目录
    private final ActivityResultLauncher<String> bgImagePicker =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null) return;
                MsgUtil.showMsg("正在处理图片…");
                //用 ApplicationContext：解码+写盘是耗时操作，不持有 Activity 引用
                CenterThreadPool.run(() -> {
                    final boolean ok = BackgroundManager.importImage(getApplicationContext(), uri);
                    runOnUiThread(() -> {
                        if (ok) SharedPreferencesUtil.putBoolean(BackgroundManager.KEY_ENABLE, true);
                        if (bgSwitch != null) bgSwitch.setChecked(ok);
                        updateBgImageState();
                        MsgUtil.showMsg(ok ? "背景图已设置\n返回其他页面即可看到效果"
                                : "图片处理失败，换一张再试试");
                    });
                });
            });

    @SuppressLint({"MissingInflatedId", "SetTextI18n", "InflateParams"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        asyncInflate(R.layout.activity_setting_ui, (layoutView, resId) -> {

            uiScaleInput = findViewById(R.id.ui_scale_input);
            uiScaleInput.setText(String.valueOf(SharedPreferencesUtil.getFloat("dpi", 1.0F)));

            uiPaddingH = findViewById(R.id.ui_padding_horizontal);
            uiPaddingH.setText(String.valueOf(SharedPreferencesUtil.getInt("paddingH_percent", 0)));
            uiPaddingV = findViewById(R.id.ui_padding_vertical);
            uiPaddingV.setText(String.valueOf(SharedPreferencesUtil.getInt("paddingV_percent", 0)));

            density_input = findViewById(R.id.density_input);
            int density = SharedPreferencesUtil.getInt("density", -1);
            DisplayMetrics displayMetrics = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
            density_input.setText(String.valueOf((density == -1 ? displayMetrics.densityDpi + "(默认)" : density)));

            SwitchMaterial newUi = findViewById(R.id.switch_new_ui);
            newUi.setChecked(SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.NEW_UI_DESIGN, true));
            newUi.setOnCheckedChangeListener((buttonView, isChecked) -> {
                SharedPreferencesUtil.putBoolean(SharedPreferencesUtil.NEW_UI_DESIGN, isChecked);
                MsgUtil.showMsg(isChecked ? "已开启新版美学设计\n应用将立即重启生效" : "已关闭新版美学设计\n应用将立即重启生效");
                //只写 SP 不重启会出现新旧混排：已打开页面保持旧样式、新开页面立即用新样式，
                //且 GlideUtil 的封面样式缓存也按旧值建。整进程重启（随后走冷启动恢复链）最干净
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        Intent restart = Intent.makeRestartActivityTask(
                                new android.content.ComponentName(SettingUIActivity.this, com.RobinNotBad.BiliClient.activity.SplashActivity.class));
                        restart.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(restart);
                    } catch (Exception e) {
                        e.printStackTrace();
                        return;   //重启失败就维持旧行为：重进应用后生效
                    }
                    //startActivity 已同步送达 system_server，进程退出不影响新任务拉起
                    android.os.Process.killProcess(android.os.Process.myPid());
                }, 600);
            });

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

            bgSwitch = findViewById(R.id.switch_bg_image);
            bgSwitch.setChecked(BackgroundManager.isEnabled());
            bgSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                SharedPreferencesUtil.putBoolean(BackgroundManager.KEY_ENABLE, isChecked);
                updateBgImageState();
            });

            bgDimInput = findViewById(R.id.bg_image_dim);
            bgDimInput.setText(String.valueOf(BackgroundManager.getDim()));

            bgImageState = findViewById(R.id.bg_image_state);
            updateBgImageState();

            findViewById(R.id.btn_bg_pick).setOnClickListener(view -> bgImagePicker.launch("image/*"));
            findViewById(R.id.btn_bg_clear).setOnClickListener(view -> {
                BackgroundManager.clear(SettingUIActivity.this);
                bgSwitch.setChecked(false);
                updateBgImageState();
                MsgUtil.showMsg("已清除背景图\n返回其他页面即可看到效果");
            });

            findViewById(R.id.preview).setOnClickListener(view -> {
                save();
                Intent intent = new Intent();
                intent.setClass(SettingUIActivity.this, UIPreviewActivity.class);
                startActivity(intent);
            });
            findViewById(R.id.reset).setOnClickListener(view -> {
                SharedPreferencesUtil.putInt("paddingH_percent", 0);
                SharedPreferencesUtil.putInt("paddingV_percent", 0);
                SharedPreferencesUtil.putFloat("dpi", 1.0f);
                SharedPreferencesUtil.putInt("density", -1);
                SharedPreferencesUtil.putBoolean("player_ui_round", false);
                uiScaleInput.setText("1.0");
                uiPaddingH.setText("0");
                uiPaddingV.setText("0");
                getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
                density_input.setText(displayMetrics.densityDpi + "(默认)");
                round.setChecked(false);
                SharedPreferencesUtil.putBoolean(SharedPreferencesUtil.NEW_UI_DESIGN, true);
                newUi.setChecked(true);
                //背景图本身不清除（那是用户自己的图片资产），只把暗度恢复默认
                SharedPreferencesUtil.putInt(BackgroundManager.KEY_DIM, 30);
                if (bgDimInput != null) bgDimInput.setText("30");
                updateBgImageState();
                MsgUtil.showMsg("恢复完成");
            });

            View scrollView = findViewById(R.id.scrollView);
            scrollView.setFocusable(true);
            scrollView.setFocusableInTouchMode(true);
            scrollView.requestFocus();
        });
    }

    private void save() {
        //三处数字输入都要防解析崩溃：numberDecimal 键盘仍可输入 "." / "1.2.3"，
        //且 save() 在 onDestroy 也会触发，输入非法值后直接退出页面同样会崩
        if (!uiScaleInput.getText().toString().isEmpty()) {
            try {
                float dpiScale = Float.parseFloat(uiScaleInput.getText().toString());
                if (dpiScale >= 0.25F && dpiScale <= 5.0F) {
                    SharedPreferencesUtil.putFloat("dpi", dpiScale);
                    BiliTerminal.DPI_FORCE_CHANGE = true;
                }
                Logu.i("dpi", uiScaleInput.getText().toString());
            } catch (NumberFormatException e) {
                Logu.i("dpi", "非法输入已忽略：" + uiScaleInput.getText().toString());
            }
        }

        if (!uiPaddingH.getText().toString().isEmpty()) {
            try {
                int paddingH = Integer.parseInt(uiPaddingH.getText().toString());
                if (paddingH <= 30) SharedPreferencesUtil.putInt("paddingH_percent", paddingH);
                Logu.i("paddingH", uiPaddingH.getText().toString());
            } catch (NumberFormatException e) {
                Logu.i("paddingH", "非法输入已忽略：" + uiPaddingH.getText().toString());
            }
        }

        if (!uiPaddingV.getText().toString().isEmpty()) {
            try {
                int paddingV = Integer.parseInt(uiPaddingV.getText().toString());
                if (paddingV <= 30) SharedPreferencesUtil.putInt("paddingV_percent", paddingV);
                Logu.i("paddingV", uiPaddingV.getText().toString());
            } catch (NumberFormatException e) {
                Logu.i("paddingV", "非法输入已忽略：" + uiPaddingV.getText().toString());
            }
        }

        if (!density_input.getText().toString().isEmpty()) {
            try {
                int density = Integer.parseInt(density_input.getText().toString());
                if (density >= 72) SharedPreferencesUtil.putInt("density", density);
            } catch (Throwable ignored) {
            }
        }

        //暗度夹到 0-100：与上面几个输入项同一套宽容口径，越界不报错只是不保存
        if (bgDimInput != null && !bgDimInput.getText().toString().isEmpty()) {
            try {
                int dim = Integer.parseInt(bgDimInput.getText().toString());
                SharedPreferencesUtil.putInt(BackgroundManager.KEY_DIM, Math.max(0, Math.min(100, dim)));
            } catch (NumberFormatException e) {
                Logu.i("bg_dim", "非法输入已忽略：" + bgDimInput.getText().toString());
            }
        }
    }

    /** 刷新背景设置的状态文案：让用户能区分"没启用"和"启用了但还没选图" */
    private void updateBgImageState() {
        if (bgImageState == null) return;
        bgImageState.setText(BackgroundManager.describeState(this));
    }

    @Override
    protected void onDestroy() {
        if (uiScaleInput != null) save();
        super.onDestroy();
    }
}