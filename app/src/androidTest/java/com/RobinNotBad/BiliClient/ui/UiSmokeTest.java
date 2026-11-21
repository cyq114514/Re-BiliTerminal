package com.RobinNotBad.BiliClient.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
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
import com.RobinNotBad.BiliClient.activity.user.favorite.FavoriteVideoListActivity;
import com.RobinNotBad.BiliClient.activity.video.BangumiActivity;
import com.RobinNotBad.BiliClient.activity.video.BgmSubjectActivity;
import com.RobinNotBad.BiliClient.activity.video.RecommendActivity;
import com.RobinNotBad.BiliClient.activity.video.info.VideoInfoActivity;
import com.RobinNotBad.BiliClient.model.AudioQueue;
import com.RobinNotBad.BiliClient.model.VideoCard;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Field;
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
        SharedPreferences.Editor edit = prefs.edit()
                .putBoolean("setup", true)
                .putBoolean("crash_report_enable", false)
                .putBoolean("crash_report_asked", true)
                .putBoolean("crash_report_notice_121", true)
                .putBoolean("tutorial_pager_SearchActivity", false)
                .putBoolean("tutorial_pager_VideoInfoActivity", false)
                .putBoolean("tutorial_pager_BangumiActivity", false)
                .putBoolean("usage_stats_enable", false)
                .putString("usage_stats_last_day", today)
                .putBoolean("player_ui_round", round)
                .putFloat("dpi", 1.0F);
        // 教程是独立 TutorialActivity；不预置已读会让它盖在目标页上，后续页面全部误判不可见。
        for (String tag : new String[]{"recommend", "video", "space", "update_install",
                "search", "message", "dynamic", "dynamic_info", "article"}) {
            edit.putInt("tutorial_ver_" + tag, 999);
        }
        try {
            // 首次进入菜单会因 last_version 为空自动打开 UpdateLogActivity，盖住菜单页。
            String versionName = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            edit.putString("last_version", versionName);
        } catch (Exception ignored) {
        }
        edit.commit();

