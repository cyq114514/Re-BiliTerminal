package com.RobinNotBad.BiliClient.adapter;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.model.InteractionVideoData;

import java.util.List;

public class InteractionDebugAdapter extends RecyclerView.Adapter<InteractionDebugAdapter.VarHolder> {

    private final List<InteractionVideoData.InteractionHiddenVar> varList;

    public InteractionDebugAdapter(List<InteractionVideoData.InteractionHiddenVar> varList) {
        this.varList = varList;
    }

    @NonNull
    @Override
    public VarHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.cell_interaction_debug_var, parent, false);
        return new VarHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VarHolder holder, int position) {
        InteractionVideoData.InteractionHiddenVar var = varList.get(position);
        String varName = var.name != null && !var.name.isEmpty() ? var.name : var.id;
        holder.nameText.setText(varName + " (" + var.id + ")");
        holder.valueEdit.setText(String.valueOf(var.value));
        
        holder.valueEdit.removeTextChangedListener(holder.textWatcher);
        holder.textWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                try {
                    if (!s.toString().isEmpty()) {
                        long newValue = Long.parseLong(s.toString());
                        var.value = newValue;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        };
        holder.valueEdit.addTextChangedListener(holder.textWatcher);
    }

    @Override
    public int getItemCount() {
        return varList != null ? varList.size() : 0;
    }

    public static class VarHolder extends RecyclerView.ViewHolder {
        final TextView nameText;
        final EditText valueEdit;
        TextWatcher textWatcher;

        public VarHolder(@NonNull View itemView) {
            super(itemView);
            nameText = itemView.findViewById(R.id.debug_var_name);
            valueEdit = itemView.findViewById(R.id.debug_var_value);
        }
    }
}

