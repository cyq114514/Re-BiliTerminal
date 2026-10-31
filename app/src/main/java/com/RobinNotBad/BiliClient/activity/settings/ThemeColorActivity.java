package com.RobinNotBad.BiliClient.activity.settings;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.TextView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.ui.theme.ThemeManager;
import com.RobinNotBad.BiliClient.ui.widget.BiliCardView;
import com.RobinNotBad.BiliClient.ui.widget.ColorPickerView;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.google.android.material.button.MaterialButton;

/**
 * 主题配色：新版美学强调色的自定义入口。
 *
 * <p>只改"强调色"，不改任何 UI 结构：色块、输入框、按钮全部沿用现有设置页的卡片语言，
 * 预览区用的就是线上真实的组件（{@link BiliCardView} / 关注按钮形态 / 徽章底）。
 *
 * <p>生效口径（见 docs/decisions-theme-color.md）：本页即时刷新；其他页面需要重新进入。
 */
public class ThemeColorActivity extends BaseActivity {

    /** 预设配色：默认第一项就是原版 B 站粉 */
    private static final int[] PRESET_COLORS = {
            0xFFFE679A, 0xFF448AFF, 0xFF9575CD, 0xFF4DB6AC, 0xFFFFB74D,
            0xFFEF6C73, 0xFF26A69A, 0xFF5C8FD6, 0xFF9E86FF, 0xFF9CCC65
    };
    private static final String[] PRESET_NAMES = {
            "B站粉", "天空蓝", "薰衣草紫", "薄荷绿", "琥珀橙",
            "珊瑚红", "青色", "深海蓝", "紫罗兰", "青柠"
    };

