package com.RobinNotBad.BiliClient.activity.source;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity;
import com.RobinNotBad.BiliClient.adapter.source.SourceEpisodeAdapter;
import com.RobinNotBad.BiliClient.source.SourceChannelHealth;
import com.RobinNotBad.BiliClient.source.SourceManager;
import com.RobinNotBad.BiliClient.source.SourcePreferenceStore;
import com.RobinNotBad.BiliClient.source.SourceStore;
import com.RobinNotBad.BiliClient.source.ThirdPartySource;
import com.RobinNotBad.BiliClient.source.model.SourceChannel;
import com.RobinNotBad.BiliClient.source.model.SourceEpisode;
import com.RobinNotBad.BiliClient.source.model.SourceException;
import com.RobinNotBad.BiliClient.source.model.SourceInfo;
import com.RobinNotBad.BiliClient.source.model.SourceSubject;
import com.RobinNotBad.BiliClient.source.play.ThirdPartyPlayback;
import com.RobinNotBad.BiliClient.ui.theme.ThemeManager;
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager;
import com.RobinNotBad.BiliClient.util.AdaptiveDialog;
import com.RobinNotBad.BiliClient.util.MsgUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 源作品详情：线路 + 剧集 + 起播。
 *
 * <p>解析就发生在本页：点某一集后底部状态栏显示「正在解析线路 X」，成功直接进播放器，
 * 失败弹「没有线路能播」并给出最后几条原因。不新开等待页——等待页会闪一下、
 * 与播放器的载入态重复，而且返回栈里多一层。
 */
public class SourceDetailActivity extends InstanceActivity {

    public static final String EXTRA_SOURCE_ID = "sourceId";
    public static final String EXTRA_SUBJECT = "subject";

    private SourceInfo info;
    private SourceSubject subject;
    private String scope = "";

    private TextView titleView;
    private TextView sourceView;
    private TextView statusText;
    private LinearLayout channelRow;
    private RecyclerView recycler;

