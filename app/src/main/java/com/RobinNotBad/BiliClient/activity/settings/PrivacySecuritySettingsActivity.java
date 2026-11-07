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

public class PrivacySecuritySettingsActivity extends RefreshListActivity {

    /**条目清单抽成静态方法：设置搜索的索引从这里自动生成，新增条目自动可搜*/
    public static List<SettingSection> buildSections(Context context) {
        final List<SettingSection> sectionList = new ArrayList<>() {
            {
                add(new SettingSection("switch", "不留浏览记录（隐私模式）", SharedPreferencesUtil.PRIVACY_MODE,
                        context.getString(R.string.desc_privacy_mode), "false"));
                add(new SettingSection("open", "隐私与伪装",
                        com.RobinNotBad.BiliClient.activity.privacy.PrivacyLockSettingsActivity.class.getName(),
                        "启动密码锁（计算器界面输入密码进入）与桌面图标/名称伪装；忘密码可走假计算器设置→用户协议→进入应用兜底，无需清数据", ""));
            }
        };
        return sectionList;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPageName("隐私与安全");

        recyclerView.setHasFixedSize(true);

        List<SettingSection> sections = buildSections(this);
        SettingsAdapter adapter = new SettingsAdapter(this, sections);
        setAdapter(adapter);
        scrollToSettingTitle(sections);
        setRefreshing(false);
    }

}
