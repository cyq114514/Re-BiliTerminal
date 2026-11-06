package com.RobinNotBad.BiliClient.activity.video;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity;
import com.RobinNotBad.BiliClient.activity.base.RefreshMainActivity;
import com.RobinNotBad.BiliClient.adapter.TimelineAdapter;
import com.RobinNotBad.BiliClient.api.TimelineApi;
import com.RobinNotBad.BiliClient.model.Timeline;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;

import java.util.ArrayList;
import java.util.List;

public class TimelineActivity extends RefreshMainActivity {

    private List<Timeline.DayInfo> dayInfoList;
    private TimelineAdapter adapter;
    private String types = "1";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //番剧分区统一：点标题栏返回。布局 activity_simple_main_refresh 被推荐/动态等页共用，
        //不能改 XML 图标，只在本页把标题的箭头换成返回样式
        setTopbarExit();
        setPageName("时间线");
        android.widget.TextView pageName = findViewById(com.RobinNotBad.BiliClient.R.id.pageName);
        if (pageName != null)
            pageName.setCompoundDrawablesWithIntrinsicBounds(
                    com.RobinNotBad.BiliClient.R.drawable.arrow_back, 0, 0, 0);

        dayInfoList = new ArrayList<>();

        setOnRefreshListener(() -> {
            dayInfoList.clear();
            loadTimeline();
        });

        loadTimeline();
    }

    private void loadTimeline() {
        swipeRefreshLayout.setRefreshing(true);
        CenterThreadPool.run(() -> {
            try {
                List<Timeline.DayInfo> result = TimelineApi.getTimeline(types, 7, 7);
                runOnUiThread(() -> {
                    dayInfoList.addAll(result);
                    if (adapter == null) {
                        adapter = new TimelineAdapter(this, dayInfoList);
                        recyclerView.setAdapter(adapter);
                    } else {
                        adapter.notifyDataSetChanged();
                    }
                    swipeRefreshLayout.setRefreshing(false);
                    if (dayInfoList.isEmpty()) {
                        recyclerView.setVisibility(View.GONE);
                    } else {
                        recyclerView.setVisibility(View.VISIBLE);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    swipeRefreshLayout.setRefreshing(false);
                    report(e);
                    MsgUtil.showMsgLong("加载失败");
                });
            }
        });
    }
}
