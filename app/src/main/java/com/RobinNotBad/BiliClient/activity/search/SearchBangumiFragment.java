package com.RobinNotBad.BiliClient.activity.search;

import android.annotation.SuppressLint;
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

//搜索结果-番剧页
public class SearchBangumiFragment extends SearchFragment {

    private ArrayList<VideoCard> bangumiCardList = new ArrayList<>();
    private VideoCardAdapter bangumiCardAdapter;

    public SearchBangumiFragment() {
    }

    public static SearchBangumiFragment newInstance() {
        return new SearchBangumiFragment();
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        bangumiCardList = new ArrayList<>();
        bangumiCardAdapter = new VideoCardAdapter(requireContext(), bangumiCardList);
        setAdapter(bangumiCardAdapter);

        setOnRefreshListener(this::refreshInternal);
        setOnLoadMoreListener(this::continueLoading);
    }

    private void continueLoading(int page) {
        final int myGeneration = loadGeneration;
        CenterThreadPool.run(() -> {
            Log.e("debug", "加载下一页");
            try {
                com.google.gson.JsonElement result = SearchApi.searchType(keyword, page, "media_bangumi");
                //代际守卫：过期请求整体丢弃（同 SearchVideoFragment）
                if (myGeneration != loadGeneration) return;
                if (result != null) {
                    if (page == 1) showEmptyView(false);
                    if (result.isJsonArray()) {
                        ArrayList<VideoCard> list = new ArrayList<>();
                        SearchApi.getBangumiFromSearchResult(new JSONArray(result.getAsJsonArray().toString()), list);
                        if (list.size() == 0) setBottom(true);
                        else CenterThreadPool.runOnUiThread(() -> {
                            if (myGeneration != loadGeneration) return;
                            //列表只在主线程变更，避免 RecyclerView bind 时读到被后台线程修改的数据
                            int lastSize = bangumiCardList.size();
                            bangumiCardList.addAll(list);
                            bangumiCardAdapter.notifyItemRangeInserted(lastSize, bangumiCardList.size() - lastSize);
                        });
                    } else setBottom(true);
                } else setBottom(true);
                setRefreshing(false);
            } catch (Exception e) {
                report(e);
                if (myGeneration == loadGeneration) setRefreshing(false);
            }
        });
    }

    public void refreshInternal() {
        CenterThreadPool.runOnUiThread(() -> {
            loadGeneration++;   //清空前先作废在途的旧页请求
            page = 1;
            if (this.bangumiCardAdapter == null)
                this.bangumiCardAdapter = new VideoCardAdapter(this.requireContext(), this.bangumiCardList);
            int size_old = this.bangumiCardList.size();
            this.bangumiCardList.clear();
            if (size_old != 0) this.bangumiCardAdapter.notifyItemRangeRemoved(0, size_old);
            CenterThreadPool.run(() -> continueLoading(page));
        });
    }
}
