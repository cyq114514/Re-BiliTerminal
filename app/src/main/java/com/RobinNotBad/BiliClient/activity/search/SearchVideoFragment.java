package com.RobinNotBad.BiliClient.activity.search;

import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;

import com.RobinNotBad.BiliClient.adapter.video.VideoCardAdapter;
import com.RobinNotBad.BiliClient.api.SearchApi;
import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;

import org.json.JSONArray;

import java.util.ArrayList;

public class SearchVideoFragment extends SearchFragment {
    private ArrayList<VideoCard> videoCardList = new ArrayList<>();
    private VideoCardAdapter videoCardAdapter;

    public SearchVideoFragment() {
    }

    public static SearchVideoFragment newInstance() {
        return new SearchVideoFragment();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        videoCardList = new ArrayList<>();
        videoCardAdapter = new VideoCardAdapter(requireContext(), videoCardList);
        setAdapter(videoCardAdapter);

        setOnRefreshListener(this::refreshInternal);
        setOnLoadMoreListener(this::continueLoading);
    }

    /**视频分类的排序档位。综合排序走聚合接口 all/v2，其余档位走分类接口 search/type（见 continueLoading）。 */
    @Override
    public String[][] sortOptions() {
        return new String[][]{
                {"综合排序", "totalrank"},
                {"最多播放", "click"},
                {"最新发布", "pubdate"},
                {"最多收藏", "stow"}
        };
    }

    private void continueLoading(int page) {
        final int myGeneration = loadGeneration;
        //请求前抓排序快照：排序/关键词中途变化都会自增 loadGeneration，
        //迟到响应凭代际整体作废，不会把旧排序的結果 addAll 进新列表
        final String orderAtRequest = sortOrder;
        CenterThreadPool.run(() -> {
            Log.e("debug", "加载下一页");
            try {
                ArrayList<VideoCard> list = new ArrayList<>();
                if (orderAtRequest.equals("totalrank")) {
                    //综合排序走聚合搜索 all/v2：它会把番剧结果混进第一页，且该接口不支持 order
                    JSONArray result = SearchApi.search(keyword, page);
                    if (myGeneration != loadGeneration) return;
                    if (result != null) {
                        if (page == 1) showEmptyView(false);
                        SearchApi.getVideosFromSearchResult(result, list, page == 1);
                        appendPage(myGeneration, list);
                    } else setBottom(true);
                } else {
                    com.google.gson.JsonElement result = SearchApi.searchType(keyword, page, "video", orderAtRequest);
                    if (myGeneration != loadGeneration) return;
                    if (result != null) {
                        if (page == 1) showEmptyView(false);
                        SearchApi.getVideosFromTypeResult(result, list);
                        appendPage(myGeneration, list);
                    } else setBottom(true);
                }
                setRefreshing(false);
            } catch (Exception e) {
                e.printStackTrace();
                if (myGeneration == loadGeneration) loadFail(e);
            }
        });
    }

    /**页面追加与到底判断（从 continueLoading 抽出，两条接口分支共用）。 */
    private void appendPage(int myGeneration, ArrayList<VideoCard> list) {
        Log.d("debug-size", String.valueOf(list.size()));
        if (list.size() == 0) setBottom(true);
        else CenterThreadPool.runOnUiThread(() -> {
            if (myGeneration != loadGeneration) return;
            int lastSize = videoCardList.size();
            videoCardList.addAll(list);
            videoCardAdapter.notifyItemRangeInserted(lastSize, videoCardList.size() - lastSize);
        });
    }

    public void refreshInternal() {
        CenterThreadPool.runOnUiThread(() -> {
            loadGeneration++;   //清空前先作废在途的旧页请求
            page = 1;
            if (this.videoCardAdapter == null)
                this.videoCardAdapter = new VideoCardAdapter(this.requireContext(), this.videoCardList);
            int size_old = this.videoCardList.size();
            this.videoCardList.clear();
            if (size_old != 0) this.videoCardAdapter.notifyItemRangeRemoved(0, size_old);
            CenterThreadPool.run(() -> continueLoading(page));
        });
    }
}
