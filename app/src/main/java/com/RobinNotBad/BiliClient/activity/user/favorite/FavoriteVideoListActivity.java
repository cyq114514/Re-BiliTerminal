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
import com.RobinNotBad.BiliClient.adapter.video.VideoCardWithHeaderAdapter;
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

    /** 排序栏容器：作为列表头部 item 的一部分，随列表一起滚走 */
    private View sortBar;

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

        setupSortBar();
        setupAudioQueueEntry();
        reloadFromFirst();
    }

    /** 音频播放入口：独立一行放在排序条上方（不改通用布局，仅本页面态可见） */
    private void setupAudioQueueEntry() {
        LinearLayout container = findViewById(R.id.listContainer);
        if (container == null || sortBar == null) return;

        //把「听全部 / 下载全部」与排序栏做成 RecyclerView 的第一个 item。
        //
        //历史：先插在 listContainer 里（不跟手）→ 改成浮层 + translationY（能跟手，但 2026-11-21
        //反馈滚动后入口卡和 sortBar 被顶到标题栏位置，压住标题；clipChildren 实测裁不住）。
        //现在交给 RecyclerView：item 滚动天然跟手，超出列表上沿的内容由 RecyclerView 自己裁剪，
        //结构上不可能再画到固定的标题栏上。
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        ViewGroup sortParent = (ViewGroup) sortBar.getParent();
        if (sortParent != null) sortParent.removeView(sortBar);

        //与视频卡片同款半透明卡片：直接 inflate 卡片布局（card_bg_new #4D262626 + 描边 + 零投影 + 同款外边距），
        //不再用自绘背景，保证与列表里的视频卡片视觉完全一致
        header.addView(buildAudioEntryCard("听全部（纯音频）", "按当前列表顺序连续播放",
                view -> startAudioQueue()));
        header.addView(buildAudioEntryCard("下载全部（音频）", "整夹加入下载队列，可后台继续",
                view -> confirmDownloadAll()));
        header.addView(sortBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        //包一层 VideoCardWithHeaderAdapter：委托方的 notify 会自动 +1 偏移，
        //收藏夹自己的多选/长按/分页位置语义不变（仍按列表内位置传回）
        setAdapter(new VideoCardWithHeaderAdapter(header, videoCardAdapter));
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
        int screenWidthDp = getResources().getConfiguration().screenWidthDp;
        //宽度也要看：此前只按高度判断，窄屏（或大字号把可用宽度挤小）时卡片标题仍会被截。
        if (screenHeightDp < 420 || screenWidthDp < 360) {
            if (subView != null) subView.setVisibility(View.GONE);
            //手表宽度下两条入口卡标题会截成"听全部（纯…"：小屏去掉括号里的说明，
            //只留动作名，配一行卡片本身已经足够表达
            if (titleView != null) {
                titleView.setText(title.replace("（纯音频）", "").replace("（音频）", ""));
            }
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
                .setPositiveButton("下载", (dialog, which) -> downloadAll())
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
                .setPositiveButton("确认", (dialog, which) -> deleteSelected(videoCardAdapter.getSelectedAids()))
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
