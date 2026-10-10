package com.RobinNotBad.BiliClient.activity.user;

import android.os.Bundle;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.adapter.CoinLogAdapter;
import com.RobinNotBad.BiliClient.api.CoinLogApi;
import com.RobinNotBad.BiliClient.model.CoinLog;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.UserLogStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class CoinLogActivity extends BaseActivity {

    //本地累积的存储前缀（实际 key 还会带上 mid，见 UserLogStore）
    private static final String STORE_KEY = "coin_log_history";

    private RecyclerView recyclerView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private List<CoinLog> logList;
    private CoinLogAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_simple_refresh);

        setPageName("硬币变化记录");

        recyclerView = findViewById(R.id.recyclerView);
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);

        swipeRefreshLayout.setEnabled(false);
        swipeRefreshLayout.setRefreshing(true);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        logList = new ArrayList<>();

        CenterThreadPool.run(() -> {
            try {
                List<CoinLog> fresh = CoinLogApi.getCoinLog();
                //接口仅能查询最近一周（社区文档），没有分页参数，所以用本地累积补历史：
                //每次打开把新记录并入本地并去重，越用越全
                JSONArray merged = UserLogStore.merge(STORE_KEY, toJson(fresh));
                List<CoinLog> show = fromJson(merged);
                final boolean localHistoryMerged = show.size() > fresh.size();

                runOnUiThread(() -> {
                    if (show.isEmpty()) {
                        MsgUtil.showMsg("暂无硬币变化记录");
                        findViewById(R.id.emptyTip).setVisibility(View.VISIBLE);
                    } else {
                        logList.clear();
                        logList.addAll(show);
                        adapter = new CoinLogAdapter(this, logList);
                        recyclerView.setAdapter(adapter);
                        if (localHistoryMerged)
                            MsgUtil.showMsgLong("已合并本机累积的历史记录\n（接口仅提供最近一周，更早记录由本机累积）");
                    }
                    swipeRefreshLayout.setRefreshing(false);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    MsgUtil.showMsg("加载失败：" + e.getMessage());
                    swipeRefreshLayout.setRefreshing(false);
                    //网络失败时本地累积仍然可用，别让页面变成一片空白
                    List<CoinLog> show = fromJson(UserLogStore.load(STORE_KEY));
                    if (!show.isEmpty()) {
                        logList.clear();
                        logList.addAll(show);
                        adapter = new CoinLogAdapter(this, logList);
                        recyclerView.setAdapter(adapter);
                    } else {
                        findViewById(R.id.emptyTip).setVisibility(View.VISIBLE);
                    }
                });
                e.printStackTrace();
            }
        });
    }

    private static JSONArray toJson(List<CoinLog> list) {
        JSONArray array = new JSONArray();
        if (list == null) return array;
        for (CoinLog log : list) {
            if (log == null) continue;
            JSONObject item = new JSONObject();
            try {
                item.put("time", log.time);
                item.put("delta", log.delta);
                item.put("reason", log.reason);
            } catch (Exception e) {
                continue;
            }
            array.put(item);
        }
        return array;
    }

    private static List<CoinLog> fromJson(JSONArray array) {
        List<CoinLog> list = new ArrayList<>();
        if (array == null) return list;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            list.add(new CoinLog(item.optString("time", ""), item.optInt("delta", 0), item.optString("reason", "")));
        }
        return list;
    }
}
