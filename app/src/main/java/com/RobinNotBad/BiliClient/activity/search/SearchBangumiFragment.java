package com.RobinNotBad.BiliClient.activity.search;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;

import com.RobinNotBad.BiliClient.adapter.video.VideoCardAdapter;
import com.RobinNotBad.BiliClient.api.BgmApi;
import com.RobinNotBad.BiliClient.api.SearchApi;
import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.model.bgm.BgmSubject;
import com.RobinNotBad.BiliClient.source.SourceManager;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.Logu;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 搜索结果-番剧页。
 *
 * <p><b>2026-11 增补</b>：B 站结果出完后，再用同一关键词搜一次 Bangumi，把 B 站没有的番剧
 * 以带封面的卡片补在末尾（`type = third_bgm`）。点这类卡片进「选择片源」页，
 * 由各第三方源提供资源——这样「B 站没有的番剧」既有规范标题也有封面，
 * 不用再看站点那种「第X季 1080P」的脏标题。
 *
 * <p>为什么是 Bangumi 而不是直接拿站点结果混排：站点搜索没有封面，标题也不可信，
 * 混进来只会让列表变脏（上一版就是这个毛病）。Bangumi 只做元数据，站点只做资源。
 */
public class SearchBangumiFragment extends SearchFragment {

    /** 最多补多少条 Bangumi 条目，避免把 B 站结果顶出屏幕 */
    private static final int MERGE_RESULT_LIMIT = 10;

