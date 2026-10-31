package com.RobinNotBad.BiliClient.activity.search;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;

import com.RobinNotBad.BiliClient.adapter.article.ArticleCardAdapter;
import com.RobinNotBad.BiliClient.api.SearchApi;
import com.RobinNotBad.BiliClient.model.ArticleCard;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;

import org.json.JSONArray;

import java.util.ArrayList;

public class SearchArticleFragment extends SearchFragment {

    private ArrayList<ArticleCard> articleCardList = new ArrayList<>();
    private ArticleCardAdapter articleCardAdapter;

    public SearchArticleFragment() {
    }

    public static SearchArticleFragment newInstance() {
        return new SearchArticleFragment();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        articleCardList = new ArrayList<>();
        articleCardAdapter = new ArticleCardAdapter(requireContext(), articleCardList);
        setAdapter(articleCardAdapter);

        setOnRefreshListener(this::refreshInternal);
        setOnLoadMoreListener(this::continueLoading);
    }

    /**专栏分类的排序档位：order 值与视频相同，仅「最多播放」在专栏语境下是「最多阅读」。 */
    @Override
    public String[][] sortOptions() {
        return new String[][]{
                {"综合排序", "totalrank"},
                {"最多阅读", "click"},
                {"最新发布", "pubdate"},
                {"最多收藏", "stow"}
        };
    }

    private void continueLoading(int page) {
        final int myGeneration = loadGeneration;
        //请求前抓排序快照，代际守卫兜住中途切档的迟到响应（同 SearchVideoFragment）
        final String orderAtRequest = sortOrder;
        CenterThreadPool.run(() -> {
            Log.e("debug", "加载下一页");
            try {
                com.google.gson.JsonElement result = SearchApi.searchType(keyword, page, "article", orderAtRequest);
                //代际守卫：过期请求整体丢弃（同 SearchVideoFragment）
                if (myGeneration != loadGeneration) return;
                if (result != null) {
                    if (page == 1) showEmptyView(false);
                    if (result.isJsonArray()) {
                        ArrayList<ArticleCard> list = new ArrayList<>();
                        SearchApi.getArticlesFromSearchResult(new org.json.JSONArray(result.getAsJsonArray().toString()), list);
                        if (list.size() == 0) setBottom(true);
                        CenterThreadPool.runOnUiThread(() -> {
                        if (myGeneration != loadGeneration) return;
                        int lastSize = articleCardList.size();
                        articleCardList.addAll(list);
                        articleCardAdapter.notifyItemRangeInserted(lastSize, articleCardList.size() - lastSize);
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
            if (this.articleCardAdapter == null)
                this.articleCardAdapter = new ArticleCardAdapter(this.requireContext(), this.articleCardList);
            int size_old = this.articleCardList.size();
            this.articleCardList.clear();
            if (size_old != 0) this.articleCardAdapter.notifyItemRangeRemoved(0, size_old);
            CenterThreadPool.run(() -> continueLoading(page));
        });
    }
}
