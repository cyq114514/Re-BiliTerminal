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

public class DownloadStorageSettingsActivity extends RefreshListActivity {

    /**条目清单抽成静态方法：设置搜索的索引从这里自动生成，新增条目自动可搜*/
    public static List<SettingSection> buildSections(Context context) {
        final List<SettingSection> sectionList = new ArrayList<>() {
            {
                add(new SettingSection("input_string", "视频缓存路径", "save_path_video",
                        context.getString(R.string.setting_lab_path_video), com.RobinNotBad.BiliClient.util.FileUtil.getVideoDownloadPath().toString()));
                add(new SettingSection("input_string", "图片下载路径", "save_path_pictures",
                        context.getString(R.string.setting_lab_path_pictures), com.RobinNotBad.BiliClient.util.FileUtil.getPicturePath().toString()));
                add(new SettingSection("switch", "禁止视频在相册中显示", "save_ban_gallery",
                        context.getString(R.string.desc_ban_gallery), "true"));
                add(new SettingSection("switch", "兼容模式取流（下载失败时尝试）", "dev_download_old",
                        "走旧版取流链路；新版取流失败、下载一直没反应时再打开", "false"));
            }
        };
        return sectionList;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPageName("下载与存储");

        recyclerView.setHasFixedSize(true);

        List<SettingSection> sections = buildSections(this);
        SettingsAdapter adapter = new SettingsAdapter(this, sections);
        setAdapter(adapter);
        scrollToSettingTitle(sections);
        setRefreshing(false);
    }

}
