package com.RobinNotBad.BiliClient.activity.settings;

import android.content.Context;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity;
import com.RobinNotBad.BiliClient.adapter.SettingsAdapter;
import com.RobinNotBad.BiliClient.model.SettingSection;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import java.util.ArrayList;
import java.util.List;

import com.RobinNotBad.BiliClient.api.RecommendApi;
import com.RobinNotBad.BiliClient.activity.settings.SearchSortActivity;
import com.RobinNotBad.BiliClient.util.AppBuvid;
import android.os.Bundle;

public class RecommendSearchSettingsActivity extends RefreshListActivity {

    /**条目清单抽成静态方法：设置搜索的索引从这里自动生成，新增条目自动可搜*/
    public static List<SettingSection> buildSections(Context context) {
        final List<SettingSection> sectionList = new ArrayList<>() {
            {
                add(new SettingSection("title", "首页推荐", "", "", ""));
                add(new SettingSection("switch", "首页使用app端推荐", RecommendApi.KEY_APP_RECOMMEND,
                        "默认使用 app 端推荐（相关性更好）；若观感不符预期，可关闭改用 web 端推荐", "true"));
                add(new SettingSection("input_string", "推荐请求设备标识", AppBuvid.KEY,
                        "App 端推荐请求头 buvid 的值，留空或格式不对会自动重新生成。\n"
                                + "这是长期设备标识，B 站可用它把同一设备上的多个账号关联起来；换新值等于重置推荐偏好，"
                                + "需要刷新几轮才见效。", AppBuvid.get()));
                add(new SettingSection("switch", "推荐请求加签", RecommendApi.KEY_APP_RECOMMEND_SIGN,
                        "按官方客户端方式给请求加 appkey/ts/sign（参数不变）。关掉后退回不签名请求，"
                                + "用于排查签名被拒导致推荐变少的情况", "true"));
                add(new SettingSection("switch", "推荐请求携带登录凭证", RecommendApi.KEY_APP_RECOMMEND_ACCESS_KEY,
                        "默认关闭。开启后推荐请求会带上登录凭证（需已通过扫码换到过），失败不影响匿名推荐", "false"));
                add(new SettingSection("input_string", "推荐屏蔽的 UP 主", RecommendApi.KEY_BLOCK_MIDS,
                        "填 UP 主 mid（数字），多个用逗号或换行分隔；命中就不在首页推荐里出现。填错的行会被忽略", ""));
                add(new SettingSection("input_string", "推荐屏蔽的关键词", RecommendApi.KEY_BLOCK_WORDS,
                        "按视频标题做包含匹配，不区分大小写，多个用逗号或换行分隔", ""));
                add(new SettingSection("title", "搜索", "", "", ""));
                add(new SettingSection("switch", "搜索建议", "search_suggestions_enable",
                        context.getString(R.string.desc_search_suggestions_enable), "true"));
                add(new SettingSection("switch", "搜索框显示推荐内容", SharedPreferencesUtil.SEARCH_DEFAULT_CONTENT_ENABLE,
                        context.getString(R.string.desc_search_default_content_enable), "false"));
                add(new SettingSection("switch", "搜索页显示热搜", "search_hot_enable",
                        "打开后点搜索框、输入框为空时会弹出热搜词；关闭则只显示自己的搜索历史（默认关闭）", "false"));
                add(new SettingSection("open", "搜索类别排序", SearchSortActivity.class.getName(),
                        "拖动调整搜索页各分类（视频/番剧/专栏/用户/直播）的排列顺序，视频固定在第一位；保存后返回搜索页自动生效", ""));
            }
        };
        return sectionList;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPageName("推荐与搜索");

        recyclerView.setHasFixedSize(true);

        List<SettingSection> sections = buildSections(this);
        SettingsAdapter adapter = new SettingsAdapter(this, sections);
        setAdapter(adapter);
        scrollToSettingTitle(sections);
        setRefreshing(false);
    }

}
