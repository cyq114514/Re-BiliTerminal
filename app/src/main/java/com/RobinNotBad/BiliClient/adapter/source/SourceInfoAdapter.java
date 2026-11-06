package com.RobinNotBad.BiliClient.adapter.source;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.source.model.SourceInfo;
import com.RobinNotBad.BiliClient.util.ClickGuard;

import java.util.ArrayList;
import java.util.List;

/** 数据源管理列表：名称 + 状态（启停 / 优先级 / 上次失败原因） */
public class SourceInfoAdapter extends RecyclerView.Adapter<SourceInfoAdapter.Holder> {

    public interface OnItemClickListener {
        void onItemClick(SourceInfo info);
    }

    private final List<SourceInfo> list = new ArrayList<>();
    private OnItemClickListener listener;

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void update(List<SourceInfo> data) {
        list.clear();
        if (data != null) list.addAll(data);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.cell_source_info, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        final SourceInfo info = list.get(position);
        holder.name.setText(info.displayName());
        holder.meta.setText(describe(info));
        holder.itemView.setOnClickListener(view -> ClickGuard.protect(view, () -> {
            if (listener != null) listener.onItemClick(info);
        }));
    }

    private String describe(SourceInfo info) {
        StringBuilder builder = new StringBuilder();
        builder.append(info.enabled ? "已启用" : "已停用");
        builder.append(" · 优先级 ").append(info.tier);
        if (info.baseUrl != null && !info.baseUrl.isEmpty()) {
            String host = info.baseUrl;
            int scheme = host.indexOf("://");
            if (scheme > 0) host = host.substring(scheme + 3);
            builder.append(" · ").append(host);
        }
        if (info.lastStatus < 0 && info.lastError != null && !info.lastError.isEmpty()) {
            builder.append('\n').append("上次失败：").append(info.lastError);
        }
        return builder.toString();
    }

    @Override
    public int getItemCount() {
        return list.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView meta;

        Holder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.text_name);
            meta = itemView.findViewById(R.id.text_meta);
        }
    }
}
