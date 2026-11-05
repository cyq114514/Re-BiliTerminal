package com.RobinNotBad.BiliClient.activity.settings;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.NetworkPlaybackQuality;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.RobinNotBad.BiliClient.util.ToolsUtil;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.Map;

//选择播放器

public class SettingPlayerChooseActivity extends BaseActivity {

    String playerCurr = SharedPreferencesUtil.getString("player", "null");
    MaterialCardView terminalPlayer, mtvPlayer, aliangPlayer, qn_choose;
    ArrayList<MaterialCardView> cardViewList;
    int checkPosition = -1;
    final String[] playerList = {"null", "terminalPlayer", "mtvPlayer", "aliangPlayer"};

    private boolean just_create = true;


    @SuppressLint({"MissingInflatedId", "SetTextI18n", "InflateParams"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        asyncInflate(R.layout.activity_setting_player_choose, (layoutView, resId) -> {

            terminalPlayer = findViewById(R.id.terminalPlayer);
            mtvPlayer = findViewById(R.id.mtvPlayer);
            aliangPlayer = findViewById(R.id.aliangPlayer);
            qn_choose = findViewById(R.id.qn_choose);

            qn_choose.setOnClickListener((view) -> handleQnChoose());
            findViewById(R.id.qn_mobile_choose).setOnClickListener(view ->
                    openNetworkQuality(NetworkPlaybackQuality.MOBILE));
            findViewById(R.id.qn_wifi_choose).setOnClickListener(view ->
                    openNetworkQuality(NetworkPlaybackQuality.WIFI));

            cardViewList = new ArrayList<>();
            cardViewList.add(terminalPlayer);
            cardViewList.add(mtvPlayer);
            cardViewList.add(aliangPlayer);

            for (int i = 1; i < playerList.length; i++) {
                if (playerList[i].equals(playerCurr)) {
                    setChecked(i - 1);
                    break;
                }
            }

            setOnClick();
            terminalPlayer.setOnLongClickListener(view -> {
                Intent intent = new Intent();
                intent.setClass(this, SettingTerminalPlayerActivity.class);
                startActivity(intent);
                return true;
            });

            updateQn();
            just_create = false;

            View scrollView = findViewById(R.id.scrollView);
            scrollView.setFocusable(true);
            scrollView.setFocusableInTouchMode(true);
            scrollView.requestFocus();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateQn();
    }

    private void updateQn() {
        if (findViewById(R.id.qn_tv) != null) {
            int savedVal = SharedPreferencesUtil.getInt("play_qn", 16);
            for (Map.Entry<String, Integer> entry : SettingQualityActivity.getQnMap().entrySet()) {
                if (entry.getValue() == savedVal) {
                    ((TextView) findViewById(R.id.qn_tv)).setText(entry.getKey());
                    break;
                }
            }
        }
        updateNetworkQn(R.id.qn_mobile_tv, NetworkPlaybackQuality.MOBILE);
        updateNetworkQn(R.id.qn_wifi_tv, NetworkPlaybackQuality.WIFI);
    }

    private void updateNetworkQn(int id, String key) {
        TextView label = findViewById(id);
        if (label == null) return;
        int value = SharedPreferencesUtil.getInt(key, -1);
        if (value < 0) {
            label.setText("跟随全局");
            return;
        }
        for (Map.Entry<String, Integer> entry : SettingQualityActivity.getQnMap().entrySet()) {
            if (entry.getValue() == value) {
                label.setText(entry.getKey());
                return;
            }
        }
        //存的值不在档位表里时，回退成跟随全局，避免界面显示空白
        SharedPreferencesUtil.putInt(key, -1);
        label.setText("跟随全局");
    }

    private void openNetworkQuality(String key) {
        Intent intent = new Intent(this, SettingQualityActivity.class);
        intent.putExtra(NetworkPlaybackQuality.EXTRA_KEY, key);
        startActivity(intent);
    }

    private void handleQnChoose() {
        startActivity(new Intent(this, SettingQualityActivity.class));
    }


    private void setOnClick() {
        for (int i = 0; i < cardViewList.size(); i++) {
            int finalI = i;
            cardViewList.get(i).setOnClickListener(view -> {
                setChecked(finalI);
                Log.e("debug", "点击了" + finalI);
            });
        }
    }

    private void setChecked(int position) {
        checkPosition = position;
        for (int i = 0; i < cardViewList.size(); i++) {
            if (position == i) {
                cardViewList.get(i).setStrokeColor(getResources().getColor(R.color.pink));
                cardViewList.get(i).setStrokeWidth(ToolsUtil.dp2px(1));
            } else {
                cardViewList.get(i).setStrokeColor(getResources().getColor(R.color.gray));
                cardViewList.get(i).setStrokeWidth(ToolsUtil.dp2px(0.1f));
            }
        }
        if (!just_create) {
            switch (playerList[checkPosition + 1]) {
                case "terminalPlayer":
                    if (playerCurr.equals("null"))
                        startActivity(new Intent(this, SettingTerminalPlayerActivity.class));
                    break;
                case "mtvPlayer":
                    MsgUtil.showDialog("提醒", "不再推荐使用小电视播放器，许多功能已不再支持，推荐使用内置播放器", -1);
                    break;

                case "aliangPlayer":
                    if (Build.VERSION.SDK_INT <= 19)
                        MsgUtil.showDialog("提醒", "您的安卓版本过低，可能无法使用凉腕播放器，可以使用内置播放器", -1);
                    break;
            }
            playerCurr = playerList[checkPosition + 1];
            SharedPreferencesUtil.putString("player", playerCurr);
        }
    }
}
