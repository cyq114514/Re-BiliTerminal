package com.RobinNotBad.BiliClient.activity.user.favorite;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity;
import com.RobinNotBad.BiliClient.activity.player.AudioPlayerActivity;
import com.RobinNotBad.BiliClient.adapter.video.VideoCardAdapter;
import com.RobinNotBad.BiliClient.api.AudioQueueApi;
import com.RobinNotBad.BiliClient.api.FavoriteApi;
import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.service.AudioPlayerService;
import com.RobinNotBad.BiliClient.service.DownloadService;
import com.RobinNotBad.BiliClient.ui.theme.ThemeManager;
import com.RobinNotBad.BiliClient.util.AdaptiveDialog;
import com.RobinNotBad.BiliClient.util.AudioProgressStore;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;

import java.util.ArrayList;
import java.util.List;
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
    private String folderName = "";
    private ArrayList<VideoCard> videoList;
    private VideoCardAdapter videoCardAdapter;

    private int longClickPosition = -1;
    private long longClickTimestamp;

    private String order = FavoriteApi.ORDER_FAV_TIME;
    private TextView sortByFavTime, sortByView, sortByPubtime, manageEntries;
    private int unselectedSortColor;
    private boolean managing = false;
    private boolean loading = false;

    /** 排序栏容器：随列表滚动收起/展开（不再焊死在页面顶端） */
    private View sortBar;
    /** 头部容器（听全部 + 排序栏）：两者一起收起 */
    private View headerBar;
    /** 头部展开时的完整高度，-1 表示尚未量到 */
    private int sortBarFullHeight = -1;
    private boolean sortBarCollapsed = false;
    private android.animation.ValueAnimator sortBarAnimator;

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
        folderName = intent.getStringExtra("name");
        if (folderName == null) folderName = "";

        setPageName(folderName);

        videoList = new ArrayList<>();
        videoCardAdapter = new VideoCardAdapter(this, videoList);
        bindLongClickDelete();
        setOnLoadMoreListener(this::continueLoading);
        setAdapter(videoCardAdapter);

        setupSortBar();
        setupAudioQueueEntry();
        //头部容器（听全部 + 排序栏）建好后才能挂滚动收起
        attachSortBarCollapse();
        reloadFromFirst();
    }

    /** 音频播放入口：独立一行放在排序条上方（不改通用布局，仅本页面态可见） */
    private void setupAudioQueueEntry() {
        LinearLayout container = findViewById(R.id.listContainer);
        if (container == null || sortBar == null) return;

        //把「听全部」与排序栏装进同一个头部容器：两者一起随列表收起，且「听全部」不再是裸文字
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        int index = container.indexOfChild(sortBar);
        container.removeView(sortBar);
        if (index < 0) index = 0;
        container.addView(header, index, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        header.addView(sortBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        //与视频卡片同款半透明卡片：直接 inflate 卡片布局（card_bg_new #4D262626 + 描边 + 零投影 + 同款外边距），
        //不再用自绘背景，保证与列表里的视频卡片视觉完全一致
        header.addView(buildAudioEntryCard("听全部（纯音频）", "按当前列表顺序连续播放",
                view -> startAudioQueue()), 0);
        header.addView(buildAudioEntryCard("下载全部（音频）", "整夹加入下载队列，可后台继续",
                view -> confirmDownloadAll()), 1);

        headerBar = header;
    }

    /** 「听全部」「下载全部」共用的入口卡片：图标跟随主题色，文案由调用方给 */
    private View buildAudioEntryCard(String title, String subtitle, View.OnClickListener listener) {
        View card = getLayoutInflater().inflate(R.layout.view_audio_entry_card, null, false);
        TextView titleView = card.findViewById(R.id.audioEntryTitle);
        TextView subView = card.findViewById(R.id.audioEntrySub);
        android.widget.ImageView icon = card.findViewById(R.id.audioEntryIcon);
        if (titleView != null) titleView.setText(title);
        if (subView != null) subView.setText(subtitle);
        if (icon != null && android.os.Build.VERSION.SDK_INT >= 21) {
            icon.setImageTintList(android.content.res.ColorStateList.valueOf(ThemeManager.getPrimary()));
        }
        card.setOnClickListener(listener);
        return card;
    }

    /** 「下载全部」：先弹确认（体积提示），再逐页把整夹加入音频下载队列 */
    private void confirmDownloadAll() {
        int loaded = 0;
        for (VideoCard card : videoList) {
            if (card != null && card.aid > 0 && card.cid > 0 && !card.invalid) loaded++;
        }
        if (loaded == 0) {
            MsgUtil.showMsg("当前列表没有可下载的稿件");
            return;
        }
        new AdaptiveDialog.Builder(this)
                .setTitle("下载全部（音频）")
                .setMessage("将把整个收藏夹的音频加入下载队列：已加载 " + loaded
                        + " 条，剩余在后台自动拉取。\n\n"
                        + "按每首约 6MB 估算，100 首约 600MB，请留意剩余空间。"
                        + "下载在后台进行，可在「我的 → 下载」查看进度，可随时取消。")
                .setPositiveButton("开始下载", (dialog, which) -> downloadAll())
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 逐页把整夹加入音频下载队列。
     *
     * <p>每页同步插库（{@link DownloadService#startDownloadBatch}）后启动一次 worker，
     * 不用逐条 {@code startDownload}——那样 worker 可能在插完前就因队列空退出。
     * 已存在的条目由 aid+cid 去重，重复点「下载全部」不会重复下载。
     */
    private void downloadAll() {
        final List<VideoCard> initial = new ArrayList<>(videoList);
        final long media = mediaId > 0 ? mediaId : FavoriteApi.mediaIdOf(mid, fid);
        final String orderValue = order;
        MsgUtil.showMsg("正在加入下载队列…");
        CenterThreadPool.run(() -> {
            DownloadService.startDownloadBatch(initial, 16);
            int page = Math.max(1,
                    (int) Math.ceil(initial.size() / (double) FavoriteApi.FOLDER_PAGE_SIZE));
            int added = initial.size();
            for (int guard = 0; guard < 100; guard++) {
                AudioQueueApi.Page result;
                try {
                    result = AudioQueueApi.loadPage(media, page, orderValue);
                } catch (Exception e) {
                    break;
                }
                if (!result.items.isEmpty()) {
                    DownloadService.startDownloadBatch(result.items, 16);
                    added += result.items.size();
                }
                if (!result.hasMore) break;
                page++;
            }
            final int total = added;
            MsgUtil.showMsg("已加入约 " + total + " 条音频下载");
        });
    }

    /** 音频播放：用当前已加载的列表立即起播，后续页由服务在播放中懒加载 */
    private void startAudioQueue() {
        List<VideoCard> items = new ArrayList<>();
        for (VideoCard card : videoList) {
            if (card != null && card.aid > 0 && !card.invalid) items.add(card);
        }
        if (items.isEmpty()) {
            MsgUtil.showMsg("当前列表没有可播放的稿件");
            return;
        }
        //同一收藏夹才续播：换了夹子就从头开始，避免"在 A 夹点听全部却播起 B 夹的歌"。
        //记录里的 cid 必须还在当前已加载列表里才定位，否则静默从头，不会 seek 到别的歌上。
        int startIndex = 0;
        int startPosition = 0;
        AudioProgressStore.Progress saved = AudioProgressStore.load();
        if (saved != null && saved.mediaId == mediaId && saved.cid > 0) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).cid == saved.cid) {
                    startIndex = i;
                    startPosition = saved.positionMs;
                    break;
                }
            }
        }
        AudioPlayerService.startQueue(this, items, mediaId, order, folderName, startIndex, startPosition);
        if (startPosition > 0) {
            MsgUtil.showMsg("从上次 " + AudioProgressStore.formatTime(startPosition) + " 继续");
        }
        startActivity(new Intent(this, AudioPlayerActivity.class));
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void setupSortBar() {
        sortBar = findViewById(R.id.sortBar);
        sortBar.setVisibility(View.VISIBLE);
        sortByFavTime = findViewById(R.id.sortByFavTime);
        sortByView = findViewById(R.id.sortByView);
        sortByPubtime = findViewById(R.id.sortByPubtime);
        manageEntries = findViewById(R.id.manageEntries);
        unselectedSortColor = getResources().getColor(R.color.text_gray);

        //胶囊化：与番剧页签、搜索筛选同一套观感（选中=主题色底白字，未选中=半透明暗底）
        styleChip(sortByFavTime);
        styleChip(sortByView);
        styleChip(sortByPubtime);
        styleChip(manageEntries);

        sortByFavTime.setOnClickListener(view -> switchOrder(FavoriteApi.ORDER_FAV_TIME));
        sortByView.setOnClickListener(view -> switchOrder(FavoriteApi.ORDER_VIEW));
        sortByPubtime.setOnClickListener(view -> switchOrder(FavoriteApi.ORDER_PUBTIME));
        updateSortColors();

        manageEntries.setVisibility(View.VISIBLE);
        manageEntries.setOnClickListener(view -> toggleManage());
    }

    /** 把纯文字按钮改成胶囊 chip：圆角、高度 30dp、左右 12dp 内边距（颜色在 updateSortColors 里刷） */
    private void styleChip(TextView chip) {
        if (chip == null) return;
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(14));
        bg.setColor(0x1F000000);
        chip.setBackground(bg);
        chip.setGravity(Gravity.CENTER);
        chip.setMinHeight(dp(30));
        chip.setPadding(dp(12), 0, dp(12), 0);
        chip.setSingleLine(true);
    }

    /** 按选中态刷 chip 的背景与文字色（跟随主题色） */
    private void applyChipState(TextView chip, boolean selected) {
        if (chip == null) return;
        android.graphics.drawable.Drawable bg = chip.getBackground();
        if (bg instanceof android.graphics.drawable.GradientDrawable) {
            android.graphics.drawable.GradientDrawable shape =
                    (android.graphics.drawable.GradientDrawable) bg.mutate();
            shape.setColor(selected ? ThemeManager.getPrimary() : 0x1F000000);
            chip.setBackground(shape);
        }
        chip.setTextColor(selected ? android.graphics.Color.WHITE : unselectedSortColor);
    }

    /**
     * 排序栏随列表滚动收起/展开，不再焊死在顶端。
     *
     * <p>为什么不做"逐像素跟手收缩"：排序栏在列表外面，列表滚动 dy 的同时容器再缩 dy，
     * 条目在屏幕上会以 2 倍速度上移，手感是错的。真正的跟手折叠要靠 CoordinatorLayout +
     * AppBarLayout 接管滚动，而这个布局是 RefreshListActivity 与另外 5 个页面共用的，
     * 改结构会波及回复楼层、经验/硬币记录等页面。
     *
     * <p>这里用"滚过一屏就收起、回到顶部再展开"的自动隐藏方案：只在跨过阈值时做一次
     * 150ms 的收起动画（高度 + 位移 + 透明度一起变），条目本身仍是 1:1 跟手。
     */
    private void attachSortBarCollapse() {
        final View header = headerBar != null ? headerBar : sortBar;
        if (header == null || recyclerView == null) return;
        header.post(() -> {
            int h = header.getHeight();
            if (h > 0) sortBarFullHeight = h;
        });
        recyclerView.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@androidx.annotation.NonNull androidx.recyclerview.widget.RecyclerView rv,
                                   int dx, int dy) {
                if (sortBarFullHeight <= 0) {
                    sortBarFullHeight = header.getHeight();
                    if (sortBarFullHeight <= 0) return;
                }
                int offset = Math.max(rv.computeVerticalScrollOffset(), 0);
                //滞回：滚过一整条才收，回到顶部（8dp 内）才展开。展开阈值从「半条」收紧到
                //「8dp」是 1.3.3 反馈的关键：收起时有滚动补偿，收完 offset 恰好≈8dp，
                //若保留「半条」阈值会在阈值附近来回收起/展开，表现为内容反复跳
                if (!sortBarCollapsed && offset > sortBarFullHeight + dp(16)) {
                    animateSortBar(true);
                } else if (sortBarCollapsed && offset < dp(8)) {
                    animateSortBar(false);
                }
            }
        });
    }

    /** 收起/展开头部（听全部 + 排序栏）：高度、位移、透明度一起走，条目本身不参与动画 */
    private void animateSortBar(final boolean collapse) {
        final View header = headerBar != null ? headerBar : sortBar;
        if (header == null || sortBarFullHeight <= 0) return;
        sortBarCollapsed = collapse;
        if (sortBarAnimator != null) {
            sortBarAnimator.cancel();
            sortBarAnimator = null;
        }
        final int from = collapse ? sortBarFullHeight : 0;
        final int to = collapse ? 0 : sortBarFullHeight;
        android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofInt(from, to);
        animator.setDuration(150);
        final int[] lastH = {from};
        animator.addUpdateListener(animation -> {
            int h = (int) animation.getAnimatedValue();
            android.view.ViewGroup.LayoutParams lp = header.getLayoutParams();
            lp.height = h;
            header.setLayoutParams(lp);
            //位移跟着高度走：视觉上像被顶栏吸上去，而不是从底部被压扁
            header.setTranslationY(-(sortBarFullHeight - h));
            header.setAlpha(h / (float) sortBarFullHeight);
            //滚动补偿（1.3.3 反馈确认：滚过一屏收起时「跳一下」）：栏每矮 1px，列表
            //上缘就上移 1px，条目跟着整体上抬。收起时按帧反向 scrollBy，把条目钉在
            //原屏幕位置——收起只是把底部视口撑高，不再产生位移。展开在列表顶部发生，
            //「栏推着内容下去」是各 App 通用行为，不补偿
            int dh = lastH[0] - h;
            if (collapse && dh > 0 && recyclerView != null) recyclerView.scrollBy(0, -dh);
            lastH[0] = h;
        });
        //收起结束后直接 GONE：只把高度缩到 0 + 负位移，会在顶部留一条看不见但仍参与绘制的边缘，
        //滚动时表现为"顶栏下方多出一道缝"，用户看到的 UI 割裂就是它
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(android.animation.Animator animation) {
                if (!collapse) header.setVisibility(View.VISIBLE);
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (!collapse) return;
                header.setVisibility(View.GONE);
                android.view.ViewGroup.LayoutParams lp = header.getLayoutParams();
                lp.height = 0;
                header.setLayoutParams(lp);
                header.setTranslationY(0);
                header.setAlpha(1f);
            }
        });
        animator.start();
        sortBarAnimator = animator;
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
        applyChipState(sortByFavTime, order.equals(FavoriteApi.ORDER_FAV_TIME));
        applyChipState(sortByView, order.equals(FavoriteApi.ORDER_VIEW));
        applyChipState(sortByPubtime, order.equals(FavoriteApi.ORDER_PUBTIME));
        //「管理」不是排序项，但同样用胶囊呈现：进入管理模式时高亮，让退出入口显眼
        applyChipState(manageEntries, managing);
    }

    /** 进入/退出多选管理。退出时若有选中项，先确认再批量取消收藏 */
    private void toggleManage() {
        if (!managing) {
            managing = true;
            videoCardAdapter.setSelectionMode(true);
            manageEntries.setText("取消管理");
            updateSortColors();
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
        updateSortColors();
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
            //先在后台线程把这一页拿进临时表，再由主线程并入 adapter 持有的列表：
            //后台线程直接往 videoList 里追加会让 RecyclerView 在没有 notify 的情况下看到 itemCount 变化，
            //下一次 layout 就可能抛 Inconsistency detected（与收藏夹列表页崩溃同一类问题）
            final ArrayList<VideoCard> pageItems = new ArrayList<>();
            final int result = FavoriteApi.getFolderVideos(mediaId, pageNumber, order, pageItems);
            runOnUiThread(() -> {
                loading = false;
                setRefreshing(false);
                if (result != -1) {
                    int lastSize = videoList.size();
                    videoList.addAll(pageItems);
                    videoCardAdapter.notifyItemRangeInserted(lastSize, pageItems.size());
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
                //aid 必须在主线程先取：后台线程读 videoList 时，用户可能已经滚到下一页/删了别的条目，
                //position 此时对不上，get() 会越界
                if (position < 0 || position >= videoList.size()) return;
                final long aid = videoList.get(position).aid;
                CenterThreadPool.run(() -> {
                    try {
                        int delResult = FavoriteApi.deleteFavorite(aid, mediaId);
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
