package com.RobinNotBad.BiliClient.ui.theme;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.view.ViewCompat;
import androidx.core.widget.TextViewCompat;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.ui.widget.AmbientBackground;
import com.RobinNotBad.BiliClient.ui.widget.BiliCardView;

/**
 * 把布局里"写死的默认强调色"换成用户选的主题色。
 *
 * <p>为什么不在改造时把 21 处 {@code @color/bili_pink} 改成 Java 赋值：
 * 那会散落成几十处侵入式改动，且默认值一旦漏改就是视觉回归。这里保留 XML 原色作为**默认兜底**，
 * 只在用户改过主题色时做一次语义替换（方案 R1，见 docs/decisions-theme-color.md）。
 *
 * <p>判定规则是"精确值 + 白名单类型"，不是按数值无脑替换：
 * <ul>
 *   <li>只认 {@code #FE679A}（新版强调色）与它的两个派生资源（{@code card_bg_highlight}、
 *       {@code background_badge}），旧版粉 {@code #FB7299}、品牌蓝、语义色都不在白名单；</li>
 *   <li>只处理审计确认属于强调色的控件类型（TextView 文字色、按钮底、进度条、纯色/徽章底、箭头图标）。</li>
 * </ul>
 *
 * <p>默认主题下 {@link #apply(View)} 第一行就返回，零遍历、零开销，保证原版效果逐像素不变。
 *
 * <p><b>覆盖范围</b>：只覆盖"调用时已经在视图树里"的控件。RecyclerView 行、对话框、
 * Fragment 里的强调色需要在各自创建/绑定处显式调用一次 {@link #apply(View)}。
 */
public final class ThemeApplier {

    private static final int DEFAULT_PRIMARY = 0xFFFE679A;

    /** 徽章底/粉色箭头的 ConstantState 只解析一次：逐行逐控件重复解析矢量图会白烧 CPU */
    private static Drawable.ConstantState badgeState;
    private static Drawable.ConstantState arrowState;
    private static boolean refsLoaded = false;

    private ThemeApplier() {
    }

    /**
     * 递归应用主题色。
     *
     * @param root 已 inflate 完成（且已加入父容器亦无妨）的视图根
     */
    public static void apply(View root) {
        if (root == null) return;
        //默认主题（或关掉新版美学）：布局里的颜色本来就是正确值，直接跳过，避免无意义的树遍历
        if (ThemeManager.isDefault()) return;
        applyTree(root);
    }

