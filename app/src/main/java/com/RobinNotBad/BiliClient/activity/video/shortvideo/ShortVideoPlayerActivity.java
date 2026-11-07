package com.RobinNotBad.BiliClient.activity.video.shortvideo;

import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;

import androidx.annotation.Nullable;
import androidx.viewpager.widget.ViewPager;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity;
import com.RobinNotBad.BiliClient.model.ShortVideoItem;
import com.RobinNotBad.BiliClient.util.MsgUtil;

/**
 * 竖屏短视频外壳：横向四页（短视频 / 详情 / 评论 / 相关推荐）。
 *
 * <p>为什么做成"外壳 + 四页"：短视频刷到感兴趣的内容要能顺手看详情、看评论、看相关推荐，
 * 再返回还能继续刷（页面状态不丢）。这几件事各自的页面本项目都已经有了，
 * 复用到 pager 里比新写一套便宜得多。
 *
 * <p>手势约定：外壳只负责横向翻页（ViewPager 自己处理），纵向刷视频由第 0 页的 RecyclerView 处理。
 * 返回键：先退回第 0 页，再退出。
 */
//继承 InstanceActivity：与其它菜单入口一致，离开后由菜单页面负责把老页面收掉
public class ShortVideoPlayerActivity extends InstanceActivity {

    public static final int PAGE_FEED = 0;
    public static final int PAGE_DETAIL = 1;
    public static final int PAGE_REPLY = 2;
    public static final int PAGE_RCMD = 3;

    private ViewPager pager;
    private ShortVideoShellAdapter adapter;
    private ShortVideoFeedFragment feedFragment;
    /** 音量键：短按系统音量 ±1 档 + 提示，按住 600ms 弹无极调音（用户反馈 2026-11-07） */
    private com.RobinNotBad.BiliClient.util.VolumeKeyHelper volumeKeyHelper;

    /** 第 0 页换视频时先暂存，等真的滑到右侧页再重建（避免翻视频时反复重建 Fragment） */
    private Anchor pendingAnchor;

    private static final class Anchor {
        final long aid;
        final String bvid;

        Anchor(long aid, String bvid) {
            this.aid = aid;
            this.bvid = bvid;
        }
    }

    /**
     * 不进冷启动恢复链：本页有"正在播放的实例 + 当前看到第几条"这类纯运行时状态，
     * 光凭 intent 重建不出来；BaseActivity 的注释也明确写了"播放器这类页面不要打开这个开关"。
     * 不覆盖的话会继承 InstanceActivity 的 true，冷启动时恢复出一个空壳短视频页。
     */
    @Override
    public boolean isRestorablePage() {
        return false;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_short_video_player);
        setPageName("短视频");

