package com.RobinNotBad.BiliClient.activity.privacy;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.CalculatorActivity;
import com.RobinNotBad.BiliClient.activity.SplashActivity;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.util.AdaptiveDialog;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.PrivacyLockUtil;
import com.google.android.material.switchmaterial.SwitchMaterial;

/**
 * 隐私与伪装设置页：启动密码锁开关、设置/修改密码、桌面图标伪装开关。
 *
 * <p>三条硬规则（用户拍板过的取舍，别图省事改掉）：
 * ① 关锁 / 改密码必须先验证现有密码——否则别人在设置页三秒就把锁关了，功能形同虚设；
 * ② 关锁时如果伪装还开着必须联动关掉——否则桌面图标还是计算器，点进去却没有密码可验，
 * 用户会被永远扣在计算器页；
 * ③ 图标别名切换必须「先启用目标别名、再禁用当前别名」——顺序反了中途被杀，桌面就一个入口都没了。
 * ④ 别名组件名必须从系统反查（{@link #resolveAlias}），不许用 getPackageName() 拼字符串——
 *    namespace（com.RobinNotBad.BiliClient）与 applicationId（...BiliClient.re）不一致，
 *    拼出来的组件在系统里不存在，异常还会被 catch 吞成「开关自己弹回去」。
 *
 * <p>密码规则：纯数字 4-12 位（计算器上按得出来），只存加盐 SHA-256（见 PrivacyLockUtil）。
 */
public class PrivacyLockSettingsActivity extends BaseActivity {

    private static final String TAG = "privacy";

    /**别名全名兜底值：正常由 {@link #resolveAlias} 从系统反查得到，这里只在反查失败时用（见规则④）。 */
    private static final String FALLBACK_ALIAS_DEFAULT = "com.RobinNotBad.BiliClient.LauncherDefault";
    private static final String FALLBACK_ALIAS_CALC = "com.RobinNotBad.BiliClient.LauncherCalculator";

