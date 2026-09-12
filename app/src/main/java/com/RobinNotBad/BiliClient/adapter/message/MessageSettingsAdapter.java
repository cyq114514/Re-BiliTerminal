package com.RobinNotBad.BiliClient.adapter.message;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.model.message.MessageSettingItem;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.List;

public class MessageSettingsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private final Context context;
    private final List<MessageSettingItem> items;
    private final OnSettingChangedListener listener;

    public interface OnSettingChangedListener {
        void onSettingChanged(String key, boolean value);
    }

    public MessageSettingsAdapter(Context context, List<MessageSettingItem> items, OnSettingChangedListener listener) {
        this.context = context;
        this.items = items;
        this.listener = listener;
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).type;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == MessageSettingItem.TYPE_CHOOSE) {
            View view = LayoutInflater.from(context).inflate(R.layout.cell_setting_choose, parent, false);
            return new ChooseHolder(view);
        } else {
            View view = LayoutInflater.from(context).inflate(R.layout.cell_setting_switch, parent, false);
            return new SwitchHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        MessageSettingItem item = items.get(position);
        if (holder instanceof SwitchHolder) {
            ((SwitchHolder) holder).bind(item, listener);
        } else if (holder instanceof ChooseHolder) {
            ((ChooseHolder) holder).bind(item, listener);
        }
    }

    @Override
    public int getItemCount() {
        return items != null ? items.size() : 0;
    }

    static class SwitchHolder extends RecyclerView.ViewHolder {
        final TextView desc;
        final SwitchMaterial switchMaterial;

        public SwitchHolder(@NonNull View itemView) {
            super(itemView);
            desc = itemView.findViewById(R.id.setting_switch_desc);
            switchMaterial = itemView.findViewById(R.id.setting_switch);
        }

        public void bind(MessageSettingItem item, OnSettingChangedListener listener) {
            if (item.desc == null || item.desc.isEmpty()) {
                desc.setVisibility(View.GONE);
            } else {
                desc.setText(item.desc);
                desc.setVisibility(View.VISIBLE);
            }

            switchMaterial.setText(item.title);
            switchMaterial.setOnCheckedChangeListener(null);
            switchMaterial.setChecked(item.value);
            switchMaterial.setOnCheckedChangeListener((buttonView, isChecked) -> {
                item.value = isChecked;
                if (listener != null) {
                    listener.onSettingChanged(item.key, isChecked);
                }
            });
        }
    }

    static class ChooseHolder extends RecyclerView.ViewHolder {
        final RadioButton chocola;
        final RadioButton vanilla;
        final TextView name;
        final TextView desc;

        public ChooseHolder(@NonNull View itemView) {
            super(itemView);
            chocola = itemView.findViewById(R.id.setting_choose_chocola);
            vanilla = itemView.findViewById(R.id.setting_choose_vanilla);
            desc = itemView.findViewById(R.id.setting_choose_desc);
            name = itemView.findViewById(R.id.setting_choose_name);
        }

        public void bind(MessageSettingItem item, OnSettingChangedListener listener) {
            if (item.desc == null || item.desc.isEmpty()) {
                desc.setVisibility(View.GONE);
            } else {
                desc.setText(item.desc);
                desc.setVisibility(View.VISIBLE);
            }

            name.setText(item.title);

            if (item.options != null && item.options.length >= 2) {
                chocola.setText(item.options[0]);
                vanilla.setText(item.options[1]);
            }

            chocola.setOnCheckedChangeListener(null);
            vanilla.setOnCheckedChangeListener(null);

            chocola.setChecked(item.value);
            vanilla.setChecked(!item.value);

            chocola.setOnCheckedChangeListener((buttonView, isChecked) -> {
                item.value = isChecked;
                if (listener != null) {
                    listener.onSettingChanged(item.key, isChecked);
                }
            });
        }
    }
}
