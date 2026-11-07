package com.RobinNotBad.BiliClient.activity.settings;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.util.Logu;
import android.content.Context;
import com.RobinNotBad.BiliClient.model.SettingSection;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置项搜索。
 *
 * <p><b>为什么要有这个页面</b>：设置已经散在 10 个页面、120+ 个条目里，用户记得「有个弹幕开关」却
 * 不知道在「内置播放器设置」还是「实验室」，只能一页页翻。
 *
 * <p><b>索引是手写的，为什么不做成反射/自动生成</b>：各设置页的条目在 onCreate 里用
 * {@code new SettingSection(...)} 拼出来，还绑着监听器与页面实例；抽成静态定义要改全部设置页，
 * 改动面远大于收益。代价是<b>新加设置项必须顺手往 {@link #buildIndex()} 里补一行</b>，
 * 漏了只会搜不到，不会崩。
 */
public class SettingSearchActivity extends BaseActivity {

    /** 跳转到设置页时携带的条目名：目标页会把列表滚到这一项 */
    public static final String EXTRA_SCROLL_TITLE = "setting_scroll_title";

    private static final class Entry {
        final String title;
        final String page;
        final Class<?> target;

        Entry(String title, String page, Class<?> target) {
            this.title = title;
            this.page = page;
            this.target = target;
        }
    }

    private final List<Entry> all = new ArrayList<>();
    private final List<Entry> shown = new ArrayList<>();
    private ResultAdapter adapter;
    private TextView emptyTip;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setting_search);

        View top = findViewById(R.id.top);
        if (top != null) top.setOnClickListener(view -> finish());

        all.addAll(buildIndex());

        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setHasFixedSize(true);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setItemAnimator(null);
        adapter = new ResultAdapter();
        recyclerView.setAdapter(adapter);

        emptyTip = findViewById(R.id.emptyTip);

        EditText input = findViewById(R.id.searchInput);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override public void afterTextChanged(Editable s) {
                applyFilter(s == null ? "" : s.toString());
            }
        });

        //表冠滚动（适配审计 P2-8）：结果列表换 Rotary 容器后，IME 的「搜索」键作为
        //确认动作——收起键盘、把焦点交给结果列表，表冠才能滚结果（输入中焦点必须留给输入框）
        input.setOnEditorActionListener((v, actionId, event) -> {
            input.clearFocus();
            recyclerView.requestFocus();
            return true;
        });

        applyFilter("");
    }

    private void applyFilter(String keyword) {
        String key = keyword.trim().toLowerCase();
        shown.clear();
        for (Entry entry : all) {
            if (key.isEmpty()
                    || entry.title.toLowerCase().contains(key)
                    || entry.page.toLowerCase().contains(key)) {
                shown.add(entry);
            }
        }
        adapter.notifyDataSetChanged();
        boolean empty = shown.isEmpty();
        emptyTip.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void open(Entry entry) {
        Intent intent = new Intent(this, entry.target);
        //页面级条目（title == page）没有具体设置项可定位，只打开页面
        if (!entry.title.equals(entry.page)) {
            intent.putExtra(EXTRA_SCROLL_TITLE, entry.title);
        }
        startActivity(intent);
    }

    private static void add(List<Entry> list, Class<?> target, String page, String... titles) {
        for (String title : titles) list.add(new Entry(title, page, target));
    }

    /** 整页级入口：本身就是一个设置页，点开即可，没有可定位的具体条目 */
    private static void addPage(List<Entry> list, Class<?> target, String page) {
        list.add(new Entry(page, "打开该设置页", target));
    }

    /** 列表型设置页的条目清单数据源（各设置页的静态构建器） */
    private interface SectionProvider {
        java.util.List<SettingSection> get(Context context);
    }

    private static final class AutoPage {
        final String title;
        final Class<?> target;
        final SectionProvider provider;

        AutoPage(String title, Class<?> target, SectionProvider provider) {
            this.title = title;
            this.target = target;
            this.provider = provider;
        }
    }

    /**
     * 列表型设置页注册表（2026-11-07 设置页重构）：条目索引从各页 {@code buildSections}
     * 静态清单自动生成，新增设置项自动可搜，不再手维护别名（旧手写索引已腐烂：
     * 「从历史位置播放」重复登记两次，且重构迁移后的条目指向旧页面）。
     */
    private static final AutoPage[] AUTO_PAGES = {
            new AutoPage("偏好设置", SettingPrefActivity.class, SettingPrefActivity::buildSections),
            new AutoPage("内置播放器设置", SettingTerminalPlayerActivity.class, SettingTerminalPlayerActivity::buildSections),
            new AutoPage("实验室", SettingLaboratoryActivity.class, SettingLaboratoryActivity::buildSections),
            new AutoPage("详情页设置", SettingInfoActivity.class, SettingInfoActivity::buildSections),
            new AutoPage("评论区设置", SettingRepliesActivity.class, SettingRepliesActivity::buildSections),
            new AutoPage("推荐与搜索", RecommendSearchSettingsActivity.class, RecommendSearchSettingsActivity::buildSections),
            new AutoPage("下载与存储", DownloadStorageSettingsActivity.class, DownloadStorageSettingsActivity::buildSections),
            new AutoPage("隐私与安全", PrivacySecuritySettingsActivity.class, PrivacySecuritySettingsActivity::buildSections),
            new AutoPage("通知与后台", NotificationBackgroundSettingsActivity.class, NotificationBackgroundSettingsActivity::buildSections),
    };

    /**
     * 全部可搜索条目 = 自动索引（列表型设置页）+ 手写条目（自绘/特殊设置页）。
     * 自绘页仍需在下面补条目，但范围已从全部设置缩小到 5 个自绘页。
     */
    private List<Entry> buildIndex() {
        List<Entry> list = new ArrayList<>();

        //自动索引：页级入口 + 每个条目（title/divider 行跳过；open 行直接定位目标页）
        for (AutoPage page : AUTO_PAGES) {
            addPage(list, page.target, page.title);
            try {
                for (SettingSection section : page.provider.get(this)) {
                    if (section.name == null || section.name.isEmpty()) continue;
                    if (section.type.equals("title") || section.type.equals("divider")) continue;
                    Class<?> target = page.target;
                    if (section.type.equals("open") && section.id != null) {
                        try {
                            target = Class.forName(section.id);
                        } catch (Throwable ignored) {
                        }
                    }
                    list.add(new Entry(section.name, page.title, target));
                }
            } catch (Throwable t) {
                Logu.e("setting-search", "索引生成失败 " + page.title + ": " + t);
            }
        }

        //自绘/特殊设置页：没有 SettingSection 清单，手工列出
        addPage(list, SettingUIActivity.class, "界面设置");
        add(list, SettingUIActivity.class, "界面设置",
                "界面大小", "左右留白", "上下留白", "强制屏幕密度", "新版美学设计",
                "番剧页显示推荐", "圆屏适配", "背景图", "背景图暗度", "恢复默认界面设置");
        addPage(list, SettingPlayerChooseActivity.class, "播放器选择");
        add(list, SettingPlayerChooseActivity.class, "播放器选择",
                "内置播放器", "凉腕播放器", "小电视播放器", "默认清晰度",
                "移动数据清晰度", "Wi-Fi 清晰度");
        add(list, ThemeColorActivity.class, "主题配色",
                "主题颜色", "自定义颜色", "恢复默认配色");
        addPage(list, com.RobinNotBad.BiliClient.activity.privacy.PrivacyLockSettingsActivity.class, "隐私与伪装");
        add(list, com.RobinNotBad.BiliClient.activity.privacy.PrivacyLockSettingsActivity.class, "隐私与伪装",
                "启动密码锁", "设置/修改密码", "图标伪装成计算器", "忘密码兜底入口");
        add(list, SettingMenuActivity.class, "菜单设置",
                "显示“番剧”", "显示“短视频”", "显示“热门”", "显示“热搜”", "显示“直播”",
                "显示“入站必刷”", "显示“全站排行榜”", "显示“时间线”");
        addPage(list, SortSettingActivity.class, "菜单排序");
        addPage(list, SearchSortActivity.class, "搜索类别排序");
        addPage(list, UpdateActivity.class, "检查更新");
        add(list, UpdateActivity.class, "检查更新",
                "更新源", "切换镜像", "跳过该版本");
        addPage(list, UpdateLogActivity.class, "更新日志");
        addPage(list, AboutActivity.class, "关于");
        add(list, AboutActivity.class, "关于",
                "开发者模式", "回声洞", "清除教程进度");

        return list;
    }

    private class ResultAdapter extends RecyclerView.Adapter<ResultAdapter.Holder> {

        class Holder extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView page;

            Holder(@NonNull View itemView) {
                super(itemView);
                title = itemView.findViewById(R.id.resultTitle);
                page = itemView.findViewById(R.id.resultPage);
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.cell_search_result, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Entry entry = shown.get(position);
            holder.title.setText(entry.title);
            holder.page.setText(entry.page);
            holder.itemView.setOnClickListener(view -> open(entry));
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }
    }
}
