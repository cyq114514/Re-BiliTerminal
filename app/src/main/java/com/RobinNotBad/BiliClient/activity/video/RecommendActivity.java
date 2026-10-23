package com.RobinNotBad.BiliClient.activity.video;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.RefreshMainActivity;
import com.RobinNotBad.BiliClient.adapter.video.VideoCardAdapter;
import com.RobinNotBad.BiliClient.api.RecommendApi;
import com.RobinNotBad.BiliClient.helper.TutorialHelper;
import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

//推荐页面
//2023-07-13

public class RecommendActivity extends RefreshMainActivity {

    private List<VideoCard> videoCardList;
    private VideoCardAdapter videoCardAdapter;
    private boolean firstRefresh = true;

    /**
     * 客户端兜底去重：服务端换批（fresh_idx/idx 游标）已经能保证"相邻两页不重复"，
     * 但网络重试、切回旧页面、两个数据源交替时仍可能拿到同一批，这里按 aid/bvid 再挡一层。
     * 刷新（page<=1）时清空，语义与「下拉刷新=重新开始」一致。
     */
    private final Set<Long> seenAids = new HashSet<>();
    private final Set<String> seenBvids = new HashSet<>();

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setMenuClick();
        Log.e("debug", "进入推荐页");

        setOnRefreshListener(this::refreshRecommend);
        setOnLoadMoreListener(this::addRecommend);

        setPageName("推荐");

        recyclerView.setHasFixedSize(true);

        TutorialHelper.showTutorialList(this, R.array.tutorial_recommend, 0);

        refreshRecommend();
    }

    @SuppressLint("NotifyDataSetChanged")
    public void refreshRecommend() {
        Log.e("debug", "刷新");
        setRefreshing(true);
        if (firstRefresh) {
            videoCardList = new ArrayList<>();
        } else {
            int last = videoCardList.size();
            videoCardList.clear();
            videoCardAdapter.notifyItemRangeRemoved(0, last);
        }
        //刷新=重新开始：去重集合必须一起清空，否则刷新后整批都被当成"已看过"
        seenAids.clear();
        seenBvids.clear();
        setBottom(false);

        addRecommend(1);
    }

    private void addRecommend(int page) {
        Log.e("debug", "加载下一页 page=" + page);
        CenterThreadPool.run(() -> {
            try {
                List<VideoCard> list = new ArrayList<>();
                int count = RecommendApi.getRecommend(list, page);

                //去重后再上屏（只比较身份字段，不影响卡片内容）
                List<VideoCard> filtered = new ArrayList<>(list.size());
                for (VideoCard card : list) {
                    if (card == null) continue;
                    if (card.aid > 0) {
                        if (!seenAids.add(card.aid)) continue;
                    } else if (!TextUtils.isEmpty(card.bvid)) {
                        if (!seenBvids.add(card.bvid)) continue;
                    }
                    filtered.add(card);
                }
                Log.e("debug", "推荐返回 " + count + " 条，去重后 " + filtered.size() + " 条");

                final boolean emptyPage = filtered.isEmpty();
                setRefreshing(false);

                runOnUiThread(() -> {
                    if (filtered.isEmpty()) {
                        //整页都被去重/接口没数据：到底了，别再无限触发加载
                        setBottom(true);
                        if (page <= 1) {
                            MsgUtil.showMsg("推荐加载失败，下拉重试");
                        }
                        return;
                    }
                    int start = videoCardList.size();
                    videoCardList.addAll(filtered);
                    if (firstRefresh) {
                        firstRefresh = false;
                        videoCardAdapter = new VideoCardAdapter(this, videoCardList);
                        setAdapter(videoCardAdapter);
                    } else if (videoCardAdapter != null) {
                        videoCardAdapter.notifyItemRangeInserted(start, filtered.size());
                    }
                });
            } catch (Exception e) {
                loadFail(e);
            }
        });
    }
}
