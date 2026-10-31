package com.RobinNotBad.BiliClient.activity.user.favorite;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity;
import com.RobinNotBad.BiliClient.adapter.video.VideoCardAdapter;
import com.RobinNotBad.BiliClient.api.FavoriteApi;
import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.ui.theme.ThemeManager;
import com.RobinNotBad.BiliClient.util.AdaptiveDialog;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;

import java.util.ArrayList;
import java.util.Set;

//收藏夹内
//2023-08-08
//2024-05-01
//2026-10-31 新增收藏时间/播放量/投稿时间排序（服务端 order 参数）与多选批量取消收藏

public class FavoriteVideoListActivity extends RefreshListActivity {

    private long mid;
    private long fid;
    /** v3 内容接口（x/v3/fav/resource/list）只认 media_id，fid 仅作兜底换算 */
    private long mediaId;
    private ArrayList<VideoCard> videoList;
    private VideoCardAdapter videoCardAdapter;

    private int longClickPosition = -1;
    private long longClickTimestamp;

    private String order = FavoriteApi.ORDER_FAV_TIME;
    private TextView sortByFavTime, sortByView, sortByPubtime, manageEntries;
    private int unselectedSortColor;
    private boolean managing = false;
    private boolean loading = false;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        mid = intent.getLongExtra("mid", 0);
        fid = intent.getLongExtra("fid", 0);
        mediaId = intent.getLongExtra("mediaId", 0);
        //老入口（或第三方拉起）只带 fid 时按 B 站规则换算：media_id = fid*100 + mid 后两位
        if (mediaId <= 0) mediaId = FavoriteApi.mediaIdOf(mid, fid);
        String name = intent.getStringExtra("name");

        setPageName(name);

        videoList = new ArrayList<>();
        videoCardAdapter = new VideoCardAdapter(this, videoList);
        bindLongClickDelete();
        setOnLoadMoreListener(this::continueLoading);
        setAdapter(videoCardAdapter);

