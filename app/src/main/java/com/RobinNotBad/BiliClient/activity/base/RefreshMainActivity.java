package com.RobinNotBad.BiliClient.activity.base;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.listener.OnLoadMoreListener;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.view.ImageAutoLoadScrollListener;


/*
尝试造轮子以减少代码量
2024-05-02
 */

public class RefreshMainActivity extends InstanceActivity {
    public SwipeRefreshLayout swipeRefreshLayout;
    public RecyclerView recyclerView;
    public OnLoadMoreListener listener;
    public boolean bottom = false;
    public int page = 1;
    public long lastLoadTimestamp;
    protected boolean isRefreshing;

    //帮你findView和设置滚动监测器，自动显示转圈动画
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_simple_main_refresh);
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        swipeRefreshLayout.setEnabled(false);
        swipeRefreshLayout.setRefreshing(true);
        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(getLayoutManager());
        //与 RefreshListActivity 同口径：低配设备滚动减负（默认 ItemAnimator 在动态页每行增删都在跑）
        recyclerView.setItemAnimator(null);
        recyclerView.setHasFixedSize(true);
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
                    if (lastItemPosition >= (itemCount - 3) && dy > 0 && !swipeRefreshLayout.isRefreshing() && !isRefreshing && !bottom) {// 滑动到倒数第三个就可以刷新了
                        goOnLoad();
                    }
                }
            }
        });
        ImageAutoLoadScrollListener.install(recyclerView);

        //顶栏一键刷新按钮：与下拉刷新等价，另外把列表滚回顶部。
        //用户诉求原文：推荐流往下翻多了没有想看的内容，得先点别的入口再切回首页才有新内容
        refreshButton = findViewById(R.id.btn_refresh);
        if (refreshButton != null) refreshButton.setOnClickListener(v -> triggerRefresh());
    }

    private View refreshButton;
    private SwipeRefreshLayout.OnRefreshListener refreshListener;

    /**
     * 一键刷新：先把列表滚回顶部，再触发与下拉刷新完全相同的回调。
     * 页面没注册刷新回调时按钮不会显示（见 setOnRefreshListener），因此不存在点了没反应的死按钮。
     */
    public void triggerRefresh() {
        if (refreshListener == null) return;
        if (recyclerView != null) recyclerView.scrollToPosition(0);
        swipeRefreshLayout.setRefreshing(true);
        refreshListener.onRefresh();
    }

    public void setAdapter(RecyclerView.Adapter<?> adapter) {
        runOnUiThread(() -> recyclerView.setAdapter(adapter));
    }

    public void setOnRefreshListener(SwipeRefreshLayout.OnRefreshListener listener) {
        refreshListener = listener;
        swipeRefreshLayout.setOnRefreshListener(listener);
        swipeRefreshLayout.setEnabled(true);
        //注册了刷新回调才露出按钮：没注册的页面点了不会有任何反应
        if (refreshButton != null) refreshButton.setVisibility(View.VISIBLE);
    }

    public void setRefreshing(boolean bool) {
        runOnUiThread(() -> swipeRefreshLayout.setRefreshing(bool));
        isRefreshing = bool;
    }

    public void setOnLoadMoreListener(OnLoadMoreListener loadMore) {
        listener = loadMore;
    }

    //自动
    private void goOnLoad() {
        synchronized (this) {
            long timeCurrent = System.currentTimeMillis();
            if (timeCurrent - lastLoadTimestamp > 100) {
                swipeRefreshLayout.setRefreshing(true);
                page++;
                listener.onLoad(page);
                lastLoadTimestamp = timeCurrent;
            }
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
