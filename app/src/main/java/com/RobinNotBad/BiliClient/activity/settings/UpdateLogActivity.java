package com.RobinNotBad.BiliClient.activity.settings;

import android.os.Bundle;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.adapter.video.VersionTabAdapter;
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager;
import com.RobinNotBad.BiliClient.util.UpdateLog;

/**
 * 历史更新日志：按版本分选项卡展示（数据源 UpdateLog，与 GitHub Releases 的说明一致）。
 * 关于页"查看历史更新日志"进入；应用首次安装/升级后首次进入主菜单时
 * 会带上当前版本的下标（version_index）自动跳转到这里。
 */
public class UpdateLogActivity extends BaseActivity {

    private VersionTabAdapter tabAdapter;
    private TextView logText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_update_log);

        findViewById(R.id.top).setOnClickListener(view -> finish());

        logText = findViewById(R.id.log_text);
        RecyclerView tabsRecycler = findViewById(R.id.rv_version_tabs);

        tabAdapter = new VersionTabAdapter(allVersions());
        tabAdapter.setOnTabClickListener(this::showVersion);
        tabsRecycler.setLayoutManager(new CustomLinearManager(this, LinearLayoutManager.HORIZONTAL, false));
        tabsRecycler.setAdapter(tabAdapter);

        int preselect = getIntent().getIntExtra("version_index", 0);
        if (preselect < 0 || preselect >= UpdateLog.count()) preselect = 0;
        showVersion(preselect);
    }

    private String[] allVersions() {
        String[] versions = new String[UpdateLog.count()];
        for (int i = 0; i < versions.length; i++) versions[i] = UpdateLog.version(i);
        return versions;
    }

    private void showVersion(int index) {
        tabAdapter.setSelectedIndex(index);

        StringBuilder str = new StringBuilder("Re：哔哩终端 ").append(UpdateLog.version(index));
        String[] items = UpdateLog.items(index);
        for (int i = 0; i < items.length; i++) {
            str.append("\n").append(i + 1).append(". ").append(items[i]);
        }
        logText.setText(str.toString());
        //切回顶部：布局未完成时 scrollTo 不生效，用 post 保证切 tab 后总在顶部
        findViewById(R.id.log_scroll).post(() -> findViewById(R.id.log_scroll).scrollTo(0, 0));
    }
}
