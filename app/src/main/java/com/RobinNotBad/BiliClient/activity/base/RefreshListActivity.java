package com.RobinNotBad.BiliClient.activity.base;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.listener.OnLoadMoreListener;
import com.RobinNotBad.BiliClient.ui.theme.ThemeManager;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;


/*
尝试造轮子以减少代码量
2024-05-01
 */

public class RefreshListActivity extends BaseActivity {
    public SwipeRefreshLayout swipeRefreshLayout;
    public RecyclerView recyclerView;
    public TextView emptyView;
    public OnLoadMoreListener listener;
    //加载更多监听器在子类里于后台线程写、滚动监听在主线程读，必须 volatile 保证可见性（与 RefreshListFragment.bottom 保持一致）
    public volatile boolean bottom = false;
    public int page = 1;
    public long lastLoadTimestamp;

    //帮你findView和设置滚动监测器，自动显示转圈动画
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_simple_refresh);
        emptyView = findViewById(R.id.emptyTip);
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        swipeRefreshLayout.setEnabled(false);
        swipeRefreshLayout.setRefreshing(true);
        //新版美学：刷新指示器与氛围背景同色；初始加载期间氛围呼吸
        if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.NEW_UI_DESIGN, true)) {
            //刷新指示器颜色跟随主题强调色（默认主题下仍是原 #FE679A）
            swipeRefreshLayout.setColorSchemeColors(ThemeManager.getPrimary());
            setAmbientBreathing(true);
        }
        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setHasFixedSize(true);
        //列表是滚动加载内容，默认 item 动画在低配手表上开销明显，关闭
        recyclerView.setItemAnimator(null);
        recyclerView.setLayoutManager(getLayoutManager());
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if (listener != null && !recyclerView.canScrollVertically(1) && !swipeRefreshLayout.isRefreshing() && newState == RecyclerView.SCROLL_STATE_DRAGGING && !bottom) {
                    goOnLoad();
                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (listener != null) {
                    LinearLayoutManager manager = (LinearLayoutManager) recyclerView.getLayoutManager();
                    assert manager != null;
                    int lastItemPosition = manager.findLastCompletelyVisibleItemPosition();  //获取最后一个完全显示的itemPosition
                    int itemCount = manager.getItemCount();
                    if (lastItemPosition >= (itemCount - 3) && dy > 0 && !swipeRefreshLayout.isRefreshing() && !bottom) {// 滑动到倒数第三个就可以刷新了
                        goOnLoad();
                    }
                }
            }
        });
    }

    public void setAdapter(RecyclerView.Adapter<?> adapter) {
        runOnUiThread(() -> recyclerView.setAdapter(adapter));
    }

    public void setOnRefreshListener(SwipeRefreshLayout.OnRefreshListener listener) {
        swipeRefreshLayout.setOnRefreshListener(listener);
        swipeRefreshLayout.setEnabled(true);
    }

    public void showEmptyView() {
        if (emptyView != null) {
            runOnUiThread(() -> {
                recyclerView.setVisibility(View.GONE);
                emptyView.setVisibility(View.VISIBLE);
            });
        }
    }

    public void hideEmptyView() {
        if (emptyView != null) {
            runOnUiThread(() -> {
                recyclerView.setVisibility(View.VISIBLE);
                emptyView.setVisibility(View.GONE);
            });
        }
    }

    public void setRefreshing(boolean bool) {
        runOnUiThread(() -> {
            swipeRefreshLayout.setRefreshing(bool);
            //加载态与氛围呼吸联动：数据到位即停，与 WearBili 的加载呼吸一致
            setAmbientBreathing(bool);
        });
    }

    public void setOnLoadMoreListener(OnLoadMoreListener loadMore) {
        listener = loadMore;
    }

    /**
     * 从「设置项搜索」跳进来时，把列表滚到命中的那一条。
     *
     * <p>只滚不highlight：设置项没有统一的"命中"样式，高亮要改每个 cell 的渲染。
     * 命中项落在屏幕顶端已经足够——用户是照着搜索结果点进来的。
     */
    public void scrollToSettingTitle(java.util.List<com.RobinNotBad.BiliClient.model.SettingSection> sections) {
        if (sections == null || getIntent() == null) return;
        String target = getIntent().getStringExtra(
                com.RobinNotBad.BiliClient.activity.settings.SettingSearchActivity.EXTRA_SCROLL_TITLE);
        if (target == null || target.isEmpty()) return;
        int index = -1;
        for (int i = 0; i < sections.size(); i++) {
            if (target.equals(sections.get(i).name)) {
                index = i;
                break;
            }
        }
        if (index < 0) return;
        final int position = index;
        recyclerView.post(() -> {
            RecyclerView.LayoutManager manager = recyclerView.getLayoutManager();
            if (manager instanceof LinearLayoutManager)
                ((LinearLayoutManager) manager).scrollToPositionWithOffset(position, 0);
            else
                recyclerView.scrollToPosition(position);
        });
    }

    //自动
    private void goOnLoad() {
        long timeCurrent = System.currentTimeMillis();
        if (timeCurrent - lastLoadTimestamp > 100) {
            swipeRefreshLayout.setRefreshing(true);
            page++;
            listener.onLoad(page);
            lastLoadTimestamp = timeCurrent;
        }
    }

    public void setBottom(boolean bool) {
        bottom = bool;
    }

    public void loadFail() {
        page--;
        MsgUtil.showMsgLong("加载失败");
        setRefreshing(false);
    }

    public void loadFail(Exception e) {
        page--;
        report(e);
        setRefreshing(false);
    }
}