    private EditText hexInput;
    private TextView stateText;
    private TextView accentText;
    private TextView badge;
    private MaterialButton previewButton;
    private BiliCardView highlightCard;
    private GridLayout presetContainer;
    private ColorPickerView colorPicker;
    /** 防止「调色盘 → 输入框 → 调色盘」互相触发形成死循环 */
    private boolean syncing;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        asyncInflate(R.layout.activity_setting_theme_color, (layoutView, resId) -> {
            hexInput = findViewById(R.id.hex_input);
            stateText = findViewById(R.id.theme_state);
            accentText = findViewById(R.id.preview_accent_text);
            badge = findViewById(R.id.preview_badge);
            previewButton = findViewById(R.id.preview_button);
            highlightCard = findViewById(R.id.preview_highlight_card);
            presetContainer = findViewById(R.id.preset_container);

            buildPresets();
            setUpColorPicker();

            findViewById(R.id.btn_apply_hex).setOnClickListener(view -> applyPickedColor());
            findViewById(R.id.btn_reset_theme).setOnClickListener(view -> {
                ThemeManager.resetToDefault();
                refreshPreview();
                MsgUtil.showMsg("已恢复默认配色\n其他页面重新进入后生效");
            });
            findViewById(R.id.btn_preview).setOnClickListener(view -> {
                Intent intent = new Intent();
                intent.setClass(ThemeColorActivity.this, UIPreviewActivity.class);
                startActivity(intent);
            });

            //高亮卡片展示的就是选中态，进页即高亮（与 UIPreviewActivity 一致）
            if (highlightCard != null) highlightCard.setHighlighted(true);
            refreshPreview();

            View scrollView = findViewById(R.id.scrollView);
            scrollView.setFocusable(true);
            scrollView.setFocusableInTouchMode(true);
            scrollView.requestFocus();
        });
    }

    // ---------------------------------------------------------------- 预设色块

    /**
     * 生成预设色块：5 列网格，每格一枚 40dp 圆点。
     * 选中态用一圈描边表示——与新版"半透明卡片 + 细描边"的语言一致，不引入新样式。
     */
    private void buildPresets() {
        if (presetContainer == null) return;
        presetContainer.removeAllViews();
        final int circleSize = dp(40);
        final int cellHeight = dp(52);

        for (int i = 0; i < PRESET_COLORS.length; i++) {
            final int color = PRESET_COLORS[i];
            final String name = PRESET_NAMES[i];

            FrameLayout cell = new FrameLayout(this);
            GridLayout.LayoutParams cellParams = new GridLayout.LayoutParams(
                    GridLayout.spec(i / 5), GridLayout.spec(i % 5, 1f));
            cellParams.width = 0;
            cellParams.height = cellHeight;
            cell.setLayoutParams(cellParams);

            View dot = new View(this);
            FrameLayout.LayoutParams dotParams = new FrameLayout.LayoutParams(circleSize, circleSize);
            dotParams.gravity = Gravity.CENTER;
            dot.setLayoutParams(dotParams);
            dot.setTag(color);
            dot.setOnClickListener(view -> {
                ThemeManager.setSeed(color);
                refreshPreview();
                MsgUtil.showMsg("已应用「" + name + "」\n其他页面重新进入后生效");
            });
            cell.addView(dot);
            presetContainer.addView(cell);
        }
    }

    /** 刷新色块选中环：当前种子色对应的那一枚加描边 */
    private void refreshSwatchSelection() {
        if (presetContainer == null) return;
        final int current = ThemeManager.getSeed();
        final int strokeColor = getResources().getColor(R.color.textwhite);
        for (int i = 0; i < presetContainer.getChildCount(); i++) {
            FrameLayout cell = (FrameLayout) presetContainer.getChildAt(i);
            if (cell.getChildCount() == 0) continue;
            View dot = cell.getChildAt(0);
            Object tag = dot.getTag();
            if (!(tag instanceof Integer)) continue;
            int color = (Integer) tag;

            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(color);
            if (color == (current | 0xFF000000)) {
                shape.setStroke(dp(2), strokeColor);
            }
            dot.setBackground(shape);
        }
    }

    // ---------------------------------------------------------------- 应用与预览

    /**
     * 调色盘接线：拖动只改本页预览与 HEX 文本，**不落盘**；点「应用」才写主题。
     */
    private void setUpColorPicker() {
        colorPicker = findViewById(R.id.color_picker);
        if (colorPicker == null) return;

        //小屏/手表上别让调色盘吃掉半屏，高度封顶为屏幕高的 1/3（仍可用 HEX 精确输入）
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        ViewGroup.LayoutParams params = colorPicker.getLayoutParams();
        params.height = Math.max(dp(110), Math.min(dp(180), screenHeight / 3));
        colorPicker.setLayoutParams(params);

        colorPicker.setOnColorChangedListener(color -> {
            if (syncing) return;
            syncing = true;
            if (hexInput != null) hexInput.setText(toHex(color));
            syncing = false;
            renderPreview(color, true);
        });

        if (hexInput != null) {
            hexInput.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    if (syncing) return;
                    Integer parsed = parseHex(s == null ? null : s.toString());
                    if (parsed == null) return;   //半截输入/非法输入：不联动，等用户点应用时再提示
                    syncing = true;
                    colorPicker.setColor(parsed);
                    syncing = false;
                    renderPreview(parsed, true);
                }
            });
        }
    }

    /**
     * 「应用」：优先用输入框里的合法 HEX（用户明确敲进来的值），否则用调色盘当前色。
     * 这是唯一会把选中的颜色写进主题的地方（预设圆点与恢复默认各自也是显式动作）。
     */
    private void applyPickedColor() {
        String raw = hexInput == null ? "" : hexInput.getText().toString().trim();
        Integer hexColor = parseHex(raw);
        if (!raw.isEmpty() && hexColor == null) {
            //非法输入只提示，不写盘、不崩
            MsgUtil.showMsg("格式不对，需要 6 位十六进制，例如 #448AFF");
            return;
        }
        int color = hexColor != null ? hexColor
                : (colorPicker != null ? colorPicker.getColor() : 0);
        if (color == 0) {
            MsgUtil.showMsg("请先在调色盘里选一个颜色");
            return;
        }
        if (!ThemeManager.setSeed(color)) {
            MsgUtil.showMsg("这个颜色不可用，换一个试试");
            return;
        }
        refreshPreview();
        MsgUtil.showMsg("已应用 " + toHex(color) + "\n其他页面重新进入后生效");
    }

    /** 解析 6 位 HEX；非法返回 null（不抛异常） */
    private static Integer parseHex(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (!text.matches("^#?[0-9a-fA-F]{6}$")) return null;
        try {
            String digits = text.startsWith("#") ? text.substring(1) : text;
            return 0xFF000000 | Integer.parseInt(digits, 16);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 按"已生效"的主题色刷新整个页面（预设点击、应用、恢复默认之后调用）。
     *
     * <p>必须逐项显式赋值：{@code ThemeApplier} 的规则是"把默认粉替换成主题色"，
     * 本页在应用新色时页面已经渲染过一版，再走一遍应用器不会命中，只能直接落色。
     */
    private void refreshPreview() {
        int seed = ThemeManager.getSeed();
        renderPreview(ThemeManager.getPrimary(), false);
        refreshSwatchSelection();

        //调色盘与输入框回到"已生效"的颜色
        syncing = true;
        if (colorPicker != null) colorPicker.setColor(seed);
        if (hexInput != null && !hexInput.hasFocus()) hexInput.setText(toHex(seed));
        syncing = false;

        if (stateText != null) {
            boolean newUi = SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.NEW_UI_DESIGN, true);
            if (!newUi) {
                stateText.setText("已保存 " + toHex(seed) + "，但「新版美学设计」已关闭，当前不生效");
            } else if (ThemeManager.isDefault()) {
                stateText.setText("当前为默认配色 " + toHex(seed) + "（与原版一致）");
            } else {
                stateText.setText("当前配色 " + toHex(seed) + "（" + nearestPresetName(seed) + "）");
            }
        }
    }

    /**
     * 只按给定颜色重画预览区（拖动调色盘/输入合法 HEX 时用，不读写持久化）。
     *
     * @param asPreview true 时连高亮卡片也临时落成这个颜色（尚未应用），false 时卡片交给
     *                  {@code ThemeManager} 自己刷新
     */
    private void renderPreview(int primary, boolean asPreview) {
        if (accentText != null) accentText.setTextColor(primary);

        if (previewButton != null) {
            //与「我的空间」关注按钮同一形态：主操作按钮吃主题色
            previewButton.setBackgroundTintList(ColorStateList.valueOf(primary));
        }

        if (badge != null) {
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.RECTANGLE);
            shape.setColor(primary);
            shape.setCornerRadius(getResources().getDimension(R.dimen.badge_round));
            badge.setBackground(shape);
        }

        if (highlightCard != null) {
            if (!highlightCard.isHighlighted()) highlightCard.setHighlighted(true);
            if (asPreview) {
                //临时色：底色沿用原高亮底的 10% alpha 口径
                highlightCard.setHighlightColorsForPreview(
                        (primary & 0x00FFFFFF) | 0x1A000000, primary);
            }
        }
    }

    private String nearestPresetName(int color) {
        for (int i = 0; i < PRESET_COLORS.length; i++) {
            if (PRESET_COLORS[i] == (color | 0xFF000000)) return PRESET_NAMES[i];
        }
        return "自定义";
    }

    private static String toHex(int color) {
        return String.format("#%06X", color & 0xFFFFFF);
    }

    private int dp(int value) {
        return (int) (getResources().getDisplayMetrics().density * value);
    }
}
