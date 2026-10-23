package com.RobinNotBad.BiliClient.activity.settings;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.util.Log;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity;
import com.RobinNotBad.BiliClient.adapter.SettingsAdapter;
import com.RobinNotBad.BiliClient.api.RecommendApi;
import com.RobinNotBad.BiliClient.model.SettingSection;
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

        final List<SettingSection> sectionList = new ArrayList<>() {
            {
                add(new SettingSection("title", "功能", "", "", ""));
                add(new SettingSection("switch", "隐私模式", SharedPreferencesUtil.PRIVACY_MODE, getString(R.string.desc_privacy_mode), "false"));
                //崩溃上报：与首启授权写同一个键，切换后由 CrashReportUtil 的偏好监听立即生效
                //默认值必须与 CrashReportUtil.isEnabled()（默认 true）一致，
                //否则设置页显示"关"、实际在传，用户会据此误判上报链路不通
                add(new SettingSection("switch", "崩溃上报", SharedPreferencesUtil.CRASH_REPORT_ENABLE,
                        getString(R.string.desc_crash_report), "true"));
                //活跃设备统计：只报匿名安装标识+版本+机型，每天最多一条，用于算日活/月活
                add(new SettingSection("switch", "活跃设备统计", SharedPreferencesUtil.USAGE_STATS_ENABLE,
                        "每天最多上报一次匿名安装标识、应用版本与机型，用于统计日活/月活设备数；不含账号信息",
                        "true"));
                add(new SettingSection("switch", "长按复制", "copy_enable", getString(R.string.desc_copy_enable), "true"));
                add(new SettingSection("switch", "创作中心", "creative_enable", getString(R.string.desc_creative_enable),
                        "true"));
                add(new SettingSection("switch", "搜索建议", "search_suggestions_enable",
                        getString(R.string.desc_search_suggestions_enable), "true"));
                add(new SettingSection("switch", "默认搜索内容", SharedPreferencesUtil.SEARCH_DEFAULT_CONTENT_ENABLE,
                        getString(R.string.desc_search_default_content_enable), "false"));
                add(new SettingSection("switch", "识别链接", "link_enable", getString(R.string.desc_link_enable), "true"));
                //首页推荐数据源：app 端与 web 端返回的内容不同，用户可按观感自行切换。
                //默认值必须与 RecommendApi.getRecommend 里的默认值一致（true），否则会重演
                //"设置页显示关、实际在开"的口径不一致问题
                add(new SettingSection("switch", "首页使用app端推荐", RecommendApi.KEY_APP_RECOMMEND,
                        "默认使用 app 端推荐（相关性更好）；若观感不符预期，可关闭改用 web 端推荐", "true"));
                add(new SettingSection("switch", "新动态数量检查", SharedPreferencesUtil.DYNAMIC_UPDATE_CHECK_ENABLE,
                        getString(R.string.desc_dynamic_update_check_enable), "true"));
                add(new SettingSection("switch", "消息数量检查", SharedPreferencesUtil.MESSAGE_UPDATE_CHECK_ENABLE,
                        getString(R.string.desc_message_update_check_enable), "true"));
                add(new SettingSection("switch", "最近更新的UP主", SharedPreferencesUtil.RECENT_UP_DISPLAY_ENABLE,
                        getString(R.string.desc_recent_up_display_enable), "true"));
                add(new SettingSection("switch", "私信自动已读", SharedPreferencesUtil.PRIVATE_MSG_AUTO_READ_ENABLE,
                        getString(R.string.desc_private_msg_auto_read_enable), "true"));
                add(new SettingSection("switch", "我的关注列表分组", SharedPreferencesUtil.FOLLOW_GROUP_MODE,
                        getString(R.string.desc_follow_group_mode), "false"));
                add(new SettingSection("switch", "夜深了", SharedPreferencesUtil.NIGHT_REMINDER_ENABLE,
                        getString(R.string.desc_night_reminder), "true"));
                add(new SettingSection("switch", "记住退出页面", SharedPreferencesUtil.RESUME_PAGE_ENABLE,
                        getString(R.string.desc_resume_page), "false"));
                //冷启动自动检查更新：默认关闭（启动链路已经做过提速，这一项会额外发一次网络请求）
                add(new SettingSection("switch", "启动时检查更新", SharedPreferencesUtil.UPDATE_CHECK_ON_START,
                        "每次冷启动在后台从 GitHub（直连失败会依次尝试内置镜像）检查新版本，发现更新时弹窗提示；默认关闭",
                        "false"));


                add(new SettingSection("title", "优化", "", "", ""));
                add(new SettingSection("switch", "禁用返回键", "back_disable", getString(R.string.desc_back_disable),
                        "false"));
                add(new SettingSection("switch", "禁止视频在相册中显示", "save_ban_gallery", getString(R.string.desc_ban_gallery),
                        "true"));
                add(new SettingSection("switch", "请求JPG格式图片", "image_request_jpg",
                        getString(R.string.desc_img_request_jpg), "false"));

                add(new SettingSection("title", "视觉", "", "", ""));
                add(new SettingSection("switch", "加载渐入渐出动画", SharedPreferencesUtil.LOAD_TRANSITION,
                        getString(R.string.desc_load_transition), "true"));
                //总开关：Activity 转场 + 页面渐入渐出一起关掉，低配设备切换更干脆
                add(new SettingSection("switch", "关闭界面过渡动画", SharedPreferencesUtil.DISABLE_ANIMATION,
                        "关闭页面转场与渐入渐出动画，切换更干脆；默认不启用（动画照常）", "false"));
                add(new SettingSection("switch", "翻动时不加载图片", "image_no_load_onscroll",
                        getString(R.string.desc_img_no_load_onscroll), "false"));
                add(new SettingSection("switch", "异步加载布局", SharedPreferencesUtil.ASYNC_INFLATE_ENABLE,
                        getString(R.string.desc_async_inflate_enable), "true"));
                add(new SettingSection("switch", "新提示信息显示方式", SharedPreferencesUtil.SNACKBAR_ENABLE,
                        "打开此选项，会启用新提示信息显示方式", "true"));

                add(new SettingSection("switch", "新版开屏动画", SharedPreferencesUtil.SPLASH_NEW_UI,
                        "新版开屏为「图标+粉色短线+氛围光晕」，觉得图标不协调可关闭，回到纯黑+文字的旧开屏", "true"));
                add(new SettingSection("title", "表冠", "", "", ""));
                add(new SettingSection("switch", "启用表冠适配", "ui_rotatory_enable",
                        getString(R.string.setting_lab_ui_rotatory), "false"));
                add(new SettingSection("input_float", "表冠适配灵敏度（Recycler）", "ui_rotatory_recycler", "", "0"));
                add(new SettingSection("input_float", "表冠适配灵敏度（Scroll）", "ui_rotatory_scroll", "", "0"));

            }
        };

        recyclerView.setHasFixedSize(true);

        SettingsAdapter adapter = new SettingsAdapter(this, sectionList);
        setAdapter(adapter);

        setRefreshing(false);

    }

}