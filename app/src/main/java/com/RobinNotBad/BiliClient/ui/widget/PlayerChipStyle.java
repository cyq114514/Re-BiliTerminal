package com.RobinNotBad.BiliClient.ui.widget;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.ui.theme.ThemeManager;

/**
 * 播放器选择器 chip 的统一着色出口。
 *
 * <p>选中态 = 主题强调色实心 + 白字（对齐全站筛选胶囊）；未选中 = 卡片灰底 + 发丝描边。
 * 只用于播放器浮层里的 {@code cell_player_chip}，不改番剧/直播共用的 {@code cell_episode}。
 *
 * <p>另外两个 {@code applyLegacy*} 出口给「旧版播放器控制栏样式」用：那是 1.3.9 的浮层外观，
 * 条目是 {@code cell_page_item} / {@code cell_subtitle} / {@code cell_episode}，着色规则与当前相反
 * （选中染文字、不铺强调色底）。两套规则都收在这个类里，适配器只按开关二选一。
 */
public final class PlayerChipStyle {

    private PlayerChipStyle() {
    }

    public static void apply(TextView chip, boolean selected) {
        if (chip == null) return;
        chip.setTextColor(ContextCompat.getColor(chip.getContext(), R.color.textwhite));
        if (selected) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(chip.getResources().getDimension(R.dimen.radius_chip));
            bg.setColor(ThemeManager.getPrimary());
            chip.setBackground(bg);
        } else {
            chip.setBackgroundResource(R.drawable.player_pill_bg);
        }
    }

    /**
     * 旧版（1.3.9）分P条目着色：选中 = 主题色文字 + 实心卡片底，未选 = 白字 + 透明卡片底。
     *
     * <p>底色挂在 {@code itemView} 上：{@code cell_page_item} 的根是包裹 TextView 的 LinearLayout，
     * 卡片底与点击区都在那一层。
     */
    public static void applyLegacyCard(TextView text, View itemView, boolean selected) {
        if (text == null) return;
        text.setTextColor(selected
                ? ThemeManager.getPrimary()
                : ContextCompat.getColor(text.getContext(), R.color.textwhite));
        if (itemView != null) {
            itemView.setBackgroundResource(selected
                    ? R.drawable.background_card
                    : R.drawable.background_card_borderless);
        }
    }

    /**
     * 旧版（1.3.9）按钮条目着色（字幕/清晰度/视角）：选中 = 深字 + 亮底，未选 = 白字 + 深底。
     *
     * <p>色值走 {@code res/color/background_button} 与 {@code background_button_selected}
     * 两个 ColorStateList——1.4.0 换肤后仍然保留（{@code MediaEpisodeAdapter} 在用），
     * 因此这里能原样复现旧观感，不必新增颜色 token。
     */
    public static void applyLegacyButton(TextView text, boolean selected) {
        if (text == null) return;
        Context context = text.getContext();
        if (selected) {
            text.setTextColor(0xcc262626);
        } else {
            text.setTextColor(ContextCompat.getColor(context, R.color.textwhite));
        }
        ViewCompat.setBackgroundTintList(text, AppCompatResources.getColorStateList(context,
                selected ? R.color.background_button_selected : R.color.background_button));
    }
}