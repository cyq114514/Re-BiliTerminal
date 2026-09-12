package com.RobinNotBad.BiliClient.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.model.ExpLog;

import java.util.List;

public class ExpLogAdapter extends RecyclerView.Adapter<ExpLogAdapter.ViewHolder> {

    private final Context context;
    private final List<ExpLog> logList;

    public ExpLogAdapter(Context context, List<ExpLog> logList) {
        this.context = context;
        this.logList = logList;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.cell_exp_log, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        if (position < 0 || position >= logList.size()) {
            return;
        }
        ExpLog log = logList.get(position);
        if (log == null) {
            return;
        }

        String deltaText = "+" + log.delta;
        holder.delta.setText(deltaText);
        holder.reason.setText(log.reason);
        holder.time.setText(log.time);
    }

    @Override
    public int getItemCount() {
        return logList != null ? logList.size() : 0;
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView delta;
        final TextView reason;
        final TextView time;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            delta = itemView.findViewById(R.id.delta);
            reason = itemView.findViewById(R.id.reason);
            time = itemView.findViewById(R.id.time);
        }
    }
}

