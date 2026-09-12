package com.RobinNotBad.BiliClient.adapter.user;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.user.info.UserInfoActivity;
import com.RobinNotBad.BiliClient.model.MedalInfo;
import com.RobinNotBad.BiliClient.util.GlideUtil;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;

import java.util.List;

public class MedalListAdapter extends RecyclerView.Adapter<MedalListAdapter.Holder> {

    final Context context;
    final List<MedalInfo> medalList;

    public MedalListAdapter(Context context, List<MedalInfo> medalList) {
        this.context = context;
        this.medalList = medalList;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(this.context).inflate(R.layout.cell_medal_list, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        if (position < 0 || position >= medalList.size())
            return;
        MedalInfo medal = medalList.get(position);
        if (medal == null)
            return;

        holder.medalName.setText(medal.medal_name);
        holder.targetName.setText(medal.target_name);
        
        String levelText = "Lv." + medal.level;
        if (medal.wearing_status == 1) {
            levelText += " (佩戴中)";
        }
        holder.level.setText(levelText);
        
        String intimacyText = "亲密度: " + medal.intimacy;
        if (medal.next_intimacy > 0) {
            intimacyText += " / " + medal.next_intimacy;
        }
        holder.intimacy.setText(intimacyText);
        
        if (medal.target_icon != null && !medal.target_icon.isEmpty()) {
            Glide.with(BiliTerminal.context).asDrawable().load(GlideUtil.url(medal.target_icon))
                    .transition(GlideUtil.getTransitionOptions())
                    .placeholder(R.mipmap.akari)
                    .apply(RequestOptions.circleCropTransform())
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .into(holder.avatar);
            holder.avatar.setVisibility(View.VISIBLE);
        } else {
            holder.avatar.setVisibility(View.GONE);
        }
        
        if (medal.target_id > 0) {
            holder.itemView.setOnClickListener(view -> {
                Intent intent = new Intent()
                        .setClass(context, UserInfoActivity.class)
                        .putExtra("mid", medal.target_id);
                context.startActivity(intent);
            });
        }
    }

    @Override
    public int getItemCount() {
        return medalList != null ? medalList.size() : 0;
    }

    public static class Holder extends RecyclerView.ViewHolder {
        final TextView medalName;
        final TextView targetName;
        final TextView level;
        final TextView intimacy;
        final ImageView avatar;

        public Holder(@NonNull View itemView) {
            super(itemView);
            medalName = itemView.findViewById(R.id.medalName);
            targetName = itemView.findViewById(R.id.targetName);
            level = itemView.findViewById(R.id.level);
            intimacy = itemView.findViewById(R.id.intimacy);
            avatar = itemView.findViewById(R.id.avatar);
        }
    }
}

