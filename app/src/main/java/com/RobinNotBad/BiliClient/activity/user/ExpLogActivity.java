package com.RobinNotBad.BiliClient.activity.user;

import android.os.Bundle;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.adapter.ExpLogAdapter;
import com.RobinNotBad.BiliClient.api.ExpLogApi;
import com.RobinNotBad.BiliClient.model.ExpLog;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;

import java.util.ArrayList;
import java.util.List;

public class ExpLogActivity extends BaseActivity {

    private RecyclerView recyclerView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private List<ExpLog> logList;
    private ExpLogAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_simple_refresh);

        setPageName("经验变化记录");

        recyclerView = findViewById(R.id.recyclerView);
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);

        swipeRefreshLayout.setEnabled(false);
        swipeRefreshLayout.setRefreshing(true);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        logList = new ArrayList<>();

        CenterThreadPool.run(() -> {
            try {
                logList = ExpLogApi.getExpLog();

                runOnUiThread(() -> {
                    if (logList.isEmpty()) {
                        MsgUtil.showMsg("暂无经验变化记录");
                        findViewById(R.id.emptyTip).setVisibility(View.VISIBLE);
                    } else {
                        adapter = new ExpLogAdapter(this, logList);
                        recyclerView.setAdapter(adapter);
                    }
                    swipeRefreshLayout.setRefreshing(false);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    MsgUtil.showMsg("加载失败：" + e.getMessage());
                    swipeRefreshLayout.setRefreshing(false);
                    findViewById(R.id.emptyTip).setVisibility(View.VISIBLE);
                });
                e.printStackTrace();
            }
        });
    }
}

