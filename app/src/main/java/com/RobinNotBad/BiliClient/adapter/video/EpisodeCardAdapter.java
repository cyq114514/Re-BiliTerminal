package com.RobinNotBad.BiliClient.adapter.video;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.listener.OnItemClickListener;
import com.RobinNotBad.BiliClient.model.Bangumi;

import java.util.List;

/**
 * 番剧选集两行卡片（横向 RecyclerView），样式对齐官方选集区 / PiliPlus 的 PgcPanel：
 * 第一行 [播放中图标]第N话 [badge]，第二行 long_title；当前集整卡粉色高亮。
 * 不复用 MediaEpisodeAdapter：那个单行按钮样式被直播页（清晰度/线路）使用，语义不同。
 */
public class EpisodeCardAdapter extends RecyclerView.Adapter<EpisodeCardAdapter.EpisodeCardHolder> {

    private static final int COLOR_SELECTED = 0xfffb7299;      //B站粉，与全 app 高亮色一致
    private static final int COLOR_NORMAL = 0xffebe0e2;        //默认浅字
    private static final int COLOR_BADGE_VIP = 0xfffb7299;     //会员
    private static final int COLOR_BADGE_FREE = 0xff6eb26e;    //限免
    private static final int COLOR_BADGE_OTHER = 0x99ebe0e2;   //预告/其它

    private List<Bangumi.Episode> episodeList;
    public int selectedItemIndex = 0;
    private OnItemClickListener listener;

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void setSelectedItemIndex(int index) {
        int previous = this.selectedItemIndex;
        this.selectedItemIndex = index;
        notifyItemChanged(previous);
        notifyItemChanged(index);
    }

    @SuppressLint("NotifyDataSetChanged")
    public void setData(List<Bangumi.Episode> episodeList) {
        this.episodeList = episodeList;
        selectedItemIndex = 0;
        notifyDataSetChanged();
    }

    public List<Bangumi.Episode> getData() {
        return episodeList;
    }

    @NonNull
    @Override
    public EpisodeCardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.cell_episode_card, parent, false);
        return new EpisodeCardHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull EpisodeCardHolder holder, int position) {
        if (position < 0 || episodeList == null || position >= episodeList.size()) return;
        Bangumi.Episode episode = episodeList.get(position);
        boolean selected = position == selectedItemIndex;

        holder.title.setText(episode.title);
        holder.title.setTextColor(selected ? COLOR_SELECTED : COLOR_NORMAL);

        //播放中图标：仅当前集显示（官方选集卡同款声波标）
        holder.playing.setVisibility(selected ? View.VISIBLE : View.GONE);

        if (episode.badge != null && !episode.badge.isEmpty()) {
            holder.badge.setVisibility(View.VISIBLE);
            holder.badge.setText(episode.badge);
            holder.badge.setTextColor(colorOfBadge(episode.badge));
        } else {
            holder.badge.setVisibility(View.GONE);
        }

        if (episode.title_long != null && !episode.title_long.isEmpty()) {
            holder.titleLong.setVisibility(View.VISIBLE);
            holder.titleLong.setText(episode.title_long);
            holder.titleLong.setTextColor(selected ? COLOR_SELECTED : COLOR_NORMAL);
        } else {
            holder.titleLong.setVisibility(View.GONE);
        }

        holder.itemView.setOnClickListener(v -> {
            int previous = selectedItemIndex;
            selectedItemIndex = position;
            notifyItemChanged(previous);
            notifyItemChanged(position);
            if (listener != null) listener.onItemClick(position);
        });
    }

    private static int colorOfBadge(String badge) {
        if ("会员".equals(badge)) return COLOR_BADGE_VIP;
        if ("限免".equals(badge)) return COLOR_BADGE_FREE;
        return COLOR_BADGE_OTHER;
    }

    @Override
    public int getItemCount() {
        return episodeList != null ? episodeList.size() : 0;
    }

    public static class EpisodeCardHolder extends RecyclerView.ViewHolder {
        final ImageView playing;
        final TextView title;
        final TextView badge;
        final TextView titleLong;

        public EpisodeCardHolder(@NonNull View itemView) {
            super(itemView);
            playing = itemView.findViewById(R.id.episode_playing);
            title = itemView.findViewById(R.id.episode_title);
            badge = itemView.findViewById(R.id.episode_badge);
            titleLong = itemView.findViewById(R.id.episode_title_long);
        }
    }
}
