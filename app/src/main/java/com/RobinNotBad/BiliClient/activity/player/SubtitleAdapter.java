package com.RobinNotBad.BiliClient.activity.player;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.listener.OnItemClickListener;
import com.RobinNotBad.BiliClient.model.SubtitleLink;
import com.RobinNotBad.BiliClient.ui.widget.PlayerChipStyle;
import com.RobinNotBad.BiliClient.util.ToolsUtil;

public class SubtitleAdapter extends RecyclerView.Adapter<SubtitleAdapter.Holder> {
    private SubtitleLink[] list;

    public OnItemClickListener listener;
    public int selectedItemIndex = 0;

    /**
     * 旧版（1.3.9）浮层样式：条目换成 {@code cell_subtitle} 按钮，着色见
     * {@link PlayerChipStyle#applyLegacyButton}。默认关闭 = 现版 chip。
     */
    private boolean legacyStyle;

    public SubtitleAdapter() {
    }

    public void setLegacyStyle(boolean legacyStyle) {
        this.legacyStyle = legacyStyle;
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void setSelectedItemIndex(int selectedItemIndex) {
        // Cancel previous selected item and set current item
        int previousSelectedIndex = this.selectedItemIndex;
        this.selectedItemIndex = selectedItemIndex;
        notifyItemChanged(previousSelectedIndex);
        notifyItemChanged(selectedItemIndex);
    }

    @SuppressLint("NotifyDataSetChanged")
    public void setData(SubtitleLink[] episodeList) {
        this.list = episodeList;
        selectedItemIndex = 0;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(legacyStyle ? R.layout.cell_subtitle : R.layout.cell_player_chip, parent, false);
        //1.3.9 的字幕条目同样有按压缩放反馈，旧版/新版都保留
        ToolsUtil.applyPressScale(view);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        if (position < 0 || list == null || position >= list.length)
            return;
        if (listener != null) {
            holder.listener = listener;
        }
        holder.bind(position, selectedItemIndex == position);
    }

    @Override
    public int getItemCount() {
        return list != null ? list.length : 0;
    }

    public class Holder extends RecyclerView.ViewHolder {

        private OnItemClickListener listener;
        private final TextView chip;

        public Holder(View view) {
            super(view);
            chip = itemView.findViewById(legacyStyle ? R.id.btn : R.id.chip_text);
        }

        void bind(int currentIndex, boolean isSelected) {
            if (currentIndex < 0 || list == null || currentIndex >= list.length)
                return;
            chip.setText(list[currentIndex].lang);
            if (legacyStyle) {
                PlayerChipStyle.applyLegacyButton(chip, isSelected);
            } else {
                PlayerChipStyle.apply(chip, isSelected);
            }
            chip.setOnClickListener(v -> {
                setSelectedItemIndex(currentIndex);
                if (listener != null) {
                    listener.onItemClick(currentIndex);
                }
            });
        }
    }
}