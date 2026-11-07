package com.RobinNotBad.BiliClient.activity.settings;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity;
import com.RobinNotBad.BiliClient.adapter.SettingsAdapter;
import com.RobinNotBad.BiliClient.model.SettingSection;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import java.util.ArrayList;
import java.util.List;

public class SettingRepliesActivity extends RefreshListActivity {

    @SuppressLint({"MissingInflatedId", "SetTextI18n", "InflateParams"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPageName("评论区设置");
        Log.e("debug", "进入评论区设置");

final List<SettingSection> sectionList = new ArrayList<>(buildSections(this));

        recyclerView.setHasFixedSize(true);

        SettingsAdapter adapter = new SettingsAdapter(this, sectionList);
        setAdapter(adapter);
        scrollToSettingTitle(sectionList);

        setRefreshing(false);

    }

    /**
     * 条目清单抽成静态方法（2026-11-07 设置页重构，搜索自动索引用）。
     * 「众生平等/粉丝铭牌消失术」按反馈改为直白标题（key 不变）。
     */
    public static List<SettingSection> buildSections(Context context) {
        final List<SettingSection> sectionList = new ArrayList<>();
        sectionList.add(new SettingSection("switch", "隐藏会员红名", SharedPreferencesUtil.NO_VIP_COLOR, context.getString(R.string.desc_no_vip_color), "false"));
        sectionList.add(new SettingSection("switch", "隐藏粉丝铭牌", SharedPreferencesUtil.NO_MEDAL, context.getString(R.string.desc_no_medal), "false"));
        sectionList.add(new SettingSection("switch", "昵称不换行显示", SharedPreferencesUtil.REPLY_MARQUEE_NAME, context.getString(R.string.desc_reply_marquee_name), "true"));
        return sectionList;
    }
}