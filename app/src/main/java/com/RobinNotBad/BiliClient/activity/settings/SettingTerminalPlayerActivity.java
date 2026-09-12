package com.RobinNotBad.BiliClient.activity.settings;

import android.os.Build;
import android.os.Bundle;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity;
import com.RobinNotBad.BiliClient.adapter.SettingsAdapter;
import com.RobinNotBad.BiliClient.model.SettingSection;

import java.util.ArrayList;
import java.util.List;

public class SettingTerminalPlayerActivity extends RefreshListActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPageName("内置播放器设置");

        final List<SettingSection> sectionList = new ArrayList<>() {
            {
                add(new SettingSection("switch", "长按倍速", "player_longclick", "", "true"));
                add(new SettingSection("switch", "双击快进快退", "player_doubletap_seek", "", "false"));
                add(new SettingSection("input_int", "快进快退秒数", "player_doubletap_seek_seconds", "", "10"));
                add(new SettingSection("switch", "双击优先还原缩放/移动", "player_doubletap_reset_first",
                        "开启后，双击会优先还原视频缩放与位置，再进行播放/暂停切换", "true"));
                add(new SettingSection("switch", "洗脑循环", "player_loop", "", "false"));
                add(new SettingSection("switch", "熄屏继续播放", "player_background", "", "false"));
                add(new SettingSection("switch", "默认横屏", "player_autolandscape", "", "false"));
                add(new SettingSection("switch", "从历史位置播放", "player_from_last",
                        getString(R.string.desc_fromlast),
                        "true"));
                add(new SettingSection("switch", "显示实时人数", "player_show_online",
                        getString(R.string.desc_showonline),
                        "false"));
                add(new SettingSection("switch", "听视频模式", "player_audio_only",
                        getString(R.string.desc_audio_only), "false"));
                add(new SettingSection("switch", "视频可缩放", "player_scale",
                        getString(R.string.desc_scale), "true"));
                add(new SettingSection("switch", "缩放时可移动", "player_doublemove",
                        getString(R.string.desc_doublemove),
                        "true"));

                add(new SettingSection("divider", "", "", "", ""));

                add(new SettingSection("choose", "显示方式", "player_display",
                        getString(R.string.desc_display),
                        String.valueOf(Build.VERSION.SDK_INT < 26),
                        new String[]{"TextureView", "SurfaceView"}));
                add(new SettingSection("choose", "解码方式", "player_codec",
                        getString(R.string.desc_videocodec), "true",
                        new String[]{"硬件解码", "软件解码"}));
                add(new SettingSection("choose", "音频输出", "player_audio",
                        getString(R.string.desc_audiocodec), "false",
                        new String[]{"OpenSles", "AudioTrack"}));

                add(new SettingSection("divider", "", "", "", ""));

                add(new SettingSection("switch", "显示高能进度条", "player_high_energy",
                        getString(R.string.desc_player_high_energy), "false"));
                add(new SettingSection("switch", "弹幕允许重叠", "player_danmaku_allowoverlap", "", "true"));
                add(new SettingSection("switch", "合并重复弹幕", "player_danmaku_mergeduplicate", "",
                        "false"));
                add(new SettingSection("switch", "强制为滚动弹幕", "player_danmaku_forceR2L",
                        getString(R.string.desc_danmaku_force_r2l), "false"));
                add(new SettingSection("switch", "显示直播弹幕发送者", "player_danmaku_showsender",
                        getString(R.string.desc_danmaku_showsender), "true"));
                add(new SettingSection("input_int", "弹幕最大行数", "player_danmaku_maxline", "", "10"));
                add(new SettingSection("input_float", "弹幕字号大小", "player_danmaku_size", "", "0.7"));
                add(new SettingSection("input_float", "弹幕不透明度", "player_danmaku_transparency", "",
                        "0.5"));
                add(new SettingSection("input_float", "弹幕速度", "player_danmaku_speed", "", "1.0"));

                add(new SettingSection("divider", "", "", "", ""));

                add(new SettingSection("switch", "自动弹出字幕选择", "player_subtitle_autoshow",
                        getString(R.string.desc_subtitle_autoshow), "true"));
                add(new SettingSection("switch", "允许仅AI字幕", "player_subtitle_ai_allowed",
                        getString(R.string.desc_subtitle_ai_allowed), "false"));

                add(new SettingSection("divider", "", "", "", ""));

                add(new SettingSection("input_float", "字幕校准", "player_subtitle_delta",
                        "将字幕提前/退后一段时间，从而与视频对齐", "0.3"));

                add(new SettingSection("divider", "", "", "", ""));

                add(new SettingSection("switch", "显示旋转按钮", "player_ui_showRotateBtn", "", "true"));
                add(new SettingSection("switch", "显示弹幕按钮", "player_ui_showDanmakuBtn", "", "true"));
                add(new SettingSection("switch", "显示清晰度按钮", "player_ui_showQualityBtn",
                        "", "true"));
                add(new SettingSection("switch", "显示分P按钮", "player_ui_showPageBtn",
                        "", "true"));
                add(new SettingSection("input_float", "互动选项字体大小", "player_interaction_choice_size", "", "17.0"));
            }
        };

        recyclerView.setHasFixedSize(true);

        SettingsAdapter adapter = new SettingsAdapter(this, sectionList);
        setAdapter(adapter);

        setRefreshing(false);

    }

}