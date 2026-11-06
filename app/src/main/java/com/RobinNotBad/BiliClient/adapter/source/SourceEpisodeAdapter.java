package com.RobinNotBad.BiliClient.adapter.source;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.source.model.SourceEpisode;
import com.RobinNotBad.BiliClient.util.ClickGuard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 剧集列表：名称 + 集号（集号取解析结果，不用下标猜） */
public class SourceEpisodeAdapter extends RecyclerView.Adapter<SourceEpisodeAdapter.Holder> {

    public interface OnItemClickListener {
        void onItemClick(SourceEpisode episode, int position);
    }

    private final List<SourceEpisode> list = new ArrayList<>();
    private OnItemClickListener listener;

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void update(List<SourceEpisode> data) {
        list.clear();
        if (data != null) list.addAll(data);
        notifyDataSetChanged();
    }

    public List<SourceEpisode> items() {
        return list;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.cell_source_episode, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        final SourceEpisode episode = list.get(position);
        holder.name.setText(episode.display());
        holder.sort.setText(sortLabel(episode));
        final int index = position;
        holder.itemView.setOnClickListener(view -> ClickGuard.protect(view, () -> {
            if (listener != null) listener.onItemClick(episode, index);
        }));
    }

    private String sortLabel(SourceEpisode episode) {
        if (episode.sortRaw != null && !episode.sortRaw.trim().isEmpty())
            return episode.sortRaw.trim();
        double sort = episode.sort;
        if (sort == Math.floor(sort) && !Double.isInfinite(sort))
            return String.format(Locale.US, "%.0f", sort);
        return String.format(Locale.US, "%.1f", sort);
    }

    @Override
    public int getItemCount() {
        return list.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView sort;

        Holder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.text_name);
            sort = itemView.findViewById(R.id.text_sort);
        }
    }
}
