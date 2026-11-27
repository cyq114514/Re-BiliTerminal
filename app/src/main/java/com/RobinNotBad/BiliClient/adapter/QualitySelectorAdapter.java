package com.RobinNotBad.BiliClient.adapter;

import android.annotation.SuppressLint;
import android.view.ContextThemeWrapper;
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

public class QualitySelectorAdapter extends RecyclerView.Adapter<QualitySelectorAdapter.QualityHolder> {
    /**
     * 旧版（1.3.9）浮层样式：条目换成 {@code cell_episode} 胶囊按钮，着色见
     * {@link PlayerChipStyle#applyLegacyButton}。默认关闭 = 现版 chip。
     */
    private boolean legacyStyle;
    private String[] qualityNames;
    private int[] qualityValues;
    public OnItemClickListener listener;
    public int selectedItemIndex = 0;

    @SuppressLint("NotifyDataSetChanged")
    public void setData(String[] qualityNames, int[] qualityValues, int currentQuality) {
        this.qualityNames = qualityNames;
        this.qualityValues = qualityValues;
        this.selectedItemIndex = -1;

        for (int i = 0; i < qualityValues.length; i++) {
            if (qualityValues[i] == currentQuality) {
                this.selectedItemIndex = i;
                break;
            }
        }

        if (this.selectedItemIndex == -1 && qualityValues.length > 0) {
            this.selectedItemIndex = 0;
        }

        notifyDataSetChanged();
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void setLegacyStyle(boolean legacyStyle) {
        this.legacyStyle = legacyStyle;
    }

    public void setSelectedItemIndex(int selectedItemIndex) {
        int previousSelectedIndex = this.selectedItemIndex;
        this.selectedItemIndex = selectedItemIndex;
        notifyItemChanged(previousSelectedIndex);
        notifyItemChanged(selectedItemIndex);
    }

    @NonNull
    @Override
    public QualityHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        //cell_episode 是 MaterialButton，1.3.9 在这里用 BiliClient 主题包了一层 Context
        //（否则 MaterialComponents 会在部分 API 上拒绝 inflate）
        LayoutInflater inflater = legacyStyle
                ? LayoutInflater.from(new ContextThemeWrapper(parent.getContext(), R.style.Theme_BiliClient))
                : LayoutInflater.from(parent.getContext());
        View view = inflater.inflate(legacyStyle ? R.layout.cell_episode : R.layout.cell_player_chip,
                parent, false);
        if (!legacyStyle) ToolsUtil.applyPressScale(view);
        return new QualityHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull QualityHolder holder, int position) {
        if (position < 0 || qualityNames == null || position >= qualityNames.length)
            return;
        if (listener != null) {
            holder.listener = listener;
        }
        holder.bind(position, selectedItemIndex == position);
    }

    @Override
    public int getItemCount() {
        return qualityNames != null ? qualityNames.length : 0;
    }

    public class QualityHolder extends RecyclerView.ViewHolder {
        private OnItemClickListener listener;
        private final TextView chip;

        public QualityHolder(View view) {
            super(view);
            chip = itemView.findViewById(legacyStyle ? R.id.btn : R.id.chip_text);
        }

        void bind(int currentIndex, boolean isSelected) {
            if (currentIndex < 0 || qualityNames == null || currentIndex >= qualityNames.length)
                return;
            chip.setText(qualityNames[currentIndex]);
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