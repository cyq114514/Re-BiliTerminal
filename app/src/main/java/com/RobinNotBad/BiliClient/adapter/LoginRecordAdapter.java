package com.RobinNotBad.BiliClient.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.model.LoginRecord;

import java.util.List;

public class LoginRecordAdapter extends RecyclerView.Adapter<LoginRecordAdapter.ViewHolder> {

    private final Context context;
    private final List<LoginRecord> recordList;

    public LoginRecordAdapter(Context context, List<LoginRecord> recordList) {
        this.context = context;
        this.recordList = recordList;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.cell_login_record, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        if (position < 0 || position >= recordList.size()) {
            return;
        }
        LoginRecord record = recordList.get(position);
        if (record == null) {
            return;
        }

        holder.deviceName.setText(record.deviceName);
        holder.loginType.setText("登录方式：" + record.loginType);
        holder.loginTime.setText("登录时间：" + record.loginTime);
        holder.location.setText(record.location);
        holder.ip.setText(record.ip);
    }

    @Override
    public int getItemCount() {
        return recordList != null ? recordList.size() : 0;
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView deviceName;
        final TextView loginType;
        final TextView loginTime;
        final TextView location;
        final TextView ip;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            deviceName = itemView.findViewById(R.id.device_name);
            loginType = itemView.findViewById(R.id.login_type);
            loginTime = itemView.findViewById(R.id.login_time);
            location = itemView.findViewById(R.id.location);
            ip = itemView.findViewById(R.id.ip);
        }
    }
}