    private SwitchMaterial lockSwitch;
    private SwitchMaterial disguiseSwitch;
    private TextView changePasswordDesc;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_privacy_lock);
        setPageName("隐私与伪装");

        lockSwitch = findViewById(R.id.lock_switch);
        disguiseSwitch = findViewById(R.id.disguise_switch);
        changePasswordDesc = findViewById(R.id.change_password_desc);

        findViewById(R.id.change_password).setOnClickListener(view -> onChangePasswordClick());

        lockSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> onLockSwitchChanged(isChecked));
        disguiseSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> onDisguiseSwitchChanged(isChecked));

        initTutorial();

        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        //从桌面回来（去验证图标变了没）时别名状态可能被系统/桌面改过，重读一遍
        refreshUi();
    }

    /**按当前数据重画两个开关（先摘监听再拨回去，避免 setChecked 触发回调写回数据）。 */
    private void refreshUi() {
        lockSwitch.setOnCheckedChangeListener(null);
        lockSwitch.setChecked(PrivacyLockUtil.isSwitchOn());
        disguiseSwitch.setOnCheckedChangeListener(null);
        disguiseSwitch.setChecked(isDisguiseOn());
        changePasswordDesc.setText(PrivacyLockUtil.hasPassword() ? "已设置（修改需先验证现有密码）" : "未设置");
        reattachListeners();
    }

    private void reattachListeners() {
        lockSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> onLockSwitchChanged(isChecked));
        disguiseSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> onDisguiseSwitchChanged(isChecked));
    }

    private void onLockSwitchChanged(boolean isChecked) {
        if (isChecked) {
            if (PrivacyLockUtil.hasPassword()) {
                PrivacyLockUtil.setEnabled(true);
                MsgUtil.showMsg("密码锁已开启");
            } else {
                MsgUtil.showMsg("请先设置密码");
                showSetPasswordDialog(); //设置成功后回调里会一并把开关打开
            }
        } else {
            showVerifyDialog("关闭密码锁", "请输入现有密码以确认关闭", () -> {
                //联动关闭伪装（见类注释规则②）：伪装开着而锁关了，用户会被扣在计算器页
                if (isDisguiseOn()) {
                    applyDisguise(false);
                    MsgUtil.showMsgLong("已同时关闭计算器伪装，桌面图标即将恢复");
                }
                PrivacyLockUtil.setEnabled(false);
                MsgUtil.showMsg("密码锁已关闭");
                //数据层已变，必须立刻重画开关，否则 UI 停留在 ON 直到离开本页（onResume 才纠正）
                refreshUi();
            });
        }
        refreshUi();
    }

    private void onChangePasswordClick() {
        if (!PrivacyLockUtil.hasPassword()) {
            showSetPasswordDialog();
        } else {
            //修改密码 = 验旧 + 设新，三个输入框全部显示
            View view = inflatePasswordDialog(true, true, true);
            EditText oldInput = view.findViewById(R.id.password_old);
            EditText newInput = view.findViewById(R.id.password_new);
            EditText confirmInput = view.findViewById(R.id.password_confirm);
            new AdaptiveDialog.Builder(this)
                    .setTitle("修改密码")
                    .setView(view)
                    .setPositiveButton("确定", (dialog, which) -> {
                        if (!PrivacyLockUtil.verify(oldInput.getText().toString())) {
                            MsgUtil.showMsgLong("现有密码不正确");
                            return;
                        }
                        if (saveNewPassword(newInput, confirmInput)) {
                            MsgUtil.showMsg("密码已修改");
                            refreshUi();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        }
    }

    /**首次设置密码（两栏：新密码 / 确认）。成功后自动把锁打开。 */
    private void showSetPasswordDialog() {
        View view = inflatePasswordDialog(false, true, true);
        EditText newInput = view.findViewById(R.id.password_new);
        EditText confirmInput = view.findViewById(R.id.password_confirm);
        new AdaptiveDialog.Builder(this)
                .setTitle("设置密码")
                .setView(view)
                .setPositiveButton("确定", (dialog, which) -> {
                    if (saveNewPassword(newInput, confirmInput)) {
                        PrivacyLockUtil.setEnabled(true);
                        MsgUtil.showMsgLong("密码已设置，密码锁已开启。忘了密码只能清除应用数据，请牢记！");
                        refreshUi();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**复用密码对话框（dialog_privacy_password.xml），按模式显隐三个输入框。 */
    private View inflatePasswordDialog(boolean showOld, boolean showNew, boolean showConfirm) {
        View view = getLayoutInflater().inflate(R.layout.dialog_privacy_password, null);
        view.findViewById(R.id.password_old).setVisibility(showOld ? View.VISIBLE : View.GONE);
        view.findViewById(R.id.password_new).setVisibility(showNew ? View.VISIBLE : View.GONE);
        view.findViewById(R.id.password_confirm).setVisibility(showConfirm ? View.VISIBLE : View.GONE);
        return view;
    }

    /**校验并落库新密码；不合法时给提示并返回 false（弹窗已关，用户重开再试）。 */
    private boolean saveNewPassword(EditText newInput, EditText confirmInput) {
        String newPwd = newInput.getText().toString();
        String confirm = confirmInput.getText().toString();
        if (!newPwd.matches("\\d{4,12}")) {
            MsgUtil.showMsgLong("密码需为 4-12 位纯数字");
            return false;
        }
        if (!newPwd.equals(confirm)) {
            MsgUtil.showMsgLong("两次输入的密码不一致");
            return false;
        }
        if (PrivacyLockUtil.verify(newPwd)) {
            MsgUtil.showMsgLong("新密码不能与现有密码相同");
            return false;
        }
        PrivacyLockUtil.setPassword(newPwd);
        return true;
    }

    /**需要现有密码确认的操作（关锁等）：验对才执行回调。 */
    private void showVerifyDialog(String title, String message, Runnable onVerified) {
        View view = inflatePasswordDialog(true, false, false);
        EditText oldInput = view.findViewById(R.id.password_old);
        new AdaptiveDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setView(view)
                .setPositiveButton("确定", (dialog, which) -> {
                    if (PrivacyLockUtil.verify(oldInput.getText().toString())) {
                        onVerified.run();
                    } else {
                        MsgUtil.showMsgLong("密码不正确");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void onDisguiseSwitchChanged(boolean isChecked) {
        if (isChecked && !PrivacyLockUtil.isEnabled()) {
            MsgUtil.showMsgLong("请先开启启动密码锁并设置密码");
            refreshUi();
            return;
        }
        new AdaptiveDialog.Builder(this)
                .setTitle(isChecked ? "开启计算器伪装" : "关闭计算器伪装")
                .setMessage(isChecked
                        ? "桌面图标与名称将变为「计算器」，点开后是真计算器，输入密码按等号进入应用。\n\n注意：部分桌面图标刷新有延迟；从最近任务切回不会重新上锁。恢复入口在本页。"
                        : "桌面图标与名称将恢复为「Re：哔哩终端」，部分桌面图标刷新有延迟。")
                .setPositiveButton("确定", (dialog, which) -> {
                    //失败时不报成功（原来无论成败都弹「已伪装为计算器」，把 bug 藏成了「开关自己弹回去」）
                    if (applyDisguise(isChecked)) {
                        MsgUtil.showMsgLong(isChecked ? "已伪装为计算器" : "已恢复原图标");
                    }
                })
                .setNegativeButton("取消", null)
                .setOnDismissListener(dialog -> refreshUi())
                .show();
    }

    /**使用说明的折叠开关（默认收起，不抢开关的注意力）。 */
    private void initTutorial() {
        View header = findViewById(R.id.tutorial_header);
        TextView body = findViewById(R.id.tutorial_body);
        TextView arrow = findViewById(R.id.tutorial_arrow);
        header.setOnClickListener(view -> {
            boolean expand = body.getVisibility() != View.VISIBLE;
            body.setVisibility(expand ? View.VISIBLE : View.GONE);
            arrow.setText(expand ? "▾" : "▸");
        });
    }

    /**
     * 桌面别名切换。必须先启用目标别名、再禁用当前别名（类注释规则③）：
     * 顺序反了或两步之间被杀，桌面就一个入口都不剩。
     *
     * @return 是否真的切成功了；返回 false 时不改开关状态，由调用方按失败提示，别报成功。
     */
    private boolean applyDisguise(boolean enable) {
        PackageManager pm = getPackageManager();
        ComponentName def = defaultAlias();
        ComponentName calc = calculatorAlias();
        try {
            if (enable) {
                setComponentEnabled(pm, calc, true);
                setComponentEnabled(pm, def, false);
            } else {
                setComponentEnabled(pm, def, true);
                setComponentEnabled(pm, calc, false);
            }
        } catch (Exception e) {
            Log.e(TAG, "切换桌面别名失败 target=" + (enable ? calc : def), e);
            MsgUtil.showMsgLong("伪装切换失败：" + e.getMessage());
            refreshUi();
            return false;
        }
        //落库后再读一次系统状态，确认真的生效（有些 ROM 会静默拒绝）
        boolean applied = isDisguiseOn() == enable;
        if (!applied) {
            Log.e(TAG, "别名状态与预期不符 enable=" + enable
                    + " calcState=" + pm.getComponentEnabledSetting(calc));
        }
        refreshUi();
        return applied;
    }

    private void setComponentEnabled(PackageManager pm, ComponentName component, boolean enabled) {
        pm.setComponentEnabledSetting(component,
                enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    /**伪装当前是否生效（读系统真实状态而不是自己存的偏好，桌面/系统改了也能对上）。 */
    private boolean isDisguiseOn() {
        return getPackageManager().getComponentEnabledSetting(calculatorAlias())
                == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    private ComponentName defaultAlias() {
        return resolveAlias(SplashActivity.class, FALLBACK_ALIAS_DEFAULT);
    }

    private ComponentName calculatorAlias() {
        return resolveAlias(CalculatorActivity.class, FALLBACK_ALIAS_CALC);
    }

    /**
     * 反查 activity-alias 的真实组件名，不要用 getPackageName() 拼（规则④）。
     *
     * <p>清单里别名写相对名（.LauncherCalculator），AGP 按 <b>namespace</b> 展开成
     * com.RobinNotBad.BiliClient.LauncherCalculator；getPackageName() 返回的却是
     * <b>applicationId</b>（com.RobinNotBad.BiliClient.re）。差一个后缀，拼出来的组件系统里不存在：
     * 2026-10-31 真机日志 IllegalArgumentException: Component class
     * com.RobinNotBad.BiliClient.re.LauncherCalculator does not exist in com.RobinNotBad.BiliClient.re。
     *
     * <p>改成按 targetActivity 从 PackageManager 反查：只要清单里的 targetActivity 不改，
     * 以后动 namespace / applicationId 都不会再错位。
     */
    private ComponentName resolveAlias(Class<?> targetActivity, String fallbackClass) {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_ACTIVITIES);
            if (info.activities != null) {
                for (ActivityInfo ai : info.activities) {
                    if (targetActivity.getName().equals(ai.targetActivity)) {
                        Log.d(TAG, "别名解析 " + targetActivity.getSimpleName() + " → " + ai.name);
                        return new ComponentName(getPackageName(), ai.name);
                    }
                }
            }
            Log.w(TAG, "未找到 targetActivity=" + targetActivity.getName() + " 的别名，回退硬编码");
        } catch (Exception e) {
            Log.w(TAG, "别名反查异常，回退硬编码", e);
        }
        return new ComponentName(getPackageName(), fallbackClass);
    }
}
