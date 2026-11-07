package com.RobinNotBad.BiliClient.activity.video.local;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity;
import com.RobinNotBad.BiliClient.adapter.video.LocalVideoAdapter;
import com.RobinNotBad.BiliClient.model.LocalVideo;
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
        localAudioBtn.setOnClickListener(v -> {
            if (videoList.isEmpty()) {
                MsgUtil.showMsg("还没有可听的缓存");
                return;
            }
            java.util.ArrayList<com.RobinNotBad.BiliClient.model.VideoCard> items = new java.util.ArrayList<>();
            for (com.RobinNotBad.BiliClient.model.LocalVideo localVideo : videoList) {
                for (int i = 0; i < localVideo.videoFileList.size(); i++) {
                    com.RobinNotBad.BiliClient.model.VideoCard card = new com.RobinNotBad.BiliClient.model.VideoCard();
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
            if (items.isEmpty()) {
                MsgUtil.showMsg("还没有可听的缓存");
                return;
            }
            //mediaId 用 -1：不与任何收藏夹冲突，续播记录天然隔离
            com.RobinNotBad.BiliClient.service.AudioPlayerService.startQueue(
                    this, items, -1, "", "本地缓存");
            startActivity(new android.content.Intent(this,
                    com.RobinNotBad.BiliClient.activity.player.AudioPlayerActivity.class));
        });

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