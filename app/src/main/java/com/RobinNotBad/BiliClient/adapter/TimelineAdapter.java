package com.RobinNotBad.BiliClient.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.model.Timeline;
import com.RobinNotBad.BiliClient.util.GlideUtil;
import com.RobinNotBad.BiliClient.util.TerminalContext;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.card.MaterialCardView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class TimelineAdapter extends RecyclerView.Adapter<TimelineAdapter.DayViewHolder> {

    private final Context context;
    private final List<Timeline.DayInfo> dayInfoList;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

    public TimelineAdapter(Context context, List<Timeline.DayInfo> dayInfoList) {
        this.context = context;
        this.dayInfoList = dayInfoList;
    }

    @NonNull
    @Override
    public DayViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.cell_timeline_day, parent, false);
        return new DayViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull DayViewHolder holder, int position) {
        Timeline.DayInfo dayInfo = dayInfoList.get(position);
        
        String dateText = dayInfo.date;
        if (dayInfo.is_today == 1) {
            dateText = dateText + " (今天)";
        }
        holder.dateText.setText(dateText);

        holder.episodesLayout.removeAllViews();
        
        if (dayInfo.episodes != null && !dayInfo.episodes.isEmpty()) {
            for (Timeline.Episode episode : dayInfo.episodes) {
                View episodeView = LayoutInflater.from(context).inflate(R.layout.cell_timeline_episode, holder.episodesLayout, false);
                
                ImageView cover = episodeView.findViewById(R.id.img_cover);
                TextView title = episodeView.findViewById(R.id.text_title);
                TextView episodeText = episodeView.findViewById(R.id.text_episode);
                TextView timeText = episodeView.findViewById(R.id.text_time);
                
                title.setText(episode.title);
                episodeText.setText(episode.pub_index);
                
                if (episode.pub_ts > 0) {
                    Date pubDate = new Date(episode.pub_ts * 1000);
                    timeText.setText(timeFormat.format(pubDate));
                } else {
                    timeText.setText(episode.pub_time);
                }
                
                String coverUrl = GlideUtil.url(episode.cover);
                Glide.with(BiliTerminal.context).asDrawable().load(coverUrl)
                        .placeholder(R.mipmap.placeholder)
                        .format(DecodeFormat.PREFER_RGB_565)
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .apply(RequestOptions.bitmapTransform(new RoundedCorners(8))
                                .sizeMultiplier(0.85f))
                        .into(cover);
                
                holder.episodesLayout.addView(episodeView);
            }
        }
    }

    @Override
    public int getItemCount() {
        return dayInfoList != null ? dayInfoList.size() : 0;
    }

    static class DayViewHolder extends RecyclerView.ViewHolder {
        TextView dateText;
        LinearLayout episodesLayout;

        DayViewHolder(@NonNull View itemView) {
            super(itemView);
            dateText = itemView.findViewById(R.id.text_date);
            episodesLayout = itemView.findViewById(R.id.episodes_layout);
        }
    }
}

