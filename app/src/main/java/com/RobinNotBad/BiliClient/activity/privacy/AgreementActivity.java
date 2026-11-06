package com.RobinNotBad.BiliClient.activity.privacy;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.SplashActivity;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.PrivacyLockUtil;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 用户协议页：伪装模式忘记密码时的兜底通道。
 *
 * <p>点「进入应用」直接进入真身，不要密码、没有任何隐藏手势——兜底要的就是
 * 忘掉一切细节也能进得去。隐蔽性不靠交互技巧，靠的是没人会去翻一个
 * 计算器的设置和协议（计算器设置页里有说明指路）。
 */
public class AgreementActivity extends Activity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(BiliTerminal.getFitDisplayContext(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //与 CalculatorActivity 同款做法：非 AppCompat 环境（桌面别名链路）手动套主题
        setTheme(R.style.Theme_BiliClient);
        setContentView(R.layout.activity_agreement);

        TextView body = findViewById(R.id.agreement_text);
        body.setText(readAgreement());

        findViewById(R.id.enter_app_btn).setOnClickListener(v -> enterRealApp());
    }

    /** 进入真身。按进入路径分流，与 {@code CalculatorActivity.unlock()} 同一套约定。 */
    private void enterRealApp() {
        try {
            if (getCallingActivity() != null) {
                //Splash 的 startActivityForResult 路径（未伪装、仅密码锁）：回去让它继续启动链
                setResult(RESULT_OK);
            } else {
                //桌面计算器图标（activity-alias）路径：清掉伪装栈后跳 Splash 并声明已解锁
                Intent intent = new Intent(this, SplashActivity.class);
                intent.putExtra(PrivacyLockUtil.EXTRA_UNLOCKED, true);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finishAffinity();
                return;
            }
        } catch (Exception e) {
            Logu.d("agreement", "进入真身失败：" + e);
        }
        finish();
    }

    private String readAgreement() {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(getAssets().open("agreement.txt"), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
        } catch (Exception e) {
            return "协议内容加载失败，请重装或反馈给维护者。";
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
        }
        return sb.toString();
    }
}
