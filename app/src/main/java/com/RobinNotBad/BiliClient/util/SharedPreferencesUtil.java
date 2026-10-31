package com.RobinNotBad.BiliClient.util;

import android.content.SharedPreferences;

/**
 * 被 luern0313 创建于 2020/5/4.
 * #以下代码部分来源于腕上哔哩的开源项目，有修改。感谢开源者做出的贡献！
 */
public class SharedPreferencesUtil {
    public static final String LINK_ENABLE = "link_enable";
    public static final String MENU_SORT = "menu_sort";
    //搜索类别排序：分号分隔的类别键序（如 video;bangumi;article;user;live），空串 = 默认顺序。
    //解析入口只有 SearchSortActivity.loadOrderedKeys()，视频钉死首位
    public static final String SEARCH_CATEGORY_SORT = "search_category_sort";
    public static final String ASYNC_INFLATE_ENABLE = "async_inflate_enable";
    public static final String LOAD_TRANSITION = "load_transition";
    public static final String SNACKBAR_ENABLE = "snackbar_enable";
    public static final String NO_VIP_COLOR = "no_vip_color";
    public static final String NO_MEDAL = "no_medal";
    public static final String REPLY_MARQUEE_NAME = "reply_marquee_name";
    public static final String NEW_DANMAKU_API = "new_danmaku_api";
    public static final String DYNAMIC_UPDATE_CHECK_ENABLE = "dynamic_update_check_enable";
    public static final String DYNAMIC_UPDATE_NUM = "dynamic_update_num";
    public static final String MESSAGE_UPDATE_CHECK_ENABLE = "message_update_check_enable";
    public static final String MESSAGE_UPDATE_NUM = "message_update_num";
    public static final String PRIVATE_MSG_UNREAD_BADGE_ENABLE = "private_msg_unread_badge_enable";
    public static final String PRIVATE_MSG_AUTO_READ_ENABLE = "private_msg_auto_read_enable";
    public static final String FOLLOW_GROUP_MODE = "follow_group_mode";
    public static final String NIGHT_REMINDER_ENABLE = "night_reminder_enable";
    public static final String SEARCH_DEFAULT_CONTENT_ENABLE = "search_default_content_enable";
    public static final String PLAYER_MEDIA_SESSION_ENABLE = "player_media_session_enable";
    public static final String RECENT_UP_DISPLAY_ENABLE = "recent_up_display_enable";
    public static final String API_RETRY_INTERVAL_SECONDS = "api_retry_interval_seconds";
    public static final String API_RETRY_MAX_TIMES = "api_retry_max_times";
    public static final String PRIVACY_MODE = "privacy_mode";
    //冷启动页面恢复总开关（恢复栈读写见 ResumePageUtil），默认关闭：关闭时不再写入恢复栈、冷启动始终进主页
    public static final String RESUME_PAGE_ENABLE = "resume_page_enable";
    //新版美学设计（移植自 Re-WearBili 的视觉语言）总开关，关闭后回到旧版观感
    public static final String NEW_UI_DESIGN = "new_ui_design";
    //新版开屏动画单独开关：新版开屏用「图标 + 粉色短线 + 氛围光晕」，部分设备上图标与整体观感不协调，
    //允许单独关掉回到旧版纯黑+文字，而不影响其它页面的新版美学（仅在新版美学开启时生效）
    public static final String SPLASH_NEW_UI = "splash_new_ui";
    //崩溃上报授权：默认关闭，未授权时全程不初始化 Bugly（见 CrashReportUtil）
    public static final String CRASH_REPORT_ENABLE = "crash_report_enable";
    //是否已就崩溃上报征求过意见，避免每次进引导页都弹窗
    public static final String CRASH_REPORT_ASKED = "crash_report_asked";
    //活跃设备统计：每天最多上报一次「匿名安装标识 + 版本 + 机型」，用于统计日活/月活。
    //与崩溃上报分开授权（同一张表、同一通道，crash_type=launch），默认开启，可单独关闭
    public static final String USAGE_STATS_ENABLE = "usage_stats_enable";
    //上次上报活跃统计的日期（yyyyMMdd），用于每天只报一次
    public static final String USAGE_STATS_LAST_DAY = "usage_stats_last_day";
    //关闭界面过渡动画：关掉 Activity 转场与页面渐入渐出（低配设备/晕动症用户），默认关闭（动画照常）
    public static final String DISABLE_ANIMATION = "ui_disable_animation";
    //每次冷启动自动检查更新（GitHub 直连 + 内置镜像），只有打开后才在启动时探测，默认关闭
    public static final String UPDATE_CHECK_ON_START = "update_check_on_start";
    //接口出错时是否展示可复制的完整详情（接口名、参数、B站返回码与原始返回片段），默认关闭
    public static final String API_ERROR_DETAIL = "dev_api_error_detail";

    public static String cookies = "cookies";
    public static String mid = "mid";
    public static String csrf = "csrf";
    public static String access_key = "access_key";
    public static String refresh_token = "refresh_token";
    public static String setup = "setup";
    public static String last_version = "last_version";
    public static String player = "player";
    public static String padding_horizontal = "padding_horizontal";
    public static String padding_vertical = "padding_vertical";
    public static String cookie_refresh = "cookie_refresh";
    public static String search_history = "search_history";
    public static String cover_play_enabled = "cover_play_enabled";
    public static String tutorial_version = "tutorial_version";

    public static SharedPreferences sharedPreferences;

    public static SharedPreferences getSharedPreferences() {
        return sharedPreferences;
    }

    //所有读取方法都做空保护：webHeaders 等静态初始化器会在 Application.onCreate 赋值 sharedPreferences
    //之前触发类加载，不保护的话一个读取顺序变化就是启动即 NPE
    public static String getString(String key, String def) {
        return sharedPreferences != null ? sharedPreferences.getString(key, def) : def;
    }

    public static void putString(String key, String value) {
        sharedPreferences.edit().putString(key, value).apply();
    }

    /**
     * 同步写字符串：写盘完成才返回。
     *
     * <p>只用于「写完马上就要杀进程」的路径（用户主动退出、崩溃收尾）。
     * {@link #putString} 走 apply()，写盘任务排在后台队列里，紧接着的
     * {@code Process.killProcess} 是 SIGKILL，队列来不及 flush，那次写入就丢了——
     * 表现就是「点了退出，下次打开又回到退出前的页面」。
     */
    public static void putStringSync(String key, String value) {
        if (sharedPreferences == null) return;
        //noinspection ApplySharedPref
        sharedPreferences.edit().putString(key, value).commit();
    }

    public static int getInt(String key, int def) {
        return sharedPreferences != null ? sharedPreferences.getInt(key, def) : def;
    }

    public static void putInt(String key, int value) {
        sharedPreferences.edit().putInt(key, value).apply();
    }

    public static long getLong(String key, long def) {
        return sharedPreferences != null ? sharedPreferences.getLong(key, def) : def;
    }

    public static void putLong(String key, long value) {
        sharedPreferences.edit().putLong(key, value).apply();
    }

    public static boolean getBoolean(String key, boolean def) {
        return sharedPreferences != null ? sharedPreferences.getBoolean(key, def) : def;
    }

    public static void putBoolean(String key, boolean value) {
        sharedPreferences.edit().putBoolean(key, value).apply();
    }

    public static void putFloat(String key, float value) {
        sharedPreferences.edit().putFloat(key, value).apply();
    }

    public static float getFloat(String key, float def) {
        return sharedPreferences != null ? sharedPreferences.getFloat(key, def) : def;
    }

    public static void removeValue(String key) {
        sharedPreferences.edit().remove(key).apply();
    }

}
