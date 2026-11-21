package com.RobinNotBad.BiliClient.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.core.app.ApplicationProvider;

import com.RobinNotBad.BiliClient.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * 播放器圆屏对称性回归：
 * <ul>
 *   <li>左列（-10s/倍速/+10s）与右列（弹幕/旋转/更多）同尺寸、同中心线；</li>
 *   <li>底排选P键隐藏时用末尾占位格补齐 5 格，播放键落在整行正中间。</li>
 * </ul>
 *
 * <p>只验证 XML 几何，不启动播放器/解码器；配合 PlayerActivity 的圆屏分支一起看。
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 33)
public class PlayerSymmetricLayoutTest {

    @Test
    public void sideColumnsAndBottomPlayAreSymmetric() {
        Context context = new ContextThemeWrapper(
                ApplicationProvider.getApplicationContext(), R.style.Theme_NoSwipe);
        View root = LayoutInflater.from(context).inflate(R.layout.activity_player, null);

        //倍速按钮在真实播放页的控制栏显示时是 VISIBLE；XML 默认 GONE，这里先还原
        root.findViewById(R.id.text_speed).setVisibility(View.VISIBLE);
        //Space 默认 INVISIBLE（轻量占位不绘制）；真实页面由 updateBottomSpacer() 按选P键显隐点亮
        View pageSelector = root.findViewById(R.id.button_page_selector);
        View spacer = root.findViewById(R.id.bottom_spacer);
        spacer.setVisibility(pageSelector.getVisibility() == View.VISIBLE
                ? View.GONE : View.VISIBLE);

        int size = 384;
        root.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, size, size);

        View seekBack = root.findViewById(R.id.button_seek_back);
        View danmaku = root.findViewById(R.id.danmaku_btn);
        assertEquals("左右按钮直径必须一致", seekBack.getWidth(), danmaku.getWidth());
        assertEquals("左右按钮高度必须一致", seekBack.getHeight(), danmaku.getHeight());

        View left = root.findViewById(R.id.left_control);
        View right = root.findViewById(R.id.right_control);
        int leftCenter = (left.getTop() + left.getBottom()) / 2;
        int rightCenter = (right.getTop() + right.getBottom()) / 2;
        assertTrue("左右两列中心线必须重合：left=" + leftCenter + " right=" + rightCenter,
                Math.abs(leftCenter - rightCenter) <= 1);

        View play = root.findViewById(R.id.button_video);
        ViewGroup row = (ViewGroup) play.getParent();
        int playCenter = play.getLeft() + play.getWidth() / 2;
        int rowCenter = row.getWidth() / 2;
        assertTrue("播放键必须在底排正中间：play=" + playCenter + " row=" + rowCenter,
                Math.abs(playCenter - rowCenter) <= 1);

        assertEquals("底排应有 5 个占位（选P键 GONE 时由末尾 spacer 补齐）：" + describeRow(row),
                5, countVisibleSlots(row));
    }

    private static String describeRow(ViewGroup row) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            out.append(i).append('=').append(child.getClass().getSimpleName())
                    .append('#').append(child.getId())
                    .append(",vis=").append(child.getVisibility()).append(';');
        }
        return out.toString();
    }

    private static int countVisibleSlots(ViewGroup row) {
        int visible = 0;
        for (int i = 0; i < row.getChildCount(); i++) {
            if (row.getChildAt(i).getVisibility() == View.VISIBLE) visible++;
        }
        return visible;
    }
}