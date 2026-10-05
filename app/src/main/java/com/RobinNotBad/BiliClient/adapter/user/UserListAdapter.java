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
import com.RobinNotBad.BiliClient.model.UserInfo;
import com.RobinNotBad.BiliClient.util.GlideUtil;
import com.RobinNotBad.BiliClient.util.StringUtil;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;

import java.util.List;

//关注列表
//2023-08-29

public class UserListAdapter extends RecyclerView.Adapter<UserListAdapter.Holder> {

    final Context context;
    final List<UserInfo> userList;

    public UserListAdapter(Context context, List<UserInfo> userList) {
        this.context = context;
        this.userList = userList;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(this.context).inflate(R.layout.cell_user_list, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        if (position < 0 || position >= userList.size())
            return;
        UserInfo user = userList.get(position);
        if (user == null)
            return;

        holder.name.setText(user.name);
        //非 vip 必须恢复主题默认色（textwhite #ebe0e2，不能显式设纯白，否则普通用户全部变色）；
        //vip 分支统一走 parseVipColor（坏色值兜底白色），此前缺 else 导致复用后残留上一条 vip 色
        if (user.vip_nickname_color != null && !user.vip_nickname_color.isEmpty()) {
            holder.name.setTextColor(StringUtil.parseVipColor(user.vip_nickname_color));
        } else {
            holder.name.setTextColor(holder.defaultNameColor);
        }
        holder.desc.setText(user.sign);

        if (user.avatar == null || user.avatar.isEmpty()) {
            holder.avatar.setVisibility(View.GONE);
            holder.desc.setSingleLine(false);
        } else {
            Glide.with(BiliTerminal.context).asDrawable().load(GlideUtil.url(user.avatar))
                    .transition(GlideUtil.getTransitionOptions())
                    .placeholder(R.mipmap.akari)
                    .apply(RequestOptions.circleCropTransform())
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .into(holder.avatar);
            holder.avatar.setVisibility(View.VISIBLE);
            holder.desc.setSingleLine(true);
        }

        if (user.mid != -1) {
            holder.itemView.setOnClickListener(view -> {
                Intent intent = new Intent()
                        .setClass(context, UserInfoActivity.class)
                        .putExtra("mid", user.mid);
                context.startActivity(intent);
            });
        }
    }

    @Override
    public int getItemCount() {
        return userList != null ? userList.size() : 0;
    }

    public static class Holder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView desc;
        final ImageView avatar;
        //构造时捕获 XML/主题默认色，供非 vip 复用恢复
        final int defaultNameColor;

        public Holder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.userName);
            desc = itemView.findViewById(R.id.userDesc);
            avatar = itemView.findViewById(R.id.userAvatar);
            defaultNameColor = name.getTextColors().getDefaultColor();
        }
    }
}
