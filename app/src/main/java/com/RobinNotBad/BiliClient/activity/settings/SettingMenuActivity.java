package com.RobinNotBad.BiliClient.activity.settings;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.widget.CompoundButton;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

public class SettingMenuActivity extends BaseActivity {

    private SwitchMaterial menu_bangumi, menu_short_video, menu_popular, menu_hotsearch, menu_live, menu_precious, menu_ranking, menu_timeline;

    @SuppressLint("InflateParams")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        asyncInflate(R.layout.activity_setting_menu, (layoutView, resId) -> {

            //切换即落库（与 SettingsAdapter 的设置项口径一致）：
            //此前只在 onDestroy 统一 save，进程被杀时改动全丢，且加载占位期退出还会对 null 控件 NPE
            //布局里本来就有「显示番剧」，但这里一直漏绑定：开关既读不到当前值也写不进配置，
            //菜单那边的 menu_bangumi 判断形同虚设
            bindSwitch(menu_bangumi = findViewById(R.id.menu_bangumi), "menu_bangumi", true);
            bindSwitch(menu_short_video = findViewById(R.id.menu_short_video), "menu_short_video", true);
            bindSwitch(menu_popular = findViewById(R.id.menu_popular), "menu_popular", true);
            bindSwitch(menu_hotsearch = findViewById(R.id.menu_hotsearch), "menu_hotsearch", true);
            bindSwitch(menu_live = findViewById(R.id.menu_live), "menu_live", false);
            bindSwitch(menu_precious = findViewById(R.id.menu_precious), "menu_precious", false);
            bindSwitch(menu_ranking = findViewById(R.id.menu_ranking), "menu_ranking", false);
            bindSwitch(menu_timeline = findViewById(R.id.menu_timeline), "menu_timeline", false);

            MaterialButton sort_btn = findViewById(R.id.sort);
            sort_btn.setOnClickListener(view -> {
                Intent intent = new Intent(SettingMenuActivity.this, SortSettingActivity.class);
                startActivity(intent);
            });
        });
    }

    private void bindSwitch(SwitchMaterial switchView, String key, boolean defValue) {
        switchView.setChecked(SharedPreferencesUtil.getBoolean(key, defValue));
        switchView.setOnCheckedChangeListener((CompoundButton buttonView, boolean isChecked) ->
                SharedPreferencesUtil.putBoolean(key, isChecked));
    }
}
