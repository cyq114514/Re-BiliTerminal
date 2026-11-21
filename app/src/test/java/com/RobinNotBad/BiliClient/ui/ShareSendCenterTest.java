package com.RobinNotBad.BiliClient.ui;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.core.app.ApplicationProvider;

import com.RobinNotBad.BiliClient.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * 「转发到消息」发送键居中回归：圆屏底部弦宽很窄，发送键贴右时只剩一条边；
 * 改成 center_horizontal 后必须落在整张面板的水平中心。
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 33)
public class ShareSendCenterTest {

    @Test
    public void sendButtonIsCentered() {
        Context context = new ContextThemeWrapper(
                ApplicationProvider.getApplicationContext(), R.style.Theme_BiliClient_BottomSheet);
        View root = LayoutInflater.from(context).inflate(R.layout.dialog_share_to_msg, null);

        root.measure(View.MeasureSpec.makeMeasureSpec(384, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST));
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());

        View send = root.findViewById(R.id.share_send);
        int sendCenter = send.getLeft() + send.getWidth() / 2;
        int rootCenter = root.getWidth() / 2;
        assertTrue("发送键必须水平居中：send=" + sendCenter + " root=" + rootCenter,
                Math.abs(sendCenter - rootCenter) <= 1);
    }
}