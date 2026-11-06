package com.RobinNotBad.BiliClient.activity.live;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.util.Log;

import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity;
import com.RobinNotBad.BiliClient.adapter.LiveCardAdapter;
import com.RobinNotBad.BiliClient.api.LiveApi;
import com.RobinNotBad.BiliClient.model.LiveRoom;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;

import java.util.ArrayList;
import java.util.List;

public class FollowLiveActivity extends RefreshListActivity {
    private List<LiveRoom> roomList;
    private LiveCardAdapter adapter;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setPageName("我关注的直播");

        recyclerView.setHasFixedSize(true);

        roomList = new ArrayList<>();

        CenterThreadPool.run(() -> {
            try {
                roomList = LiveApi.getFollowed(page);
                //同 RecommendLiveActivity：getFollowed 失败返回 null 而不抛异常，
                //放行下去 roomList 为 null，下面 roomList.size() 和加载更多都会 NPE
                if (roomList == null) throw new Exception("关注列表为空");
                adapter = new LiveCardAdapter(this, roomList);
                setOnLoadMoreListener(this::continueLoading);
                setRefreshing(false);
                setAdapter(adapter);
                if (roomList.size() < 1) showEmptyView();
            } catch (Exception e) {
                loadFail(e);
            }
        });
    }

    private void continueLoading(int page) {
        CenterThreadPool.run(() -> {
            try {
                List<LiveRoom> list;
                list = LiveApi.getFollowed(page);
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
