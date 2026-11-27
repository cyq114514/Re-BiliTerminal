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
import com.RobinNotBad.BiliClient.ui.widget.PlayerChipStyle;
import com.RobinNotBad.BiliClient.util.ToolsUtil;

import java.util.ArrayList;

public class PageSelectorAdapter extends RecyclerView.Adapter<PageSelectorAdapter.Holder> {
    private ArrayList<String> pagenames;
    private int selectedItemIndex = 0;
    private OnItemClickListener listener;

    /**
     * 旧版（1.3.9）浮层样式：条目换成 {@code cell_page_item}，着色见
     * {@link PlayerChipStyle#applyLegacyCard}。默认关闭 = 现版 chip。
     */
    private boolean legacyStyle;

    public PageSelectorAdapter() {
    }

    public void setLegacyStyle(boolean legacyStyle) {
        this.legacyStyle = legacyStyle;
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void setSelectedItemIndex(int selectedItemIndex) {
        int previousSelectedIndex = this.selectedItemIndex;
        this.selectedItemIndex = selectedItemIndex;
        notifyItemChanged(previousSelectedIndex);
        notifyItemChanged(selectedItemIndex);
    }

    @SuppressLint("NotifyDataSetChanged")
    public void setData(ArrayList<String> pagenames, int currentIndex) {
        this.pagenames = pagenames;
        this.selectedItemIndex = currentIndex;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(legacyStyle ? R.layout.cell_page_item : R.layout.cell_player_chip, parent, false);
        //1.3.9 的分P条目是普通卡片，两种样式都要按压缩放反馈（Quality/ViewPoint 的 1.3.9 实现没有，故不共用此分支）
        ToolsUtil.applyPressScale(view);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        if (position < 0 || pagenames == null || position >= pagenames.size())
            return;
        holder.bind(position, selectedItemIndex == position);
    }

    @Override
    public int getItemCount() {
        return pagenames != null ? pagenames.size() : 0;
    }

    public class Holder extends RecyclerView.ViewHolder {
        private final TextView chip;

        public Holder(View view) {
            super(view);
            chip = itemView.findViewById(legacyStyle ? R.id.page_name : R.id.chip_text);
        }

        void bind(int currentIndex, boolean isSelected) {
            if (currentIndex < 0 || pagenames == null || currentIndex >= pagenames.size())
                return;

            chip.setText("P" + (currentIndex + 1) + " " + pagenames.get(currentIndex));
            if (legacyStyle) {
                PlayerChipStyle.applyLegacyCard(chip, itemView, isSelected);
            } else {
                PlayerChipStyle.apply(chip, isSelected);
            }

            //旧版条目是"卡片包文字"，点击区在卡片上；新版 root 就是 chip 本身，两者等价
            itemView.setOnClickListener(v -> {
                setSelectedItemIndex(currentIndex);
                if (listener != null) {
                    listener.onItemClick(currentIndex);
                }
            });
        }
    }
}