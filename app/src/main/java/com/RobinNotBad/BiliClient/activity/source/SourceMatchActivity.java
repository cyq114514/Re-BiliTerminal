package com.RobinNotBad.BiliClient.activity.source;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity;
import com.RobinNotBad.BiliClient.adapter.source.SourceSubjectAdapter;
import com.RobinNotBad.BiliClient.api.BgmApi;
import com.RobinNotBad.BiliClient.source.SourceManager;
import com.RobinNotBad.BiliClient.source.SourceSearchTask;
import com.RobinNotBad.BiliClient.source.model.SourceException;
import com.RobinNotBad.BiliClient.source.model.SourceInfo;
import com.RobinNotBad.BiliClient.source.model.SourceSubject;
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.util.ArrayList;
import java.util.List;

/**
 * 「Bangumi 条目 → 各站点候选」的匹配页。
 *
 * <p>不做自动匹配：同名不同季、剧场版、OVA 靠字符串判断必然出错，强制用户点一下
 * （RE 的私有侧也是「手动选源」）。默认用中文名搜，中文名 0 结果时自动用原名再搜一次。
 */
public class SourceMatchActivity extends InstanceActivity {

    public static final String EXTRA_BGM_ID = "bgmId";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_ORIGINAL = "original";
    public static final String EXTRA_COVER = "cover";

    /** 默认只搜的健康源数量（首屏速度与覆盖率的折中） */
    private static final int DEFAULT_SOURCE_LIMIT = 24;

    private String title;
    private String original;
    private String cover;

    private TextView statusText;
    private TextView emptyTip;
    private TextView btnOriginal;
    private RecyclerView recycler;

