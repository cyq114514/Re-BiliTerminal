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

    public PageSelectorAdapter() {
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
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.cell_player_chip, parent, false);
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
            chip = itemView.findViewById(R.id.chip_text);
        }

        void bind(int currentIndex, boolean isSelected) {
            if (currentIndex < 0 || pagenames == null || currentIndex >= pagenames.size())
                return;

            chip.setText("P" + (currentIndex + 1) + " " + pagenames.get(currentIndex));
            PlayerChipStyle.apply(chip, isSelected);

            chip.setOnClickListener(v -> {
                setSelectedItemIndex(currentIndex);
                if (listener != null) {
                    listener.onItemClick(currentIndex);
                }
            });
        }
    }
}