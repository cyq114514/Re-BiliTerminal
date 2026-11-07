package com.RobinNotBad.BiliClient.activity.player;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.ui.theme.ThemeApplier;
import com.RobinNotBad.BiliClient.ui.theme.ThemeManager;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.model.AudioQueue;
import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.service.AudioPlayerService;
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager;

import java.util.ArrayList;
import java.util.List;

//收藏夹音频播放：队列页（当前项高亮，点击切歌）
//2026-11-05
public class AudioQueueActivity extends BaseActivity implements AudioPlayerService.Listener {

    private QueueAdapter adapter;
    private final List<VideoCard> items = new ArrayList<>();
    private RecyclerView recycler;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_audio_queue);

        findViewById(R.id.top).setOnClickListener(v -> finish());

        recycler = findViewById(R.id.recycler);
        recycler.setLayoutManager(new CustomLinearManager(this));
        items.addAll(AudioQueue.get().getItems());
        adapter = new QueueAdapter();
        recycler.setAdapter(adapter);
        //表冠滚动需要焦点路由到列表（适配审计 P2-8）：队列页换 Rotary 容器后在此聚焦
        recycler.requestFocus();
        AudioPlayerService.addListener(this);
        scrollToCurrent();
    }

    @Override
    public void onAudioStateChanged() {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            //全量拉取逐页到达：队列变长时同步进列表——
            //此前 items 只在 onCreate 快照一次，后台补页后队列页永远看不到新条目，
            //用户看到的队列永远停在起播那几首（"无法一次性获取全部内容"的 UI 面）
            int size = AudioQueue.get().size();
            if (size != items.size()) {
                items.clear();
                items.addAll(AudioQueue.get().getItems());
                //新页到达不抢滚动位置：仅 onCreate 时定位过当前曲目
                adapter.notifyDataSetChanged();
                return;
            }
            adapter.notifyDataSetChanged();
        });
    }

    private void scrollToCurrent() {
        int index = AudioQueue.get().getIndex();
        if (index >= 0 && index < items.size()) recycler.scrollToPosition(index);
    }

    @Override
    protected void onDestroy() {
        AudioPlayerService.removeListener(this);
        super.onDestroy();
    }

    private class QueueAdapter extends RecyclerView.Adapter<QueueAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.cell_audio_queue, parent, false);
            //cell 是 setContentView 之后才绑定的，BaseActivity 的全树换色跑不到这里：
            //playingTag 的强调粉要在 bind 期补一次（G-01）
            ThemeApplier.apply(view);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            VideoCard item = items.get(position);
            holder.indexText.setText(String.valueOf(position + 1));
            holder.titleText.setText(item.title);
            holder.upNameText.setText(item.upName != null ? item.upName : "");
            boolean current = position == AudioQueue.get().getIndex();
            holder.playingTag.setVisibility(current ? View.VISIBLE : View.GONE);
            //当前曲目强调色走主题色（此前硬编码旧版粉 0xFFFB7299，不随主题）
            holder.titleText.setTextColor(current ? ThemeManager.getPrimary()
                    : holder.itemView.getContext().getResources().getColor(R.color.textwhite));
            holder.itemView.setOnClickListener(v -> {
                if (position == AudioQueue.get().getIndex() && AudioPlayerService.isActive()) {
                    finish();
                    return;
                }
                AudioPlayerService.playIndex(AudioQueueActivity.this, position);
                scrollToCurrent();
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView indexText, titleText, upNameText, playingTag;

            Holder(@NonNull View itemView) {
                super(itemView);
                indexText = itemView.findViewById(R.id.indexText);
                titleText = itemView.findViewById(R.id.titleText);
                upNameText = itemView.findViewById(R.id.upNameText);
                playingTag = itemView.findViewById(R.id.playingTag);
            }
        }
    }
}
