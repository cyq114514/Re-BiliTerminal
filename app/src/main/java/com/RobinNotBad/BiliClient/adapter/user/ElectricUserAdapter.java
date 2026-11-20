package com.RobinNotBad.BiliClient.adapter.user;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.user.info.UserInfoActivity;
import com.RobinNotBad.BiliClient.model.ElectricUser;
import com.RobinNotBad.BiliClient.util.GlideUtil;

import java.util.List;

public class ElectricUserAdapter extends RecyclerView.Adapter<ElectricUserAdapter.ViewHolder> {

    private final Context context;
    private final List<ElectricUser> userList;

    public ElectricUserAdapter(Context context, List<ElectricUser> userList) {
        this.context = context;
        this.userList = userList;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.cell_electric_user, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ElectricUser user = userList.get(position);

        holder.userName.setText(user.uname);

        //走统一入口：Glide.with(Activity context) 在页面 finishing/destroyed 后仍被
        //RecyclerView 的延迟布局任务 bind 时会抛 "You cannot start a load for a destroyed activity"
        //（线上 9912）。requestRound 内含 skipIfHostDead 判活 + url()/circleCrop/transition。
        GlideUtil.requestRound(holder.userAvatar, user.avatar, R.mipmap.akari);

        if (user.message != null && !user.message.isEmpty()) {
            holder.userMessage.setVisibility(View.VISIBLE);
            holder.userMessage.setText(user.message);
        } else {
            holder.userMessage.setVisibility(View.GONE);
        }

        holder.itemView.setOnClickListener(v -> {
            Intent intent = new Intent(context, UserInfoActivity.class);
            intent.putExtra("mid", user.pay_mid);
            context.startActivity(intent);
        });
    }

    @Override
    public int getItemCount() {
        return userList != null ? userList.size() : 0;
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView userAvatar;
        TextView userName;
        TextView userMessage;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            userAvatar = itemView.findViewById(R.id.userAvatar);
            userName = itemView.findViewById(R.id.userName);
            userMessage = itemView.findViewById(R.id.userMessage);
        }
    }
}
