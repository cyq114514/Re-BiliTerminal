package com.RobinNotBad.BiliClient.activity.dynamic;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.api.DynamicApi;
import com.RobinNotBad.BiliClient.model.VoteInfo;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.GlideUtil;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.bumptech.glide.Glide;
import com.google.android.material.card.MaterialCardView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static com.RobinNotBad.BiliClient.util.StringUtil.toWan;

/**
 * 投票详情与参与页面（参考PiliPlus的投票实现）
 * 支持文字/图片投票、多选（choice_cnt）、已投与过期状态的百分比展示。
 */
public class VoteActivity extends BaseActivity {

    private long voteId;
    private long dynamicId;
    private VoteInfo voteInfo;
    private final List<Integer> selected = new ArrayList<>();
    private boolean submitting = false;

    private TextView voteTitle, voteMeta;
    private LinearLayout optionsContainer;
    private MaterialCardView submit;

    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vote);

        voteId = getIntent().getLongExtra("voteId", 0);
        dynamicId = getIntent().getLongExtra("dynamicId", 0);

        TextView pageName = findViewById(R.id.pageName);
        pageName.setText("投票详情");
        pageName.setOnClickListener(view -> finish());

        voteTitle = findViewById(R.id.voteTitle);
        voteMeta = findViewById(R.id.voteMeta);
        optionsContainer = findViewById(R.id.optionsContainer);
        submit = findViewById(R.id.submit);

        if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) == 0) {
            MsgUtil.showMsg("还没有登录喵~");
        }

        submit.setOnClickListener(view -> {
            if (submitting || voteInfo == null || selected.isEmpty()) return;
            submitting = true;
            MsgUtil.showMsg("投票中...");
            CenterThreadPool.run(() -> {
                try {
                    VoteInfo newInfo = DynamicApi.doVote(voteId, selected, false, dynamicId);
                    if (newInfo != null) {
                        voteInfo = newInfo;
                        //do_vote返回的vote_info可能不带my_votes，回填以防重复投票入口
                        if (!voteInfo.voted()) {
                            voteInfo.myVotes = new int[selected.size()];
                            for (int i = 0; i < selected.size(); i++) voteInfo.myVotes[i] = selected.get(i);
                        }
                    }
                    runOnUiThread(() -> {
                        submitting = false;
                        selected.clear();
                        MsgUtil.showMsg("投票成功~");
                        render();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        submitting = false;
                        MsgUtil.err(e);
                    });
                }
            });
        });

        findViewById(R.id.top).setOnClickListener(view -> finish());

        load();
    }

    private void load() {
        CenterThreadPool.run(() -> {
            try {
                VoteInfo info = DynamicApi.getVoteInfo(voteId);
                if (info == null) throw new Exception("无效的投票");
                voteInfo = info;
                runOnUiThread(this::render);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    MsgUtil.err(e);
                    voteTitle.setText("投票加载失败");
                });
            }
        });
    }

    @SuppressLint("SetTextI18n")
    private void render() {
        if (voteInfo == null) return;
        voteTitle.setText(voteInfo.title.isEmpty() ? voteInfo.desc : voteInfo.title);

        StringBuilder meta = new StringBuilder();
        meta.append(toWan(voteInfo.joinNum)).append("人参与");
        if (voteInfo.endTime > 0) {
            String end = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date(voteInfo.endTime * 1000L));
            meta.append(" · ").append(voteInfo.ended() ? "已于 " + end + " 结束" : "至 " + end);
        }
        if (voteInfo.choiceCnt > 1) meta.append(" · 最多可选").append(voteInfo.choiceCnt).append("项");
        if (voteInfo.voted()) meta.append(" · 已投过票");
        voteMeta.setText(meta.toString());

        optionsContainer.removeAllViews();
        boolean canVote = voteInfo.enabled()
                && SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) != 0;

        if (canVote) {
            for (VoteInfo.Option option : voteInfo.options) {
                TextView cell = buildOptionCell(option.desc.isEmpty() && !option.imgUrl.isEmpty() ? "图片选项" : option.desc, option.imgUrl);
                boolean isSelected = selected.contains(option.idx);
                cell.setTextColor(isSelected ? Color.rgb(0xfe, 0x67, 0x9a) : 0xFFFFFFFF);
                cell.setOnClickListener(view -> {
                    if (selected.contains(option.idx)) {
                        selected.remove(Integer.valueOf(option.idx));
                    } else {
                        //多选超出可选项数时先进先出替换（参考PiliPlus行为）
                        if (selected.size() >= voteInfo.choiceCnt) selected.remove(0);
                        selected.add(option.idx);
                    }
                    render();
                });
                optionsContainer.addView(cell);
            }
            submit.setVisibility(selected.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
        } else {
            long total = voteInfo.totalCnt();
            for (VoteInfo.Option option : voteInfo.options) {
                int percent = total > 0 ? (int) (option.cnt * 100 / total) : 0;
                String text = option.desc.isEmpty() && !option.imgUrl.isEmpty() ? "图片选项" : option.desc;
                text = text + "　" + percent + "%（" + toWan(option.cnt) + "票）";
                TextView cell = buildOptionCell(text, option.imgUrl);
                boolean isMyVote = false;
                for (int my : voteInfo.myVotes) if (my == option.idx) isMyVote = true;
                if (isMyVote) cell.setTextColor(Color.rgb(0xfe, 0x67, 0x9a));
                optionsContainer.addView(cell);
            }
            if (voteInfo.ended() && !voteInfo.voted()) {
                TextView endedTip = new TextView(this);
                endedTip.setText("该投票已结束");
                endedTip.setAlpha(0.7f);
                endedTip.setGravity(Gravity.CENTER);
                endedTip.setPadding(0, dp(8), 0, 0);
                optionsContainer.addView(endedTip);
            }
            submit.setVisibility(android.view.View.GONE);
        }
    }

    private TextView buildOptionCell(String text, String imgUrl) {
        TextView cell = new TextView(this);
        cell.setText(text);
        cell.setTextSize(14);
        cell.setTextColor(0xFFFFFFFF);
        cell.setGravity(Gravity.CENTER_VERTICAL);
        cell.setPadding(dp(12), dp(10), dp(12), dp(10));
        cell.setBackgroundDrawable(buildCellBackground());
        int pad = dp(4);
        cell.setCompoundDrawablePadding(pad);
        if (imgUrl != null && !imgUrl.isEmpty()) {
            int size = dp(40);
            Glide.with(this).asDrawable().load(GlideUtil.url(imgUrl)).override(size).centerCrop()
                    .placeholder(R.mipmap.placeholder)
                    .into(new DrawableTarget(cell, size));
        }
        return cell;
    }

    /**图片选项的缩略图加载到TextView左侧*/
    private static class DrawableTarget extends com.bumptech.glide.request.target.CustomTarget<android.graphics.drawable.Drawable> {
        private final TextView textView;
        private final int size;

        DrawableTarget(TextView textView, int size) {
            this.textView = textView;
            this.size = size;
        }

        @Override
        public void onResourceReady(android.graphics.drawable.Drawable resource, com.bumptech.glide.request.transition.Transition<? super android.graphics.drawable.Drawable> transition) {
            resource.setBounds(0, 0, size, size);
            textView.setCompoundDrawables(resource, null, null, null);
        }

        @Override
        public void onLoadCleared(android.graphics.drawable.Drawable placeholder) {
        }
    }

    @SuppressWarnings("deprecation")
    private android.graphics.drawable.Drawable buildCellBackground() {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(Color.argb(0x18, 0x80, 0x80, 0x80));
        drawable.setCornerRadius(dp(6));
        return drawable;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