    private static void applyTree(View view) {
        applyTo(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyTree(group.getChildAt(i));
            }
        }
    }

    private static void applyTo(View view) {
        //这两个组件自己盯着 ThemeManager，重复处理会把动画状态搅乱
        if (view instanceof BiliCardView || view instanceof AmbientBackground) return;

        final int primary = ThemeManager.getPrimary();
        final Context context = view.getContext();

        applyBackground(view, context, primary);

        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            if (textView.getCurrentTextColor() == DEFAULT_PRIMARY) {
                textView.setTextColor(primary);
            }
            applyCompoundDrawable(textView, context, primary);
        }

        if (view instanceof ProgressBar) {
            applyProgressTint((ProgressBar) view, primary);
        }

        //MaterialButton 的底是 backgroundTint（不是 background），单独处理
        ColorStateList bgTint = ViewCompat.getBackgroundTintList(view);
        if (bgTint != null && bgTint.getColorForState(view.getDrawableState(), 0) == DEFAULT_PRIMARY) {
            ViewCompat.setBackgroundTintList(view, ColorStateList.valueOf(primary));
        }
    }

    /** 背景色：纯色底（tab 指示条/开屏短线）与粉色徽章底 */
    private static void applyBackground(View view, Context context, int primary) {
        Drawable background = view.getBackground();
        if (background == null) return;

        if (background instanceof ColorDrawable) {
            if (((ColorDrawable) background).getColor() == DEFAULT_PRIMARY) {
                view.setBackgroundColor(primary);
            }
            return;
        }

        //徽章底按 drawable 身份识别（不是按颜色猜）：@drawable/background_badge 的实体色就是强调色
        if (background instanceof GradientDrawable && isBadgeBackground(background, context)) {
            GradientDrawable badge = new GradientDrawable();
            badge.setShape(GradientDrawable.RECTANGLE);
            badge.setColor(primary);
            badge.setCornerRadius(context.getResources().getDimension(R.dimen.badge_round));
            view.setBackground(badge);
        }
    }

    /**
     * 判断背景是不是 {@code @drawable/background_badge}。
     *
     * <p>用 ConstantState 比对而不是比较颜色：XML shape 走同一份缓存，ConstantState 是同一个实例，
     * 判定稳定；按颜色比则会把其他粉色形状误伤。
     */
    private static boolean isBadgeBackground(Drawable background, Context context) {
        Drawable.ConstantState ref = badgeState(context);
        if (ref == null) return false;
        Drawable.ConstantState cs = background.getConstantState();
        return cs != null && ref.equals(cs);
    }

    private static Drawable.ConstantState badgeState(Context context) {
        loadRefs(context);
        return badgeState;
    }

    /** 只解析一次两个参照 drawable 的 ConstantState（跨页面复用） */
    private static void loadRefs(Context context) {
        if (refsLoaded) return;
        refsLoaded = true;
        try {
            Drawable badge = AppCompatResources.getDrawable(context, R.drawable.background_badge);
            if (badge != null) badgeState = badge.getConstantState();
            Drawable arrow = AppCompatResources.getDrawable(context, R.drawable.arrow_forward_pink);
            if (arrow != null) arrowState = arrow.getConstantState();
        } catch (Throwable t) {
            //解析失败就当作"认不出来"，保持原色，不影响页面
        }
    }

    /** 复合图标里的粉色箭头（{@code arrow_forward_pink}）：按 drawable 身份识别后改色 */
    private static void applyCompoundDrawable(TextView textView, Context context, int primary) {
        //先处理 app:drawableTint 声明（`app:drawableTint="@color/bili_pink"`）：接口在 API 23 起才有
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                ColorStateList tint = TextViewCompat.getCompoundDrawableTintList(textView);
                if (tint != null && tint.getColorForState(textView.getDrawableState(), 0) == DEFAULT_PRIMARY) {
                    TextViewCompat.setCompoundDrawableTintList(textView, ColorStateList.valueOf(primary));
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            Drawable[] drawables = TextViewCompat.getCompoundDrawablesRelative(textView);
            if (drawables == null) return;
            loadRefs(context);
            if (arrowState == null) return;

            boolean changed = false;
            for (int i = 0; i < drawables.length; i++) {
                Drawable d = drawables[i];
                if (d == null || d.getConstantState() == null) continue;
                if (!arrowState.equals(d.getConstantState())) continue;
                Drawable tinted = DrawableCompat.wrap(d.mutate());
                DrawableCompat.setTint(tinted, primary);
                drawables[i] = tinted;
                changed = true;
            }
            if (changed) {
                TextViewCompat.setCompoundDrawablesRelative(textView, drawables[0], drawables[1], drawables[2], drawables[3]);
            }
        } catch (Throwable ignored) {
            //图标没换色不影响文字，静默跳过
        }
    }

    /** 进度条（短视频页的粉色进度）：API 21 以下没有 tint 接口，保持原样 */
    private static void applyProgressTint(ProgressBar progressBar, int primary) {
        if (Build.VERSION.SDK_INT < 21) return;
        try {
            ColorStateList tint = progressBar.getProgressTintList();
            if (tint != null && tint.getColorForState(progressBar.getDrawableState(), 0) == DEFAULT_PRIMARY) {
                progressBar.setProgressTintList(ColorStateList.valueOf(primary));
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 开关的激活色。Material 的 {@code colorControlActivated} 是主题属性，运行时无法改，
     * 只能给每个开关单独上 tint（{@link SwitchCompat} 的 tint 接口在 appcompat 里是全 API 可用的）。
     *
     * <p>做法是"只换 RGB、保留原有 alpha 与关态/禁用态颜色"：从主题给出的 ColorStateList 里
     * 读出选中态/常态/禁用态三个值，选中态的 RGB 换成主题色、alpha 原样保留。这样不用猜
     * Material 的轨道透明度，也不会把关态的颜色改错。
     *
     * @param button  SwitchMaterial / SwitchCompat 实例
     * @param primary 主题强调色
     */
    public static void applySwitch(android.widget.CompoundButton button, int primary) {
        if (!(button instanceof SwitchCompat)) return;
        if (ThemeManager.isDefault()) return;   //默认主题不动，保持主题原有渲染
        SwitchCompat sw = (SwitchCompat) button;
        try {
            ColorStateList thumb = sw.getThumbTintList();
            if (thumb != null) {
                ColorStateList rebuilt = replaceCheckedColor(thumb, primary);
                if (rebuilt != null) sw.setThumbTintList(rebuilt);
            } else {
                //本项目的 switchStyle 继承 AppCompat 基础样式，部分设备/版本上拿不到 tint 列表，
                //此时用主题里的关态色 + 主题色自己拼一份，保证开关确实跟随
                sw.setThumbTintList(buildThumbFallback(sw.getContext(), primary));
            }
            ColorStateList track = sw.getTrackTintList();
            if (track != null) {
                ColorStateList rebuilt = replaceCheckedColor(track, primary);
                if (rebuilt != null) sw.setTrackTintList(rebuilt);
            } else {
                sw.setTrackTintList(buildTrackFallback(sw.getContext(), primary));
            }
        } catch (Throwable ignored) {
            //拿不到 tint 就维持主题默认色：颜色不完全跟随，但绝不崩
        }
    }

    /** 关态/选中态三档（启用+选中 / 启用 / 禁用），与 SwitchCompat 的判定顺序一致 */
    private static final int[][] SWITCH_STATES = {
            {android.R.attr.state_enabled, android.R.attr.state_checked},
            {android.R.attr.state_enabled},
            {-android.R.attr.state_enabled}
    };

    /** 滑块：选中态用主题色，关态取主题里的 colorSwitchThumbNormal，禁用态同色半透明 */
    private static ColorStateList buildThumbFallback(Context context, int primary) {
        int off = themeColor(context, androidx.appcompat.R.attr.colorSwitchThumbNormal, 0xFFFFFFFF);
        int disabled = (off & 0x00FFFFFF) | 0x80000000;
        return new ColorStateList(SWITCH_STATES, new int[]{primary, off, disabled});
    }

    /** 轨道：选中态用主题色约 40% alpha（Material 开关轨道的观感），关态取 colorControlNormal */
    private static ColorStateList buildTrackFallback(Context context, int primary) {
        int off = themeColor(context, androidx.appcompat.R.attr.colorControlNormal, 0x33FFFFFF);
        int on = (primary & 0x00FFFFFF) | 0x66000000;
        return new ColorStateList(SWITCH_STATES, new int[]{on, off, 0x00000000});
    }

    /** 读主题属性的颜色；取不到就用给定的兜底值 */
    private static int themeColor(Context context, int attr, int fallback) {
        android.content.res.TypedArray ta = null;
        try {
            ta = context.obtainStyledAttributes(new int[]{attr});
            return ta.getColor(0, fallback);
        } catch (Throwable t) {
            return fallback;
        } finally {
            if (ta != null) ta.recycle();
        }
    }

    /**
     * 把 ColorStateList 里"选中态"的 RGB 换成新色，alpha 与其他态保持原值。
     * 原始列表为空（主题没给 tint）时返回 null，交由调用方保持原样。
     */
    private static ColorStateList replaceCheckedColor(ColorStateList source, int newRgb) {
        if (source == null) return null;
        final int[] checked = {android.R.attr.state_checked};
        int checkedColor = source.getColorForState(checked, 0);
        if (checkedColor == 0) return null;
        int normalColor = source.getColorForState(new int[]{}, checkedColor);
        int disabledColor = source.getColorForState(new int[]{-android.R.attr.state_enabled}, normalColor);

        int newChecked = (checkedColor & 0xFF000000) | (newRgb & 0x00FFFFFF);
        int[][] states = {
                {android.R.attr.state_enabled, android.R.attr.state_checked},
                {android.R.attr.state_enabled},
                {-android.R.attr.state_enabled}
        };
        int[] colors = {newChecked, normalColor, disabledColor};
        return new ColorStateList(states, colors);
    }
}
