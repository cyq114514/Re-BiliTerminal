package com.RobinNotBad.BiliClient.activity.video;

import android.annotation.SuppressLint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.ui.theme.ThemeManager;
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity;
import com.RobinNotBad.BiliClient.adapter.BgmGridAdapter;
import com.RobinNotBad.BiliClient.adapter.video.VideoCardAdapter;
import com.RobinNotBad.BiliClient.api.BangumiApi;
import com.RobinNotBad.BiliClient.api.BangumiIndexApi;
import com.RobinNotBad.BiliClient.api.BgmApi;
import com.RobinNotBad.BiliClient.api.BgmCache;
import com.RobinNotBad.BiliClient.model.bgm.BgmSubject;
import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.RobinNotBad.BiliClient.util.view.ImageAutoLoadScrollListener;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

//番剧主页：推荐 / 追番 / 索引（动态筛选） / 新番
//2026-07-22
//2026-11 调整：索引接口迁移到 /pgc/season/index/result；新增推荐页（可在设置关闭）；新番移到末位；
//筛选选项改为 condition 接口动态下发（排序/地区/风格/类型/付费/配音/版权/状态/年份/季度）
public class BangumiActivity extends InstanceActivity {

    private static final int TAB_RECOMMEND = 0;
    private static final int TAB_FOLLOWING = 1;
    private static final int TAB_INDEX = 2;
    private static final int TAB_TIMELINE = 3;
    private static final int TAB_COUNT = 4;
    /** 收起时显示的筛选行数：排序 + 前两组 */
    private static final int FILTER_COLLAPSED_ROWS = 3;

    private TextView[] tabs;
    private SwipeRefreshLayout[] refreshes;
    //空态/失败态：4 个 tab 共用一块（X-11，此前布局无该节点、失败只有 toast）
    private TextView emptyTip;
    private RecyclerView[] recyclers;
    private boolean[] loaded;
    private int currentTab;
    private View filterBar;
    /** 页签+筛选栏容器：随列表滚出屏幕，切页签时用 setExpanded 收回来 */
    private com.google.android.material.appbar.AppBarLayout headerBar;
    private boolean recommendEnabled;

    //筛选（动态条件，参数集中管理：field → keyword，排序单独一项）
    private LinearLayout filterRows;
    private TextView filterToggle;
    private BangumiIndexApi.Condition indexCondition;
    private final Map<String, String> selectedFilters = new LinkedHashMap<>();
    private String selectedOrder = "";
    private boolean filterExpanded = false;

    //推荐（固定热度排序，无筛选）
    private int recPage = 1;
    private boolean recEnd, recLoading;
    private int recGeneration;
    private List<VideoCard> recList;
    private VideoCardAdapter recAdapter;

    //追番
    private int followingPage;
    private boolean followingEnd;
    private List<VideoCard> followingList;
    private VideoCardAdapter followingAdapter;

    //索引
    private int idxPage = 1;
    private boolean idxEnd, idxLoading;
    private int idxGeneration;
    private List<VideoCard> indexList;
    private VideoCardAdapter indexAdapter;

