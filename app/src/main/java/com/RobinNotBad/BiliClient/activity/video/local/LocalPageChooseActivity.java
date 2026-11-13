package com.RobinNotBad.BiliClient.activity.video.local;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity;
import com.RobinNotBad.BiliClient.activity.player.AudioPlayerActivity;
import com.RobinNotBad.BiliClient.adapter.video.PageChooseAdapter;
import com.RobinNotBad.BiliClient.api.PlayerApi;
import com.RobinNotBad.BiliClient.model.AudioQueue;
import com.RobinNotBad.BiliClient.model.PlayerData;
import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.service.AudioPlayerService;
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.FileUtil;
import com.RobinNotBad.BiliClient.util.MsgUtil;

import java.io.File;
import java.util.ArrayList;

//分页视频选集
//2023-07-17

public class LocalPageChooseActivity extends BaseActivity {

    private int longClickPosition = -1;
    private long longClickTimestamp;
    private boolean deleted = false;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_simple_list);
        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        findViewById(R.id.top).setOnClickListener(view -> finish());

        TextView textView = findViewById(R.id.pageName);
        textView.setText("请选择分页");

        Intent intent = getIntent();
        String title = intent.getStringExtra("title");
        ArrayList<String> pageList = intent.getStringArrayListExtra("pageList");
        ArrayList<String> videoFileList = intent.getStringArrayListExtra("videoFileList");
        ArrayList<String> danmakuFileList = intent.getStringArrayListExtra("danmakuFileList");

        PageChooseAdapter adapter = new PageChooseAdapter(this, pageList);
        adapter.setOnItemClickListener(position -> {
            String mediaPath = videoFileList.get(position);
            //纯音频多P：把整组灌进听歌队列，从这一P 起播（1.3.5 反馈 R1）。
            //以前进内置播放器，播完这一P 就停，用户以为"缓存不能自动接下一首"。
            //只有整组都是音频才这么做：混着视频时整组塞进队列会把视频也当歌占位
            if (mediaPath != null && mediaPath.endsWith("audio.m4a") && allAudio(videoFileList)) {
                ArrayList<VideoCard> items = new ArrayList<>();
                for (int i = 0; i < videoFileList.size(); i++) {
                    VideoCard card = new VideoCard();
                    card.title = i < pageList.size() ? pageList.get(i) : title;
                    card.upName = "本地缓存";
                    card.localPath = videoFileList.get(i);
                    items.add(card);
                }
                AudioPlayerService.startQueue(this, items, -1, "",
                        title == null ? "本地缓存" : title, position, 0);
                if (AudioQueue.get().current() == null) {
                    MsgUtil.showMsg("这些缓存无法加入播放队列");
                    return;
                }
                startActivity(new Intent(this, AudioPlayerActivity.class));
                return;
            }
            PlayerData playerData = new PlayerData(PlayerData.TYPE_LOCAL);
            playerData.videoUrl = mediaPath;
            playerData.danmakuUrl = danmakuFileList.get(position);
            playerData.title = pageList.get(position);
            try {
                Intent player = PlayerApi.jumpToPlayer(playerData);
                // 如果是仅音频文件，标记为音频模式
                if (mediaPath.endsWith("audio.m4a")) {
                    player.putExtra("audio_only", true);
                }
                startActivity(player);
            } catch (ActivityNotFoundException e) {
                MsgUtil.showMsg("没有找到播放器，请检查是否安装");
            } catch (Exception e) {
                MsgUtil.err(e);
            }
        });
        adapter.setOnItemLongClickListener(position -> {
            long timestamp = System.currentTimeMillis();
            if (longClickPosition == position && timestamp - longClickTimestamp < 4000) {
                //列表增删与 notify 必须在主线程同序完成：旧实现在后台线程删数据、主线程提前 notify，
                //RecyclerView 布局状态与 Adapter 数据量失序，滑动时抛 IndexOutOfBoundsException
                String pageName = pageList.get(position);
                pageList.remove(position);
                videoFileList.remove(position);
                danmakuFileList.remove(position);
                adapter.notifyItemRemoved(position);
                adapter.notifyItemRangeChanged(position, pageList.size() - position);
                boolean empty = pageList.isEmpty();

                CenterThreadPool.run(() -> {
                    File workPath = FileUtil.getVideoDownloadPath();
                    File videoPath = new File(workPath, title);
                    File pagePath = new File(videoPath, pageName);

                    FileUtil.deleteFolder(pagePath);
                    if (empty)
                        FileUtil.deleteFolder(videoPath);
                });

                MsgUtil.showMsg("删除成功");
                longClickPosition = -1;

                deleted = true;
            } else {
                longClickPosition = position;
                longClickTimestamp = timestamp;
                MsgUtil.showMsg("再次长按删除");
            }
        });

        recyclerView.setLayoutManager(new CustomLinearManager(this));
        recyclerView.setAdapter(adapter);
    }

    /** 整组是否都是纯音频文件（混着视频的多P 不走听歌队列，仍进内置播放器） */
    private static boolean allAudio(ArrayList<String> files) {
        if (files == null || files.isEmpty()) return false;
        for (String path : files) {
            if (path == null || !path.endsWith("audio.m4a")) return false;
        }
        return true;
    }

    @Override
    protected void onDestroy() {
        InstanceActivity instance = BiliTerminal.getInstanceActivityOnTop();
        if (deleted && instance instanceof LocalListActivity && !instance.isDestroyed())
            ((LocalListActivity) (instance)).refresh();
        super.onDestroy();
    }
}