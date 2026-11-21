package com.RobinNotBad.BiliClient.ui.theme;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.content.res.AppCompatResources;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

/**
 * 卡片 / 胶囊按钮的全局背景透明度。
 *
 * <p>只改背景（MaterialCardView 的卡片底、MaterialButton 的 backgroundTint、
 * 以及排序/筛选/播放器胶囊的 drawable alpha），<b>不改文字和图标</b>——
 * 直接 View.setAlpha 会把整块内容一起变淡，标题和图标也跟着糊，不能用。
 *
 * <p>口径：0 = 当前效果，100 = 完全透明；默认 0，未开过这个设置的用户
 * 一次树遍历都不做（{@link #everApplied} 快路径）。
 *
 * <p>应用点：
 * <ul>
 *   <li>BaseActivity 内容 inflate 后 / onStart 返回页面时；</li>
 *   <li>RecyclerView 子项 attach 时（{@link #installOnLists(View)}）；</li>
 *   <li>AdaptiveDialog / ShareToMsgDialog 创建后。</li>
 * </ul>
 */
public final class UiAlpha {

    /** 排序/筛选/播放器胶囊等“胶囊底”的资源白名单；MaterialCardView 不需要列进来 */
    private static final int[] PILL_RES = {
            R.drawable.sel_filter_chip,
            R.drawable.sel_refresh_chip,
            R.drawable.player_side_pill_bg,
            R.drawable.player_pill_bg,
            R.drawable.player_accent_chip_bg,
            R.drawable.player_circle_bg,
            R.drawable.player_chrome_bg,
            R.drawable.player_panel_bg,
            R.drawable.bg_share_more,
            R.drawable.background_card_soft,
            R.drawable.bg_episode_card,
            R.drawable.background_card_gradient_border,
            R.drawable.background_searchhistory,
            R.drawable.background_privatemsg_aux,
            R.drawable.background_menu_button,
    };

    private static Drawable.ConstantState[] pillStates;
    /** 一旦有人用过非 0 透明度，之后每次 apply 都要走树：否则别的页面还停留在旧透明度 */
    private static boolean everApplied;

    private UiAlpha() {
    }

    public static void apply(View root) {
        if (root == null) return;
        int percent = SharedPreferencesUtil.getInt(SharedPreferencesUtil.UI_PANEL_ALPHA, 0);
        if (percent < 0) percent = 0;
        if (percent > 100) percent = 100;
        if (percent == 0 && !everApplied) return;
        if (percent > 0) everApplied = true;
        applyTree(root, percent);
    }

