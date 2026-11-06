package com.RobinNotBad.BiliClient.activity.privacy;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.SplashActivity;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.PrivacyLockUtil;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 用户协议页：伪装模式忘记密码时的唯一恢复通道。
 *
 * <p>为什么藏在这里——协议页在任何一台设备上都平平无奇，捡到手机的人不会点开，
 * 点开了也不会对着标题按住 5 秒；而机主只需要记住
 * 「长按显示屏 → 设置 → 用户协议 → 按住标题别松手」。
 * 按住期间无任何视觉反馈，松手即取消，旁人几乎无从发现（伪装底线）。
 */
public class AgreementActivity extends Activity {

    /** 长按标题进入真身的时长 */
    private static final long UNLOCK_HOLD_MS = 5000;

    private final Runnable unlockRunnable = new Runnable() {
        @Override
        public void run() {
            enterRealApp();
        }
    };

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

        TextView title = findViewById(R.id.agreement_title);
        TextView body = findViewById(R.id.agreement_text);
        body.setText(readAgreement());

        title.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        view.postDelayed(unlockRunnable, UNLOCK_HOLD_MS);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        //松手即取消：比"点一下就进"多一道防误触，比"连点 N 次"更好记
                        view.removeCallbacks(unlockRunnable);
                        return true;
                }
                return false;
            }
        });
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