    private SourceSubjectAdapter adapter;
    private SourceSearchTask task;
    private int generation = 0;
    private int totalSources = 0;
    private String lastError = "";
    private boolean retriedWithOriginal = false;
    /** 被相关性过滤掉的候选数（站点返回整页无关内容时用来解释「为什么没结果」） */
    private int filteredCount = 0;
    /** 是否已点过「搜索全部源」：之后同页搜索不再套 24 源上限 */
    private boolean searchAll = false;
    private TextView btnAll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_source_match);
        setTopbarExit();
        setPageName(getString(R.string.source_match_title));

        title = safe(getIntent().getStringExtra(EXTRA_TITLE));
        original = safe(getIntent().getStringExtra(EXTRA_ORIGINAL));
        cover = safe(getIntent().getStringExtra(EXTRA_COVER));

        recycler = findViewById(R.id.recycler);

        //头部（封面/标题/搜索状态/按钮）做成列表第一个 item：钉在屏幕顶端时手表上会吃掉
        //一半可视区，且列表往下翻时它一动不动。
        //root 不能传 recycler：LayoutInflater 会调 RecyclerView.generateLayoutParams()，
        //而它在还没 setLayoutManager 时直接抛 IllegalStateException（本页曾因此在 onCreate 崩溃）。
        //传 null 再手动塞 RecyclerView.LayoutParams，与设置 LayoutManager 的先后顺序彻底解耦。
        View header = getLayoutInflater()
                .inflate(R.layout.view_source_match_header, null, false);
        header.setLayoutParams(new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));

        statusText = header.findViewById(R.id.statusText);
        emptyTip = header.findViewById(R.id.emptyTip);
        btnOriginal = header.findViewById(R.id.btnOriginal);
        btnAll = header.findViewById(R.id.btnAll);

        ((TextView) header.findViewById(R.id.text_title)).setText(title);
        TextView originalView = header.findViewById(R.id.text_original);
        originalView.setText(original);
        originalView.setVisibility(original.isEmpty() ? View.GONE : View.VISIBLE);
        if (!cover.isEmpty()) {
            //直接加载 common(400px) 原图：这只是一张 48x64dp 的小图，没必要再走图片代理多一跳
            Glide.with(this)
                    .load(cover)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .placeholder(R.mipmap.placeholder)
                    .centerCrop()
                    .into((ImageView) header.findViewById(R.id.img_cover));
        }

        adapter = new SourceSubjectAdapter();
        adapter.setHeaderView(header);
        adapter.setFallbackCover(cover);
        adapter.setOnItemClickListener(this::openDetail);
        recycler.setLayoutManager(new CustomLinearManager(this));
        recycler.setAdapter(adapter);

        header.findViewById(R.id.btnManage).setOnClickListener(view ->
                startActivity(new Intent(this, SourceListActivity.class)));
        btnOriginal.setOnClickListener(view -> search(original, true));

        btnAll.setOnClickListener(view -> {
            searchAll = true;
            btnAll.setVisibility(View.GONE);
            search(title.isEmpty() ? original : title, true);
        });

        if (!title.isEmpty() && !original.isEmpty() && !title.equals(original))
            btnOriginal.setVisibility(View.VISIBLE);

        search(title.isEmpty() ? original : title, false);
    }

    private void search(String keyword, boolean manual) {
        final String kw = safe(keyword);
        if (kw.isEmpty()) {
            showEmpty(getString(R.string.source_match_no_title));
            return;
        }
        final int myGeneration = ++generation;
        if (task != null) task.cancel();
        adapter.clear();
        lastError = "";
        filteredCount = 0;
        List<SourceInfo> sources = searchAll
                ? SourceManager.get().enabled()
                : SourceManager.get().searchable();
        //默认只打前 24 个源：63 个源全打一遍首屏要 20~30s，而前 24 个（按健康度/tier 排）
        //已经能覆盖绝大多数能用的站点；其余用「搜索全部」手动展开
        if (!searchAll && sources.size() > DEFAULT_SOURCE_LIMIT)
            sources = new ArrayList<>(sources.subList(0, DEFAULT_SOURCE_LIMIT));
        totalSources = sources.size();
        if (sources.isEmpty()) {
            showEmpty(getString(R.string.source_search_no_source));
            return;
        }
        emptyTip.setVisibility(View.GONE);
        recycler.setVisibility(View.VISIBLE);
        statusText.setText(getString(R.string.source_match_running, kw, 0, totalSources));

        task = new SourceSearchTask(kw, sources, new SourceSearchTask.Listener() {
            @Override
            public void onSourceResult(SourceInfo info, List<SourceSubject> subjects,
                                       SourceException error, long costMs, int done, int total) {
                if (isFinishing() || myGeneration != generation) return;
                statusText.setText(getString(R.string.source_match_running, kw, done, total)
                        + " · " + getString(R.string.source_search_count, adapter.size())
                        + (filteredCount > 0
                        ? " · " + getString(R.string.source_match_filtered, filteredCount) : ""));
                if (error != null) {
                    lastError = error.userMessage();
                    return;
                }
                //站点搜不到时会返回「最新/推荐」整页，不过滤就会像上一版那样满屏不相关
                List<SourceSubject> relevant = new ArrayList<>();
                if (subjects != null) {
                    for (SourceSubject candidate : subjects) {
                        if (candidate == null) continue;
                        if (matches(kw, candidate.title) || matches(title, candidate.title)
                                || matches(original, candidate.title)) {
                            relevant.add(candidate);
                        } else {
                            filteredCount++;
                        }
                    }
                }
                adapter.addAll(relevant);
                if (adapter.size() > 0) {
                    emptyTip.setVisibility(View.GONE);
                    recycler.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onFinished(int success, int failed, boolean cancelled) {
                if (isFinishing() || cancelled || myGeneration != generation) return;
                if (adapter.size() > 0) {
                    statusText.setText(getString(R.string.source_match_done, adapter.size(), success, failed));
                    return;
                }
                //中文名没搜到、且有原名且还没自动重试过：用原名再试一次
                if (!retriedWithOriginal && !original.isEmpty() && !original.equals(kw)) {
                    retriedWithOriginal = true;
                    search(original, false);
                    return;
                }
                showEmpty(filteredCount > 0
                        ? getString(R.string.source_match_all_filtered, filteredCount)
                        : getString(R.string.source_match_none, lastError));
            }
        });
        task.start();
        if (manual) retriedWithOriginal = true;
    }

    private void openDetail(SourceSubject subject) {
        //把 Bangumi 的规范标题随条目带到播放链路：弹弹play 按番剧名匹配，
        //站点标题（「XXX 第X季 HD」这类）命中率明显低
        subject.danmakuTitle = title.isEmpty() ? original : title;
        Intent intent = new Intent(this, SourceDetailActivity.class);
        intent.putExtra(SourceDetailActivity.EXTRA_SOURCE_ID, subject.sourceId);
        intent.putExtra(SourceDetailActivity.EXTRA_SUBJECT, subject);
        startActivity(intent);
    }

    private void showEmpty(String message) {
        emptyTip.setText(message);
        emptyTip.setVisibility(View.VISIBLE);
        //列表不再隐藏：头部（番剧信息）住在列表里，隐藏列表会把头部一起藏掉；
        //空态时列表里只有头部一项，内容不足一屏，RecyclerView 本身不会滚动
        recycler.setVisibility(View.VISIBLE);
        statusText.setText("");
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 标题相关性粗判：包含关系，或最长公共子串 ≥ min(3, 查询长度)。
     *
     * <p>为什么不用编辑距离：番剧名常有「利兹/莉兹」这类单字差，编辑距离会把它们判成不同；
     * 而站点返回的无关条目（「末日兼职」vs「莉兹与青鸟」）公共子串几乎为 0。
     * 粗判宁可放过（用户能自己挑），也不能像上一版那样把整页无关内容全放进来。
     */
    static boolean matches(String query, String candidate) {
        String q = norm(query);
        String c = norm(candidate);
        if (q.isEmpty() || c.isEmpty()) return false;
        if (c.contains(q) || q.contains(c)) return true;
        int need = Math.min(3, q.length());
        return longestCommonSubstring(q, c) >= need;
    }

    /** 去空白与标点、转小写 */
    static String norm(String raw) {
        if (raw == null) return "";
        String text = raw.replaceAll("[\\s\\p{Punct}，。、！？：；…—“”‘’《》〈〉【】（）「」『』·～]", "")
                .toLowerCase(java.util.Locale.ROOT);
        return text.length() > 64 ? text.substring(0, 64) : text;
    }

    /** 最长公共子串长度（O(n*m)，两边都截到 64 字符，手表上也够快） */
    static int longestCommonSubstring(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int[] prev = new int[b.length() + 1];
        int best = 0;
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            for (int j = 1; j <= b.length(); j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    cur[j] = prev[j - 1] + 1;
                    if (cur[j] > best) best = cur[j];
                }
            }
            prev = cur;
        }
        return best;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        if (task != null) task.cancel();
        super.onDestroy();
    }
}
