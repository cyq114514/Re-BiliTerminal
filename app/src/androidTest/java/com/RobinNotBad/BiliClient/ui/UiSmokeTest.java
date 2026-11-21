package com.RobinNotBad.BiliClient.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.MenuActivity;
import com.RobinNotBad.BiliClient.activity.video.local.DownloadListActivity;
import com.RobinNotBad.BiliClient.activity.player.AudioQueueActivity;
import com.RobinNotBad.BiliClient.activity.search.SearchActivity;
import com.RobinNotBad.BiliClient.activity.settings.SettingMainActivity;
import com.RobinNotBad.BiliClient.activity.user.favorite.FavoriteFolderListActivity;
import com.RobinNotBad.BiliClient.activity.video.BangumiActivity;
import com.RobinNotBad.BiliClient.activity.video.BgmSubjectActivity;
import com.RobinNotBad.BiliClient.activity.video.RecommendActivity;
import com.RobinNotBad.BiliClient.activity.video.info.VideoInfoActivity;
import com.RobinNotBad.BiliClient.model.AudioQueue;
import com.RobinNotBad.BiliClient.model.VideoCard;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 多屏幕 UI 冒烟：只验证页面能启动、根视图可见且未越界、重建后不崩，并收集截图。
 *
 * <p>测试不登录、不写任何业务数据；启动前由 host 脚本写入：
 * setup=true（跳过首次设置）、crash_report_enable=false、usage_stats_enable=false，
 * 圆屏设备额外 player_ui_round=true。这里再兜底设置一次，避免 host 注入失败时污染生产上报。
 *
 * <p>为什么用 ActivityScenario 而不是从桌面一路点进去：本仓库入口依赖网络/登录态，
 * 直接启动目标 Activity 才能稳定区分「页面自身布局问题」与「首页网络加载问题」。
 */
@RunWith(AndroidJUnit4.class)
public class UiSmokeTest {

    private Context context;
    private UiDevice device;
    private File shotDir;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try {
            device.wakeUp();
        } catch (android.os.RemoteException ignored) {
            // 部分 Wear 镜像不支持唤醒；host 已通过 KEYCODE_WAKEUP/dismiss-keyguard 兜底
        }
        device.waitForIdle(1000);

        SharedPreferences prefs = context.getSharedPreferences("default", Context.MODE_PRIVATE);
        boolean round = context.getResources().getConfiguration().isScreenRound();
        String today = new SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(new Date());
        prefs.edit()
                .putBoolean("setup", true)
                .putBoolean("crash_report_enable", false)
                .putBoolean("crash_report_asked", true)
                .putBoolean("usage_stats_enable", false)
                .putString("usage_stats_last_day", today)
                .putBoolean("player_ui_round", round)
                .putFloat("dpi", 1.0F)
                .commit();

