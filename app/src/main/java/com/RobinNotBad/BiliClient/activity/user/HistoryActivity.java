package com.RobinNotBad.BiliClient.activity.user;

import android.os.Bundle;

import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity;
import com.RobinNotBad.BiliClient.adapter.video.VideoCardAdapter;
import com.RobinNotBad.BiliClient.api.HistoryApi;
import com.RobinNotBad.BiliClient.model.ApiResult;
import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;

import java.util.ArrayList;
import java.util.List;

//历史记录
//2023-08-18
//2024-04-30

public class HistoryActivity extends RefreshListActivity {

    private ApiResult lastResult = new ApiResult();
    private ArrayList<VideoCard> videoList;
    private VideoCardAdapter videoCardAdapter;

    //代际守卫：滚动触发的"加载更多"可能在上一次在途时再次被触发（setRefreshing 是
    //post 到主线程的，isRefreshing 挡不住连续回调），共享的 lastResult 游标被并发
    //getHistory 交叉读写会导致重复/漏条目
    private volatile boolean loading = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setPageName("历史记录");

        recyclerView.setHasFixedSize(true);

        videoList = new ArrayList<>();

        //注册下拉刷新：基类默认禁用且此前从未注册，加载失败后只能退出重进（D-01/X-03）
        setOnRefreshListener(this::loadHistory);
        loadHistory();
    }

    private void loadHistory() {
        if (loading) return;
        loading = true;
        CenterThreadPool.run(() -> {
            try {
                lastResult = HistoryApi.getHistory(lastResult, videoList);
                if (lastResult.code == 0) {
                    runOnUiThread(() -> {
                        if (videoCardAdapter == null) {
                            videoCardAdapter = new VideoCardAdapter(this, videoList);
                            setOnLoadMoreListener(this::continueLoading);
                            setAdapter(videoCardAdapter);
                        } else {
                            videoCardAdapter.notifyDataSetChanged();
                        }
                    });
                    if (lastResult.isBottom) {
                        setBottom(true);
                    }
                    setRefreshing(false);
                    //空历史此前从不调 showEmptyView，整页纯空白（D-01）
                    if (videoList.isEmpty()) showEmptyView("这里还什么都没有~");
                    else hideEmptyView();
                } else {
                    //失败也要关圈：此前只弹 toast，初始转圈永久卡住（D-01）
                    setRefreshing(false);
                    if (videoList.isEmpty()) showEmptyView("加载失败：" + lastResult.message + "\n下拉可重试");
                    MsgUtil.showMsg(lastResult.message);
                }
            } catch (Exception e) {
                loadFail(e);
                if (videoList.isEmpty()) showEmptyView("加载失败，请检查网络后下拉重试");
            } finally {
                loading = false;
            }
        });
    }

    private void continueLoading(int page) {
        if (loading) return;
        loading = true;
        CenterThreadPool.run(() -> {
            try {
                List<VideoCard> list = new ArrayList<>();
                ApiResult result = HistoryApi.getHistory(lastResult, list);
                if (result.code == 0) {
                    lastResult = result;
                    runOnUiThread(() -> {
                        videoList.addAll(list);
                        videoCardAdapter.notifyItemRangeInserted(videoList.size() - list.size(), list.size());
                    });
                    if (result.isBottom) {
                        setBottom(true);
                    }
                }
                setRefreshing(false);
            } catch (Exception e) {
                loadFail(e);
            } finally {
                loading = false;
            }
        });
    }
}