package com.RobinNotBad.BiliClient.activity.base;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.listener.OnLoadMoreListener;
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomGridManager;
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.RobinNotBad.BiliClient.util.view.ImageAutoLoadScrollListener;

/*
跟RefreshListActivity基本相同
2024-05-02
 */

public class RefreshListFragment extends BaseFragment {
    public SwipeRefreshLayout swipeRefreshLayout;
    public RecyclerView recyclerView;
    public TextView emptyView;
    public OnLoadMoreListener listener;
    //volatile：子类的后台加载循环读、主线程写（如 ReplyFragment 的世代守卫内落库）
    public volatile boolean bottom = false;
    public int page = 1;
    public long lastLoadTimestamp;
    public boolean force_single_column = false;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_simple_refresh, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        emptyView = view.findViewById(R.id.emptyTip);
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout);
        swipeRefreshLayout.setEnabled(false);
        swipeRefreshLayout.setRefreshing(true);
        //新版美学：刷新指示器与氛围背景同色（与 RefreshListActivity 一致）
        if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.NEW_UI_DESIGN, true)) {
            swipeRefreshLayout.setColorSchemeColors(ContextCompat.getColor(requireContext(), R.color.bili_pink));
        }
        //初始加载期间联动宿主页面的氛围呼吸
        if (getActivity() instanceof BaseActivity) ((BaseActivity) getActivity()).setAmbientBreathing(true);
        recyclerView = view.findViewById(R.id.recyclerView);
        //列表是滚动加载内容，默认 item 动画（进场淡入/变更交叉淡入）在低配手表上开销明显，关闭
        recyclerView.setItemAnimator(null);
        recyclerView.setLayoutManager(getLayoutManager());
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if (listener != null && !recyclerView.canScrollVertically(1) && !isRefreshing() && newState == RecyclerView.SCROLL_STATE_DRAGGING && !bottom) {
                    goOnLoad();
                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (listener != null) {
                    LinearLayoutManager manager = (LinearLayoutManager) recyclerView.getLayoutManager();
                    assert manager != null;
                    int lastItemPosition = manager.findLastVisibleItemPosition();  //获取最后一个显示的itemPosition
                    int itemCount = manager.getItemCount();
                    if (lastItemPosition >= (itemCount - 3) && dy > 0 && !isRefreshing() && !bottom) {// 滑动到倒数第三个就可以刷新了
                        goOnLoad();
                    }
                }
            }
        });
        ImageAutoLoadScrollListener.install(recyclerView);
    }

    public void setAdapter(RecyclerView.Adapter<?> adapter) {
        runOnUiThread(() -> recyclerView.setAdapter(adapter));
    }

    public void setOnRefreshListener(SwipeRefreshLayout.OnRefreshListener listener) {
        swipeRefreshLayout.setOnRefreshListener(listener);
        swipeRefreshLayout.setEnabled(true);
    }

    public void setRefreshing(boolean bool) {
        runOnUiThread(() -> {
            swipeRefreshLayout.setRefreshing(bool);
            //Fragment 宿主若是 BaseActivity，把列表加载态联动到氛围呼吸
            if (getActivity() instanceof BaseActivity) ((BaseActivity) getActivity()).setAmbientBreathing(bool);
        });
    }

    public void setOnLoadMoreListener(OnLoadMoreListener loadMore) {
        listener = loadMore;
    }

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

    public void showEmptyView() {
        if (emptyView != null) {
            runOnUiThread(() -> {
                recyclerView.setVisibility(View.GONE);
                emptyView.setVisibility(View.VISIBLE);
            });
        }
    }

    public boolean isRefreshing() {
        if (swipeRefreshLayout != null) return swipeRefreshLayout.isRefreshing();
        return false;
    }

    public void report(Throwable e) {
        MsgUtil.err(e);
    }

    public void loadFail() {
        page--;
        MsgUtil.showMsgLong("加载失败");
        setRefreshing(false);
    }

    public void loadFail(Throwable e) {
        page--;
        report(e);
        setRefreshing(false);
    }

    public RecyclerView.LayoutManager getLayoutManager() {
        return SharedPreferencesUtil.getBoolean("ui_landscape", false) && !force_single_column
                ? new CustomGridManager(requireContext(), 3)
                : new CustomLinearManager(requireContext());
    }

    public void setForceSingleColumn() {
        force_single_column = true;
    }
}