        setupSortBar();
        reloadFromFirst();
    }

    private void setupSortBar() {
        View sortBar = findViewById(R.id.sortBar);
        sortBar.setVisibility(View.VISIBLE);
        sortByFavTime = findViewById(R.id.sortByFavTime);
        sortByView = findViewById(R.id.sortByView);
        sortByPubtime = findViewById(R.id.sortByPubtime);
        unselectedSortColor = sortByFavTime.getCurrentTextColor();
        sortByFavTime.setOnClickListener(view -> switchOrder(FavoriteApi.ORDER_FAV_TIME));
        sortByView.setOnClickListener(view -> switchOrder(FavoriteApi.ORDER_VIEW));
        sortByPubtime.setOnClickListener(view -> switchOrder(FavoriteApi.ORDER_PUBTIME));
        updateSortColors();

        manageEntries = findViewById(R.id.manageEntries);
        manageEntries.setVisibility(View.VISIBLE);
        manageEntries.setOnClickListener(view -> toggleManage());
    }

    private void switchOrder(String target) {
        if (order.equals(target)) {
            updateSortColors();
            return;
        }
        order = target;
        updateSortColors();
        reloadFromFirst();
    }

    private void updateSortColors() {
        if (sortByFavTime == null) return;
        int selected = ThemeManager.getPrimary();
        sortByFavTime.setTextColor(order.equals(FavoriteApi.ORDER_FAV_TIME) ? selected : unselectedSortColor);
        sortByView.setTextColor(order.equals(FavoriteApi.ORDER_VIEW) ? selected : unselectedSortColor);
        sortByPubtime.setTextColor(order.equals(FavoriteApi.ORDER_PUBTIME) ? selected : unselectedSortColor);
    }

    /** 进入/退出多选管理。退出时若有选中项，先确认再批量取消收藏 */
    private void toggleManage() {
        if (!managing) {
            managing = true;
            videoCardAdapter.setSelectionMode(true);
            manageEntries.setText("取消管理");
            MsgUtil.showMsg("点选要取消收藏的稿件，选完点「取消管理」执行");
            return;
        }
        int count = videoCardAdapter.getSelectedCount();
        if (count == 0) {
            exitManage();
            return;
        }
        new AdaptiveDialog.Builder(this)
                .setTitle("取消收藏")
                .setMessage("将取消收藏选中的 " + count + " 个稿件，此操作不可撤销。")
                .setPositiveButton("取消收藏", (dialog, which) -> deleteSelected(videoCardAdapter.getSelectedAids()))
                .setNegativeButton("取消", null)
                .show();
    }

    private void exitManage() {
        managing = false;
        videoCardAdapter.setSelectionMode(false);
        manageEntries.setText("管理");
    }

    /** 逐条调取消收藏接口：batch-deal 的 resources 支持多条，但为沿用与单条删除完全一致的
     *  既有链路（错误码语义、media_id 换算），这里按条循环，数量通常不大 */
    private void deleteSelected(Set<Long> aids) {
        CenterThreadPool.run(() -> {
            int ok = 0, fail = 0, lastCode = 0;
            for (long aid : aids) {
                try {
                    int result = FavoriteApi.deleteFavorite(aid, mediaId);
                    if (result == 0) ok++;
                    else {
                        fail++;
                        lastCode = result;
                    }
                } catch (Exception e) {
                    fail++;
                }
            }
            final int okCount = ok, failCount = fail, errCode = lastCode;
            runOnUiThread(() -> {
                MsgUtil.showMsg("取消收藏完成：成功 " + okCount + " 个"
                        + (failCount > 0 ? "，失败 " + failCount + " 个（错误码 " + errCode + "）" : ""));
                exitManage();
                reloadFromFirst();
            });
        });
    }

    /** 清空列表从第一页重来：切排序、批量删除之后都走这里 */
    private void reloadFromFirst() {
        if (loading) return;
        loading = true;
        page = 1;
        setBottom(false);
        videoList.clear();
        videoCardAdapter.notifyDataSetChanged();
        setRefreshing(true);
        CenterThreadPool.run(() -> loadPage(page));
    }

    private void loadPage(int pageNumber) {
        try {
            int lastSize = videoList.size();
            int result = FavoriteApi.getFolderVideos(mediaId, pageNumber, order, videoList);
            runOnUiThread(() -> {
                loading = false;
                setRefreshing(false);
                if (result != -1) {
                    videoCardAdapter.notifyItemRangeInserted(lastSize, videoList.size() - lastSize);
                    if (videoList.isEmpty()) showEmptyView();
                    else hideEmptyView();
                    if (result == 1) {
                        Log.e("debug", "到底了");
                        setBottom(true);
                    }
                }
            });
        } catch (Exception e) {
            loading = false;
            loadFail(e);
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private void continueLoading(int pageNumber) {
        if (loading) return;
        loading = true;
        setRefreshing(true);
        CenterThreadPool.run(() -> loadPage(pageNumber));
    }

    /** 长按两次删除单条（历史行为，保留） */
    private void bindLongClickDelete() {
        videoCardAdapter.setOnLongClickListener(position -> {
            long timestamp = System.currentTimeMillis();
            if (longClickPosition == position && timestamp - longClickTimestamp < 4000) {
                CenterThreadPool.run(() -> {
                    try {
                        int delResult = FavoriteApi.deleteFavorite(videoList.get(position).aid, mediaId);
                        longClickPosition = -1;
                        if (delResult == 0) runOnUiThread(() -> {
                            MsgUtil.showMsg("删除成功");
                            if (position < videoList.size()) {
                                videoList.remove(position);
                                videoCardAdapter.notifyItemRemoved(position);
                                videoCardAdapter.notifyItemRangeChanged(position, videoList.size() - position);
                            }
                            if (videoList.isEmpty()) showEmptyView();
                        });
                        else
                            runOnUiThread(() -> MsgUtil.showMsg("删除失败，错误码：" + delResult));
                    } catch (Exception e) {
                        report(e);
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
