package com.RobinNotBad.BiliClient.activity.user.favorite;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
    /** 头部实测高度，-1 表示尚未量到（跟手位移与列表 paddingTop 都用它） */
    private int sortBarFullHeight = -1;

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
        android.view.ViewGroup root = findViewById(R.id.refresh_root);
        if (container == null || root == null || sortBar == null) return;

        //把「听全部」与排序栏装进同一个头部容器：两者一起随列表滚动，且「听全部」不再是裸文字。
        //
        //挂载方式（用户反馈 2026-11-08：头部不会跟着列表一起滑，要滚过一整屏才突然收起）：
        //原来是插进 listContainer 这个竖排 LinearLayout 里，头部只能占位、靠定时动画收起，天生不跟手。
        //改成浮在列表上方的一层（锚在 listContainer 顶部），列表用 paddingTop 给它让位，
        //滚动时按滚动偏移整块 translationY 上移——真跟手，且不会出现"条目 2 倍速上移"
        //（列表自身不再做补偿滚动）。
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        int index = container.indexOfChild(sortBar);
        container.removeView(sortBar);
        if (index < 0) index = 0;
        android.widget.RelativeLayout.LayoutParams headerLp =
                new android.widget.RelativeLayout.LayoutParams(
                        android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
                        android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT);
        headerLp.addRule(android.widget.RelativeLayout.ALIGN_TOP, R.id.listContainer);
        headerLp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_START);
        root.addView(header, headerLp);
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
        //小屏/界面放大后两张入口卡会吃掉大半屏（用户反馈 2026-11-08：手表上占 3/4）。
        //可用高度不足时隐藏副标题、图标再收一档，只留一行标题，两张卡合计高度约减半
        float density = getResources().getDisplayMetrics().density;
        int screenHeightDp = (int) (getResources().getDisplayMetrics().heightPixels / density);
        if (screenHeightDp < 420) {
            if (subView != null) subView.setVisibility(View.GONE);
            if (icon != null) {
                ViewGroup.LayoutParams lp = icon.getLayoutParams();
                int side = (int) (24 * density + 0.5f);
                lp.width = side;
                lp.height = side;
                icon.setLayoutParams(lp);
            }
        }
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
     * 头部（听全部 / 下载全部 + 排序栏）随列表 1:1 跟手滑动。
     *
     * <p>实现要点：头部是浮在列表上方的一层（不参与 LinearLayout 流式排布），列表用
     * {@code paddingTop} 给它让位，滚动时按 {@code computeVerticalScrollOffset()} 移 {@code translationY}。
     * 这样头部移走多少、内容就顶上来多少，天然同步。
     *
     * <p>为什么不直接把头部当列表的 header item：{@code VideoCardAdapter} 被 10 多个页面共用，
     * 加 header 会挪动所有 position，波及多选、长按删除、分页插入等一批调用点，回归面太大。
     *
     * <p>也不再用"滚过一整屏再播 150ms 收起动画"那一套：动画期间头部是静止的，
     * 放大/小屏上表现为"不跟着滑、然后突然跳走"（用户反馈 2026-11-08），已整段移除。
     */
    private void attachSortBarCollapse() {
        final View header = headerBar != null ? headerBar : sortBar;
        if (header == null || recyclerView == null) return;

        //列表给头部让位：paddingTop = 头部高度，内容从头部下方开始；
        //clipToPadding=false 让内容能画进 padding 区，滚上来时正好接在头部后面
        recyclerView.setClipToPadding(false);
        header.addOnLayoutChangeListener((v, left, top, right, bottom,
                                          oldLeft, oldTop, oldRight, oldBottom) -> {
            int h = bottom - top;
            if (h <= 0 || h == sortBarFullHeight) return;
            sortBarFullHeight = h;
            recyclerView.setPadding(recyclerView.getPaddingLeft(), h,
                    recyclerView.getPaddingRight(), recyclerView.getPaddingBottom());
            header.setTranslationY(-Math.min(
                    Math.max(recyclerView.computeVerticalScrollOffset(), 0), h));
        });

        //1:1 跟手：滚多少头部上移多少，偏移到自身高度时完全移出屏幕顶部。
        //不再做"滚过一整屏再播放 150ms 收起动画"——那套在放大/小屏上表现为头部长时间不动、
        //然后突然跳走（用户反馈 2026-11-08）。列表自身不做补偿滚动，也不会出现 2 倍速位移。
        recyclerView.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@androidx.annotation.NonNull androidx.recyclerview.widget.RecyclerView rv,
                                   int dx, int dy) {
                if (sortBarFullHeight <= 0) return;
                int offset = Math.max(rv.computeVerticalScrollOffset(), 0);
                header.setTranslationY(-Math.min(offset, sortBarFullHeight));
            }
        });
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