    //热门番组（Bangumi）：网格分页，首屏缓存 6 小时
    private List<BgmSubject> popularList = new ArrayList<>();
    private BgmGridAdapter popularAdapter;
    private int popularOffset;
    private boolean popularLoading, popularEnd;
    private int popularGeneration;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bangumi);
        //番剧分区是从导航菜单进入的顶级页，菜单在跳转时已把自己 finish 掉，
        //所以这里不能用 setTopbarExit()（finish 会直接退出应用）：点返回箭头要回到导航菜单。
        setMenuClick();

        tabs = new TextView[]{findViewById(R.id.tab0), findViewById(R.id.tab1),
                findViewById(R.id.tab2), findViewById(R.id.tab3)};
        refreshes = new SwipeRefreshLayout[]{findViewById(R.id.refresh0), findViewById(R.id.refresh1),
                findViewById(R.id.refresh2), findViewById(R.id.refresh3)};
        recyclers = new RecyclerView[]{findViewById(R.id.recycler0), findViewById(R.id.recycler1),
                findViewById(R.id.recycler2), findViewById(R.id.recycler3)};
        loaded = new boolean[TAB_COUNT];
        emptyTip = findViewById(R.id.emptyTip);
        filterBar = findViewById(R.id.filterBar);
        filterRows = findViewById(R.id.filterRows);
        filterToggle = findViewById(R.id.filterToggle);
        headerBar = findViewById(R.id.headerBar);

        recommendEnabled = SharedPreferencesUtil.getBoolean(
                SharedPreferencesUtil.BANGUMI_SHOW_RECOMMEND, true);

        for (int i = 0; i < TAB_COUNT; i++) {
            recyclers[i].setLayoutManager(new CustomLinearManager(this));
            ImageAutoLoadScrollListener.install(recyclers[i]);
            final int idx = i;
            tabs[i].setOnClickListener(v -> switchTab(idx));
        }
        if (!recommendEnabled) tabs[TAB_RECOMMEND].setVisibility(View.GONE);

        //第三方源入口：只加一个固定宽图标位，不动 4 个页签的等分（手表宽度下第 5 格会溢出）
        View sourceEntry = findViewById(R.id.sourceEntry);
        if (sourceEntry != null) {
            sourceEntry.setOnClickListener(view -> startActivity(
                    new android.content.Intent(this,
                            com.RobinNotBad.BiliClient.activity.source.SourceSearchActivity.class)));
        }

        refreshes[TAB_RECOMMEND].setOnRefreshListener(() -> loadRecommend(true));
        refreshes[TAB_FOLLOWING].setOnRefreshListener(() -> loadFollowing(true));
        refreshes[TAB_INDEX].setOnRefreshListener(() -> loadIndex(true));
        //热门番组：网格布局 + 触底续拉（原「新番」页签的 B 站时间线已按需求替换）
        recyclers[TAB_TIMELINE].setLayoutManager(
                new androidx.recyclerview.widget.GridLayoutManager(this, popularSpanCount()));
        recyclers[TAB_TIMELINE].addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                if (dy <= 0 || popularEnd || popularLoading || currentTab != TAB_TIMELINE) return;
                RecyclerView.LayoutManager manager = rv.getLayoutManager();
                if (manager == null || manager.getItemCount() == 0) return;
                int last;
                if (manager instanceof androidx.recyclerview.widget.GridLayoutManager)
                    last = ((androidx.recyclerview.widget.GridLayoutManager) manager).findLastVisibleItemPosition();
                else if (manager instanceof CustomLinearManager)
                    last = ((CustomLinearManager) manager).findLastVisibleItemPosition();
                else return;
                if (last >= manager.getItemCount() - 6) loadPopular(false, false);
            }
        });
        refreshes[TAB_TIMELINE].setOnRefreshListener(() -> loadPopular(true, true));

        filterToggle.setOnClickListener(v -> {
            filterExpanded = !filterExpanded;
            renderFilterRows();
        });

        recList = new ArrayList<>();
        recyclers[TAB_RECOMMEND].addOnScrollListener(new RecyclerView.OnScrollListener() {
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                if (dy > 0 && !recEnd && currentTab == TAB_RECOMMEND) {
                    CustomLinearManager lm = (CustomLinearManager) rv.getLayoutManager();
                    if (lm != null && lm.findLastVisibleItemPosition() >= rv.getLayoutManager().getItemCount() - 3)
                        loadRecommend(false);
                }
            }
        });

        followingList = new ArrayList<>();
        recyclers[TAB_FOLLOWING].addOnScrollListener(new RecyclerView.OnScrollListener() {
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                if (dy > 0 && !followingEnd && currentTab == TAB_FOLLOWING) {
                    CustomLinearManager lm = (CustomLinearManager) rv.getLayoutManager();
                    if (lm != null && lm.findLastVisibleItemPosition() >= rv.getLayoutManager().getItemCount() - 3)
                        loadFollowing(false);
                }
            }
        });

        indexList = new ArrayList<>();
        recyclers[TAB_INDEX].addOnScrollListener(new RecyclerView.OnScrollListener() {
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                if (dy > 0 && !idxEnd && currentTab == TAB_INDEX) {
                    CustomLinearManager lm = (CustomLinearManager) rv.getLayoutManager();
                    if (lm != null && lm.findLastVisibleItemPosition() >= rv.getLayoutManager().getItemCount() - 3)
                        loadIndex(false);
                }
            }
        });

        loadCondition();
        switchTab(recommendEnabled ? TAB_RECOMMEND : TAB_FOLLOWING);
    }

    /** tab 级空态/失败态：只作用于指定 tab 的列表区（X-11） */
    private void showTabEmpty(int tab, String message) {
        runOnUiThread(() -> {
            if (isFinishing() || emptyTip == null) return;
            emptyTip.setText(message);
            emptyTip.setVisibility(View.VISIBLE);
            recyclers[tab].setVisibility(View.GONE);
        });
    }

    private void hideTabEmpty(int tab) {
        runOnUiThread(() -> {
            if (isFinishing() || emptyTip == null) return;
            emptyTip.setVisibility(View.GONE);
            recyclers[tab].setVisibility(View.VISIBLE);
        });
    }

    /** Tab/筛选 chip 的胶囊底：选中=主题色、未选中=暗底（与 sel_filter_chip 同形，颜色跟随主题） */
    private android.graphics.drawable.GradientDrawable chipBackground(boolean selected) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(14));
        drawable.setColor(selected ? ThemeManager.getPrimary() : 0x15000000);
        return drawable;
    }

    private void switchTab(int tab) {
        if (tab == TAB_RECOMMEND && !recommendEnabled) tab = TAB_FOLLOWING;
        currentTab = tab;
        for (int i = 0; i < TAB_COUNT; i++) {
            //选中态：主题色胶囊底白字，选中加粗——背景在代码里按主题色重建，
            //sel_filter_chip 的硬编码旧粉不随主题（一致性整改）
            tabs[i].setSelected(i == tab);
            tabs[i].setBackground(chipBackground(i == tab));
            tabs[i].setTypeface(null, i == tab ? Typeface.BOLD : Typeface.NORMAL);
            refreshes[i].setVisibility(i == tab ? View.VISIBLE : View.GONE);
        }
        filterBar.setVisibility(tab == TAB_INDEX ? View.VISIBLE : View.GONE);
        //表冠滚动（适配审计 P2-8）：四个页签列表换 Rotary 容器后，切页签时把焦点跟过去
        recyclers[tab].requestFocus();
        //页签与筛选栏现在会随列表滚出屏幕：切页签时先收回来，
        //否则用户在列表底部切 tab 会只看到列表、找不到页签与筛选入口
        if (headerBar != null) headerBar.setExpanded(true, true);
        if (!loaded[tab]) {
            if (tab == TAB_RECOMMEND) loadRecommend(true);
            else if (tab == TAB_FOLLOWING) loadFollowing(true);
            else if (tab == TAB_INDEX) loadIndex(true);
            else loadPopular(true, false);
        }
    }

    // ============== Tab 0: 推荐 ==============
    /** 推荐流：固定最多追番排序（order=3）、无筛选，分页与索引共用同一接口 */
    @SuppressLint("NotifyDataSetChanged")
    private void loadRecommend(boolean reset) {
        if (isFinishing()) return;
        if (recLoading) {
            refreshes[TAB_RECOMMEND].setRefreshing(false);
            return;
        }
        if (reset) {
            recPage = 1;
            recEnd = false;
        }
        if (recEnd) {
            refreshes[TAB_RECOMMEND].setRefreshing(false);
            return;
        }
        recLoading = true;
        final int generation = ++recGeneration;
        final int page = recPage;
        refreshes[TAB_RECOMMEND].setRefreshing(true);
        CenterThreadPool.run(() -> {
            List<VideoCard> list = new ArrayList<>();
            Exception error = null;
            boolean hasNext = false;
            try {
                hasNext = BangumiIndexApi.getIndex(1, null, "3", page, list);
            } catch (Exception e) {
                error = e;
            }
            final Exception err = error;
            final boolean next = hasNext;
            runOnUiThread(() -> {
                recLoading = false;
                if (isFinishing() || generation != recGeneration) return;
                refreshes[TAB_RECOMMEND].setRefreshing(false);
                if (err != null) {
                    if (reset && recList.isEmpty()) showTabEmpty(TAB_RECOMMEND, "推荐加载失败\n下拉重试");
                    MsgUtil.showMsgLong("推荐加载失败，请下拉重试");
                    return;
                }
                if (reset) recList.clear();
                recList.addAll(list);
                if (recList.isEmpty()) showTabEmpty(TAB_RECOMMEND, "这里还什么都没有~");
                else hideTabEmpty(TAB_RECOMMEND);
                recPage = page + 1;
                recEnd = !next || list.isEmpty();
                if (recAdapter == null) {
                    recAdapter = new VideoCardAdapter(this, recList);
                    recyclers[TAB_RECOMMEND].setAdapter(recAdapter);
                } else recAdapter.notifyDataSetChanged();
                loaded[TAB_RECOMMEND] = true;
            });
        });
    }

    // ============== Tab 3: 热门番组（Bangumi）==============

    //v2：上一版缓存的封面是"百像素方图 + 已套代理"的地址，继续复用会一直糊，
    //换前缀让旧缓存自然失效（网络正常时首屏就会重取一次）
    private static final String POPULAR_CACHE_PREFIX = "popular_v2_o";

    /**
     * 网格列数自适应：按「单格目标宽度 120dp」由屏宽反算，不写死档位。
     * 320dp 手表→2 列、360/411dp 手机→3 列、600dp 平板→5 列，最宽 6 列。
     * 封面高度由 RatioImageView 按 2:3 自动推导，所以换列数不会破坏比例。
     */
    private int popularSpanCount() {
        int widthDp = getResources().getConfiguration().screenWidthDp;
        int span = (int) Math.floor(widthDp / 120f);
        return Math.max(2, Math.min(6, span));
    }

    /**
     * 单格像素宽：RecyclerView 实际宽 / 列数。
     *
     * <p>这是图片解码的目标尺寸——等价于 Kazumi 的
     * {@code memCacheWidth = 显示宽 × devicePixelRatio}。按它取图，
     * 2 列大格子也能拿到足够像素，不会再出现"小图放大"的马赛克。
     */
    private int popularCellWidthPx(int spanCount) {
        int width = recyclers[TAB_TIMELINE].getWidth();
        if (width <= 0) width = getResources().getDisplayMetrics().widthPixels;
        return Math.max(120, width / Math.max(1, spanCount) - dp(6));
    }

    /**
     * @param reset        回到第一页（首次进入 / 下拉刷新）
     * @param forceRefresh 跳过缓存直接请求（仅下拉刷新）
     */
    @SuppressLint("NotifyDataSetChanged")
    private void loadPopular(boolean reset, boolean forceRefresh) {
        if (isFinishing()) return;
        if (popularLoading) {
            refreshes[TAB_TIMELINE].setRefreshing(false);
            return;
        }
        if (!reset && popularEnd) {
            refreshes[TAB_TIMELINE].setRefreshing(false);
            return;
        }
        if (reset) {
            popularOffset = 0;
            popularEnd = false;
        }

        final int generation = ++popularGeneration;
        final int offset = reset ? 0 : popularOffset;
        final String cacheKey = POPULAR_CACHE_PREFIX + offset;

        if (!forceRefresh) {
            //缓存先上屏：接口挂了也要能看到上次的榜单，绝不清空
            java.util.List<BgmSubject> cached = BgmApi.parseCachedList(BgmCache.get(cacheKey));
            if (!cached.isEmpty()) {
                applyPopular(cached, reset);
                loaded[TAB_TIMELINE] = true;
                if (BgmCache.isFresh(cacheKey, BgmApi.TTL_POPULAR)) {
                    popularOffset = offset + cached.size();
                    popularEnd = cached.size() < BgmApi.POPULAR_PAGE_SIZE;
                    refreshes[TAB_TIMELINE].setRefreshing(false);
                    return;
                }
            }
        }

        popularLoading = true;
        refreshes[TAB_TIMELINE].setRefreshing(true);
        CenterThreadPool.run(() -> {
            java.util.List<BgmSubject> result = null;
            Exception error = null;
            try {
                result = BgmApi.getPopular(BgmApi.POPULAR_PAGE_SIZE, offset);
            } catch (Exception e) {
                error = e;
            }
            final java.util.List<BgmSubject> list = result;
            final Exception err = error;
            runOnUiThread(() -> {
                popularLoading = false;
                if (isFinishing() || generation != popularGeneration) return;
                refreshes[TAB_TIMELINE].setRefreshing(false);
                if (err != null || list == null) {
                    if (popularList.isEmpty()) showTabEmpty(TAB_TIMELINE, "热门番组加载失败\n下拉重试");
                    else MsgUtil.showMsgLong("刷新失败，仍显示上次的数据");
                    return;
                }
                if (list.isEmpty()) {
                    popularEnd = true;
                    if (popularList.isEmpty()) showTabEmpty(TAB_TIMELINE, "热门榜单暂时为空~");
                    return;
                }
                applyPopular(list, reset);
                //缓存的是"这一页原始数据"，跨页去重后的结果不写进单页键
                BgmCache.put(cacheKey, BgmApi.listToJson(list));
                popularOffset = offset + list.size();
                popularEnd = list.size() < BgmApi.POPULAR_PAGE_SIZE;
                loaded[TAB_TIMELINE] = true;
            });
        });
    }

    @SuppressLint("NotifyDataSetChanged")
    private void applyPopular(java.util.List<BgmSubject> list, boolean reset) {
        if (reset) popularList.clear();
        //分页边界上镜像可能重复给出同一条，按 id 去重再入表（否则网格里出现重影）
        java.util.HashSet<Long> ids = new java.util.HashSet<>();
        for (BgmSubject subject : popularList) ids.add(subject.id);
        for (BgmSubject subject : list) {
            if (ids.add(subject.id)) popularList.add(subject);
        }

        if (popularList.isEmpty()) {
            showTabEmpty(TAB_TIMELINE, "热门榜单暂时为空~");
            return;
        }
        hideTabEmpty(TAB_TIMELINE);
        if (popularAdapter == null) {
            int span = popularSpanCount();
            popularAdapter = new BgmGridAdapter(this, new ArrayList<>(popularList), span,
                    popularCellWidthPx(span));
            popularAdapter.setOnItemClickListener(this::openBgmSubject);
            recyclers[TAB_TIMELINE].setAdapter(popularAdapter);
        } else {
            popularAdapter.update(popularList);
        }
    }

    private void openBgmSubject(BgmSubject subject) {
        if (subject == null || subject.id <= 0) return;
        android.content.Intent intent = new android.content.Intent(this, BgmSubjectActivity.class);
        intent.putExtra("bgmId", subject.id);
        startActivity(intent);
    }

    // ============== Tab 1: 追番 ==============
    @SuppressLint("NotifyDataSetChanged")
    private void loadFollowing(boolean reset) {
        if (isFinishing()) return;
        if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) == 0) {
            MsgUtil.showMsgLong("未登录，无法获取追番列表");
            refreshes[TAB_FOLLOWING].setRefreshing(false);
            loaded[TAB_FOLLOWING] = true;
            return;
        }
        showTabEmpty(TAB_FOLLOWING, "未登录，无法获取追番列表");
        if (reset) {
            followingPage = 1;
            followingEnd = false;
        }
        if (followingEnd) {
            refreshes[TAB_FOLLOWING].setRefreshing(false);
            return;
        }
        refreshes[TAB_FOLLOWING].setRefreshing(true);
        CenterThreadPool.run(() -> {
            try {
                List<VideoCard> list = new ArrayList<>();
                int r = BangumiApi.getFollowingList(followingPage, list);
                if (isFinishing()) return;
                int np = followingPage + 1;
                boolean end = (r == 1 || list.isEmpty());
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    if (reset) followingList.clear();
                    followingList.addAll(list);
                    followingPage = np;
                    followingEnd = end;
                    if (followingAdapter == null) {
                        followingAdapter = new VideoCardAdapter(this, followingList);
                        recyclers[TAB_FOLLOWING].setAdapter(followingAdapter);
                    } else followingAdapter.notifyDataSetChanged();
                    loaded[TAB_FOLLOWING] = true;
                    refreshes[TAB_FOLLOWING].setRefreshing(false);
                    if (reset && followingList.isEmpty()) showTabEmpty(TAB_FOLLOWING, "还没有追番内容~");
                    else hideTabEmpty(TAB_FOLLOWING);
                });
            } catch (Exception e) {
                if (!isFinishing()) runOnUiThread(() -> {
                    refreshes[TAB_FOLLOWING].setRefreshing(false);
                    if (followingList.isEmpty()) showTabEmpty(TAB_FOLLOWING, "追番加载失败\n下拉重试");
                    MsgUtil.showMsgLong("追番加载失败，请下拉重试");
                });
            }
        });
    }

    // ============== Tab 2: 索引（动态筛选）==============

    /** 拉取动态筛选条件；失败不阻塞索引页，列表仍按默认全部条件加载 */
    private void loadCondition() {
        CenterThreadPool.run(() -> {
            BangumiIndexApi.Condition result = null;
            try {
                result = BangumiIndexApi.getCondition(1);
            } catch (Exception ignored) {
            }
            final BangumiIndexApi.Condition condition = result;
            runOnUiThread(() -> {
                if (isFinishing() || condition == null) return;
                indexCondition = condition;
                if (selectedOrder.isEmpty() && !condition.orders.isEmpty())
                    selectedOrder = condition.orders.get(0).value;
                renderFilterRows();
            });
        });
    }

    /** 动态渲染筛选行：排序 + 各筛选组；收起时只显示前 FILTER_COLLAPSED_ROWS 行 */
    private void renderFilterRows() {
        if (filterRows == null || indexCondition == null) return;
        filterRows.removeAllViews();
        int rowCount = 0;

        if (!indexCondition.orders.isEmpty()) {
            LinearLayout row = createFilterRow("排序");
            for (BangumiIndexApi.Option option : indexCondition.orders) {
                final String value = option.value;
                row.addView(createFilterChip(option.name, value.equals(selectedOrder), v -> {
                    selectedOrder = value;
                    renderFilterRows();
                    reloadIndex();
                }));
            }
            filterRows.addView(wrapFilterRow(row));
            rowCount++;
        }

        for (BangumiIndexApi.Group group : indexCondition.filters) {
            if (!filterExpanded && rowCount >= FILTER_COLLAPSED_ROWS) break;
            LinearLayout row = createFilterRow(group.name);
            String current = selectedFilters.get(group.field);
            for (BangumiIndexApi.Option option : group.options) {
                final String value = option.value;
                boolean selected = current == null ? "-1".equals(value) : current.equals(value);
                row.addView(createFilterChip(option.name, selected, v -> {
                    selectedFilters.put(group.field, value);
                    renderFilterRows();
                    reloadIndex();
                }));
            }
            filterRows.addView(wrapFilterRow(row));
            rowCount++;
        }

        filterToggle.setText(filterExpanded ? "收起筛选 ▴" : "展开筛选 ▾");
    }

    private LinearLayout createFilterRow(String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = dp(6);
        row.setPadding(padH, dp(2), padH, dp(2));

        TextView tag = new TextView(this);
        tag.setText(label);
        tag.setTextSize(13);
        tag.setTextColor(getResources().getColor(R.color.text_gray));
        tag.setMinWidth(dp(40));
        tag.setPadding(0, 0, dp(6), 0);
        row.addView(tag);
        return row;
    }

    private TextView createFilterChip(String text, boolean selected, View.OnClickListener listener) {
        TextView chip = new TextView(this);
        chip.setText(text);
        //12sp→13sp、26dp→30dp：与顶栏 Tab 胶囊同一字号/触控档（此前字小面小，手表难点）
        chip.setTextSize(13);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(10), 0, dp(10), 0);
        //背景与文字色走代码级主题色（原硬编码 0xFF888888/#FB7299 不随主题）
        chip.setBackground(chipBackground(selected));
        chip.setSelected(selected);
        chip.setTextColor(selected ? 0xFFFFFFFF : getResources().getColor(R.color.text_gray));
        chip.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(30));
        lp.setMargins(0, 0, dp(6), 0);
        chip.setLayoutParams(lp);
        return chip;
    }

    private View wrapFilterRow(LinearLayout row) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row);
        return scroll;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void reloadIndex() {
        idxPage = 1;
        idxEnd = false;
        indexList.clear();
        if (indexAdapter != null) indexAdapter.notifyDataSetChanged();
        loadIndex(true);
    }

    @SuppressLint("NotifyDataSetChanged")
    private void loadIndex(boolean reset) {
        if (isFinishing()) return;
        if (idxLoading) {
            refreshes[TAB_INDEX].setRefreshing(false);
            return;
        }
        if (reset) {
            idxPage = 1;
            idxEnd = false;
        }
        if (idxEnd) {
            refreshes[TAB_INDEX].setRefreshing(false);
            return;
        }
        idxLoading = true;
        //代际守卫：快速连点筛选时，旧请求的响应回来直接丢弃，避免覆盖新条件的结果
        final int generation = ++idxGeneration;
        final int page = idxPage;
        refreshes[TAB_INDEX].setRefreshing(true);
        CenterThreadPool.run(() -> {
            List<VideoCard> list = new ArrayList<>();
            Exception error = null;
            boolean hasNext = false;
            try {
                hasNext = BangumiIndexApi.getIndex(1, selectedFilters, selectedOrder, page, list);
            } catch (Exception e) {
                error = e;
            }
            final Exception err = error;
            final boolean next = hasNext;
            runOnUiThread(() -> {
                idxLoading = false;
                if (isFinishing() || generation != idxGeneration) return;
                refreshes[TAB_INDEX].setRefreshing(false);
                if (err != null) {
                    //失败与「没有下一页」严格区分：提示重试，不把错误当成到底
                    if (reset && indexList.isEmpty()) showTabEmpty(TAB_INDEX, "索引加载失败\n下拉重试");
                    MsgUtil.showMsgLong("索引加载失败，请重试");
                    return;
                }
                if (reset) indexList.clear();
                indexList.addAll(list);
                if (indexList.isEmpty()) showTabEmpty(TAB_INDEX, "没有符合筛选条件的番剧~");
                else hideTabEmpty(TAB_INDEX);
                idxPage = page + 1;
                idxEnd = !next || list.isEmpty();
                if (indexAdapter == null) {
                    indexAdapter = new VideoCardAdapter(this, indexList);
                    recyclers[TAB_INDEX].setAdapter(indexAdapter);
                } else indexAdapter.notifyDataSetChanged();
                loaded[TAB_INDEX] = true;
            });
        });
    }
}
