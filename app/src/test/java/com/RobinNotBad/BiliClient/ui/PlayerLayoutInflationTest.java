package com.RobinNotBad.BiliClient.ui;

import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import com.RobinNotBad.BiliClient.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * 播放页布局冒烟：用播放器真实的 Theme.NoSwipe 在 JVM 上 inflate 一次。
 *
 * <p>原因见 activity_player.xml 里 MaterialCardView 的注释：NoSwipe 不是 MaterialComponents
 * 后代，任何 Material 容器/按钮混进来都会在 inflate 阶段直接抛异常，播放页整页起不来。
 * 这个测试不能证明观感，但能在编译之外挡住“布局根本装不上”的回归。
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 33)
public class PlayerLayoutInflationTest {

    @Test
    public void playerLayoutInflatesUnderNoSwipeTheme() {
        Context context = new androidx.appcompat.view.ContextThemeWrapper(
                ApplicationProvider.getApplicationContext(), R.style.Theme_NoSwipe);
        View root = LayoutInflater.from(context).inflate(R.layout.activity_player, null);

        assertNotNull(root.findViewById(R.id.root_layout));
        assertNotNull(root.findViewById(R.id.top));
        assertNotNull(root.findViewById(R.id.bottom_control));
        assertNotNull(root.findViewById(R.id.videoprogress));
        assertNotNull(root.findViewById(R.id.button_video));
        assertNotNull(root.findViewById(R.id.layout_speed));
        assertNotNull(root.findViewById(R.id.layout_volume));
        assertNotNull(root.findViewById(R.id.card_bg));
    }
}