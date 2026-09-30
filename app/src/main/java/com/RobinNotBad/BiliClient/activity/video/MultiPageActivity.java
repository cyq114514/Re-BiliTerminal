package com.RobinNotBad.BiliClient.activity.video;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.adapter.video.PageChooseAdapter;
import com.RobinNotBad.BiliClient.api.PlayerApi;
import com.RobinNotBad.BiliClient.model.PlayerData;
import com.RobinNotBad.BiliClient.model.VideoInfo;
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager;
import com.RobinNotBad.BiliClient.util.FileUtil;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.TerminalContext;

import java.io.File;

//分页视频选集
//2023-07-17

public class MultiPageActivity extends BaseActivity {
    VideoInfo videoInfo;
    PlayerData playerData;

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
        playerData = intent.getParcelableExtra("data");

        TerminalContext.getInstance().getVideoInfoByAidOrBvId(playerData.aid, "").observe(this, result -> result.onSuccess((videoInfo -> {
            this.videoInfo = videoInfo;
            PageChooseAdapter adapter = new PageChooseAdapter(this, videoInfo.pagenames);

            if (intent.getIntExtra("download", 0) == 1) {    //下载模式
                adapter.setOnItemClickListener(position -> {
                    File rootPath = new File(FileUtil.getVideoDownloadPath(), FileUtil.stringToFile(videoInfo.title));
                    File downPath = new File(rootPath, FileUtil.stringToFile(videoInfo.pagenames.get(position)));
                    if (downPath.exists()) {
                        File file_sign = new File(downPath, ".DOWNLOADING");
                        MsgUtil.showMsg(file_sign.exists() ? "已在下载队列" : "已下载完成");
                    } else {
                        startActivity(
                                new Intent()
                                        .putExtra("page", position)
                                        .setClass(this, QualityChooserActivity.class)
                                        .putExtra("aid", videoInfo.aid)
                                        .putExtra("bvid", videoInfo.bvid)
                        );
                    }
                });
            } else {        //普通播放模式
                //上次观看的P（详情页预取 playurl 时由 last_play_cid 带回），列表里高亮提示
                int historyIndex = playerData.cidHistory > 0 ? videoInfo.cids.indexOf(playerData.cidHistory) : -1;
                if (historyIndex >= 0) {
                    adapter.setHistoryIndex(historyIndex);
                    textView.setText("请选择分页（上次看到P" + (historyIndex + 1) + "）");
                }

                //无论选哪一P都用所选P重建 PlayerData：旧逻辑在"点回上次看的P"时复用了 cid 仍是P1的旧对象，
                //导致实际播放的是P1；续播进度由 PlayerApi 按 last_play_cid 配对校验后填入
                adapter.setOnItemClickListener(position -> {
                    playerData = videoInfo.toPlayerData(position);
                    playerData.timeStamp = 0;
                    PlayerApi.startGettingUrl(playerData);
                });
            }

            recyclerView.setLayoutManager(new CustomLinearManager(this));
            recyclerView.setAdapter(adapter);
        })));

    }

}