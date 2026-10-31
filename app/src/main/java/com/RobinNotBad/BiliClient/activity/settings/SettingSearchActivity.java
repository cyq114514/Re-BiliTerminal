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

    /**
     * 全部可搜索条目。新增设置项时请同步这里，否则搜不到。
     */
    private static List<Entry> buildIndex() {
        List<Entry> list = new ArrayList<>();

        //整页级入口：本身就是一个设置入口的，点开即可
        addPage(list, SettingUIActivity.class, "界面设置");
        addPage(list, SettingMenuActivity.class, "菜单设置");
        addPage(list, SettingTerminalPlayerActivity.class, "内置播放器设置");
        addPage(list, SettingPrefActivity.class, "偏好设置");
        addPage(list, SettingRepliesActivity.class, "评论区设置");
        addPage(list, SettingInfoActivity.class, "详情页设置");
        addPage(list, SettingLaboratoryActivity.class, "实验室");
        addPage(list, ThemeColorActivity.class, "主题配色");
        addPage(list, SettingQualityActivity.class, "默认清晰度");
        addPage(list, SortSettingActivity.class, "菜单排序");
        addPage(list, SettingPlayerChooseActivity.class, "播放器选择");
        addPage(list, UpdateActivity.class, "检查更新");
        addPage(list, UpdateLogActivity.class, "更新日志");
        addPage(list, AboutActivity.class, "关于");

        add(list, SettingTerminalPlayerActivity.class, "内置播放器设置",
                "长按倍速", "双击快进快退", "横滑调进度", "快进快退秒数", "显示快进快退按钮",
                "无极调音（0-100）", "双击优先还原缩放/移动", "洗脑循环", "记住倍速",
                "后台/熄屏继续播放", "默认横屏", "从历史位置播放", "显示实时人数", "听视频模式",
                "视频可缩放", "缩放时可移动", "番剧片头/片尾跳过",
                "显示方式", "解码方式", "音频输出",
                "显示高能进度条", "弹幕防重叠", "合并重复弹幕", "强制为滚动弹幕",
                "显示直播弹幕发送者", "空降弹幕", "弹幕最大行数", "弹幕字号大小", "弹幕不透明度", "弹幕速度",
                "自动弹出字幕选择", "允许仅AI字幕", "字幕校准",
                "显示旋转按钮", "显示弹幕按钮", "显示清晰度按钮", "显示分P按钮",
                "互动选项字体大小", "互动选项自动竖屏");

        add(list, SettingPrefActivity.class, "偏好设置",
                "隐私模式", "崩溃上报", "活跃设备统计", "长按复制", "创作中心",
                "搜索建议", "默认搜索内容", "搜索页显示热搜", "识别链接",
                "首页使用app端推荐", "App 推荐设备标识", "App 推荐附带客户端签名", "App 推荐附带 access_key",
                "推荐屏蔽的 UP 主", "推荐屏蔽的关键词",
                "新动态数量检查", "消息数量检查", "最近更新的UP主", "私信自动已读", "我的关注列表分组",
                "夜深了", "记住退出页面", "启动时检查更新",
                "禁用返回键", "禁止视频在相册中显示", "请求JPG格式图片",
                "加载渐入渐出动画", "关闭界面过渡动画", "翻动时不加载图片", "异步加载布局",
                "新提示信息显示方式", "新版开屏动画", "启动时等待请求完成",
                "启用表冠适配", "表冠适配灵敏度（Recycler）", "表冠适配灵敏度（Scroll）");

        add(list, SettingLaboratoryActivity.class, "实验室",
                "新版弹幕获取方式", "私信未读标记", "使用旧版下载器", "缓存路径", "图片下载路径",
                "横屏模式", "开屏文字", "文字跑马灯", "播放器旋屏兼容方案", "显示视频分段",
                "系统媒体控件", "互动视频调试", "接口重试间隔（秒）", "接口重试次数",
                "显示详细接口错误", "允许Logu.v", "允许Logu.d", "允许Logu.i", "调试日志落文件",
                "番剧进度诊断", "主动触发崩溃");

        add(list, SettingInfoActivity.class, "详情页设置",
                "收藏夹单选", "收藏成功提示", "点击封面播放", "显示视频标签", "视频相关推荐",
                "以游客方式观看直播", "一键三连");

        add(list, SettingRepliesActivity.class, "评论区设置",
                "众生平等", "粉丝铭牌消失术", "昵称不换行显示");

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