    /** 给树里所有 RecyclerView 装“子项 attach 即应用”的监听（幂等）。 */
    public static void installOnLists(View root) {
        int percent = SharedPreferencesUtil.getInt(SharedPreferencesUtil.UI_PANEL_ALPHA, 0);
        if (percent == 0 && !everApplied) return;
        if (root instanceof RecyclerView) {
            final RecyclerView list = (RecyclerView) root;
            if (list.getTag(R.id.ui_alpha_list_installed) == null) {
                list.setTag(R.id.ui_alpha_list_installed, Boolean.TRUE);
                list.addOnChildAttachStateChangeListener(
                        new RecyclerView.OnChildAttachStateChangeListener() {
                            @Override
                            public void onChildViewAttachedToWindow(View view) {
                                apply(view);
                            }

                            @Override
                            public void onChildViewDetachedFromWindow(View view) {
                            }
                        });
            }
            for (int i = 0; i < list.getChildCount(); i++) apply(list.getChildAt(i));
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) installOnLists(group.getChildAt(i));
        }
    }

    private static void applyTree(View view, int percent) {
        applyTo(view, percent);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyTree(group.getChildAt(i), percent);
            }
        }
    }

    private static void applyTo(View view, int percent) {
        Integer applied = (Integer) view.getTag(R.id.ui_alpha_applied);
        if (percent == 0) {
            if (applied != null) restore(view);
            return;
        }
        if (applied != null && applied == percent) return;

        if (view instanceof MaterialCardView) {
            MaterialCardView card = (MaterialCardView) view;
            ColorStateList base = (ColorStateList) view.getTag(R.id.ui_alpha_base_color);
            if (base == null) {
                base = card.getCardBackgroundColor();
                view.setTag(R.id.ui_alpha_base_color, base);
            }
            card.setCardBackgroundColor(fade(base.getDefaultColor(), percent));

            Integer stroke = (Integer) view.getTag(R.id.ui_alpha_base_stroke);
            if (stroke == null) {
                stroke = card.getStrokeColor();
                view.setTag(R.id.ui_alpha_base_stroke, stroke);
            }
            card.setStrokeColor(fade(stroke, percent));
        } else if (view instanceof MaterialButton) {
            MaterialButton button = (MaterialButton) view;
            ColorStateList base = (ColorStateList) view.getTag(R.id.ui_alpha_base_tint);
            if (base == null) {
                base = button.getBackgroundTintList();
                view.setTag(R.id.ui_alpha_base_tint, base);
            }
            if (base != null) {
                button.setBackgroundTintList(fadeTint(base, percent));
            } else {
                fadeBackground(view, percent);
            }
        } else if (isPillBackground(view)) {
            fadeBackground(view, percent);
        }

        view.setTag(R.id.ui_alpha_applied, percent);
    }

    private static void restore(View view) {
        if (view instanceof MaterialCardView) {
            MaterialCardView card = (MaterialCardView) view;
            Object base = view.getTag(R.id.ui_alpha_base_color);
            if (base instanceof ColorStateList) card.setCardBackgroundColor((ColorStateList) base);
            Object stroke = view.getTag(R.id.ui_alpha_base_stroke);
            if (stroke instanceof Integer) card.setStrokeColor((Integer) stroke);
        } else if (view instanceof MaterialButton) {
            Object tint = view.getTag(R.id.ui_alpha_base_tint);
            if (tint instanceof ColorStateList) {
                ((MaterialButton) view).setBackgroundTintList((ColorStateList) tint);
            }
        }
        Object alpha = view.getTag(R.id.ui_alpha_base_alpha);
        if (alpha instanceof Integer && view.getBackground() != null) {
            view.getBackground().mutate().setAlpha((Integer) alpha);
        }
        view.setTag(R.id.ui_alpha_applied, null);
    }

    private static boolean isPillBackground(View view) {
        Drawable bg = view.getBackground();
        if (bg == null) return false;
        Drawable.ConstantState state = bg.getConstantState();
        if (state == null) return false;
        if (pillStates == null) initPillStates(view.getContext());
        if (pillStates == null) return false;
        for (Drawable.ConstantState candidate : pillStates) {
            if (candidate != null && candidate == state) return true;
        }
        return false;
    }

    private static synchronized void initPillStates(Context context) {
        if (pillStates != null) return;
        Drawable.ConstantState[] states = new Drawable.ConstantState[PILL_RES.length];
        for (int i = 0; i < PILL_RES.length; i++) {
            Drawable d = AppCompatResources.getDrawable(context, PILL_RES[i]);
            if (d != null) states[i] = d.getConstantState();
        }
        pillStates = states;
    }

    private static void fadeBackground(View view, int percent) {
        Drawable bg = view.getBackground();
        if (bg == null) return;
        Integer baseAlpha = (Integer) view.getTag(R.id.ui_alpha_base_alpha);
        if (baseAlpha == null) {
            baseAlpha = bg.getAlpha();
            view.setTag(R.id.ui_alpha_base_alpha, baseAlpha);
        }
        bg.mutate().setAlpha(Math.round(baseAlpha * (100 - percent) / 100f));
    }

    private static int fade(int color, int percent) {
        int alpha = Math.round(Color.alpha(color) * (100 - percent) / 100f);
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static ColorStateList fadeTint(ColorStateList base, int percent) {
        int fallback = base.getDefaultColor();
        int disabled = base.getColorForState(new int[]{-android.R.attr.state_enabled}, fallback);
        int pressed = base.getColorForState(new int[]{android.R.attr.state_pressed}, fallback);
        int[][] states = {
                new int[]{-android.R.attr.state_enabled},
                new int[]{android.R.attr.state_pressed},
                new int[0],
        };
        int[] colors = {
                fade(disabled, percent),
                fade(pressed, percent),
                fade(fallback, percent),
        };
        return new ColorStateList(states, colors);
    }
}