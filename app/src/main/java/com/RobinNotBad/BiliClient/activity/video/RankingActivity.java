package com.RobinNotBad.BiliClient.activity.video;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity;
import com.RobinNotBad.BiliClient.adapter.video.VideoCardAdapter;
import com.RobinNotBad.BiliClient.api.RankingApi;
import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.view.ImageAutoLoadScrollListener;

import java.util.ArrayList;
import java.util.List;

public class RankingActivity extends InstanceActivity {

    private RecyclerView recyclerView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private ArrayList<VideoCard> videoCardList;
    private VideoCardAdapter videoCardAdapter;
    private boolean firstRefresh = true;
    private boolean refreshing = false;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_simple_main_refresh);
        setMenuClick();
        Log.e("debug", "进入排行榜页");

        recyclerView = findViewById(R.id.recyclerView);
        ImageAutoLoadScrollListener.install(recyclerView);
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        swipeRefreshLayout.setOnRefreshListener(this::loadRanking);

        TextView title = findViewById(R.id.pageName);
        title.setText("全站排行榜");

        loadRanking();
    }

    @SuppressLint("NotifyDataSetChanged")
    private void loadRanking() {
        Log.e("debug", "刷新");
        if (firstRefresh) {
            recyclerView.setLayoutManager(new CustomLinearManager(this));
            videoCardList = new ArrayList<>();
        } else {
            int last = videoCardList.size();
            videoCardList.clear();
            videoCardAdapter.notifyItemRangeRemoved(0, last);
        }
        swipeRefreshLayout.setRefreshing(true);

        refreshing = true;
        CenterThreadPool.run(this::addRanking);
    }

    private void addRanking() {
        Log.e("debug", "加载排行榜");
        runOnUiThread(() -> swipeRefreshLayout.setRefreshing(true));
        try {
            List<VideoCard> list = new ArrayList<>();
            RankingApi.getRanking(list, 0, "all");
            runOnUiThread(() -> {
                videoCardList.addAll(list);
                swipeRefreshLayout.setRefreshing(false);
                refreshing = false;
                if (firstRefresh) {
                    firstRefresh = false;
                    videoCardAdapter = new VideoCardAdapter(this, videoCardList);
                    recyclerView.setAdapter(videoCardAdapter);
                } else {
                    videoCardAdapter.notifyItemRangeInserted(videoCardList.size() - list.size(), list.size());
                }
            });
        } catch (Exception e) {
            runOnUiThread(() -> MsgUtil.err(e));
        }
    }
}

