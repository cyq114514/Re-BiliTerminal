package com.RobinNotBad.BiliClient.activity.settings;

import android.content.Context;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity;
import com.RobinNotBad.BiliClient.adapter.SettingsAdapter;
import com.RobinNotBad.BiliClient.model.SettingSection;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import java.util.ArrayList;
import java.util.List;

import android.os.Bundle;

public class NotificationBackgroundSettingsActivity extends RefreshListActivity {

    /**条目清单抽成静态方法：设置搜索的索引从这里自动生成，新增条目自动可搜*/
    public static List<SettingSection> buildSections(Context context) {
        final List<SettingSection> sectionList = new ArrayList<>() {
            {
                add(new SettingSection("switch", "新动态数量检查", SharedPreferencesUtil.DYNAMIC_UPDATE_CHECK_ENABLE,
                        context.getString(R.string.desc_dynamic_update_check_enable), "true"));
                add(new SettingSection("switch", "消息数量检查", SharedPreferencesUtil.MESSAGE_UPDATE_CHECK_ENABLE,
                        context.getString(R.string.desc_message_update_check_enable), "true"));
                add(new SettingSection("switch", "记住退出页面", SharedPreferencesUtil.RESUME_PAGE_ENABLE,
                        context.getString(R.string.desc_resume_page), "false"));
                add(new SettingSection("switch", "后台/熄屏继续播放", "player_background",
                        "退到后台或熄屏时继续播放，并挂出通知栏遥控", "false"));
            }
        };
        return sectionList;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPageName("通知与后台");

        recyclerView.setHasFixedSize(true);

        List<SettingSection> sections = buildSections(this);
        SettingsAdapter adapter = new SettingsAdapter(this, sections);
        setAdapter(adapter);
        scrollToSettingTitle(sections);
        setRefreshing(false);
    }

}
