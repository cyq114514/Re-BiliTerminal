package com.RobinNotBad.BiliClient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 测试基础设施冒烟：确认 Robolectric 能在本机/CI 的 JVM 上提供真实 SharedPreferences。
 * 不验证业务逻辑，只保证后续测试依赖的框架能力可用。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class SmokeTest {

    @Test
    public void sharedPreferencesWorksOnJvm() {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences sp = context.getSharedPreferences("smoke", Context.MODE_PRIVATE);
        sp.edit().putString("k", "v").commit();
        assertEquals("v", sp.getString("k", ""));
        assertTrue(context.getPackageName().length() > 0);
    }
}