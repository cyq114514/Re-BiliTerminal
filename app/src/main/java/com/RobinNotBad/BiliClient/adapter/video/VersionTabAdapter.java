package com.RobinNotBad.BiliClient.adapter.video;

import android.annotation.SuppressLint;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;

/**
 * 历史更新日志页的版本选项卡（横向滚动，选中粉色加粗）。
 * 与番剧页的 SeasonTabAdapter 同款交互，但数据源是版本名字符串。
 */
public class VersionTabAdapter extends RecyclerView.Adapter<VersionTabAdapter.VersionTabHolder> {

    private static final int COLOR_SELECTED = 0xfffb7299;
    private static final int COLOR_NORMAL = 0xbeebe0e2;

    private final String[] versions;
    public int selectedIndex = 0;
    private OnTabClickListener listener;

    public interface OnTabClickListener {
        void onTabClick(int index);
    }

    public VersionTabAdapter(String[] versions) {
        this.versions = versions;
    }

    public void setOnTabClickListener(OnTabClickListener listener) {
        this.listener = listener;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void setSelectedIndex(int index) {
        int previous = this.selectedIndex;
        this.selectedIndex = index;
        notifyItemChanged(previous);
        notifyItemChanged(index);
    }

    public String getItem(int index) {
        if (index < 0 || versions == null || index >= versions.length) return null;
        return versions[index];
    }

    @NonNull
    @Override
    public VersionTabHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.cell_season_tab, parent, false);
        return new VersionTabHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VersionTabHolder holder, int position) {
        if (position < 0 || versions == null || position >= versions.length) return;

        holder.tabText.setText(versions[position]);
        boolean selected = position == selectedIndex;
        holder.tabText.setTextColor(selected ? COLOR_SELECTED : COLOR_NORMAL);
        holder.tabText.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);

        holder.tabText.setOnClickListener(v -> {
            if (listener != null && position != selectedIndex) {
                listener.onTabClick(position);
            }
        });
    }

    @Override
    public int getItemCount() {
        return versions != null ? versions.length : 0;
    }

    public static class VersionTabHolder extends RecyclerView.ViewHolder {
        final TextView tabText;

        public VersionTabHolder(@NonNull View itemView) {
            super(itemView);
            tabText = itemView.findViewById(R.id.tab_text);
        }
    }
}
