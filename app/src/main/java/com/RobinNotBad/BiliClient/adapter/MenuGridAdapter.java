package com.RobinNotBad.BiliClient.adapter;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 新版美学主菜单网格适配器（移植自 Re-WearBili HomeScreen 的圆形描边按钮网格）。
 * 条目由 MenuActivity 按原有的排序/隐藏/登录态逻辑生成，本适配器只负责展示与回调。
 */
public class MenuGridAdapter extends RecyclerView.Adapter<MenuGridAdapter.MenuHolder> {

    public static class MenuEntry {
        public final String key;
        public final String label;
        public final int iconRes;
        public int badgeCount;

        public MenuEntry(String key, String label, int iconRes, int badgeCount) {
            this.key = key;
            this.label = label;
            this.iconRes = iconRes;
            this.badgeCount = badgeCount;
        }
    }

    public interface OnMenuClickListener {
        void onMenuClick(String key);

        //返回 true 表示已处理（目前只有"推荐"有长按行为）
        boolean onMenuLongClick(String key);
    }

    private final List<MenuEntry> entries = new ArrayList<>();
    private final OnMenuClickListener listener;

    public MenuGridAdapter(OnMenuClickListener listener) {
        this.listener = listener;
    }

    public void setEntries(List<MenuEntry> list) {
        entries.clear();
        entries.addAll(list);
        notifyDataSetChanged();
    }

    //动态/消息红点在 onResume 里刷新
    public void updateBadge(String key, int count) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).key.equals(key)) {
                entries.get(i).badgeCount = count;
                notifyItemChanged(i);
                return;
            }
        }
    }

    @NonNull
    @Override
    public MenuHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.cell_menu_grid, parent, false);
        return new MenuHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MenuHolder holder, int position) {
        final MenuEntry entry = entries.get(position);
        holder.icon.setImageResource(entry.iconRes);
        holder.label.setText(entry.label);
        if (entry.badgeCount > 0) {
            holder.badge.setVisibility(View.VISIBLE);
            holder.badge.setText(String.valueOf(entry.badgeCount));
        } else {
            holder.badge.setVisibility(View.GONE);
        }
        holder.itemView.setOnClickListener(view -> listener.onMenuClick(entry.key));
        holder.itemView.setOnLongClickListener(view -> listener.onMenuLongClick(entry.key));
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    public static class MenuHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView label;
        final TextView badge;

        MenuHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.menu_icon);
            label = itemView.findViewById(R.id.menu_label);
            badge = itemView.findViewById(R.id.menu_badge);
        }
    }
}
