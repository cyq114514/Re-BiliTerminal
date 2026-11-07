package com.RobinNotBad.BiliClient.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.privacy.CalcSettingsActivity;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.PrivacyLockUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

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
 * <p>忘了密码：长按显示屏 → 设置 → 用户协议 → 「进入应用」，直接进入真身
 * （兜底通道，不用再清数据重登；隐蔽性靠协议页没人翻，不靠隐藏手势）。
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
    //布局完成前 post 的 runnable 可能拿到 0 高度：用有限次重试兜住（最多 5 次）
    private int fitKeypadRetry = 0;

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
        applyRoundLayout();

        //「设置」入口：长按显示屏。伪装页面上没有任何可见入口（伪装底线），
        //机主从 设置 → 用户协议 可以不输密码进真身（忘记密码的恢复通道）
        display.setOnLongClickListener(v -> {
            startActivity(new Intent(this, CalcSettingsActivity.class));
            return true;
        });
    }

    /**
     * 键盘：数字区 3 列 × 4 行（7 8 9 / 4 5 6 / 1 2 3 / . 0 ⌫），
     * 右侧一列运算符（÷ × − +），最上面一行功能键（C % ± =）。
     *
     * <p>用户反馈 2026-11-07：圆屏手表上按键太小、输入框太大。数字区严格 3×4 后
     * 每行少一个键，按键宽度多出约 1/4；显示区字号同时压小（见 activity_calculator.xml）。
     */
    private void buildKeypad() {
        String[][] rows = {
                {"C", "%", "±", "="},
                {"7", "8", "9", "÷"},
                {"4", "5", "6", "×"},
                {"1", "2", "3", "−"},
                {".", "0", "⌫", "+"}
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
                //按钮高度由行高决定（行高在布局完成后按可用空间收缩），这里先撑满
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                params.setMargins(pad, pad, pad, pad);
                line.addView(button, params);
            }
            keypad.addView(line, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (int) (getResources().getDisplayMetrics().density * 60)));
        }
        //界面缩放调大后 5×60dp 会超出屏幕，Space 的 weight 被压到 0，最后一行直接裁掉；
        //布局完成后按实际可用高度收缩（正常缩放时仍保持 60dp 外观）
        keypad.post(this::fitKeypadToScreen);
    }

    /**
     * 让 4×5 键盘始终完整落在屏幕内。
     *
     * <p>可用高度按「显示区底部 → 屏幕底部（减去根布局 paddingBottom）」算，
     * 不能用 {@code keypad.getTop()}：键盘已经超出时它也会被布局到屏幕外，
     * 算出来的可用高度恒等于键盘自身高度，永远检测不到溢出。
     */
    private void fitKeypadToScreen() {
        LinearLayout keypad = findViewById(R.id.calc_keypad);
        if (keypad == null || keypad.getChildCount() == 0) return;
        View parent = (View) keypad.getParent();
        View displayView = findViewById(R.id.calc_display);
        if (parent == null || displayView == null) return;
        if (parent.getHeight() <= 0 || displayView.getBottom() <= 0) {
            //首次 post 可能早于布局完成，最多重试 5 次（500ms），避免拿到 0 高度后不再收缩
            if (fitKeypadRetry++ < 5) keypad.postDelayed(this::fitKeypadToScreen, 100);
            return;
        }
        fitKeypadRetry = 0;

        int available = parent.getHeight() - parent.getPaddingBottom() - displayView.getBottom();
        if (available <= 0) return;

        int rows = keypad.getChildCount();
        int rowHeight = available / rows;
        int maxRowHeight = (int) (getResources().getDisplayMetrics().density * 60);
        int finalRowHeight = Math.min(maxRowHeight, rowHeight);
        if (finalRowHeight <= 0) return;

        for (int i = 0; i < rows; i++) {
            View line = keypad.getChildAt(i);
            ViewGroup.LayoutParams lp = line.getLayoutParams();
            lp.height = finalRowHeight;
            line.setLayoutParams(lp);
            if (!(line instanceof LinearLayout)) continue;
            LinearLayout row = (LinearLayout) line;
            for (int j = 0; j < row.getChildCount(); j++) {
                View key = row.getChildAt(j);
                if (key instanceof Button) {
                    //字号跟随实际行高，避免行被压矮后文字上下被裁
                    ((Button) key).setTextSize(TypedValue.COMPLEX_UNIT_PX, finalRowHeight * 0.33f);
                }
            }
        }
    }

    /**
     * 圆屏适配（用户反馈 2026-11-07：手表上按键太小、输入框太大）。
     *
     * <p>只缩首末两行：圆的最宽处在中段，中间三行保持满宽（按键最大），
     * 上下两行各向内收一点，避免四个角落的键落进圆外的裁切区。
     * 显示区字号同时压到 26sp，把省下来的高度让给键盘。
     */
    private void applyRoundLayout() {
        boolean round = false;
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
                round = getResources().getConfiguration().isScreenRound();
        } catch (Throwable ignored) {
        }
        if (!round) return;

        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        ViewGroup keypad = findViewById(R.id.calc_keypad);
        if (keypad == null) return;

        display.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);

        int rowInset = (int) (dm.widthPixels * 0.10f);
        if (keypad.getChildCount() > 0)
            keypad.getChildAt(0).setPadding(rowInset, 0, rowInset, 0);
        int last = keypad.getChildCount() - 1;
        if (last > 0)
            keypad.getChildAt(last).setPadding(rowInset, 0, rowInset, 0);

        View root = (View) keypad.getParent();
        if (root != null)
            root.setPadding(root.getPaddingLeft(), (int) (dm.heightPixels * 0.03f),
                    root.getPaddingRight(), (int) (dm.heightPixels * 0.02f));
    }

    private static boolean isOperator(String label) {
        return "÷".equals(label) || "×".equals(label) || "−".equals(label) || "+".equals(label);
    }

    @SuppressLint({"SetTextI18n", "DefaultLocale"})
    private void onKey(String key) {
        //按键震动：设置页的「按键震动」开关真实控制这里（默认开，像普通计算器一样）
        if (SharedPreferencesUtil.getBoolean(CalcSettingsActivity.KEY_CALC_HAPTIC, true))
            display.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
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
