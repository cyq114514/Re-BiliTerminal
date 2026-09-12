package com.RobinNotBad.BiliClient.activity.user;

import android.os.Bundle;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.adapter.LoginRecordAdapter;
import com.RobinNotBad.BiliClient.api.LoginRecordApi;
import com.RobinNotBad.BiliClient.model.LoginRecord;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import java.util.ArrayList;
import java.util.List;

public class LoginRecordActivity extends BaseActivity {

    private RecyclerView recyclerView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private List<LoginRecord> recordList;
    private LoginRecordAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_simple_refresh);

        setPageName("登录记录");

        recyclerView = findViewById(R.id.recyclerView);
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);

        swipeRefreshLayout.setEnabled(false);
        swipeRefreshLayout.setRefreshing(true);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        recordList = new ArrayList<>();

        CenterThreadPool.run(() -> {
            try {
                long mid = SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0);
                String buvid = "";

                recordList = LoginRecordApi.getLoginRecord(mid, buvid);

                runOnUiThread(() -> {
                    if (recordList.isEmpty()) {
                        MsgUtil.showMsg("暂无登录记录");
                        findViewById(R.id.emptyTip).setVisibility(View.VISIBLE);
                    } else {
                        adapter = new LoginRecordAdapter(this, recordList);
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

