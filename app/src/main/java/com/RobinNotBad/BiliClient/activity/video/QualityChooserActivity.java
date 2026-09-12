package com.RobinNotBad.BiliClient.activity.video;

import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.adapter.QualityChooseAdapter;
import com.RobinNotBad.BiliClient.api.PlayerApi;
import com.RobinNotBad.BiliClient.model.PlayerData;
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.TerminalContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class QualityChooserActivity extends BaseActivity {

    int[] qns;
    boolean isAudioOnlyOption = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_simple_list);
        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        findViewById(R.id.top).setOnClickListener(view -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        ((TextView) findViewById(R.id.pageName)).setText("请选择清晰度");

        long aid = getIntent().getLongExtra("aid", 0);
        String bvid = getIntent().getStringExtra("bvid");

        TerminalContext.getInstance().getVideoInfoByAidOrBvId(aid, bvid).observe(this,
                result -> result.onSuccess((videoInfo -> {

                    QualityChooseAdapter adapter = new QualityChooseAdapter(this);
                    int page = getIntent().getIntExtra("page", 0);
                    CenterThreadPool.run(() -> {
                        // 获取清晰度列表
                        try {
                            PlayerData playerData = videoInfo.toPlayerData(page);
                            PlayerApi.getVideo(playerData, true);
                            qns = playerData.qnValueList;

                            // 在清晰度列表末尾添加"仅音频"选项
                            List<String> qualityList = new ArrayList<>(Arrays.asList(playerData.qnStrList));
                            qualityList.add("仅音频");
                            isAudioOnlyOption = true;

                            runOnUiThread(() -> adapter.setNameList(qualityList));
                        } catch (Exception e) {
                            runOnUiThread(() -> MsgUtil.showMsg("清晰度列表获取失败！"));
                            e.printStackTrace();
                        }
                    });
                    adapter.setOnItemClickListener((position -> {
                        if (qns == null)
                            return;

                        if (isAudioOnlyOption && position == qns.length) {
                            // 获取DASH格式数据
                            CenterThreadPool.run(() -> {
                                try {
                                    PlayerData playerData = videoInfo.toPlayerData(page);
                                    playerData.qn = qns[0]; // 使用最高清晰度获取音频
                                    PlayerApi.getVideoDash(playerData);

                                    if (playerData.audioUrl == null || playerData.audioUrl.isEmpty()) {
                                        runOnUiThread(() -> MsgUtil.showMsg("该视频没有可用的音频流"));
                                        return;
                                    }

                                    PlayerApi.startDownloadingAudioOnly(videoInfo, page, qns[0], playerData.audioUrl);
                                    runOnUiThread(this::finish);
                                } catch (Exception e) {
                                    runOnUiThread(() -> {
                                        MsgUtil.showMsg("获取音频信息失败：" + e.getMessage());
                                        e.printStackTrace();
                                    });
                                }
                            });
                        } else {
                            // 选择了普通清晰度
                            int qn = qns[position];
                            PlayerApi.startDownloading(videoInfo, page, qn);
                            finish();
                        }
                    }));

                    recyclerView.setLayoutManager(new CustomLinearManager(this));
                    recyclerView.setAdapter(adapter);
                })));

    }
}
