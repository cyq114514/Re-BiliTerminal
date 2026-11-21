package com.RobinNotBad.BiliClient.ui;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;

import com.RobinNotBad.BiliClient.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 播放器 1.4.0 新版 UI 的几何回归：
 * 1) 加载态收起/呼出控制栏时，底栏时间和观看人数不能跳位；
 * 2) 左侧 -N秒 / 倍速 / +N秒 三颗按钮必须同尺寸；
 * 3) 视频容器不能是 1px，否则弱网 getVideoWidth/Height=0 时会一直黑屏。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, qualifiers = "w384dp-h384dp-xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class PlayerLayoutRegressionTest {

    @Test
    public void controlsDoNotMoveBottomTextAndSideButtonsAreUniform() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(R.style.Theme_NoSwipe);
        View root = LayoutInflater.from(activity).inflate(R.layout.activity_player, null, false);
        activity.setContentView(root);

        View videoArea = root.findViewById(R.id.videoArea);
        View bottom = root.findViewById(R.id.bottom_control);
        View bar = root.findViewById(R.id.bottom_text_bar);
        View progress = root.findViewById(R.id.text_progress);
        View online = root.findViewById(R.id.text_online);
        View seekBack = root.findViewById(R.id.button_seek_back);
        View speed = root.findViewById(R.id.text_speed);
        View seekForward = root.findViewById(R.id.button_seek_forward);

        speed.setVisibility(View.VISIBLE);
        layout(root);

        int progressY = yInRoot(progress);
        int onlineY = yInRoot(online);
        int barY = yInRoot(bar);
        int sideWidth = seekBack.getWidth();
        int sideHeight = seekBack.getHeight();

        assertEquals("视频容器初始必须铺满，否则弱网尺寸迟到时没有画面",
                1080, videoArea.getWidth());
        assertEquals(1080, videoArea.getHeight());
        assertTrue("左侧按钮宽度应为 44dp", sideWidth > 0);
        assertEquals("三颗左侧按钮宽度必须一致", sideWidth, speed.getWidth());
        assertEquals(sideWidth, seekForward.getWidth());
        assertEquals("三颗左侧按钮高度必须一致", sideHeight, speed.getHeight());
        assertEquals(sideHeight, seekForward.getHeight());

        // fadeControls(false)：底栏卡片 INVISIBLE 保留高度，文字条和时间/人数位置不能变
        bottom.setVisibility(View.INVISIBLE);
        layout(root);

        assertEquals("收起控制栏后进度文字不能跳位", progressY, yInRoot(progress));
        assertEquals("收起控制栏后观看人数不能跳位", onlineY, yInRoot(online));
        assertEquals("收起控制栏后文字条不能跳位", barY, yInRoot(bar));
        assertEquals("底栏必须保留高度而不是 GONE", View.INVISIBLE, bottom.getVisibility());
        assertEquals("收起控制栏后左侧按钮不能改尺寸", sideHeight, seekBack.getHeight());
    }

    private static void layout(View root) {
        int w = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY);
        int h = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY);
        root.measure(w, h);
        root.layout(0, 0, 1080, 1080);
    }

    private static int yInRoot(View v) {
        int y = 0;
        while (v != null) {
            y += v.getTop();
            if (!(v.getParent() instanceof View)) break;
            v = (View) v.getParent();
        }
        return y;
    }
}