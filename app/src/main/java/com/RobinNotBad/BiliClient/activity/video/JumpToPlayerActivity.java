package com.RobinNotBad.BiliClient.activity.video;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.DownloadActivity;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.api.HistoryApi;
import com.RobinNotBad.BiliClient.api.PlayerApi;
import com.RobinNotBad.BiliClient.model.PlayerData;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import org.json.JSONException;

import java.io.IOException;

public class JumpToPlayerActivity extends BaseActivity {
    String title;
    TextView textView;

    PlayerData playerData;

    int download;

    final ActivityResultLauncher<Intent> launcher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), new ActivityResultCallback<>() {
        @Override
        public void onActivityResult(ActivityResult o) {
            int code = o.getResultCode();
            Intent result = o.getData();
            Logu.d("进度回调", "onActivityResult");

            //外部播放器(小电视/凉腕)不会回传 RESULT_OK，这里用进入播放前拿到的进度兜底，
            //否则番剧走外部播放器时进度会完全丢失（普通视频由详情页上报掩盖了这个问题）
            //兜底值 == 进入时的续播位置 == 服务端已有的值，写回最多是"原地踏步"不会回拨；
            //但连进入时的位置都是 0（外部播放器且本集没看过）时就完全没有上报的意义了（审计 P3-5）
            int progress = (code == RESULT_OK && result != null)
                    ? result.getIntExtra("progress", playerData.progress)
                    : playerData.progress;
            boolean returnedByPlayer = code == RESULT_OK && result != null && result.hasExtra("progress");
            //播放器内可能切换过分P：最终观看的 cid 以播放器回传为准，不能沿用进入时的旧 cid，
            //否则会拿新P的进度去覆盖旧P的记录，把正确的续播位置冲掉
            long finalCid = (code == RESULT_OK && result != null && result.hasExtra("cid"))
                    ? result.getLongExtra("cid", playerData.cid)
                    : playerData.cid;
            Logu.d("进度回调", String.valueOf(progress));

            if (!returnedByPlayer && progress <= 0) {
                Logu.d("进度回调", "播放器未回传进度且进入时无续播位置，跳过兜底上报");
                finish();
                return;
            }

            CenterThreadPool.run(() -> {
                //登录态一律按实时 Cookie 判定：本地快照 mid 在切号/刷新Cookie 后会错位，
                //过去这里用 playerData.mid != 0 做门槛，错位时会把整条退出上报静默跳过
                if (!playerData.isLive() && !playerData.isLocal() && NetWorkUtil.isLoggedIn() && playerData.aid != 0) try {
                    //番剧必须走带 epid/sid 的心跳接口，否则观看记录不会按番剧维度落库——进度等于没上报
                    if (playerData.isBangumi() && playerData.epid != 0)
                        HistoryApi.reportHistoryPgc(playerData.bvid, playerData.aid, finalCid, playerData.epid,
                                playerData.seasonId, playerData.seasonType, progress / 1000);
                    else
                        HistoryApi.reportHistory(playerData.aid, finalCid, progress / 1000);
                } catch (Exception e) {
                    MsgUtil.err("进度上报：", e);
                }
                finish();
            });
        }
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player_jump);

        textView = findViewById(R.id.text_title);

        Intent intent = getIntent();
        Log.e("debug-哔哩终端-跳转页", "已接收数据");

        playerData = (PlayerData) intent.getParcelableExtra("data");

        //被外部拉起/进程重建等场景下 extra 可能缺失，直接退出而不是 NPE
        if (playerData == null) {
            MsgUtil.showMsgLong("启动参数缺失，请从应用内重新进入");
            finish();
            return;
        }

        title = playerData.title;

        download = intent.getIntExtra("download", 0);

        playerData.qn = playerData.qn != -1 ? playerData.qn : SharedPreferencesUtil.getInt("play_qn", 16);

        requestVideo();
    }

    @SuppressLint("SetTextI18n")
    private void requestVideo() {
        CenterThreadPool.run(() -> {

            try {
                if (playerData.isBangumi()) PlayerApi.getBangumi(playerData);
                else PlayerApi.getVideo(playerData, download != 0);

                Logu.d("history", String.valueOf(playerData.progress));
                jump();
            } catch (IOException e) {
                setClickExit("网络错误！\n请检查你的网络连接是否正常");
            } catch (JSONException e) {
                setClickExit("视频获取失败！\n可能的原因：\n1.本视频仅大会员可播放\n2.视频获取接口失效\n\n清除应用数据也许可以解决" + e.getMessage());
                e.printStackTrace();
            } catch (ActivityNotFoundException e) {
                setClickExit("跳转失败！\n请安装对应的播放器\n或在设置中选择正确的播放器\n或将 Re：哔哩终端 和播放器同时更新到最新版本");
                e.printStackTrace();
            }
        });
    }

    private void jump() {
        if (isDestroyed()) return;
        if (download == 0) {
            Intent intent = PlayerApi.jumpToPlayer(playerData);
            launcher.launch(intent);
            setClickExit("等待退出播放后上报进度\n（点击跳过）");
        } else {
            Intent intent = new Intent();
            intent.setClass(this, DownloadActivity.class);
            intent.putExtra("type", download);
            intent.putExtra("link", playerData.videoUrl);
            intent.putExtra("danmaku", playerData.danmakuUrl);
            intent.putExtra("title", title);
            intent.putExtra("cover", getIntent().getStringExtra("cover"));
            if (download == 2)
                intent.putExtra("parent_title", getIntent().getStringExtra("parent_title"));
            startActivity(intent);
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    private void setClickExit(String reason) {
        runOnUiThread(() -> {
            textView.setText(reason);
            textView.setOnClickListener((view) -> finish());
        });
    }
}