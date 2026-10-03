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

    private void continueLoading(int page) {
        final int myGeneration = loadGeneration;
        CenterThreadPool.run(() -> {
            Log.e("debug", "加载下一页");
            try {
                JSONArray result = SearchApi.search(keyword, page);
                //代际守卫：刷新/换关键词后，旧关键词的迟到请求在此整体丢弃，
                //不把旧内容 addAll 进新列表，也不许它动 empty/refreshing/底部状态
                if (myGeneration != loadGeneration) return;
                if (result != null) {
                    if (page == 1) showEmptyView(false);
                    ArrayList<VideoCard> list = new ArrayList<>();
                    SearchApi.getVideosFromSearchResult(result, list, page == 1);
                    Log.d("debug-size", String.valueOf(list.size()));
                    if (list.size() == 0) setBottom(true);
                    else CenterThreadPool.runOnUiThread(() -> {
                        if (myGeneration != loadGeneration) return;
                        int lastSize = videoCardList.size();
                        videoCardList.addAll(list);
                        videoCardAdapter.notifyItemRangeInserted(lastSize, videoCardList.size() - lastSize);
                    });
                } else setBottom(true);
                setRefreshing(false);
            } catch (Exception e) {
                e.printStackTrace();
                if (myGeneration == loadGeneration) loadFail(e);
            }
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