        pager = findViewById(R.id.pager);
        //音量键交给外套页统一处理：第 0 页的 Fragment 收不到按键事件
        volumeKeyHelper = new com.RobinNotBad.BiliClient.util.VolumeKeyHelper(this,
                new com.RobinNotBad.BiliClient.util.VolumeKeyHelper.Host() {
                    @Override
                    public void showVolumePercent(int percent) {
                        com.RobinNotBad.BiliClient.util.MsgUtil.showMsg("音量：" + percent + "%");
                    }

                    @Override
                    public void showVolumePanel() {
                        com.RobinNotBad.BiliClient.util.VolumeGainPanel.show(
                                ShortVideoPlayerActivity.this,
                                gain -> {
                                    if (feedFragment != null) feedFragment.applyVolumeGainFromPanel(gain);
                                });
                    }
                });
        feedFragment = new ShortVideoFeedFragment();
        adapter = new ShortVideoShellAdapter(getSupportFragmentManager(), feedFragment);
        //四页全部常驻：第 0 页必须常驻（播放器在里面），右侧三页常驻让横滑不闪
        pager.setOffscreenPageLimit(3);
        pager.setAdapter(adapter);
        pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                feedFragment.setShellPageActive(position == PAGE_FEED);
                if (position != PAGE_FEED && pendingAnchor != null) {
                    //onPageSelected 可能发生在 ViewPager 自己的滚动/布局里，这时候改 adapter
                    //会触发 dataSetChanged 重入；挪到下一个消息再重建右侧三页
                    final Anchor anchor = pendingAnchor;
                    pendingAnchor = null;
                    pager.post(() -> adapter.setDetail(anchor.aid, anchor.bvid));
                }
            }
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (volumeKeyHelper != null && volumeKeyHelper.onKeyDown(keyCode, event)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, android.view.KeyEvent event) {
        if (volumeKeyHelper != null && volumeKeyHelper.onKeyUp(keyCode, event)) return true;
        return super.onKeyUp(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (volumeKeyHelper != null) volumeKeyHelper.cancel();
        super.onDestroy();
    }

    /** 第 0 页翻到新视频时回调：右侧三页的锚点跟着换 */
    public void onFeedAnchorChanged() {
        ShortVideoItem item = feedFragment.currentItem();
        if (item == null) return;
        if (pager.getCurrentItem() == PAGE_FEED) {
            pendingAnchor = new Anchor(item.aid, item.bvid);
        } else {
            final Anchor anchor = new Anchor(item.aid, item.bvid);
            pager.post(() -> adapter.setDetail(anchor.aid, anchor.bvid));
        }
    }

    public void goToPage(int page) {
        if (page < 0 || page >= adapter.getCount()) return;
        pager.setCurrentItem(page, true);
    }

    /**
     * 「评论 / 详情」入口：先把当前这条短视频的 aid 推给右侧三页，再翻过去。
     *
     * <p>不能只调 {@link #goToPage(int)}：右侧三页是按锚点建的，锚点没设好时
     * {@link ShortVideoShellAdapter#getItem(int)} 返回的是空壳 {@code new Fragment()}，
     * 翻过去只有一张白页（v1 点"评论"偶尔空白就是这个）。
     */
    public void openPageFor(int page, long aid, String bvid) {
        if (adapter == null || pager == null) return;
        if (page < 0 || page >= adapter.getCount()) return;
        if (aid > 0) {
            //直接重建右侧三页，别再等 pendingAnchor：pendingAnchor 只在"还停在第 0 页"时才会被消费
            pendingAnchor = null;
            adapter.setDetail(aid, bvid);
        }
        pager.setCurrentItem(page, true);
    }

    /** 供第 0 页的返回按钮调用：先退回短视频页，已经在第 0 页就回菜单 */
    public void handleBack() {
        if (pager != null && pager.getCurrentItem() != PAGE_FEED) {
            pager.setCurrentItem(PAGE_FEED, true);
        } else {
            exitToMenu();
        }
    }

    /**
     * 返回上一级 = 菜单页（盖在本页上面）。
     *
     * <p>不能 {@code finish()}：旧实现"拉起菜单 + finish 自己"，而关菜单的三条路径
     * 全是裸 finish()——菜单一关任务栈就空了，直接回桌面。用户反馈的就是这个：
     * 「拉出菜单后再关掉菜单会退到桌面」。现在改成和其他页面同款语义：菜单盖在本页
     * 上面，关掉菜单就回到短视频；带上 from，菜单里点「短视频」会命中
     * {@code MenuActivity} 的回旧页分支（只关菜单、不重建实例）。
     */
    private void exitToMenu() {
        try {
            menuClick.run();
        } catch (Throwable e) {
            android.util.Log.w("short-video", "回菜单失败：" + e);
            MsgUtil.showMsg("回菜单失败，请再按一次返回");
        }
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    // ---------------------------------------------------------------- 右划回菜单

    /**
     * 右划 = 返回上一级（菜单）。
     *
     * <p>为什么要在 Activity 里接：外壳 ViewPager 停在第 0 页时右划无处可去，事件只会被
     * 系统手势/窗口 dismiss 吃掉；本页主题已关掉 windowSwipeToDismiss（见
     * {@code Theme.BiliClient.Fullscreen}），右划就必须由页面自己解释。
     *
     * <p>只接管"第 0 页、且这一划没有翻动 ViewPager"的情况：
     * 右侧三页（详情/评论/相关推荐）的右划仍旧是 ViewPager 翻回第 0 页，不抢。
     */
    private float swipeDownX, swipeDownY;
    private int swipeDownPage;
    private long swipeDownAt;
    //落点是否在进度条上：进度条上的右拖是"拖进度"，绝不能被解释成右滑返回（B-03）
    private boolean swipeStartedOnSeekBar;

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (pager != null) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    swipeDownX = ev.getX();
                    swipeDownY = ev.getY();
                    swipeDownPage = pager.getCurrentItem();
                    swipeDownAt = SystemClock.uptimeMillis();
                    swipeStartedOnSeekBar = downOnSeekBar(ev.getX(), ev.getY());
                    break;
                case MotionEvent.ACTION_UP:
                    if (swipeDownPage == PAGE_FEED && pager.getCurrentItem() == PAGE_FEED
                            && !swipeStartedOnSeekBar) {
                        float dx = ev.getX() - swipeDownX;
                        float dy = ev.getY() - swipeDownY;
                        long cost = SystemClock.uptimeMillis() - swipeDownAt;
                        //阈值 20% 屏宽、横向必须明显大于纵向（否则纵向刷视频会被误判）、600ms 内
                        float threshold = getResources().getDisplayMetrics().widthPixels * 0.2f;
                        if (dx > threshold && Math.abs(dx) > Math.abs(dy) * 1.5f && cost < 600) {
                            handleBack();
                            return true;
                        }
                    }
                    break;
                default:
                    break;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    /** 触点是否落在某个可见 SeekBar 上（windowFullscreen 下窗口坐标≈屏幕坐标） */
    private boolean downOnSeekBar(float x, float y) {
        return hitSeekBar(getWindow().getDecorView(), x, y);
    }

    private boolean hitSeekBar(View view, float x, float y) {
        if (view instanceof SeekBar && view.isShown()) {
            int[] loc = new int[2];
            view.getLocationOnScreen(loc);
            if (x >= loc[0] && x <= loc[0] + view.getWidth()
                    && y >= loc[1] && y <= loc[1] + view.getHeight()) {
                return true;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hitSeekBar(group.getChildAt(i), x, y)) return true;
            }
        }
        return false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (feedFragment != null && pager != null) {
            feedFragment.setShellPageActive(pager.getCurrentItem() == PAGE_FEED);
        }
    }
}
