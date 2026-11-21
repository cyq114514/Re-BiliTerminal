package com.RobinNotBad.BiliClient.ui.widget;

import android.graphics.drawable.GradientDrawable;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.ui.theme.ThemeManager;

/**
 * 播放器选择器 chip 的统一着色出口。
 *
 * <p>选中态 = 主题强调色实心 + 白字（对齐全站筛选胶囊）；未选中 = 卡片灰底 + 发丝描边。
 * 只用于播放器浮层里的 {@code cell_player_chip}，不改番剧/直播共用的 {@code cell_episode}。
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
}