    private SourceEpisodeAdapter adapter;
    private final List<SourceChannel> channels = new ArrayList<>();
    private int selectedChannel = 0;
    private boolean resolving = false;
    /** 本次被自动屏蔽的线路数（用于状态栏提示与「恢复」入口） */
    private int blockedChannels = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_source_detail);
        setTopbarExit();
        setPageName(getString(R.string.source_detail_title));

        titleView = findViewById(R.id.text_title);
        sourceView = findViewById(R.id.text_source);
        statusText = findViewById(R.id.statusText);
        channelRow = findViewById(R.id.channelRow);
        recycler = findViewById(R.id.recycler);

        String sourceId = getIntent().getStringExtra(EXTRA_SOURCE_ID);
        subject = (SourceSubject) getIntent().getSerializableExtra(EXTRA_SUBJECT);
        if (sourceId == null || subject == null) {
            MsgUtil.showMsgLong("作品参数缺失");
            finish();
            return;
        }
        for (SourceInfo candidate : SourceStore.load()) {
            if (candidate.id.equals(sourceId)) {
                info = candidate;
                break;
            }
        }
        if (info == null) {
            MsgUtil.showMsgLong("数据源已删除或未启用");
            finish();
            return;
        }
        scope = SourcePreferenceStore.scopeOf(info, subject);

        titleView.setText(subject.title);
        sourceView.setText(getString(R.string.source_detail_from, info.displayName()));

        adapter = new SourceEpisodeAdapter();
        adapter.setOnItemClickListener((episode, position) -> playEpisode(episode));
        recycler.setLayoutManager(new CustomLinearManager(this));
        recycler.setAdapter(adapter);

        loadChannels();
    }

    @Override
    protected void onResume() {
        super.onResume();
        //从播放器返回：解锁点击，允许换线路再试；
        //播放失败可能刚屏蔽了一条线路，用缓存里的原始列表重新过滤一次（不发网络请求）
        resolving = false;
        if (info != null && subject != null) {
            List<SourceChannel> raw = SourceManager.get().cachedChannels(info.id, subject.key());
            if (raw != null && !raw.isEmpty()) {
                applyChannels(raw);
                selectedChannel = Math.min(selectedChannel, Math.max(0, channels.size() - 1));
                renderChannels();
                fillEpisodes();
                updateChannelStatus();
            }
        }
    }

    private void loadChannels() {
        //5 分钟内进过同一作品：直接用上次的线路/剧集，省一次详情页请求
        List<SourceChannel> cached = SourceManager.get().cachedChannels(info.id, subject.key());
        if (cached != null && !cached.isEmpty()) {
            applyChannels(cached);
            selectedChannel = rememberedChannelIndex();
            renderChannels();
            fillEpisodes();
            updateChannelStatus();
            return;
        }
        statusText.setText(R.string.source_detail_loading);
        final ThirdPartySource source = SourceManager.get().newSource(info);
        if (source == null) {
            statusText.setText(SourceManager.get().unsupportedReason(info));
            return;
        }
        SourceManager.get().pool().execute(() -> {
            List<SourceChannel> result = null;
            SourceException error = null;
            try {
                result = source.channels(subject);
                SourceManager.get().noteSuccess(info.id);
            } catch (SourceException e) {
                error = e;
                SourceManager.get().noteFailure(info.id, e.userMessage());
            } catch (Throwable t) {
                error = new SourceException(SourceException.Phase.CHANNEL,
                        SourceException.Kind.PARSE, info.displayName(),
                        "线路解析异常：" + t.getClass().getSimpleName(), t);
                SourceManager.get().noteFailure(info.id, error.userMessage());
            }
            final List<SourceChannel> loaded = result;
            final SourceException failure = error;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (loaded == null || loaded.isEmpty()) {
                    statusText.setText(failure == null
                            ? getString(R.string.source_detail_no_channel)
                            : failure.userMessage());
                    MsgUtil.showMsgLong("线路获取失败");
                    return;
                }
                //缓存里存原始线路（含被屏蔽的），这样「恢复」不用重新抓详情页
                SourceManager.get().putChannels(info.id, subject.key(), loaded);
                applyChannels(loaded);
                selectedChannel = rememberedChannelIndex();
                renderChannels();
                fillEpisodes();
                updateChannelStatus();
            });
        });
    }

    /** 过滤已屏蔽线路，并把「上次成功解析过」的排到前面（下次起播第一条就命中） */
    private void applyChannels(List<SourceChannel> loaded) {
        final SourceChannelHealth health = SourceChannelHealth.get();
        List<SourceChannel> usable = new ArrayList<>();
        int blocked = 0;
        for (SourceChannel channel : loaded) {
            if (health.isBlocked(channel.id)) {
                blocked++;
                continue;
            }
            usable.add(channel);
        }
        Collections.sort(usable, (left, right) ->
                Long.compare(health.lastSuccess(right.id), health.lastSuccess(left.id)));
        channels.clear();
        channels.addAll(usable);
        blockedChannels = blocked;
    }

    private void updateChannelStatus() {
        if (channels.isEmpty() && blockedChannels > 0) {
            statusText.setText("所有线路均已被屏蔽，点「恢复」重试");
            return;
        }
        String text = getString(R.string.source_detail_channel_count,
                channels.size(), totalEpisodes());
        if (blockedChannels > 0) text += " · 已屏蔽 " + blockedChannels + " 条不可用线路";
        statusText.setText(text);
    }

    /** 手动恢复：清掉屏蔽记录后从缓存重新渲染，不重新抓详情页 */
    private void restoreChannels() {
        SourceChannelHealth.get().clearAll();
        List<SourceChannel> raw = SourceManager.get().cachedChannels(info.id, subject.key());
        if (raw == null || raw.isEmpty()) {
            loadChannels();
            return;
        }
        applyChannels(raw);
        selectedChannel = Math.min(selectedChannel, Math.max(0, channels.size() - 1));
        renderChannels();
        fillEpisodes();
        updateChannelStatus();
        MsgUtil.showMsg("已恢复全部线路");
    }

    private int rememberedChannelIndex() {
        String remembered = SourcePreferenceStore.getChannelId(scope);
        if (remembered == null || remembered.isEmpty()) return 0;
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).id.equals(remembered)) return i;
        }
        return 0;
    }

    private int totalEpisodes() {
        int count = 0;
        for (SourceChannel channel : channels) count += channel.episodes.size();
        return count;
    }

    private void renderChannels() {
        channelRow.removeAllViews();
        for (int i = 0; i < channels.size(); i++) {
            final int index = i;
            SourceChannel channel = channels.get(i);
            TextView chip = new TextView(this);
            chip.setText(channel.name);
            chip.setTextSize(13);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(12), 0, dp(12), 0);
            boolean selected = i == selectedChannel;
            chip.setBackground(chipBackground(selected));
            chip.setTextColor(selected ? 0xFFFFFFFF : getResources().getColor(R.color.text_gray));
            chip.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(30));
            params.setMargins(0, 0, dp(6), 0);
            chip.setLayoutParams(params);
            chip.setOnClickListener(view -> {
                if (selectedChannel == index) return;
                selectedChannel = index;
                renderChannels();
                fillEpisodes();
            });
            channelRow.addView(chip);
        }
        //被自动屏蔽过就给一个恢复入口：否则一次误判会让线路永久消失
        if (blockedChannels > 0) {
            TextView restore = new TextView(this);
            restore.setText("恢复 " + blockedChannels + " 条");
            restore.setTextSize(13);
            restore.setGravity(Gravity.CENTER);
            restore.setPadding(dp(12), 0, dp(12), 0);
            restore.setBackground(chipBackground(false));
            restore.setTextColor(getResources().getColor(R.color.text_gray));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(30));
            params.setMargins(0, 0, dp(6), 0);
            restore.setLayoutParams(params);
            restore.setOnClickListener(view -> restoreChannels());
            channelRow.addView(restore);
        }
    }

    private void fillEpisodes() {
        if (selectedChannel < 0 || selectedChannel >= channels.size()) return;
        final SourceChannel channel = channels.get(selectedChannel);
        adapter.update(channel.episodes);
        int remembered = SourcePreferenceStore.getEpisodeIndex(scope);
        if (remembered >= 0 && remembered < channel.episodes.size()) {
            recycler.post(() -> recycler.scrollToPosition(remembered));
        } else {
            recycler.post(() -> recycler.scrollToPosition(0));
        }
    }

    private void playEpisode(SourceEpisode episode) {
        if (resolving) {
            MsgUtil.showMsg("正在解析，请稍候");
            return;
        }
        resolving = true;
        statusText.setText(getString(R.string.source_detail_resolving, episode.display()));
        ThirdPartyPlayback.start(this, info, subject, channels, selectedChannel, episode.index, scope,
                new ThirdPartyPlayback.Callback() {
                    @Override
                    public void onStatus(String text) {
                        if (isFinishing()) return;
                        statusText.setText(text);
                    }

                    @Override
                    public void onStarted() {
                        resolving = false;
                        if (isFinishing()) return;
                        statusText.setText(R.string.source_detail_started);
                    }

                    @Override
                    public void onFailed(String reason) {
                        resolving = false;
                        if (isFinishing()) return;
                        statusText.setText(R.string.source_detail_failed);
                        new AdaptiveDialog.Builder(SourceDetailActivity.this)
                                .setTitle(R.string.source_detail_failed_title)
                                .setMessage(reason)
                                .setNegativeButton("知道了", null)
                                .show();
                    }
                });
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
}