        shotDir = new File(context.getExternalFilesDir(null), "ui-shots");
        if (!shotDir.exists() && !shotDir.mkdirs()) {
            throw new AssertionError("无法创建截图目录：" + shotDir);
        }
    }

    @After
    public void tearDown() {
        AudioQueue.get().clear();
    }

    @Test(timeout = 180000)
    public void menuPage() {
        smokePage("menu", MenuActivity.class, new Intent(context, MenuActivity.class));
    }

    @Test(timeout = 180000)
    public void homeRecommendPage() {
        smokePage("home", RecommendActivity.class, new Intent(context, RecommendActivity.class));
    }

    @Test(timeout = 180000)
    public void bangumiPage() {
        smokePage("bangumi", BangumiActivity.class, new Intent(context, BangumiActivity.class));
    }

    @Test(timeout = 180000)
    public void bgmDetailPage() {
        Intent intent = new Intent(context, BgmSubjectActivity.class);
        intent.putExtra("bgmId", 1L);
        smokePage("bgm-detail", BgmSubjectActivity.class, intent);
    }

    @Test(timeout = 180000)
    public void videoDetailPage() {
        Intent intent = new Intent(context, VideoInfoActivity.class);
        intent.putExtra("aid", 2L);
        smokePage("video-detail", VideoInfoActivity.class, intent);
    }

    @Test(timeout = 180000)
    public void searchPage() {
        smokePage("search", new Intent(context, SearchActivity.class), false);
    }

    @Test(timeout = 180000)
    public void downloadsPage() {
        smokePage("downloads", DownloadListActivity.class, new Intent(context, DownloadListActivity.class));
    }

    @Test(timeout = 180000)
    public void settingsPage() {
        smokePage("settings", SettingMainActivity.class, new Intent(context, SettingMainActivity.class));
    }

    @Test(timeout = 180000)
    public void audioQueuePage() {
        smokePage("audio-queue", AudioQueueActivity.class, new Intent(context, AudioQueueActivity.class));
    }

    @Test(timeout = 180000)
    public void favoriteFolderPage() {
        smokePage("favorites", FavoriteFolderListActivity.class, new Intent(context, FavoriteFolderListActivity.class));
    }

    private void smokePage(String name, Class<? extends Activity> activityClass, Intent intent) {
        smokePage(name, intent, true);
    }

    private void smokePage(String name, Intent intent, boolean recreate) {
        wake();
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(intent)) {
            // 页面里有 asyncInflate / 网络空态；固定等 1.2s 会误判「根视图不可见」。
            assertRootVisible(name, scenario, 8000);
            capture(name);

            if (recreate) {
                try {
                    scenario.recreate();
                    assertRootVisible(name + "（重建后）", scenario, 8000);
                    capture(name + "-recreated");
                } catch (Throwable t) {
                    dumpFailure(name + "-recreate");
                    throw t;
                }
            }
        } catch (Throwable t) {
            dumpFailure(name);
            capture(name + "-FAILED");
            throw t;
        }
    }

    private void wake() {
        try {
            device.wakeUp();
        } catch (android.os.RemoteException ignored) {
        }
    }

    private void dumpFailure(String name) {
        try {
            device.dumpWindowHierarchy(new File(shotDir, name + "-FAILED.xml"));
            System.out.println("UI_DUMP=" + new File(shotDir, name + "-FAILED.xml").getAbsolutePath());
            System.out.println("UI_CURRENT_PACKAGE=" + device.getCurrentPackageName());
        } catch (Throwable ignored) {
        }
    }

    private void assertRootVisible(String name, ActivityScenario<Activity> scenario, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        AtomicBoolean visible = new AtomicBoolean(false);
        AtomicBoolean destroyed = new AtomicBoolean(false);
        while (System.currentTimeMillis() < deadline) {
            // Wear 镜像可能进入 ambient/关屏，isShown 会瞬间变 false；轮询期间持续唤醒。
            wake();
            try {
                scenario.onActivity(activity -> {
                    // 用 decor content 作为根：部分页面（Wear 菜单/搜索）会隐藏/替换 topbar，
                    // 页面是否真的渲染出来应由 content 判断；页面内的 pageName 另行校验。
                    View root = activity.findViewById(android.R.id.content);
                    visible.set(root != null && root.isShown());
                });
            } catch (Throwable t) {
                destroyed.set(true);
                break;
            }
            if (visible.get()) {
                scenario.onActivity(activity -> {
                    assertNotNull(name + "：页面未启动（Activity 为空）", activity);
                    View root = activity.findViewById(android.R.id.content);
                    assertNotNull(name + "：根视图缺失（android.R.id.content）", root);
                    Rect rect = new Rect();
                    assertTrue(name + "：根视图没有可见区域", root.getGlobalVisibleRect(rect));
                    assertTrue(name + "：根视图宽度为 0", rect.width() > 0);
                    assertTrue(name + "：根视图高度为 0", rect.height() > 0);
                    assertTrue(name + "：根视图超出屏幕左边界", rect.left >= 0);
                    assertTrue(name + "：根视图超出屏幕右边界", rect.right <= root.getRootView().getWidth());
                    assertTrue(name + "：根视图超出屏幕上边界", rect.top >= 0);
                    assertTrue(name + "：根视图超出屏幕下边界", rect.bottom <= root.getRootView().getHeight());
                    TextView pageName = activity.findViewById(R.id.pageName);
                    if (pageName != null) assertTrue(name + "：pageName 不可见", pageName.isShown());
                });
                return;
            }
            SystemClock.sleep(250);
        }
        if (destroyed.get()) throw new AssertionError(name + "：Activity 在根视图可见前被销毁（检查页面启动参数/服务状态）");
        throw new AssertionError(name + "：根视图在 " + timeoutMs + "ms 内仍不可见");
    }

    private void capture(String name) {
        File file = new File(shotDir, name + ".png");
        if (!device.takeScreenshot(file)) {
            System.out.println("UI_SCREENSHOT_FAILED=" + file.getAbsolutePath());
            return;
        }
        System.out.println("UI_SCREENSHOT=" + file.getAbsolutePath());
    }
}