shotDir = new File(context.getExternalFilesDir(null), "ui-shots");
        if (!shotDir.exists() && !shotDir.mkdirs()) {
            throw new AssertionError("无法创建截图目录：" + shotDir);
        }
        //下载页会请求存储权限；权限弹窗会把 Activity 压到 STOPPED，ActivityScenario 等到超时。
        //CI 的 host 脚本也会授权，这里再兜底一次，保证本地单跑同样稳。
        grantStoragePermission("android.permission.READ_EXTERNAL_STORAGE");
        grantStoragePermission("android.permission.WRITE_EXTERNAL_STORAGE");
    }

    private void grantStoragePermission(String permission) {
        if (Build.VERSION.SDK_INT < 28) return;
        try {
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .grantRuntimePermission(context.getPackageName(), permission);
        } catch (Throwable ignored) {
            //部分镜像把 WRITE 标成 restricted；授权失败就交给 host 脚本/系统弹窗，不阻断测试
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
    public void rotationSmoke() throws Exception {
        // 真实手表不支持横竖屏切换，记为 SKIP（报告中可见），手机设备必须通过。
        Assume.assumeFalse("Wear OS 不执行横竖屏测试",
                context.getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_WATCH));
        try {
            device.setOrientationLeft();
            wake();
            SystemClock.sleep(1200);
            Intent intent = new Intent(context, SettingMainActivity.class);
            try (ActivityScenario<Activity> scenario = ActivityScenario.launch(intent)) {
                assertRootVisible("settings-landscape", scenario, 8000);
                capture("settings-landscape");
            }
        } catch (Throwable t) {
            capture("settings-landscape-FAILED");
            throw t;
        } finally {
            try {
                device.setOrientationNatural();
                device.unfreezeRotation();
            } catch (Throwable ignored) {
            }
        }
    }

    @Test(timeout = 180000)
    public void favoriteFolderPage() {
        smokePage("favorites", FavoriteFolderListActivity.class, new Intent(context, FavoriteFolderListActivity.class));
    }

    /**
     * 收藏夹内容页：未登录时接口通常返回空，先把空态截图；再用反射塞三条假卡片，
     * 把「听全部/下载全部 + 排序胶囊」头部拉出来，卡住 2026-11-21 反馈的
     * "头部浮层压住标题栏/列表卡片"。
     */
    @Test(timeout = 180000)
    public void favoriteVideoPage() {
        Intent intent = new Intent(context, FavoriteVideoListActivity.class);
        intent.putExtra("mid", 0L);
        intent.putExtra("fid", 0L);
        intent.putExtra("name", "默认收藏夹");
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(intent)) {
            assertRootVisible("favorite-video", scenario, 8000);
            SystemClock.sleep(2500);
            scenario.onActivity(this::injectFakeFavorites);
            SystemClock.sleep(800);
            scenario.onActivity(activity -> assertRoundSafe("favorite-video-header", activity));
            capture("favorite-video-header");
        } catch (Throwable t) {
            capture("favorite-video-header-FAILED");
            throw t;
        }
    }

    /** 测试专用：给收藏夹塞三条假卡片，让头部（入口卡 + 排序胶囊）与列表一起显示 */
    private void injectFakeFavorites(Activity activity) {
        if (!(activity instanceof FavoriteVideoListActivity)) return;
        try {
            Field listField = FavoriteVideoListActivity.class.getDeclaredField("videoList");
            listField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<VideoCard> list = (List<VideoCard>) listField.get(activity);
            Field adapterField = FavoriteVideoListActivity.class.getDeclaredField("videoCardAdapter");
            adapterField.setAccessible(true);
            androidx.recyclerview.widget.RecyclerView.Adapter<?> adapter =
                    (androidx.recyclerview.widget.RecyclerView.Adapter<?>) adapterField.get(activity);
            if (list == null || adapter == null) return;
            list.clear();
            list.add(new VideoCard("圆屏收藏夹测试一：先看标题有没有被头部压住",
                    "测试UP主", "12.3万", "", 1001, "BV1test001", 1L));
            list.add(new VideoCard("圆屏收藏夹测试二：再看排序胶囊有没有盖住卡片",
                    "测试UP主", "8.6万", "", 1002, "BV1test002", 1L));
            list.add(new VideoCard("圆屏收藏夹测试三：滚到底部不应掉进下巴",
                    "测试UP主", "3.1万", "", 1003, "BV1test003", 1L));
            ((FavoriteVideoListActivity) activity).hideEmptyView();
            adapter.notifyDataSetChanged();
        } catch (Throwable ignored) {
        }
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
                    assertRoundSafe(name, activity);
                });
                return;
            }
            SystemClock.sleep(250);
        }
        if (destroyed.get()) throw new AssertionError(name + "：Activity 在根视图可见前被销毁（检查页面启动参数/服务状态）");
        throw new AssertionError(name + "：根视图在 " + timeoutMs + "ms 内仍不可见");
    }

    /**
     * 圆屏几何门禁：可见文字 / 叶子控件（不可再分的按钮）不得有角落在内接圆外。
     *
     * <p>只做几何判定，容忍 2dp——圆边本身有表圈/抗锯齿；超过 2dp 基本就是肉眼可见的裁切。
     * 容器类（RelativeLayout、RecyclerView、MaterialCardView）不判定，避免把全宽顶栏/卡片背景
     * 当成"出圆"误报；真正影响可读性的是文字和可点击控件。
     */
    private void assertRoundSafe(String name, Activity activity) {
        if (!activity.getResources().getConfiguration().isScreenRound()) return;
        View decor = activity.getWindow().getDecorView();
        int screenW = decor.getWidth();
        int screenH = decor.getHeight();
        if (screenW <= 0 || screenH <= 0) return;
        int cx = screenW / 2;
        int cy = screenH / 2;
        int radius = Math.min(screenW, screenH) / 2;
        float tolerance = 2f * activity.getResources().getDisplayMetrics().density;
        ArrayList<String> bad = new ArrayList<>();
        collectRoundBad(decor, activity, cx, cy, radius, tolerance, bad);
        assertTrue(name + "：圆屏出圆（>2dp）：" + bad, bad.isEmpty());
    }

    private void collectRoundBad(View view, Activity activity, int cx, int cy, int radius,
                                 float tolerance, List<String> out) {
        if (out.size() >= 8 || view.getVisibility() != View.VISIBLE) return;
        boolean text = view instanceof TextView;
        boolean leafControl = view.isClickable() && !(view instanceof ViewGroup);
        if (text || leafControl) {
            Rect rect = new Rect();
            if (view.getGlobalVisibleRect(rect) && !rect.isEmpty()) {
                View root = view.getRootView();
                //整屏遮罩/根容器（例如教程 scrim）不算"控件出圆"，内容由文字节点自己判定
                boolean fullScreen = rect.width() >= root.getWidth() * 0.98f
                        && rect.height() >= root.getHeight() * 0.98f;
                if (!fullScreen) {
                    float depth = maxCornerDepth(rect, cx, cy, radius);
                    if (depth > tolerance) {
                        String id = "";
                        try {
                            id = activity.getResources().getResourceEntryName(view.getId());
                        } catch (Exception ignored) {
                        }
                        float density = activity.getResources().getDisplayMetrics().density;
                        out.add(view.getClass().getSimpleName() + (id.isEmpty() ? "" : "#" + id)
                                + rect.toShortString() + " 出圆 " + Math.round(depth / density) + "dp");
                        return;
                    }
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectRoundBad(group.getChildAt(i), activity, cx, cy, radius, tolerance, out);
                if (out.size() >= 8) return;
            }
        }
    }

    /**
     * 测试进程不依赖 App 的 util 类：apk 的 secondary dex 在仪器化进程里不一定可见
     * （实测 test APK 引用 RoundGeometry 会 ClassNotFoundException），这里内联同一套四角判定。
     */
    private static float maxCornerDepth(Rect rect, int cx, int cy, int radius) {
        float max = 0f;
        max = Math.max(max, (float) Math.hypot(rect.left - cx, rect.top - cy));
        max = Math.max(max, (float) Math.hypot(rect.right - cx, rect.top - cy));
        max = Math.max(max, (float) Math.hypot(rect.left - cx, rect.bottom - cy));
        max = Math.max(max, (float) Math.hypot(rect.right - cx, rect.bottom - cy));
        return max - radius;
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