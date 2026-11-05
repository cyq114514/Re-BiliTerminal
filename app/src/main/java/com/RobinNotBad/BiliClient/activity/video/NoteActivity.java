package com.RobinNotBad.BiliClient.activity.video;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.api.NoteApi;
import com.RobinNotBad.BiliClient.model.Note;
import com.RobinNotBad.BiliClient.model.NoteBlock;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.GlideUtil;

import java.util.List;

/** 视频笔记查看页（只读）。 */
public class NoteActivity extends BaseActivity {

    private long aid;
    private LinearLayout container;
    private TextView titleView;
    private TextView summaryView;
    private TextView emptyView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        aid = getIntent().getLongExtra("aid", 0);
        asyncInflate(R.layout.activity_note, (view, id) -> {
            titleView = findViewById(R.id.noteTitle);
            summaryView = findViewById(R.id.noteSummary);
            emptyView = findViewById(R.id.noteEmpty);
            container = findViewById(R.id.noteContainer);
            View top = findViewById(R.id.top);
            if (top != null) top.setOnClickListener(v -> finish());
            setPageName("视频笔记");
            loadNote();
        });
    }

    private void loadNote() {
        if (aid <= 0) {
            showEmpty("视频参数缺失");
            return;
        }
        CenterThreadPool.run(() -> {
            try {
                List<String> ids = NoteApi.getNoteIdsOfVideo(aid);
                if (ids.isEmpty()) {
                    runOnUiThread(() -> showEmpty("这个视频没有笔记"));
                    return;
                }
                Note note = NoteApi.getNoteDetail(ids.get(0));
                runOnUiThread(() -> renderNote(note));
            } catch (Exception e) {
                runOnUiThread(() -> showEmpty(NoteApi.failureText(e)));
            }
        });
    }

    @SuppressLint("SetTextI18n")
    private void renderNote(Note note) {
        if (note == null || (note.blocks.isEmpty() && note.title.isEmpty())) {
            showEmpty("这篇笔记没有正文");
            return;
        }
        titleView.setText(note.title.isEmpty() ? "视频笔记" : note.title);
        if (!note.summary.isEmpty()) {
            summaryView.setText(note.summary);
            summaryView.setVisibility(View.VISIBLE);
        }
        container.removeAllViews();
        for (NoteBlock block : note.blocks) {
            View view = renderBlock(block);
            if (view != null) container.addView(view);
        }
    }

    private View renderBlock(NoteBlock block) {
        if (block == null) return null;
        if (block.type == NoteBlock.TYPE_IMAGE) {
            com.google.android.material.imageview.ShapeableImageView image =
                    new com.google.android.material.imageview.ShapeableImageView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            image.setLayoutParams(lp);
            image.setAdjustViewBounds(true);
            com.bumptech.glide.Glide.with(this).load(GlideUtil.url(block.imageUrl))
                    .placeholder(R.mipmap.akari).into(image);
            return image;
        }
        TextView tv = new TextView(this);
        tv.setTextSize(14f);
        if (block.type == NoteBlock.TYPE_TAG) {
            tv.setText("⏱ " + formatSeconds(block.tagSeconds)
                    + (block.tagTitle.isEmpty() ? "" : " " + block.tagTitle));
            tv.setTextSize(13f);
            return tv;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder(block.text);
        if (block.bold) sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (block.underline) sb.setSpan(new UnderlineSpan(), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (block.strike) sb.setSpan(new StrikethroughSpan(), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (!block.color.isEmpty()) {
            int c = parseColor(block.color, Color.WHITE);
            sb.setSpan(new ForegroundColorSpan(c), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (!block.background.isEmpty()) {
            int c = parseColor(block.background, Color.TRANSPARENT);
            sb.setSpan(new BackgroundColorSpan(c), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        tv.setText(sb);
        return tv;
    }

    private void showEmpty(String msg) {
        emptyView.setText(msg);
        emptyView.setVisibility(View.VISIBLE);
    }

    private static String formatSeconds(long seconds) {
        long safe = Math.max(seconds, 0);
        long h = safe / 3600;
        long m = (safe % 3600) / 60;
        long s = safe % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%02d:%02d", m, s);
    }

    private static int parseColor(String value, int fallback) {
        try {
            if (value.startsWith("#")) return Color.parseColor(value);
            return Color.parseColor("#" + value);
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
