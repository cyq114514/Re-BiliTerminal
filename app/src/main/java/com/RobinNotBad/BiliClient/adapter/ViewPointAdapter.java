package com.RobinNotBad.BiliClient.adapter;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.listener.OnItemClickListener;
import com.RobinNotBad.BiliClient.model.ViewPoint;
import com.RobinNotBad.BiliClient.ui.widget.PlayerChipStyle;
import com.RobinNotBad.BiliClient.util.StringUtil;
import com.RobinNotBad.BiliClient.util.ToolsUtil;

import java.util.List;

public class ViewPointAdapter extends RecyclerView.Adapter<ViewPointAdapter.ViewPointHolder> {
    /**
     * 旧版（1.3.9）浮层样式：条目换成 {@code cell_episode} 胶囊按钮，着色见
     * {@link PlayerChipStyle#applyLegacyButton}。默认关闭 = 现版 chip。
     */
    private boolean legacyStyle;
    private List<ViewPoint> viewPoints;
    public OnItemClickListener listener;
    private int currentPosition = -1;
    private int lastCurrentIndex = -1;

    @SuppressLint("NotifyDataSetChanged")
    public void setData(List<ViewPoint> viewPoints) {
        this.viewPoints = viewPoints;
        notifyDataSetChanged();
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void setLegacyStyle(boolean legacyStyle) {
        this.legacyStyle = legacyStyle;
    }

    public void updateCurrentPosition(int positionInSeconds) {
        if (this.currentPosition == positionInSeconds) {
            return;
        }

        this.currentPosition = positionInSeconds;

        int newCurrentIndex = getCurrentSegmentIndex();
        if (newCurrentIndex != lastCurrentIndex) {
            int oldIndex = lastCurrentIndex;
            lastCurrentIndex = newCurrentIndex;

            if (oldIndex != -1 && oldIndex < getItemCount()) {
                notifyItemChanged(oldIndex);
            }
            if (newCurrentIndex != -1 && newCurrentIndex < getItemCount()) {
                notifyItemChanged(newCurrentIndex);
            }
        }
    }

    private int getCurrentSegmentIndex() {
        if (viewPoints == null || currentPosition < 0) {
            return -1;
        }
        for (int i = 0; i < viewPoints.size(); i++) {
            ViewPoint vp = viewPoints.get(i);
            if (currentPosition >= vp.from && currentPosition < vp.to) {
                return i;
            }
        }
        return -1;
    }

    @NonNull
    @Override
    public ViewPointHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(legacyStyle ? R.layout.cell_episode : R.layout.cell_player_chip, parent, false);
        if (!legacyStyle) ToolsUtil.applyPressScale(view);
        return new ViewPointHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewPointHolder holder, int position) {
        if (position < 0 || viewPoints == null || position >= viewPoints.size())
            return;
        if (listener != null) {
            holder.listener = listener;
        }
        holder.bind(position);
    }

    @Override
    public int getItemCount() {
        return viewPoints != null ? viewPoints.size() : 0;
    }

    public class ViewPointHolder extends RecyclerView.ViewHolder {
        private OnItemClickListener listener;
        private final TextView chip;

        public ViewPointHolder(View view) {
            super(view);
            chip = itemView.findViewById(legacyStyle ? R.id.btn : R.id.chip_text);
        }

        void bind(int currentIndex) {
            if (currentIndex < 0 || viewPoints == null || currentIndex >= viewPoints.size())
                return;
            ViewPoint viewPoint = viewPoints.get(currentIndex);
            String timeStr = StringUtil.toTime(viewPoint.from) + "-" + StringUtil.toTime(viewPoint.to);
            chip.setText(viewPoint.content + "\n" + timeStr);

            boolean isCurrent = currentPosition >= viewPoint.from && currentPosition < viewPoint.to;
            if (legacyStyle) {
                PlayerChipStyle.applyLegacyButton(chip, isCurrent);
            } else {
                PlayerChipStyle.apply(chip, isCurrent);
            }

            chip.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onItemClick(currentIndex);
                }
            });
        }
    }
}