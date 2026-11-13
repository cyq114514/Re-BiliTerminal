package com.RobinNotBad.BiliClient.activity.video.local;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity;
import com.RobinNotBad.BiliClient.activity.player.AudioPlayerActivity;
import com.RobinNotBad.BiliClient.adapter.video.LocalVideoAdapter;
import com.RobinNotBad.BiliClient.model.AudioQueue;
import com.RobinNotBad.BiliClient.model.LocalVideo;
import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.service.AudioPlayerService;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.FileUtil;
import com.RobinNotBad.BiliClient.util.MsgUtil;

import java.io.File;
import java.util.ArrayList;

//本地列表
//2023-08-07

public class LocalListActivity extends InstanceActivity {

    private SwipeRefreshLayout swipeRefreshLayout;
    private RecyclerView recyclerView;
    private final ArrayList<LocalVideo> videoList = new ArrayList<>(10);
    private LocalVideoAdapter adapter;
    private TextView emptyTip;

    private int longClickPosition = -1;
    private long longClickTimestamp;

    private boolean started;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_simple_main_refresh);
        setMenuClick();

        recyclerView = findViewById(R.id.recyclerView);

        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        swipeRefreshLayout.setOnRefreshListener(this::refresh);

        emptyTip = findViewById(R.id.emptyTip);

        TextView pageName = findViewById(R.id.pageName);
        pageName.setText("缓存");

        //听歌模式入口（1.3.3 反馈：缓存的音频不利用可惜）：把下载目录的 media 文件
        //按缓存文件夹顺序灌进音频队列，复用收藏夹「听全部」的全屏听歌页与服务
        View localAudioBtn = findViewById(R.id.local_audio_btn);
        localAudioBtn.setVisibility(View.VISIBLE);
        //听歌按钮占宽，标题右留白同步让位（与 RefreshMainActivity 同一套逻辑）
        View pageTitle = findViewById(R.id.pageName);
        if (pageTitle != null) {
            android.view.ViewGroup.LayoutParams lp = pageTitle.getLayoutParams();
            if (lp instanceof android.view.ViewGroup.MarginLayoutParams)
                ((android.view.ViewGroup.MarginLayoutParams) lp).setMarginEnd((int) (88 * getResources().getDisplayMetrics().density + 0.5f));
        }
        localAudioBtn.setOnClickListener(v -> startAudioQueue(0));

        if (!FileUtil.checkStoragePermission()) {
            FileUtil.requestStoragePermission(this);
        }

        CenterThreadPool.run(() -> {
            runOnUiThread(() -> swipeRefreshLayout.setRefreshing(true));
            //先构建到临时列表再并入 videoList：此时 adapter 尚未挂到 UI，主线程不可能并发读
            ArrayList<LocalVideo> initialList = new ArrayList<>(10);
            scan(initialList, FileUtil.getVideoDownloadPath());
            videoList.addAll(initialList);
            adapter = new LocalVideoAdapter(this, videoList);
            //点纯音频条目=从这条开始连播整个缓存（1.3.5 反馈 R1：此前点条目只播单曲，
            //用户以为"缓存不能自动接下一首"；标题栏「听歌」保留为"从头听"）
            adapter.setOnAudioItemClickListener(position -> startAudioQueue(audioIndexOf(position)));

            adapter.setOnLongClickListener(position -> {
                long timestamp = System.currentTimeMillis();
                if (longClickPosition == position && timestamp - longClickTimestamp < 4000) {
                    File file = new File(FileUtil.getVideoDownloadPath(), videoList.get(position).title);
                    CenterThreadPool.run(() -> FileUtil.deleteFolder(file));
                    MsgUtil.showMsg("删除成功");
                    videoList.remove(position);
                    adapter.notifyItemRemoved(position + 1);
                    adapter.notifyItemRangeChanged(position + 1, videoList.size() - position);
                    longClickPosition = -1;
                    checkEmpty();
                } else {
                    longClickPosition = position;
                    longClickTimestamp = timestamp;
                    MsgUtil.showMsg("再次长按删除");
                }
            });
            runOnUiThread(() -> {
                recyclerView.setLayoutManager(getLayoutManager());
                recyclerView.setAdapter(adapter);
                swipeRefreshLayout.setRefreshing(false);
                started = true;
                //首载路径此前不查空态：清缓存后首次进入整页空白，只有删除/刷新路径才补查（D-05）
                checkEmpty();
            });
        });
    }

    private void scan(ArrayList<LocalVideo> into, File folder) {
        File[] files = folder.listFiles();
        if (files == null)
            return;

        for (File video : files) {
            if (video.isDirectory()) {
                LocalVideo localVideo = new LocalVideo();
                localVideo.title = video.getName();

                localVideo.cover = (new File(video, "cover.png")).toString();

                localVideo.pageList = new ArrayList<>();
                localVideo.danmakuFileList = new ArrayList<>();
                localVideo.videoFileList = new ArrayList<>();
                localVideo.sizeList = new ArrayList<>();

                File videoFile = new File(video, "video.mp4");
                File audioFile = new File(video, "audio.m4a"); // 仅音频文件
                File danmakuFile = new File(video, "danmaku.xml");

                // 优先检查视频文件，其次检查音频文件
                File mediaFile = videoFile.exists() ? videoFile : (audioFile.exists() ? audioFile : null);

                if (mediaFile != null) {
                    File mark = new File(video, ".DOWNLOADING");
                    if (mark.exists())
                        continue;

                    localVideo.sizeList.add(mediaFile.length());
                    localVideo.videoFileList.add(mediaFile.toString());
                    localVideo.danmakuFileList.add(danmakuFile.toString()); // 单集视频

                    localVideo.calcTotalSize();
                    into.add(localVideo);
                } else {
                    File[] pages = video.listFiles(); // 分页视频
                    if (pages != null) {
                        for (File page : pages) {
                            if (page.isDirectory()) {
                                File mark = new File(page, ".DOWNLOADING");
                                if (mark.exists())
                                    continue;

                                File pageVideoFile = new File(page, "video.mp4");
                                File pageAudioFile = new File(page, "audio.m4a"); // 仅音频文件
                                File pageDanmakuFile = new File(page, "danmaku.xml");

                                // 优先检查视频文件，其次检查音频文件
                                File pageMediaFile = pageVideoFile.exists() ? pageVideoFile
                                        : (pageAudioFile.exists() ? pageAudioFile : null);

                                if (pageMediaFile != null) {
                                    localVideo.pageList.add(page.getName());
                                    localVideo.sizeList.add(pageMediaFile.length());
                                    localVideo.videoFileList.add(pageMediaFile.toString());
                                    localVideo.danmakuFileList.add(pageDanmakuFile.toString());
                                }
                            }
                        }
                        localVideo.calcTotalSize();
                        if (localVideo.videoFileList.size() > 0)
                            into.add(localVideo);
                    }
                }
            }
        }
    }

    /**
     * 把缓存列表展开成音频队列条目：单集 1 条，多P 每 P 一条，顺序与列表一致。
     *
     * <p>只负责组装，不决定从哪首开始——听歌按钮传 0，点某条音频则传该条的展开下标。
     */
    private ArrayList<VideoCard> buildAudioItems() {
        ArrayList<VideoCard> items = new ArrayList<>();
        for (LocalVideo localVideo : videoList) {
            for (int i = 0; i < localVideo.videoFileList.size(); i++) {
                VideoCard card = new VideoCard();
                //单集标题=文件夹名；分P 标题=文件夹名·分P 名，与本地选集页口径一致
                boolean single = localVideo.videoFileList.size() == 1;
                card.title = single ? localVideo.title
                        : localVideo.title + " · " + localVideo.pageList.get(i);
                card.upName = "本地缓存";
                card.cover = localVideo.cover;
                card.localPath = localVideo.videoFileList.get(i);
                items.add(card);
            }
        }
        return items;
    }

    /** 从展开后的第 startIndex 条起播，并打开全屏听歌页 */
    private void startAudioQueue(int startIndex) {
        ArrayList<VideoCard> items = buildAudioItems();
        if (items.isEmpty()) {
            MsgUtil.showMsg("还没有可听的缓存");
            return;
        }
        if (startIndex < 0 || startIndex >= items.size()) startIndex = 0;
        //mediaId 用 -1：不与任何收藏夹冲突，续播记录天然隔离；
        //同时 AudioQueue 据此判定"没有下一页"，播完最后一首会正常回卷到第一首
        AudioPlayerService.startQueue(this, items, -1, "", "本地缓存", startIndex, 0);
        if (AudioQueue.get().current() == null) {
            //队列被过滤空时不要打开听歌页——那里只会弹"没有正在播放的音频"，反而看不出原因
            MsgUtil.showMsg("这些缓存无法加入播放队列");
            return;
        }
        startActivity(new Intent(this, AudioPlayerActivity.class));
    }

    /** 第 videoIndex 个缓存文件夹在展开后的音频条目里的起始下标 */
    private int audioIndexOf(int videoIndex) {
        int index = 0;
        for (int i = 0; i < videoIndex && i < videoList.size(); i++)
            index += videoList.get(i).videoFileList.size();
        return index;
    }

    private void checkEmpty() {
        runOnUiThread(() -> {
            if (videoList.isEmpty() && emptyTip != null) {
                emptyTip.setVisibility(View.VISIBLE);
            } else {
                if (emptyTip != null) {
                    emptyTip.setVisibility(View.GONE);
                }
            }
        });
    }

    public void refresh() {
        if (started)
            CenterThreadPool.run(() -> {
                runOnUiThread(() -> swipeRefreshLayout.setRefreshing(true));
                //后台线程直改 videoList 会与 UI 线程的 onBindViewHolder 并发读写同一 ArrayList（可崩溃），
                //且 notifyItemRangeChanged 表达不了增删——改为后台构建新列表、主线程整体替换后全量刷新
                ArrayList<LocalVideo> newList = new ArrayList<>(10);
                scan(newList, FileUtil.getVideoDownloadPath());
                runOnUiThread(() -> {
                    videoList.clear();
                    videoList.addAll(newList);
                    if (adapter != null) adapter.notifyDataSetChanged();
                    swipeRefreshLayout.setRefreshing(false);
                    checkEmpty();
                });
            });
    }
}