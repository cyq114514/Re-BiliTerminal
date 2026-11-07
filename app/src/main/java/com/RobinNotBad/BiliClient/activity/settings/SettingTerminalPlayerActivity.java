package com.RobinNotBad.BiliClient.activity.settings;

import android.os.Build;
import android.content.Context;
import android.os.Bundle;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity;
import com.RobinNotBad.BiliClient.adapter.SettingsAdapter;
import com.RobinNotBad.BiliClient.model.SettingSection;
import com.RobinNotBad.BiliClient.util.SegmentSkipController;
import com.RobinNotBad.BiliClient.util.SegmentSkipMode;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import java.util.ArrayList;
import java.util.List;

public class SettingTerminalPlayerActivity extends RefreshListActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPageName("内置播放器设置");

final List<SettingSection> sectionList = new ArrayList<>(buildSections(this));

        recyclerView.setHasFixedSize(true);

        SettingsAdapter adapter = new SettingsAdapter(this, sectionList);
        setAdapter(adapter);
        scrollToSettingTitle(sectionList);

        setRefreshing(false);

    }

    /**
     * 条目清单抽成静态方法（2026-11-07 设置页重构，搜索自动索引用）。
     * 重排为「播放行为/手势/画面与音量/番剧/引擎/弹幕/字幕/播放页按钮/互动/听歌」分组；
     * 「后台/熄屏继续播放」迁出到 NotificationBackgroundSettingsActivity（key 不变）；
     * 弹幕组若干标题按实际效果改名；「强制为滚动弹幕」自此版本起真正生效。
     */
    public static List<SettingSection> buildSections(Context context) {
        final List<SettingSection> sectionList = new ArrayList<>();
        sectionList.add(new SettingSection("title", "播放行为", "", "", ""));
        sectionList.add(new SettingSection("switch", "长按倍速", "player_longclick", "", "true"));
        sectionList.add(new SettingSection("switch", "双击快进快退", "player_doubletap_seek", "", "false"));
        sectionList.add(new SettingSection("input_int", "快进快退秒数", "player_doubletap_seek_seconds", "", "10"));
        sectionList.add(new SettingSection("switch", "显示快进快退按钮", "player_seek_buttons",
                "在播放页左侧（倍速那一列）显示 ±N 秒按钮；关掉后只保留双击/横滑调进度", "true"));
        sectionList.add(new SettingSection("switch", "横滑调进度", "player_swipe_seek",
                "在播放画面上左右滑动调整进度，松手才跳转；画面放大后单指拖动仍然是拖动画布",
                "true"));
        sectionList.add(new SettingSection("input_float", "横滑调进度灵敏度", "player_swipe_seek_scale",
                "满屏滑动对应的进度倍率，支持 0.25 / 0.5 / 1.0 / 1.5 / 2.0 / 3.0；"
                        + "手表低 DPI 手指一动跳太多时调小，想划一点就跳远时调大",
                "1.0"));
        sectionList.add(new SettingSection("switch", "单视频循环播放", "player_loop",
                "当前视频播完后自动从头再放（原「洗脑循环」）", "false"));
        sectionList.add(new SettingSection("switch", "记住倍速", "player_speed_remember",
                "手动调整倍速后，之后的视频（含换P/换清晰度）自动应用", "false"));
        sectionList.add(new SettingSection("switch", "默认横屏", "player_autolandscape", "", "false"));
        sectionList.add(new SettingSection("switch", "从历史位置播放", "player_from_last",
                context.getString(R.string.desc_fromlast),
                "true"));
        sectionList.add(new SettingSection("switch", "本地缓存续播", "player_local_resume",
                "本地缓存视频从上次离开的位置继续播放；进度存在视频目录里，不联网。"
                        + "隐私模式下不记录",
                "true"));
        sectionList.add(new SettingSection("switch", "显示实时人数", "player_show_online",
                context.getString(R.string.desc_showonline),
                "false"));
        sectionList.add(new SettingSection("switch", "播放页顶栏显示时间", "player_show_clock",
                "在播放页顶栏右上角电量旁显示当前时间", "true"));
        sectionList.add(new SettingSection("switch", "播放视频时暂停后台音乐", "player_pause_bg_music",
                "开启后，视频起播会自动暂停正在放的歌（从通知栏或顶部悬浮球手动继续）；"
                        + "关闭则保持两路同时播放", "true"));

        sectionList.add(new SettingSection("title", "手势与画面", "", "", ""));
        sectionList.add(new SettingSection("switch", "双击优先还原缩放/移动", "player_doubletap_reset_first",
                "开启后，双击会优先还原视频缩放与位置，再进行播放/暂停切换", "true"));
        sectionList.add(new SettingSection("switch", "视频可缩放", "player_scale",
                context.getString(R.string.desc_scale), "true"));
        sectionList.add(new SettingSection("switch", "缩放时可移动", "player_doublemove",
                context.getString(R.string.desc_doublemove),
                "true"));

        sectionList.add(new SettingSection("title", "音量与音频", "", "", ""));
        //软件音量增益：原「无极调音（0-100）」名称不直白，改名（2026-11-07 设置页重构）。
        //和播放页长按音量按钮弹出的滑条共用 player_volume_gain（0~100 的百分比）
        sectionList.add(new SettingSection("input_float", "软件音量增益（0-100）", "player_volume_gain",
                "软件增益，100 为原音量。系统音量档位很少的设备（手表/点读笔）用它可以调出 35 这种精确档位；"
                        + "与播放页长按音量按钮打开的滑条是同一个值", "100"));
        //纯音频播放：原「听视频模式」，按实际效果改名（2026-11-07 设置页重构）
        sectionList.add(new SettingSection("switch", "纯音频播放（听视频）", "player_audio_only",
                context.getString(R.string.desc_audio_only), "false"));
        //收藏夹「听全部（纯音频）」的加载策略：预取下一首 + 音频落盘缓存，
        //切歌与拖动进度条直接命中本地；关掉后回到"每次回源"的直连方式
        sectionList.add(new SettingSection("switch", "听歌快速加载", SharedPreferencesUtil.AUDIO_FAST_LOAD,
                "预取下一首，并把听过的音频缓存到本地：切歌、拖动进度条都更快。"
                        + "最多占用 80MB 本地空间，超出后自动清理最久没听的；关掉后不占用空间，但加载变慢",
                "true"));

        sectionList.add(new SettingSection("title", "番剧", "", "", ""));
        //片头/片尾时间点由 B 站官方接口随取流一起下发（clip_info_list），不依赖第三方服务
        //默认档位取自 SegmentSkipMode.DEFAULT（当前是「手动跳过」），
        //defaultValue 与读侧共用同一个来源，避免两处漂移
        sectionList.add(new SettingSection("choose", "番剧片头/片尾跳过", SegmentSkipController.KEY_MODE,
                "只对番剧生效。总是跳过会直接跳到片段结束；跳过一次在你手动拖回去后不再跳；"
                        + "手动跳过会在片段区间内显示一个跳过按钮；仅显示只提示不跳",
                String.valueOf(SegmentSkipMode.defaultIndex()),
                SegmentSkipMode.labels()));

        sectionList.add(new SettingSection("title", "解码与渲染", "", "", ""));
        sectionList.add(new SettingSection("choose", "显示方式", "player_display",
                context.getString(R.string.desc_display),
                String.valueOf(Build.VERSION.SDK_INT < 26),
                new String[]{"TextureView", "SurfaceView"}));
        sectionList.add(new SettingSection("choose", "解码方式", "player_codec",
                context.getString(R.string.desc_videocodec), "true",
                new String[]{"硬件解码", "软件解码"}));
        sectionList.add(new SettingSection("choose", "音频输出", "player_audio",
                context.getString(R.string.desc_audiocodec), "false",
                new String[]{"OpenSles", "AudioTrack"}));

        sectionList.add(new SettingSection("title", "弹幕", "", "", ""));
        //弹幕屏蔽词（1.3.3 反馈：能不能加入弹幕屏蔽词）
        sectionList.add(new SettingSection("input_string", "弹幕屏蔽词", "danmaku_blocked_words",
                "逗号或空格分隔的关键词，弹幕文字包含任一关键词即整条不显示；留空不过滤。改完从下个视频生效",
                ""));
        //空降弹幕：点击带时间点的弹幕直接跳到该位置；原「空降弹幕」是B站黑话，标题补充说明
        sectionList.add(new SettingSection("switch", "点击时间戳弹幕跳转（空降）", "player_danmaku_airborne",
                "点击弹幕里带时间点的内容（如「空降05:30」）可直接跳到该位置", "true"));
        //强制滚动弹幕：把左右/底部弹幕统一转为右→左滚动（两个解析器均已实现）
        sectionList.add(new SettingSection("switch", "强制为滚动弹幕", "player_danmaku_forceR2L",
                context.getString(R.string.desc_danmaku_force_r2l), "false"));
        //弹幕防重叠：key 里存的是「允许重叠」，界面按实际效果命名
        sectionList.add(new SettingSection("switch", "允许弹幕重叠", "player_danmaku_allowoverlap", "", "true"));
        sectionList.add(new SettingSection("switch", "合并重复弹幕", "player_danmaku_mergeduplicate", "",
                "false"));
        sectionList.add(new SettingSection("switch", "显示直播弹幕发送者", "player_danmaku_showsender",
                context.getString(R.string.desc_danmaku_showsender), "true"));
        sectionList.add(new SettingSection("input_int", "弹幕最大行数", "player_danmaku_maxline", "", "15"));
        sectionList.add(new SettingSection("input_float", "弹幕缩放倍率", "player_danmaku_size", "", "0.7"));
        sectionList.add(new SettingSection("input_float", "弹幕不透明度", "player_danmaku_transparency", "",
                "0.5"));
        sectionList.add(new SettingSection("input_float", "弹幕速度倍率", "player_danmaku_speed", "", "1.0"));
        //高能进度条：原「显示高能进度条」，B站黑话，标题补充说明
        sectionList.add(new SettingSection("switch", "弹幕密度曲线（高能条）", "player_high_energy",
                context.getString(R.string.desc_player_high_energy), "false"));

        sectionList.add(new SettingSection("title", "字幕", "", "", ""));
        sectionList.add(new SettingSection("switch", "自动弹出字幕选择", "player_subtitle_autoshow",
                context.getString(R.string.desc_subtitle_autoshow), "true"));
        sectionList.add(new SettingSection("switch", "允许仅AI字幕", "player_subtitle_ai_allowed",
                context.getString(R.string.desc_subtitle_ai_allowed), "false"));
        sectionList.add(new SettingSection("input_float", "字幕校准（秒）", "player_subtitle_delta",
                "将字幕提前/退后一段时间，从而与视频对齐", "0.3"));

        sectionList.add(new SettingSection("title", "播放页按钮", "", "", ""));
        sectionList.add(new SettingSection("switch", "显示旋转按钮", "player_ui_showRotateBtn", "", "true"));
        sectionList.add(new SettingSection("switch", "显示弹幕按钮", "player_ui_showDanmakuBtn", "", "true"));
        sectionList.add(new SettingSection("switch", "显示清晰度按钮", "player_ui_showQualityBtn",
                "", "true"));
        sectionList.add(new SettingSection("switch", "显示分P按钮", "player_ui_showPageBtn",
                "", "true"));

        sectionList.add(new SettingSection("title", "互动视频", "", "", ""));
        sectionList.add(new SettingSection("input_float", "互动选项字体大小", "player_interaction_choice_size", "", "17.0"));
        sectionList.add(new SettingSection("switch", "互动选项自动竖屏", "player_interaction_portrait",
                "横屏播放互动视频时，弹出选项的瞬间自动转成竖屏，选完再转回横屏。"
                        + "低分辨率设备横屏放不下全部选项时很有用（页面本来就是竖屏则不动）", "false"));
        return sectionList;
    }
}