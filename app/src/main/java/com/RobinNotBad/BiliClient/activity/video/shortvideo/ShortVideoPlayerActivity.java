package com.RobinNotBad.BiliClient.activity.video.shortvideo;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.viewpager.widget.ViewPager;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity;
import com.RobinNotBad.BiliClient.model.ShortVideoItem;

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
     * 返回上一级 = 菜单页。
     *
     * <p>不能只 {@code finish()}：菜单入口是「跳转式」的——{@code MenuActivity.killAndJump()}
     * 在 startActivity 之后会把自己 finish 掉，栈里只剩当前这一页，
     * 直接 finish 就等于把任务清空、回到桌面（用户看到的就是"点了返回直接退出应用"）。
     * 这里照菜单键的做法重新拉起菜单，再关掉自己。
     */
    private void exitToMenu() {
        try {
            //本页马上 finish，菜单里没有"旧的短视频页"可回；不能再继承本页的 from，
            //否则菜单点「短视频」会命中 killAndJump 的回旧页分支，只关菜单 → 任务栈清空
            openMenuWithoutFrom();
        } catch (Throwable e) {
            android.util.Log.w("short-video", "回菜单失败：" + e);
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (feedFragment != null && pager != null) {
            feedFragment.setShellPageActive(pager.getCurrentItem() == PAGE_FEED);
        }
    }
}
