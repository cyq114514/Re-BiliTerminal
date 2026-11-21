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
import com.RobinNotBad.BiliClient.activity.player.AudioPlayerActivity;
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

    @Test
    public void menuPage() {
        smokePage("menu", MenuActivity.class, new Intent(context, MenuActivity.class), R.id.menu_scroll);
    }

    @Test
    public void homeRecommendPage() {
        smokePage("home", RecommendActivity.class, new Intent(context, RecommendActivity.class),
                R.id.recyclerView);
    }

    @Test
    public void bangumiPage() {
        smokePage("bangumi", BangumiActivity.class, new Intent(context, BangumiActivity.class),
                R.id.tabBar);
    }

    @Test
    public void bgmDetailPage() {
        Intent intent = new Intent(context, BgmSubjectActivity.class);
        intent.putExtra("bgmId", 1L);
        smokePage("bgm-detail", BgmSubjectActivity.class, intent, R.id.text_title);
    }

    @Test
    public void videoDetailPage() {
        Intent intent = new Intent(context, VideoInfoActivity.class);
        intent.putExtra("aid", 2L);
        smokePage("video-detail", VideoInfoActivity.class, intent, R.id.viewPager);
    }

    @Test
    public void searchPage() {
        smokePage("search", SearchActivity.class, new Intent(context, SearchActivity.class),
                R.id.keywordInput);
    }

    @Test
    public void downloadsPage() {
        smokePage("downloads", DownloadListActivity.class,
                new Intent(context, DownloadListActivity.class), R.id.pageName);
    }

    @Test
    public void settingsPage() {
        smokePage("settings", SettingMainActivity.class,
                new Intent(context, SettingMainActivity.class), R.id.scrollView);
    }

    @Test
    public void audioQueuePage() {
        smokePage("audio-queue", AudioQueueActivity.class,
                new Intent(context, AudioQueueActivity.class), R.id.recycler);
    }

    @Test
    public void audioPlayerPage() {
        List<VideoCard> items = new ArrayList<>();
        items.add(new VideoCard("UI 冒烟测试曲目", "测试 UP", "1", "", 1L, "BV1xx411c7mD", 1L));
        AudioQueue.get().setQueue(items, 0L, "list", "UI Smoke");
        AudioQueue.get().setIndex(0);
        smokePage("audio-player", AudioPlayerActivity.class,
                new Intent(context, AudioPlayerActivity.class), R.id.coverCard);
    }

    @Test
    public void favoriteFolderPage() {
        smokePage("favorites", FavoriteFolderListActivity.class,
                new Intent(context, FavoriteFolderListActivity.class), R.id.recyclerView);
    }

    private void smokePage(String name, Class<? extends Activity> activityClass, Intent intent, int rootId) {
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                assertNotNull(name + "：页面未启动（Activity 为空）", activity);
                View root = activity.findViewById(rootId);
                assertNotNull(name + "：根视图缺失（id=" + rootId + "）", root);
                assertTrue(name + "：根视图不可见", root.isShown());
                Rect rect = new Rect();
                assertTrue(name + "：根视图没有可见区域", root.getGlobalVisibleRect(rect));
                assertTrue(name + "：根视图宽度为 0", rect.width() > 0);
                assertTrue(name + "：根视图高度为 0", rect.height() > 0);
                assertTrue(name + "：根视图超出屏幕左边界", rect.left >= 0);
                assertTrue(name + "：根视图超出屏幕右边界", rect.right <= root.getRootView().getWidth());
                assertTrue(name + "：根视图超出屏幕上边界", rect.top >= 0);
                assertTrue(name + "：根视图超出屏幕下边界", rect.bottom <= root.getRootView().getHeight());
                TextView pageName = activity.findViewById(R.id.pageName);
                if (pageName != null) {
                    assertTrue(name + "：pageName 不可见", pageName.isShown());
                }
            });

            SystemClock.sleep(1200);
            device.waitForIdle(2500);
            capture(name);

            scenario.recreate();
            scenario.onActivity(activity -> {
                View root = activity.findViewById(rootId);
                assertNotNull(name + "：页面重建后根视图丢失", root);
                assertTrue(name + "：页面重建后根视图不可见", root.isShown());
            });
            SystemClock.sleep(600);
            device.waitForIdle(2000);
            capture(name + "-recreated");
        } catch (Throwable t) {
            capture(name + "-FAILED");
            throw t;
        }
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