package com.RobinNotBad.BiliClient.activity.video;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity;
import com.RobinNotBad.BiliClient.api.BgmApi;
import com.RobinNotBad.BiliClient.api.BgmLocalStore;
import com.RobinNotBad.BiliClient.model.bgm.BgmSubject;
import com.RobinNotBad.BiliClient.ui.theme.ThemeManager;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.util.Locale;

/**
 * Bangumi 番剧详情。
 *
 * <p>只做元数据展示 + 本地追番状态；「资源匹配」（B站/第三方来源）在后续阶段接入，
 * 在此之前不显示任何假的播放入口——需求要求元数据与播放源严格分离。
 */
public class BgmSubjectActivity extends InstanceActivity {

    private long bgmId;
    private BgmSubject subject;

    private ImageView cover;
    private TextView title, original, rating, count, meta, tags, summary;
    private TextView summaryToggle, source;
    private LinearLayout followBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bgm_subject);
        //从热门网格进来的子页面：点标题栏直接返回上一级（原先 setMenuClick 只弹菜单，
        //用户只能靠左滑返回）
        setTopbarExit();
        setPageName(getString(R.string.bgm_detail_title));

        bgmId = getIntent().getLongExtra("bgmId", 0);
        if (bgmId <= 0) {
            MsgUtil.showMsgLong("番剧参数缺失");
            finish();
            return;
        }

        cover = findViewById(R.id.img_cover);
        title = findViewById(R.id.text_title);
        original = findViewById(R.id.text_original);
        rating = findViewById(R.id.text_rating);
        count = findViewById(R.id.text_count);
        meta = findViewById(R.id.text_meta);
        tags = findViewById(R.id.text_tags);
        summary = findViewById(R.id.text_summary);
        summaryToggle = findViewById(R.id.btn_summary_toggle);
        source = findViewById(R.id.text_source);
        followBar = findViewById(R.id.followBar);

        summaryToggle.setOnClickListener(v -> {
            boolean expanded = summary.getVisibility() == View.VISIBLE;
            summary.setVisibility(expanded ? View.GONE : View.VISIBLE);
            summaryToggle.setText(expanded ? R.string.bgm_expand_summary : R.string.bgm_collapse_summary);
        });

        findViewById(R.id.btn_open_bangumi).setOnClickListener(v -> openInBangumi());
        followBar.setVisibility(View.GONE);

        //第三方播放源入口：只带标题去搜，不猜「这部番对应哪个站点的哪一条」
        findViewById(R.id.btn_search_source).setOnClickListener(v -> openThirdPartySearch());

        CenterThreadPool.run(() -> {
            BgmSubject result = null;
            Exception error = null;
            try {
                result = BgmApi.getSubject(bgmId, true);
            } catch (Exception e) {
                error = e;
            }
            final BgmSubject loaded = result;
            final Exception err = error;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (loaded == null) {
                    //缓存也没有时才提示失败；有缓存的话 getSubject 已经返回缓存
                    MsgUtil.showMsgLong("番剧信息获取失败\n" + (err == null ? "" : err.getMessage()));
                    finish();
                    return;
                }
                subject = loaded;
                render(loaded);
            });
        });
    }

    @SuppressLint("SetTextI18n")
    private void render(BgmSubject data) {
        //详情页用原图（800px 封顶）：列表格子里放原图是浪费，但详情页只有这一张，
        //用 400px 的 common 在 3x 屏上仍会发虚
        String detailCover = data.coverLarge.isEmpty() ? data.coverCommon : data.coverLarge;
        //详情封面显示宽 104dp，3x 屏约 312px；给到 600px 留出放大看的余量
        Glide.with(this)
                .load(BgmApi.imageFor(detailCover, Math.max(600, dp(104) * 3)))
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .placeholder(R.mipmap.placeholder)
                .centerCrop()
                .into(cover);

        title.setText(data.title());

        String originalTitle = data.originalTitle();
        original.setText(originalTitle);
        original.setVisibility(originalTitle.isEmpty() ? View.GONE : View.VISIBLE);

        if (data.hasScore()) {
            rating.setText("★ " + String.format(Locale.US, "%.1f", data.score));
            rating.setVisibility(View.VISIBLE);
            StringBuilder builder = new StringBuilder();
            if (data.ratingTotal > 0)
                builder.append(formatCount(data.ratingTotal)).append("人评分");
            if (data.rank > 0) {
                if (builder.length() > 0) builder.append(" · ");
                builder.append("Bangumi 排名 #").append(data.rank);
            }
            count.setText(builder.toString());
            count.setVisibility(builder.length() > 0 ? View.VISIBLE : View.GONE);
        } else {
            rating.setVisibility(View.GONE);
            count.setVisibility(View.GONE);
        }

        meta.setText(buildMeta(data));

        if (data.tags.isEmpty()) {
            tags.setVisibility(View.GONE);
        } else {
            tags.setText("标签：" + android.text.TextUtils.join(" / ", data.tags));
            tags.setVisibility(View.VISIBLE);
        }

        String text = data.summary == null ? "" : data.summary.trim();
        if (text.isEmpty()) {
            summaryToggle.setVisibility(View.GONE);
            summary.setVisibility(View.GONE);
        } else {
            summary.setText(text);
            summaryToggle.setVisibility(View.VISIBLE);
        }

        source.setText(BgmApi.isUsingMirror() ? "数据来源：Bangumi（镜像）" : "数据来源：Bangumi");

        followBar.setVisibility(View.VISIBLE);
        renderFollowBar();
    }

    /** 开播日 · 放送星期 · 季度 · 平台 · 集数（缺失项整段省略） */
    private String buildMeta(BgmSubject data) {
        StringBuilder builder = new StringBuilder();
        if (data.date != null && !data.date.isEmpty()) {
            builder.append("开播：").append(data.date);
            int[] season = seasonOf(data.date);
            if (season != null) {
                if (builder.length() > 0) builder.append(" · ");
                builder.append(BgmApi.seasonLabel(season[0], season[1]));
            }
        }
        if (data.airWeekday >= 1 && data.airWeekday <= 7) {
            if (builder.length() > 0) builder.append(" · ");
            builder.append("放送：");
            String[] names = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
            builder.append(names[data.airWeekday - 1]);
        }
        if (data.platform != null && !data.platform.isEmpty()) {
            if (builder.length() > 0) builder.append(" · ");
            builder.append(data.platform);
        }
        if (data.totalEpisodes > 0) {
            if (builder.length() > 0) builder.append(" · ");
            builder.append("全 ").append(data.totalEpisodes).append(" 集");
        }
        return builder.toString();
    }

    /** yyyy-MM-dd → {year, quarter}；解析不了返回 null（不猜） */
    private int[] seasonOf(String date) {
        if (date == null || date.length() < 7) return null;
        try {
            int year = Integer.parseInt(date.substring(0, 4));
            int month = Integer.parseInt(date.substring(5, 7));
            if (month < 1 || month > 12) return null;
            return new int[]{year, (month - 1) / 3 + 1};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 打开第三方源搜索，带上当前番剧标题作为默认关键词。
     *
     * <p>只传标题、不传 bgmId：第三方源不认识 Bangumi 的 ID，把 bgmId 当第三方 ID 用
     * 会串到别的作品（需求里明确禁止的红线）。
     */
    private void openThirdPartySearch() {
        //直接用本条目（bgmId + 规范标题）去各站点匹配，不再让用户重新打一遍关键词
        String cn = subject != null ? subject.title() : "";
        if (cn == null || cn.trim().isEmpty()) {
            CharSequence text = title.getText();
            cn = text == null ? "" : text.toString();
        }
        android.content.Intent intent = new android.content.Intent(this,
                com.RobinNotBad.BiliClient.activity.source.SourceMatchActivity.class);
        intent.putExtra(com.RobinNotBad.BiliClient.activity.source.SourceMatchActivity.EXTRA_BGM_ID, bgmId);
        intent.putExtra(com.RobinNotBad.BiliClient.activity.source.SourceMatchActivity.EXTRA_TITLE,
                cn == null ? "" : cn.trim());
        intent.putExtra(com.RobinNotBad.BiliClient.activity.source.SourceMatchActivity.EXTRA_ORIGINAL,
                subject == null ? "" : subject.originalTitle());
        intent.putExtra(com.RobinNotBad.BiliClient.activity.source.SourceMatchActivity.EXTRA_COVER,
                subject == null ? "" : BgmApi.imageFor(
                        subject.coverCommon.isEmpty() ? subject.coverLarge : subject.coverCommon, 200));
        startActivity(intent);
    }

    private void openInBangumi() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://bgm.tv/subject/" + bgmId)));
        } catch (ActivityNotFoundException e) {
            MsgUtil.showMsg("没有可打开网页的应用");
        }
    }

    // ==================== 本地追番 ====================

    private void renderFollowBar() {
        followBar.removeAllViews();
        String current = BgmLocalStore.getStatus(bgmId);
        addFollowChip(BgmLocalStore.STATUS_WISH, current);
        addFollowChip(BgmLocalStore.STATUS_DOING, current);
        addFollowChip(BgmLocalStore.STATUS_DONE, current);
        addFollowChip(BgmLocalStore.STATUS_ON_HOLD, current);
    }

    private void addFollowChip(final String status, String current) {
        boolean selected = status.equals(current);
        TextView chip = new TextView(this);
        chip.setText(BgmLocalStore.statusLabel(status));
        chip.setTextSize(13);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(10), 0, dp(10), 0);
        chip.setBackground(chipBackground(selected));
        chip.setTextColor(selected ? 0xFFFFFFFF : getResources().getColor(R.color.text_gray));
        chip.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(30));
        params.setMargins(0, 0, dp(6), 0);
        chip.setLayoutParams(params);
        chip.setOnClickListener(v -> {
            //再点已选中的状态＝取消收藏：不额外加"取消"按钮，省一行触控区
            String next = status.equals(BgmLocalStore.getStatus(bgmId)) ? "" : status;
            BgmLocalStore.setStatus(bgmId, next);
            MsgUtil.showMsg(next.isEmpty() ? "已取消收藏" : "已标记为" + BgmLocalStore.statusLabel(next));
            renderFollowBar();
        });
        followBar.addView(chip);
    }

    private GradientDrawable chipBackground(boolean selected) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(14));
        drawable.setColor(selected ? ThemeManager.getPrimary() : 0x15000000);
        return drawable;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String formatCount(int num) {
        if (num >= 10000) return String.format(Locale.US, "%.1f万", num / 10000.0);
        return String.valueOf(num);
    }
}
