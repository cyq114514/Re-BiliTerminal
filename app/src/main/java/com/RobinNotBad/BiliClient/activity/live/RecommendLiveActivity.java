package com.RobinNotBad.BiliClient.activity.live;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.util.Log;

import com.RobinNotBad.BiliClient.activity.base.RefreshMainActivity;
import com.RobinNotBad.BiliClient.adapter.LiveCardAdapter;
import com.RobinNotBad.BiliClient.api.LiveApi;
import com.RobinNotBad.BiliClient.model.LiveRoom;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;

import java.util.ArrayList;
import java.util.List;

public class RecommendLiveActivity extends RefreshMainActivity {
    private List<LiveRoom> roomList;
    private LiveCardAdapter adapter;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setPageName("推荐直播");

        recyclerView.setHasFixedSize(true);

        roomList = new ArrayList<>();

        setMenuClick();

        CenterThreadPool.run(() -> {
            try {
                roomList = LiveApi.getRecommend(page);
                //getRecommend 失败时返回 null 而不抛异常（LiveApi 统一约定），
                //必须当加载失败处理；否则 roomList 被赋成 null，继续放行到
                //加载更多就是「addAll 空接收者」NPE（1.2.9 实测崩溃）
                if (roomList == null) throw new Exception("直播间列表为空");
                adapter = new LiveCardAdapter(this, roomList);
                setOnLoadMoreListener(this::continueLoading);
                setRefreshing(false);
                setAdapter(adapter);
            } catch (Exception e) {
                loadFail(e);
            }
        });
    }

    private void continueLoading(int page) {
        CenterThreadPool.run(() -> {
            try {
                List<LiveRoom> list;
                list = LiveApi.getRecommend(page);
                Log.e("debug", "下一页");
                runOnUiThread(() -> {
                    //接收者同样要判空：参数判空挡不住 roomList/adapter 本身为 null 的 NPE
                    if (list != null && roomList != null && adapter != null) {
                        roomList.addAll(list);
                        adapter.notifyItemRangeInserted(roomList.size() - list.size(), list.size());
                    }
                });
                if (list != null && list.size() < 1) {
                    Log.e("debug", "到底了");
                    setBottom(true);
                }
                if (list == null) loadFail(new Exception("下一页加载失败"));
                setRefreshing(false);
            } catch (Exception e) {
                loadFail(e);
            }
        });
    }
}
