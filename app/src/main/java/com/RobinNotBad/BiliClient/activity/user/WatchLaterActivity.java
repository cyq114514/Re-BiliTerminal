package com.RobinNotBad.BiliClient.activity.user;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity;
import com.RobinNotBad.BiliClient.adapter.video.VideoCardAdapter;
import com.RobinNotBad.BiliClient.api.WatchLaterApi;
import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.ui.theme.ThemeManager;
import com.RobinNotBad.BiliClient.util.AdaptiveDialog;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;

import java.util.ArrayList;

//稍后再看
//2023-08-17
//2026-10-31 新增「全部 / 未看完」筛选与「清除已看完」（服务端 viewed / clear 接口）

public class WatchLaterActivity extends RefreshListActivity {

    private int longClickPosition = -1;
    private long longClickTimestamp;

    /** 接口返回的当前档位列表；adapter 持有同一个引用，删除时两边是同一张表 */
    private final ArrayList<VideoCard> cards = new ArrayList<>();
    private VideoCardAdapter adapter;

    /** 当前筛选档位，见 {@link WatchLaterApi#VIEWED_ALL} */
    private int viewed = WatchLaterApi.VIEWED_ALL;

    private TextView filterAll, filterUnfinished;
    private int unselectedColor;
    private boolean loading;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPageName("稍后再看");
        recyclerView.setHasFixedSize(true);
        adapter = new VideoCardAdapter(this, cards);
        setAdapter(adapter);

        findViewById(R.id.filterBar).setVisibility(View.VISIBLE);
        filterAll = findViewById(R.id.filterAll);
        filterUnfinished = findViewById(R.id.filterUnfinished);
        // 未选中态直接用布局里的次要文字色，避免再引一份颜色常量
        unselectedColor = filterAll.getCurrentTextColor();
        filterAll.setOnClickListener(view -> switchFilter(WatchLaterApi.VIEWED_ALL));
        filterUnfinished.setOnClickListener(view -> switchFilter(WatchLaterApi.VIEWED_UNFINISHED));

        TextView clearWatched = findViewById(R.id.clearWatched);
        clearWatched.setVisibility(View.VISIBLE);
        clearWatched.setOnClickListener(view -> confirmClearWatched());

        updateFilterColors();
        load();
    }

    /** 筛选是服务端参数，切档位必须重新请求：本地这张表里只有当前档位的数据 */
    private void switchFilter(int target) {
        if (loading || viewed == target) {
            updateFilterColors();
            return;
        }
        viewed = target;
        updateFilterColors();
        load();
    }

    private void updateFilterColors() {
        if (filterAll == null || filterUnfinished == null) return;
        int selected = ThemeManager.getPrimary();
        filterAll.setTextColor(viewed == WatchLaterApi.VIEWED_ALL ? selected : unselectedColor);
        filterUnfinished.setTextColor(viewed == WatchLaterApi.VIEWED_UNFINISHED ? selected : unselectedColor);
    }

    private void load() {
        if (loading) return;
        loading = true;
        setRefreshing(true);
        CenterThreadPool.run(() -> {
            try {
                ArrayList<VideoCard> loaded = WatchLaterApi.getWatchLaterList(viewed);
                runOnUiThread(() -> {
                    cards.clear();
                    cards.addAll(loaded);
                    adapter.notifyDataSetChanged();
                    if (cards.isEmpty()) showEmptyView();
                    else hideEmptyView();
                    loading = false;
                    setRefreshing(false);
                    bindLongClickDelete();
                });
            } catch (Exception e) {
                loading = false;
                loadFail(e);
            }
        });
    }

    private void confirmClearWatched() {
        new AdaptiveDialog.Builder(this)
                .setTitle("清除已看完")
                .setMessage("将删除「稍后再看」里所有已看完的稿件，此操作不可撤销。")
                .setPositiveButton("清除", (dialog, which) -> clearWatched())
                .setNegativeButton("取消", null)
                .show();
    }

    private void clearWatched() {
        CenterThreadPool.run(() -> {
            try {
                int result = WatchLaterApi.clearWatched();
                runOnUiThread(() -> {
                    if (result == 0) {
                        MsgUtil.showMsg("已清除已看完");
                        load();
                    } else {
                        MsgUtil.showMsg("清除失败，错误码：" + result);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> MsgUtil.showMsg("清除失败：" + e.getMessage()));
            }
        });
    }

    private void bindLongClickDelete() {
        adapter.setOnLongClickListener(position -> {
            if (position < 0 || position >= cards.size()) return;
            long timestamp = System.currentTimeMillis();
            if (longClickPosition == position && timestamp - longClickTimestamp < 4000) {
                CenterThreadPool.run(() -> {
                    try {
                        int result = WatchLaterApi.delete(cards.get(position).aid);
                        longClickPosition = -1;
                        runOnUiThread(() -> {
                            if (result == 0) {
                                MsgUtil.showMsg("删除成功");
                                if (position < cards.size()) {
                                    cards.remove(position);
                                    adapter.notifyItemRemoved(position);
                                    adapter.notifyItemRangeChanged(position, cards.size() - position);
                                }
                                if (cards.isEmpty()) showEmptyView();
                            } else {
                                MsgUtil.showMsg("删除失败，错误码：" + result);
                            }
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> MsgUtil.showMsg("删除失败：" + e.getMessage()));
                    }
                });
            } else {
                longClickPosition = position;
                longClickTimestamp = timestamp;
                MsgUtil.showMsg("再次长按删除");
            }
        });
    }
}
