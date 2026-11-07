package com.RobinNotBad.BiliClient.activity.settings;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity;
import com.RobinNotBad.BiliClient.adapter.SettingsAdapter;
import com.RobinNotBad.BiliClient.model.SettingSection;

import java.util.ArrayList;
import java.util.List;

public class SettingInfoActivity extends RefreshListActivity {

    @SuppressLint({"MissingInflatedId", "SetTextI18n", "InflateParams"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPageName("详情页设置");
        Log.e("debug", "进入详情页设置");

final List<SettingSection> sectionList = new ArrayList<>(buildSections(this));

        recyclerView.setHasFixedSize(true);

        SettingsAdapter adapter = new SettingsAdapter(this, sectionList);
        setAdapter(adapter);
        scrollToSettingTitle(sectionList);

        setRefreshing(false);

    }

    /**条目清单抽成静态方法（2026-11-07 设置页重构，搜索自动索引用）*/
    public static List<SettingSection> buildSections(Context context) {
        final List<SettingSection> sectionList = new ArrayList<>();
        sectionList.add(new SettingSection("switch", "收藏夹单选", "fav_single", context.getString(R.string.desc_fav_single), "false"));
        sectionList.add(new SettingSection("switch", "收藏成功提示", "fav_notice", context.getString(R.string.desc_fav_notice), "true"));
        sectionList.add(new SettingSection("switch", "点击封面播放", "cover_play_enable", context.getString(R.string.desc_cover_play), "true"));
        sectionList.add(new SettingSection("switch", "显示视频标签", "tags_enable", context.getString(R.string.desc_tags_enable), "true"));
        sectionList.add(new SettingSection("switch", "视频相关推荐", "related_enable", context.getString(R.string.desc_related_enable), "true"));
        sectionList.add(new SettingSection("switch", "以游客方式观看直播", "live_by_guest", context.getString(R.string.desc_live_by_guest), "false"));
        sectionList.add(new SettingSection("switch", "长按点赞触发三连", "like_one_triple", context.getString(R.string.desc_one_triple), "true"));
        return sectionList;
    }
}