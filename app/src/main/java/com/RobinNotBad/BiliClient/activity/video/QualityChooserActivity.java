package com.RobinNotBad.BiliClient.activity.video;

import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.adapter.QualityChooseAdapter;
import com.RobinNotBad.BiliClient.api.PlayerApi;
import com.RobinNotBad.BiliClient.api.VipApi;
import com.RobinNotBad.BiliClient.model.PlayerData;
import com.RobinNotBad.BiliClient.model.VipInfo;
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.RobinNotBad.BiliClient.util.TerminalContext;
import com.RobinNotBad.BiliClient.util.ToolsUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class QualityChooserActivity extends BaseActivity {

    int[] qns;
    boolean isAudioOnlyOption = false;

    /**
     * 强制高分辨率选项：API 的 accept_quality 里没有、但尝试请求的高清晰度。
     * key=qn，value=显示名。这些清晰度走 DASH 双流下载（durl 拿不到）。
     */
    private static final LinkedHashMap<Integer, String> FORCED_QUALITY_MAP = new LinkedHashMap<Integer, String>() {{
        put(120, "4K 超清");
        put(112, "1080P 高码率");
        put(80, "1080P 高清");
    }};

    /**
     * 强制选项对应的 fnval（4048 = DASH|HDR|4K|杜比全景声|杜比视界|8K|AV1 全开）：
     * 4K 及以上不带对应位时服务端不会下发该清晰度的流。
     */
    private static final int FORCED_FNVAL = 4048;

    /** 列表位置 → 强制项 qn。构建列表时填好，点击时按位置取，不再推算 */
    private final Map<Integer, Integer> forcedQnByPosition = new LinkedHashMap<>();

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
                    if (!ToolsUtil.isHostAlive(this)) return;

                    QualityChooseAdapter adapter = new QualityChooseAdapter(this);
                    int page = getIntent().getIntExtra("page", 0);
                    CenterThreadPool.run(() -> {
                        // 获取清晰度列表
                        try {
                            PlayerData playerData = videoInfo.toPlayerData(page);
                            // 以最高画质请求，让账号能拿到的清晰度全部出现在列表里
                            playerData.qn = 127;
                            try {
                                PlayerApi.getVideo(playerData, true);
                            } catch (Exception durlError) {
                                // 4K/HDR 等视频用 qn=127 请求时服务端只回 DASH 不回 durl，
                                // 上面会抛「durl is empty」：改走 DASH 拿清晰度列表（accept_quality 同样会填）
                                PlayerApi.getVideoDash(playerData, FORCED_FNVAL);
                            }
                            qns = playerData.qnValueList;

                            List<String> qualityList = new ArrayList<>(Arrays.asList(playerData.qnStrList));

                            // 强制高分辨率：把 API 没返回的高清晰度补进列表（标 [强制]），
                            // 点了走 DASH 双流下载并本地合并成 MP4
                            forcedQnByPosition.clear();
                            if (SharedPreferencesUtil.getBoolean("force_high_quality_options", true) && qns != null) {
                                Set<Integer> existingQns = new HashSet<>();
                                for (int qn : qns) existingQns.add(qn);
                                int offset = 0;
                                for (Map.Entry<Integer, String> entry : FORCED_QUALITY_MAP.entrySet()) {
                                    if (!existingQns.contains(entry.getKey())) {
                                        int position = qns.length + offset;
                                        qualityList.add(entry.getValue() + " [强制]");
                                        forcedQnByPosition.put(position, entry.getKey());
                                        offset++;
                                    }
                                }
                            }

                            // 在清晰度列表末尾添加"仅音频"选项
                            qualityList.add("仅音频");
                            isAudioOnlyOption = true;
                            int audioOnlyPosition = qualityList.size() - 1;
                            int[] currentQns = qns;

                            runOnUiThread(() -> adapter.setNameList(qualityList));

                            adapter.setOnItemClickListener((position -> {
                                if (currentQns == null)
                                    return;

                                if (isAudioOnlyOption && position == audioOnlyPosition) {
                                    // 获取DASH格式数据
                                    CenterThreadPool.run(() -> {
                                        try {
                                            PlayerData dashData = videoInfo.toPlayerData(page);
                                            dashData.qn = currentQns[0]; // 使用最高清晰度获取音频
                                            PlayerApi.getVideoDash(dashData);

                                            if (dashData.audioUrl == null || dashData.audioUrl.isEmpty()) {
                                                runOnUiThread(() -> MsgUtil.showMsg("该视频没有可用的音频流"));
                                                return;
                                            }

                                            PlayerApi.startDownloadingAudioOnly(videoInfo, page, currentQns[0], dashData.audioUrl);
                                            runOnUiThread(this::finish);
                                        } catch (Exception e) {
                                            runOnUiThread(() -> {
                                                MsgUtil.showMsg("获取音频信息失败：" + e.getMessage());
                                                e.printStackTrace();
                                            });
                                        }
                                    });
                                } else if (forcedQnByPosition.containsKey(position)) {
                                    // 强制高分辨率：112/120 仅大会员可下载，先校验再发起
                                    CenterThreadPool.run(() -> {
                                        try {
                                            int targetQn = forcedQnByPosition.get(position);
                                            String qualityName = FORCED_QUALITY_MAP.get(targetQn);

                                            if (targetQn >= 112) {
                                                VipInfo vipInfo = VipApi.getVipInfo();
                                                if (vipInfo == null || vipInfo.vipStatus != 1) {
                                                    runOnUiThread(() -> MsgUtil.showMsg(qualityName + " 仅大会员可下载"));
                                                    return;
                                                }
                                            }

                                            PlayerData dashData = videoInfo.toPlayerData(page);
                                            dashData.qn = targetQn;
                                            PlayerApi.getVideoDash(dashData, FORCED_FNVAL);
                                            if (dashData.videoUrls.isEmpty()) {
                                                runOnUiThread(() -> MsgUtil.showMsg("该视频没有 " + qualityName + " 的可用视频流"));
                                                return;
                                            }
                                            String audioUrl = dashData.audioUrl != null ? dashData.audioUrl : "";
                                            PlayerApi.startDownloadingDash(videoInfo, page, targetQn, audioUrl);
                                            runOnUiThread(this::finish);
                                        } catch (Exception e) {
                                            runOnUiThread(() -> {
                                                MsgUtil.showMsg("获取清晰度信息失败：" + e.getMessage());
                                                e.printStackTrace();
                                            });
                                        }
                                    });
                                } else {
                                    // 选择了普通清晰度
                                    int qn = currentQns[position];
                                    PlayerApi.startDownloading(videoInfo, page, qn);
                                    finish();
                                }
                            }));

                            // setLayoutManager/setAdapter 必须在主线程：后台线程调用会抛
                            // 「Cannot call this method while RecyclerView is computing a layout」
                            runOnUiThread(() -> {
                                recyclerView.setLayoutManager(new CustomLinearManager(this));
                                recyclerView.setAdapter(adapter);
                            });
                        } catch (Exception e) {
                            runOnUiThread(() -> MsgUtil.showMsg("清晰度列表获取失败！"));
                            e.printStackTrace();
                        }
                    });
                })));

    }
}
