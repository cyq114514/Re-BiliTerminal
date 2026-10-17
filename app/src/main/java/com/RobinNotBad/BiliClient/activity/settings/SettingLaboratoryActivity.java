package com.RobinNotBad.BiliClient.activity.settings;

import android.annotation.SuppressLint;
import android.os.Bundle;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity;
import com.RobinNotBad.BiliClient.adapter.SettingsAdapter;
import com.RobinNotBad.BiliClient.model.SettingSection;
import com.RobinNotBad.BiliClient.util.CrashReportUtil;
import com.RobinNotBad.BiliClient.util.FileUtil;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import java.util.ArrayList;
import java.util.List;

public class SettingLaboratoryActivity extends RefreshListActivity {

    @SuppressLint({"MissingInflatedId", "SetTextI18n"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPageName("实验室");

        final List<SettingSection> sectionList = new ArrayList<>() {
            {
                add(new SettingSection("title", "可用性", "", "", ""));
                add(new SettingSection("switch", "新版弹幕获取方式", "new_danmaku_api",
                        getString(R.string.desc_new_danmaku_api), "true"));
                add(new SettingSection("switch", "私信未读标记", SharedPreferencesUtil.PRIVATE_MSG_UNREAD_BADGE_ENABLE,
                        getString(R.string.desc_private_msg_unread_badge_enable), "false"));

                add(new SettingSection("title", "下载", "", "", ""));
                add(new SettingSection("switch", "使用旧版下载器", "dev_download_old",
                        getString(R.string.setting_lab_download_old), "false"));
                add(new SettingSection("input_string", "缓存路径", "save_path_video",
                        getString(R.string.setting_lab_path_video), FileUtil.getVideoDownloadPath().toString()));
                add(new SettingSection("input_string", "图片下载路径", "save_path_pictures",
                        getString(R.string.setting_lab_path_pictures), FileUtil.getPicturePath().toString()));

                add(new SettingSection("title", "UI", "", "", ""));
                add(new SettingSection("switch", "横屏模式", "ui_landscape", getString(R.string.setting_lab_ui_landscape),
                        "false"));
                add(new SettingSection("input_string", "开屏文字", "ui_splashtext",
                        getString(R.string.setting_lab_splashtext), "欢迎使用\nRe：哔哩终端"));
                add(new SettingSection("switch", "文字跑马灯", "marquee_enable", getString(R.string.setting_lab_marquee),
                        "true"));

                add(new SettingSection("title", "播放器", "", "", ""));
                add(new SettingSection("switch", "播放器旋屏兼容方案", "dev_player_rotate_software",
                        "在极少数手表上（如小米手表），系统旋屏存在显示不全的问题。打开此开关，播放器将会使用软件旋屏方法。", "false"));
                add(new SettingSection("switch", "显示视频分段", "player_show_viewpoints",
                        "显示视频的章节看点信息，可快速跳转到指定章节", "false"));
                add(new SettingSection("switch", "系统媒体控件", SharedPreferencesUtil.PLAYER_MEDIA_SESSION_ENABLE,
                        getString(R.string.setting_lab_media_session), "false"));
                add(new SettingSection("switch", "互动视频调试", "player_interaction_debug",
                        "在互动视频播放时，在左侧倍速按钮上方显示调试按钮，可以查看和修改互动视频的变量", "false"));

                add(new SettingSection("title", "网络请求", "", "", ""));
                add(new SettingSection("input_float", "接口重试间隔（秒）", SharedPreferencesUtil.API_RETRY_INTERVAL_SECONDS,
                        getString(R.string.setting_lab_api_retry_interval), "0.1"));
                add(new SettingSection("input_int", "接口重试次数", SharedPreferencesUtil.API_RETRY_MAX_TIMES,
                        getString(R.string.setting_lab_api_retry_max_times), "5"));

                add(new SettingSection("title", "调试", "", "", ""));
                //以下调试开关一律默认关闭，且与是否 debug 构建无关：
                //它们会改变应用行为、产生额外请求或本地落盘，普通用户误开只会带来困扰
                add(new SettingSection("switch", "允许Logu.v", "dev_logv", getString(R.string.setting_lab_logv),
                        "false"));
                add(new SettingSection("switch", "允许Logu.d", "dev_logd", "", "false"));
                add(new SettingSection("switch", "允许Logu.i", "dev_logi", "", "false"));
                //把上面三个开关放出来的日志同时写进文件。
                //存在意义：logcat 在 Android 4.1+ 只能用电脑经 adb 取，第三方应用读不到别的应用的日志；
                //目标设备是手表/低配机，用户手上不一定有电脑。文件与番剧进度诊断同目录，取一次就够。
                //用 switch_warn：打开与关闭都要先确认（打开是"别乱开"，关闭是"会删掉本地日志"）
                add(new SettingSection("switch_warn", "调试日志落文件", Logu.KEY_FILE_LOG,
                        "日志同时写入 Download/ReBiliDiag/app-log.txt（该目录不可写时退到 "
                                + "Android/media/<包名>/app-log.txt）。超过 1MB 自动重建，只保留最近一段。"
                                + "开关切换会立即生效，无需重启应用。",
                        "false",
                        new String[]{
                                "这是开发者用来排查问题的调试开关，普通使用请保持关闭。\n\n"
                                        + "打开后：应用的运行日志会被持续写入本地文件"
                                        + "（Download/ReBiliDiag/app-log.txt），"
                                        + "文件对文件管理器与其他应用可见。\n\n"
                                        + "确定要打开吗？",
                                "关闭后会【立即删除】本地已写入的日志文件（app-log.txt），"
                                        + "已抓到的日志无法恢复。\n\n"
                                        + "如果还需要这份日志，请先把它复制或发送出去，再回来关闭。\n\n"
                                        + "确定要关闭吗？"
                        }));
                //番剧进度诊断默认关闭：它会额外请求服务端并在设备上写诊断文件，
                //普通使用没必要承担这份开销与痕迹；排查番剧进度问题时才打开
                add(new SettingSection("switch", "番剧进度诊断", "diag_readback",
                        "打开后：播放番剧满 25 秒会回读一次服务端（观看记录/季级状态/本集进度）确认上报是否落库，"
                                + "并把番剧进度的读写细节写入诊断文件（Download/ReBiliDiag/ 或 Android/media/ 对应包名目录下）。"
                                + "排查\"续播位置不对/进度记不上\"这类问题时打开，把诊断文件发给维护者即可。", "false"));
                //主动崩溃：验证崩溃上报链路是否真的能收到数据。
                //不是普通开关——打开即触发一次真实崩溃，随后自动复位，避免重启后再次崩溃
                add(new SettingSection("switch", "主动触发崩溃", CrashReportUtil.KEY_DEBUG_CRASH,
                        "打开后应用会立即崩溃一次，并弹出崩溃页面，用于验证崩溃上报是否生效。\n"
                                + "开关会在触发后自动复位，不会在重启后再次崩溃。\n"
                                + "需要先在 设置-偏好设置 里打开「崩溃上报」，否则这次崩溃不会被上报，只会弹崩溃页。",
                        "false"));
            }
        };

        recyclerView.setHasFixedSize(true);

        SettingsAdapter adapter = new SettingsAdapter(this, sectionList);
        setAdapter(adapter);

        setRefreshing(false);
    }

}