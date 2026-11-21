package com.RobinNotBad.BiliClient.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.RobinNotBad.BiliClient.BuildConfig;
import com.RobinNotBad.BiliClient.activity.MenuActivity;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertTrue;

/**
 * 启动请求尚未返回就打开主菜单时，未读数在请求返回后必须即时补到菜单上。
 *
 * <p>回归 2026-11-21 反馈：刚进应用点标题栏开菜单，动态/消息红点一直不出现。
 * 这里用「先开菜单，再改 SharedPreferences」模拟后台请求迟到，断言旧版菜单按钮文本更新。
 */
@RunWith(AndroidJUnit4.class)
public class MenuBadgeRefreshTest {

    @Test
    public void lateUnreadCountsUpdateOpenMenu() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("default", Context.MODE_PRIVATE);

        long oldMid = prefs.getLong(SharedPreferencesUtil.mid, 0);
        boolean oldNewUi = prefs.getBoolean(SharedPreferencesUtil.NEW_UI_DESIGN, true);
        String oldLastVersion = prefs.getString(SharedPreferencesUtil.last_version, "");
        int oldDynamic = prefs.getInt(SharedPreferencesUtil.DYNAMIC_UPDATE_NUM, 0);
        int oldMessage = prefs.getInt(SharedPreferencesUtil.MESSAGE_UPDATE_NUM, 0);
        boolean oldDynamicCheck = prefs.getBoolean(SharedPreferencesUtil.DYNAMIC_UPDATE_CHECK_ENABLE, true);
        boolean oldMessageCheck = prefs.getBoolean(SharedPreferencesUtil.MESSAGE_UPDATE_CHECK_ENABLE, true);
        boolean oldUpdateCheck = prefs.getBoolean(SharedPreferencesUtil.UPDATE_CHECK_ON_START, true);

        //旧版菜单用 MaterialButton 直接承载文字，断言比 RecyclerView 徽章更容易稳定
        prefs.edit()
                .putLong(SharedPreferencesUtil.mid, 1)
                .putBoolean(SharedPreferencesUtil.NEW_UI_DESIGN, false)
                .putString(SharedPreferencesUtil.last_version, BuildConfig.VERSION_NAME)
                .putInt(SharedPreferencesUtil.DYNAMIC_UPDATE_NUM, 0)
                .putInt(SharedPreferencesUtil.MESSAGE_UPDATE_NUM, 0)
                .putBoolean(SharedPreferencesUtil.DYNAMIC_UPDATE_CHECK_ENABLE, false)
                .putBoolean(SharedPreferencesUtil.MESSAGE_UPDATE_CHECK_ENABLE, false)
                .putBoolean(SharedPreferencesUtil.UPDATE_CHECK_ON_START, false)
                .commit();

        try (ActivityScenario<MenuActivity> scenario = ActivityScenario.launch(MenuActivity.class)) {
            //菜单先以 0 打开；随后模拟启动请求返回、后台任务写入未读数
            prefs.edit()
                    .putInt(SharedPreferencesUtil.DYNAMIC_UPDATE_NUM, 7)
                    .putInt(SharedPreferencesUtil.MESSAGE_UPDATE_NUM, 3)
                    .commit();

            AtomicReference<String> dynamicText = new AtomicReference<>("");
            long deadline = SystemClock.uptimeMillis() + 3000;
            while (SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity(activity ->
                        dynamicText.set(buttonText(activity, "dynamicButton")));
                if (dynamicText.get().contains("(7)")) break;
                Thread.sleep(50);
            }
            assertTrue("动态按钮应在请求返回后补上红点，实际: " + dynamicText.get(),
                    dynamicText.get().contains("(7)"));

            AtomicReference<String> messageText = new AtomicReference<>("");
            scenario.onActivity(activity ->
                    messageText.set(buttonText(activity, "messageButton")));
            assertTrue("消息按钮应在请求返回后补上红点，实际: " + messageText.get(),
                    messageText.get().contains("(3)"));
        } finally {
            prefs.edit()
                    .putLong(SharedPreferencesUtil.mid, oldMid)
                    .putBoolean(SharedPreferencesUtil.NEW_UI_DESIGN, oldNewUi)
                    .putString(SharedPreferencesUtil.last_version, oldLastVersion)
                    .putInt(SharedPreferencesUtil.DYNAMIC_UPDATE_NUM, oldDynamic)
                    .putInt(SharedPreferencesUtil.MESSAGE_UPDATE_NUM, oldMessage)
                    .putBoolean(SharedPreferencesUtil.DYNAMIC_UPDATE_CHECK_ENABLE, oldDynamicCheck)
                    .putBoolean(SharedPreferencesUtil.MESSAGE_UPDATE_CHECK_ENABLE, oldMessageCheck)
                    .putBoolean(SharedPreferencesUtil.UPDATE_CHECK_ON_START, oldUpdateCheck)
                    .commit();
        }
    }

    private static String buttonText(MenuActivity activity, String fieldName) {
        try {
            Field field = MenuActivity.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(activity);
            return value instanceof TextView ? ((TextView) value).getText().toString() : "";
        } catch (Exception e) {
            return "";
        }
    }
}