    private ArrayList<VideoCard> bangumiCardList = new ArrayList<>();
    private VideoCardAdapter bangumiCardAdapter;
    /** 已为哪一代列表补过 Bangumi 结果：同一次搜索只补一次 */
    private int mergedGeneration = -1;
    private int mergedCount = 0;

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
                        if (list.size() == 0) {
                            setBottom(true);
                            if (page == 1) appendBangumiExtras(myGeneration);
                        } else CenterThreadPool.runOnUiThread(() -> {
                            if (myGeneration != loadGeneration) return;
                            //列表只在主线程变更，避免 RecyclerView bind 时读到被后台线程修改的数据
                            int lastSize = bangumiCardList.size();
                            bangumiCardList.addAll(list);
                            bangumiCardAdapter.notifyItemRangeInserted(lastSize, bangumiCardList.size() - lastSize);
                            //补漏必须排在 B 站卡片真正入列表之后，否则顺序颠倒、notify 下标错位
                            if (page == 1) appendBangumiExtras(myGeneration);
                        });
                    } else {
                    //null 响应=接口异常/无结果体：页1给出可重试文案，不再伪装成「啥都木有~」（X-10）
                    setBottom(true);
                    if (page == 1) {
                        showEmptyView("搜索失败，请检查网络后重试");
                        //B 站接口挂了但番剧库还能用：仍然补一轮，能播就比空白强
                        appendBangumiExtras(myGeneration);
                    }
                }
                } else {
                    //null 响应=接口异常/无结果体：页1给出可重试文案，不再伪装成「啥都木有~」（X-10）
                    setBottom(true);
                    if (page == 1) {
                        showEmptyView("搜索失败，请检查网络后重试");
                        appendBangumiExtras(myGeneration);
                    }
                }
                setRefreshing(false);
            } catch (Exception e) {
                report(e);
                if (myGeneration == loadGeneration) setRefreshing(false);
                if (myGeneration == loadGeneration && page == 1) showEmptyView("搜索失败，请检查网络后重试");
            }
        });
    }

    /**
     * 用关键词搜 Bangumi，把 B 站没有的番剧补在列表末尾。
     *
     * <p>只在第一页补一次；没导入任何第三方源时不补（补了也播不了，属于噪声）。
     */
    private void appendBangumiExtras(final int generation) {
        if (generation != loadGeneration || mergedGeneration == generation) return;
        final String kw = keyword;
        if (kw == null || kw.trim().isEmpty()) return;
        if (SourceManager.get().enabled().isEmpty()) return;
        mergedGeneration = generation;
        mergedCount = 0;
        CenterThreadPool.run(() -> {
            List<BgmSubject> found = null;
            try {
                found = BgmApi.searchSubjects(kw, 0, true);
            } catch (Throwable t) {
                Logu.d("third-merge", "番剧库搜索失败：" + t.getClass().getSimpleName());
            }
            if (found == null || found.isEmpty()) return;
            final List<BgmSubject> list = found;
            CenterThreadPool.runOnUiThread(() -> {
                if (!isAdded() || generation != loadGeneration) return;
                appendBgmCards(list);
            });
        });
    }

    private void appendBgmCards(List<BgmSubject> subjects) {
        if (bangumiCardAdapter == null) return;
        Set<String> existing = new HashSet<>();
        for (VideoCard card : bangumiCardList) existing.add(normalizeTitle(card.title));

        int start = bangumiCardList.size();
        int added = 0;
        for (BgmSubject subject : subjects) {
            if (mergedCount >= MERGE_RESULT_LIMIT) break;
            String title = subject.title();
            if (title == null || title.trim().isEmpty()) continue;
            //B 站已经有的条目不再补：同一条目出现两次会让用户以为是两条番
            if (existing.contains(normalizeTitle(title))) continue;
            if (existing.contains(normalizeTitle(subject.originalTitle()))) continue;

            VideoCard card = new VideoCard();
            card.type = "third_bgm";
            card.title = title.trim();
            card.sourceOriginal = subject.originalTitle();
            //必须走图片代理：设备网络直连 lain.bgm.tv 拉不到图（真机实测表现为「全变占位图」），
            //而 wsrv 代理是热门番组网格一直在用、确认可用的通道
            card.cover = BgmApi.imageFor(bestCover(subject), 360);
            card.upName = "Bangumi" + (subject.date == null || subject.date.length() < 4
                    ? "" : " · " + subject.date.substring(0, 4));
            card.view = subject.hasScore()
                    ? String.format(Locale.US, "%.1f 分", subject.score) : "";
            card.sourceSubjectId = String.valueOf(subject.id);
            bangumiCardList.add(card);
            existing.add(normalizeTitle(title));
            mergedCount++;
            added++;
        }
        if (added == 0) return;
        bangumiCardAdapter.notifyItemRangeInserted(start, added);
        //B 站 0 结果时列表被空态盖住了，补漏有结果就把列表放出来
        showEmptyView(false);
        Logu.d("third-merge", "番剧库补漏 " + added + " 条");
    }

    private static String bestCover(BgmSubject subject) {
        if (subject.coverLarge != null && !subject.coverLarge.isEmpty()) return subject.coverLarge;
        if (subject.coverCommon != null && !subject.coverCommon.isEmpty()) return subject.coverCommon;
        return subject.coverGrid == null ? "" : subject.coverGrid;
    }

    /** 标题归一化：去空白与标点、转小写，只用于「B 站是否已有」的粗判 */
    private static String normalizeTitle(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("[\\s\\p{Punct}，。、！？：；…—“”‘’《》〈〉【】（）「」『』·～]", "")
                .toLowerCase(Locale.ROOT);
    }

    public void refreshInternal() {
        CenterThreadPool.runOnUiThread(() -> {
            loadGeneration++;   //清空前先作废在途的旧页请求
            page = 1;
            mergedCount = 0;
            mergedGeneration = -1;
            if (this.bangumiCardAdapter == null)
                this.bangumiCardAdapter = new VideoCardAdapter(this.requireContext(), this.bangumiCardList);
            int size_old = this.bangumiCardList.size();
            this.bangumiCardList.clear();
            if (size_old != 0) this.bangumiCardAdapter.notifyItemRangeRemoved(0, size_old);
            CenterThreadPool.run(() -> continueLoading(page));
        });
    }
}
