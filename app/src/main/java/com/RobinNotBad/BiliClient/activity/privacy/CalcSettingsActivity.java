package com.RobinNotBad.BiliClient.activity.privacy;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Switch;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

/**
 * 计算器的「设置」页：伪装的一部分，同时是忘记密码时的隐藏后门入口。
 *
 * <p>入口：长按计算器显示屏。页面对外只展示一个普通计算器都该有的设置项
 * （按键震动，真实生效）与「用户协议」；进入真身的暗门藏在协议页里
 * （长按标题 5 秒，见 {@link AgreementActivity}），本页不留任何提示。
 */
public class CalcSettingsActivity extends Activity {

    /** 按键震动开关的存档键：CalculatorActivity.onKey 每次按键都会读 */
    public static final String KEY_CALC_HAPTIC = "calc_haptic";

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(BiliTerminal.getFitDisplayContext(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //与 CalculatorActivity 同款做法：非 AppCompat 环境（桌面别名链路）手动套主题
        setTheme(R.style.Theme_BiliClient);
        setContentView(R.layout.activity_calc_settings);

        Switch haptic = findViewById(R.id.calc_haptic_switch);
        haptic.setChecked(SharedPreferencesUtil.getBoolean(KEY_CALC_HAPTIC, true));
        haptic.setOnCheckedChangeListener((buttonView, isChecked) ->
                SharedPreferencesUtil.putBoolean(KEY_CALC_HAPTIC, isChecked));

        findViewById(R.id.agreement_row).setOnClickListener(v ->
                startActivity(new Intent(this, AgreementActivity.class)));
    }
}
