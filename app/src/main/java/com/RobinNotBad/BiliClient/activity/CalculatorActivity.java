package com.RobinNotBad.BiliClient.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.PrivacyLockUtil;

/**
 * 计算器锁屏：伪装与解锁的双重身份。
 *
 * <p>两个进入路径：
 * ① 隐私锁开着（未伪装）：SplashActivity startActivityForResult 进来，验对密码 setResult 回去继续启动链；
 * ② 图标伪装开着：它是桌面计算器图标的 alias target，验对密码后带 EXTRA_UNLOCKED 跳 SplashActivity。
 * 用 getCallingActivity() 区分：forResult 方式启动的才有调用方。
 *
 * <p>解锁规则：当前输入是纯数字且无未完成的运算时按「=」视为密码尝试——
 * 匹配就解锁，不匹配提示密码错误（计算器本身照常可用，有运算符就老老实实算数）。
 * 密码就是用户设置的数字串本身，不做万能码（做后门等于整个功能白做）。
 *
 * <p>忘了密码没有后门：只能去系统设置清除应用数据（会同时清掉登录态）。
 */
public class CalculatorActivity extends Activity {

    private TextView display;
    private TextView preview;

    //当前输入（只可能是数字/小数点的组合，运算符即时结算，不拼表达式字符串）
    private String current = "0";
    private double accumulator = 0;
    private String pendingOp = null;
    private boolean freshInput = true; //刚按过运算符或等号，下一个数字应覆盖显示

    private static final int MAX_INPUT_LEN = 12; //显示宽度限制，超长输入挤爆 40sp 的显示区

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(BiliTerminal.getFitDisplayContext(newBase));
    }

    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //与 SplashActivity 同款做法：非 AppCompat 环境（桌面别名直接拉起），手动套主题
        setTheme(R.style.Theme_BiliClient);
        setContentView(R.layout.activity_calculator);

        display = findViewById(R.id.calc_display);
        preview = findViewById(R.id.calc_preview);
        buildKeypad();
    }

    /**4×5 按钮网格：{标签, 动作标记}。全部同构，代码生成。 */
    private void buildKeypad() {
        String[][] rows = {
                {"C", "⌫", "%", "÷"},
                {"7", "8", "9", "×"},
                {"4", "5", "6", "−"},
                {"1", "2", "3", "+"},
                {"±", "0", ".", "="}
        };
        LinearLayout keypad = findViewById(R.id.calc_keypad);
        int pad = (int) (getResources().getDisplayMetrics().density * 4);
        for (String[] row : rows) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (String label : row) {
                Button button = new Button(this, null, android.R.attr.buttonBarButtonStyle);
                button.setText(label);
                button.setTextSize(20);
                button.setAllCaps(false);
                button.setTextColor(Color.WHITE);
                button.setBackgroundResource("=".equals(label)
                        ? R.drawable.background_calc_key_function
                        : isOperator(label) ? R.drawable.background_calc_key_accent
                        : R.drawable.background_calc_key);
                button.setOnClickListener(v -> onKey(label));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        0, (int) (getResources().getDisplayMetrics().density * 60), 1f);
                params.setMargins(pad, pad, pad, pad);
                line.addView(button, params);
            }
            keypad.addView(line, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
    }

    private static boolean isOperator(String label) {
        return "÷".equals(label) || "×".equals(label) || "−".equals(label) || "+".equals(label);
    }

    @SuppressLint({"SetTextI18n", "DefaultLocale"})
    private void onKey(String key) {
        switch (key) {
            case "C":
                current = "0";
                accumulator = 0;
                pendingOp = null;
                freshInput = true;
                preview.setText("");
                break;
            case "⌫":
                if (freshInput) { //退格只对本轮输入生效，结果不逐字回删
                    current = "0";
                    freshInput = true;
                } else if (current.length() > 1) {
                    current = current.substring(0, current.length() - 1);
                } else {
                    current = "0";
                }
                break;
            case "%":
                if (pendingOp != null && !freshInput) computePending(); //如 200-10% = 180 的连续输入场景
                current = format(parse(current) / 100.0);
                freshInput = true;
                break;
            case "±":
                if (!current.startsWith("-") && !"0".equals(current)) current = "-" + current;
                else if (current.startsWith("-")) current = current.substring(1);
                break;
            case ".":
                if (freshInput) {
                    current = "0.";
                    freshInput = false;
                } else if (!current.contains(".")) {
                    current = current + ".";
                }
                break;
            case "+": case "−": case "×": case "÷":
                if (pendingOp != null && !freshInput) computePending();
                else accumulator = parse(current);
                pendingOp = key;
                freshInput = true;
                preview.setText(format(accumulator) + " " + key);
                break;
            case "=":
                onEquals();
                return;
            default: //数字
                if (freshInput) {
                    current = key;
                    freshInput = false;
                } else if (current.length() < MAX_INPUT_LEN) {
                    current = "0".equals(current) ? key : current + key;
                }
                break;
        }
        display.setText(current);
    }

    /**
     * 等号：先看是不是密码尝试（纯数字、无未完成运算、锁生效中），是就验密码；
     * 否则老老实实当计算器用。有未完成运算时（如 12+34=）永远按算数处理，
     * 保证「计算器必须真的能算」这条伪装底线。
     */
    private void onEquals() {
        boolean pureDigits = current.matches("\\d+");
        if (pendingOp == null && pureDigits && PrivacyLockUtil.isEnabled()) {
            if (PrivacyLockUtil.verify(current)) {
                unlock();
            } else {
                MsgUtil.showMsg("密码错误");
            }
            return;
        }
        if (pendingOp != null && !freshInput) {
            computePending();
            pendingOp = null;
            preview.setText("");
        }
        display.setText(current);
    }

    private void computePending() {
        double operand = parse(current);
        double result;
        switch (pendingOp) {
            case "+": result = accumulator + operand; break;
            case "−": result = accumulator - operand; break;
            case "×": result = accumulator * operand; break;
            case "÷":
                if (operand == 0) {
                    current = "错误";
                    freshInput = true;
                    return;
                }
                result = accumulator / operand;
                break;
            default: result = operand;
        }
        accumulator = result;
        current = format(result);
        freshInput = true;
    }

    private static double parse(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**整数去掉 .0尾巴，小数保留合理精度。 */
    @SuppressLint("DefaultLocale")
    private static String format(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return "错误";
        if (value == Math.rint(value) && Math.abs(value) < 1e15) return String.valueOf((long) value);
        return String.format("%.8f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /**验对密码：按进入路径分流（见类注释）。 */
    private void unlock() {
        if (getCallingActivity() != null) {
            //Splash 的 startActivityForResult 路径：回去让它继续启动链
            setResult(RESULT_OK);
            finish();
        } else {
            //桌面计算器图标（activity-alias）路径：跳 Splash 并声明已解锁
            Intent intent = new Intent(this, SplashActivity.class);
            intent.putExtra(PrivacyLockUtil.EXTRA_UNLOCKED, true);
            startActivity(intent);
            finish();
        }
    }
}
