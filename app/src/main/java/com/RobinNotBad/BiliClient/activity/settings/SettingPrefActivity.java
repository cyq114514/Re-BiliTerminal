package com.RobinNotBad.BiliClient.activity.settings;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity;
import com.RobinNotBad.BiliClient.adapter.SettingsAdapter;
import com.RobinNotBad.BiliClient.api.RecommendApi;
import com.RobinNotBad.BiliClient.model.SettingSection;
import com.RobinNotBad.BiliClient.util.AppBuvid;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import java.util.ArrayList;
import java.util.List;

public class SettingPrefActivity extends RefreshListActivity {

    @SuppressLint({"MissingInflatedId", "SetTextI18n", "InflateParams"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPageName("偏好设置");
        Log.e("debug", "进入偏好设置");

final List<SettingSection> sectionList = new ArrayList<>(buildSections(this));

        recyclerView.setHasFixedSize(true);

        SettingsAdapter adapter = new SettingsAdapter(this, sectionList);
        setAdapter(adapter);
        scrollToSettingTitle(sectionList);

        setRefreshing(false);

    }

    /**
     * 条目清单抽成静态方法（2026-11-07 设置页重构）：设置搜索的索引自动从这里生成，
     * 新增条目自动可搜。2026-11-07 重构迁出（key 全部不变）：
     * 推荐与搜索 10 条 → RecommendSearchSettingsActivity；
     * 隐私模式 + 隐私与伪装 → PrivacySecuritySettingsActivity；
     * 禁止视频进相册 → DownloadStorageSettingsActivity；
     * 新动态/消息数量检查 + 记住退出页面 → NotificationBackgroundSettingsActivity；
     * 表冠适配 3 条 → SettingLaboratoryActivity。
     */
    public static List<SettingSection> buildSections(Context context) {
        final List<SettingSection> sectionList = new ArrayList<>();
        {
            sectionList.add(new SettingSection("title", "功能", "", "", ""));
            //崩溃上报：与首启授权写同一个键，切换后由 CrashReportUtil 的偏好监听立即生效
            //默认值必须与 CrashReportUtil.isEnabled()（默认 true）一致，
            //否则设置页显示"关"、实际在传，用户会据此误判上报链路不通
            sectionList.add(new SettingSection("switch", "崩溃上报", SharedPreferencesUtil.CRASH_REPORT_ENABLE,
                    context.getString(R.string.desc_crash_report), "true"));
            sectionList.add(new SettingSection("switch", "活跃设备统计", SharedPreferencesUtil.USAGE_STATS_ENABLE,
                    "每天最多上报一次匿名安装标识、应用版本与机型，用于统计日活/月活设备数；不含账号信息",
                    "true"));
            sectionList.add(new SettingSection("switch", "长按复制", "copy_enable", context.getString(R.string.desc_copy_enable), "true"));
            sectionList.add(new SettingSection("switch", "显示主页刷新按钮", SharedPreferencesUtil.TOPBAR_REFRESH_ENABLE,
                    "关闭后主页顶栏不再显示刷新按钮；长按顶栏右上角的时间仍可刷新", "true"));
            sectionList.add(new SettingSection("switch", "显示视频摘要/笔记入口", SharedPreferencesUtil.VIDEO_SUMMARY_NOTE_ENABLE,
                    "关闭后视频详情页不再显示「视频摘要」和「笔记」两个按钮", "true"));
            sectionList.add(new SettingSection("switch", "创作中心", "creative_enable", context.getString(R.string.desc_creative_enable),
                    "true"));
            sectionList.add(new SettingSection("switch", "识别链接", "link_enable", context.getString(R.string.desc_link_enable), "true"));
            sectionList.add(new SettingSection("switch", "最近更新的UP主", SharedPreferencesUtil.RECENT_UP_DISPLAY_ENABLE,
                    context.getString(R.string.desc_recent_up_display_enable), "true"));
            sectionList.add(new SettingSection("switch", "私信自动已读", SharedPreferencesUtil.PRIVATE_MSG_AUTO_READ_ENABLE,
                    context.getString(R.string.desc_private_msg_auto_read_enable), "true"));
            sectionList.add(new SettingSection("switch", "我的关注列表分组", SharedPreferencesUtil.FOLLOW_GROUP_MODE,
                    context.getString(R.string.desc_follow_group_mode), "false"));
            sectionList.add(new SettingSection("switch", "深夜使用提醒", SharedPreferencesUtil.NIGHT_REMINDER_ENABLE,
                    context.getString(R.string.desc_night_reminder), "true"));
            sectionList.add(new SettingSection("switch", "禁用返回键", "back_disable", context.getString(R.string.desc_back_disable),
                    "false"));
            sectionList.add(new SettingSection("switch", "启动时检查更新", SharedPreferencesUtil.UPDATE_CHECK_ON_START,
                    "每次冷启动在首屏之后从 GitHub（直连失败会依次尝试内置镜像）检查新版本，发现更新时弹窗提示；默认开启，不想让它联网可以关掉",
                    "true"));

            sectionList.add(new SettingSection("title", "视觉与动画", "", "", ""));
            sectionList.add(new SettingSection("switch", "请求JPG格式图片", "image_request_jpg",
                    context.getString(R.string.desc_img_request_jpg), "false"));
            sectionList.add(new SettingSection("switch", "加载渐入渐出动画", SharedPreferencesUtil.LOAD_TRANSITION,
                    context.getString(R.string.desc_load_transition), "true"));
            sectionList.add(new SettingSection("switch", "关闭界面过渡动画", SharedPreferencesUtil.DISABLE_ANIMATION,
                    "关闭页面转场与渐入渐出动画，切换更干脆；默认不启用（动画照常）", "false"));
            sectionList.add(new SettingSection("switch", "翻动时不加载图片", "image_no_load_onscroll",
                    context.getString(R.string.desc_img_no_load_onscroll), "false"));
            sectionList.add(new SettingSection("switch", "页面异步加载（提升打开速度）", SharedPreferencesUtil.ASYNC_INFLATE_ENABLE,
                    context.getString(R.string.desc_async_inflate_enable), "true"));
            sectionList.add(new SettingSection("switch", "提示样式（气泡/横条）", SharedPreferencesUtil.SNACKBAR_ENABLE,
                    "打开此选项，会启用新提示信息显示方式", "true"));
            sectionList.add(new SettingSection("switch", "新版开屏动画", SharedPreferencesUtil.SPLASH_NEW_UI,
                    "新版开屏为「图标+粉色短线+氛围光晕」，觉得图标不协调可关闭，回到纯黑+文字的旧开屏", "true"));
            sectionList.add(new SettingSection("switch", "启动时等待请求完成", "splash_wait_requests",
                    "打开后启动会先等 Cookie 续期/刷新与终端接口返回、并等开屏打字动画播完再进首页，"
                            + "开屏动画能完整看到；弱网最多多等 6 秒。默认关闭（开屏立刻进入）", "false"));
        }
        return sectionList;
    }
}