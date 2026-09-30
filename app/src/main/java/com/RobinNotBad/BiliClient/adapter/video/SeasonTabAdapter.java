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
import com.RobinNotBad.BiliClient.model.Bangumi;

import java.util.ArrayList;

/**
 * 番剧详情页"选集"区的季 tab 行（横向滚动）。
 * 数据源是 pgc/view/web/season 响应的 seasons 数组（同系列所有季），与官方选集 tab 同源；
 * 交互对齐 PiliPlus 的 EpisodePanel：tab 数 > 1 才显示整行（显示与否由页面控制），
 * 选中 tab 粉色加粗（#FB7299 与全 app 高亮色一致）。
 */
public class SeasonTabAdapter extends RecyclerView.Adapter<SeasonTabAdapter.SeasonTabHolder> {

    private static final int COLOR_SELECTED = 0xfffb7299;
    private static final int COLOR_NORMAL = 0xbeebe0e2; //默认文字带 75% 透明度，弱化未选中 tab

    private final ArrayList<Bangumi.Season> seasonList;
    public int selectedIndex = 0;
    private OnTabClickListener listener;

    public interface OnTabClickListener {
        void onTabClick(int index);
    }

    public SeasonTabAdapter(ArrayList<Bangumi.Season> seasonList) {
        this.seasonList = seasonList;
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

    public Bangumi.Season getItem(int index) {
        if (index < 0 || seasonList == null || index >= seasonList.size()) return null;
        return seasonList.get(index);
    }

    @NonNull
    @Override
    public SeasonTabHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.cell_season_tab, parent, false);
        return new SeasonTabHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SeasonTabHolder holder, int position) {
        if (position < 0 || seasonList == null || position >= seasonList.size()) return;
        Bangumi.Season season = seasonList.get(position);

        holder.tabText.setText(season.season_title);
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
        return seasonList != null ? seasonList.size() : 0;
    }

    public static class SeasonTabHolder extends RecyclerView.ViewHolder {
        final TextView tabText;

        public SeasonTabHolder(@NonNull View itemView) {
            super(itemView);
            tabText = itemView.findViewById(R.id.tab_text);
        }
    }
}
