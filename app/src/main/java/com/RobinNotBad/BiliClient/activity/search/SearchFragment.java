package com.RobinNotBad.BiliClient.activity.search;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
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

public class SearchFragment extends Fragment {
    public SwipeRefreshLayout swipeRefreshLayout;
    public RecyclerView recyclerView;
    public TextView emptyView;
    public OnLoadMoreListener listener;
    public SwipeRefreshLayout.OnRefreshListener refreshListener;
    public String keyword;
    public boolean bottom = false;
    public int page = 1;
    //列表代际：换关键词/刷新时自增，在途的旧请求凭代际整体作废，
    //避免旧关键词的迟到响应 addAll 进新列表（此前仅 ReplyFragment 有此守卫）
    public volatile int loadGeneration = 0;
    public long lastLoadTimestamp;
    public boolean refreshable = false;

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

        recyclerView = view.findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(getLayoutManager());
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if (newState != RecyclerView.SCROLL_STATE_DRAGGING) return;

                if (!recyclerView.canScrollVertically(-1)) {
                    if (requireActivity() instanceof SearchActivity) {
                        SearchActivity activity = (SearchActivity) requireActivity();  //不能向上滚动了就显示搜索栏
                        activity.onScrolled(-114);
                    }
                } else if (listener != null && !recyclerView.canScrollVertically(1) && !swipeRefreshLayout.isRefreshing() && !bottom) {
                    goOnLoad();
                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (listener != null) {
                    RecyclerView.LayoutManager lm = recyclerView.getLayoutManager();
                    int lastItemPosition = -1;
                    int itemCount = 0;
                    if (lm instanceof LinearLayoutManager) {
                        LinearLayoutManager manager = (LinearLayoutManager) lm;
                        lastItemPosition = manager.findLastVisibleItemPosition();
                        itemCount = manager.getItemCount();
                    }
                    if (lastItemPosition >= (itemCount - 3) && dy > 0 && !swipeRefreshLayout.isRefreshing() && !bottom) {// 滑动到倒数第三个就可以刷新了
                        goOnLoad();
                    }

                    if (requireActivity() instanceof SearchActivity) {
                        SearchActivity activity = (SearchActivity) requireActivity();
                        activity.onScrolled(dy);
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
        this.refreshListener = listener;
    }

    public void setRefreshing(boolean bool) {
        runOnUiThread(() -> swipeRefreshLayout.setRefreshing(bool));
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
        if (page == 1) showEmptyView(bool);
        else if (bool && isAdded()) {
            MsgUtil.showMsg("已经到底啦OwO");
        }
    }

    public void runOnUiThread(Runnable runnable) {
        if (isAdded()) requireActivity().runOnUiThread(runnable);
    }

    public void showEmptyView(boolean empty) {
        if (emptyView != null) {
            runOnUiThread(() -> emptyView.setVisibility(empty ? View.VISIBLE : View.GONE));
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
        return SharedPreferencesUtil.getBoolean("ui_landscape", false)
                ? new CustomGridManager(requireContext(), 3)
                : new CustomLinearManager(requireContext());
    }

    public void update(String keyword) {
        loadGeneration++;   //关键词变了，旧关键词的在途请求全部作废
        this.page = 1;
        this.keyword = keyword;
        this.refreshable = true;
        setBottom(false);
    }

    //当前排序档位（B站 search/type 的 order 参数值，totalrank=综合排序）。
    //volatile：档位在主线程写、加载在 CenterThreadPool 后台线程读，没有 happens-before 边界时
    //后台可能读到旧值——表现为"点了排序但拉回来的还是旧排序的结果"
    public volatile String sortOrder = "totalrank";

    /**
     * 本分类支持的排序档位，每项为 {显示名, order 参数值}；空数组 = 该分类不支持排序
     * （番剧/用户/直播的排序键与视频不通用，不硬套）。
     */
    public String[][] sortOptions() {
        return new String[0][];
    }

    public String getSortOrder() {
        return sortOrder;
    }

    /**
     * 切换排序档位：同值短路；否则作废在途请求（loadGeneration 自增，连同排序快照一起兜住
     * 迟到响应）、清列表重拉第一页。由 SearchActivity 的排序弹窗调用，
     * 只对 sortOptions 非空的分类可用。
     */
    public void selectSort(String order) {
        if (order == null || order.equals(sortOrder)) return;
        sortOrder = order;
        loadGeneration++;
        page = 1;
        bottom = false;
        refreshable = true; //走 refresh() 的正常消费路径，保持"页面切换才刷新"的状态机一致
        refresh();
    }

    public void refresh() {
        if (!refreshable) return;
        refreshable = false;
        setRefreshing(true);
        refreshListener.onRefresh();
    }

    protected void refreshInternal() {
    }
}
