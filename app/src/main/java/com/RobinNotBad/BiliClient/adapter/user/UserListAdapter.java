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

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.user.info.UserInfoActivity;
import com.RobinNotBad.BiliClient.model.UserInfo;
import com.RobinNotBad.BiliClient.ui.theme.ThemeApplier;
import com.RobinNotBad.BiliClient.util.GlideUtil;
import com.RobinNotBad.BiliClient.util.StringUtil;

import java.util.List;

//关注列表
//2023-08-29

public class UserListAdapter extends RecyclerView.Adapter<UserListAdapter.Holder> {

    final Context context;
    final List<UserInfo> userList;
    //互关徽章只在查看自己的关注/粉丝列表时显示（看别人的列表 attribute 语义是“与对方互关”）
    private boolean showMutualBadge = false;

    public UserListAdapter(Context context, List<UserInfo> userList) {
        this.context = context;
        this.userList = userList;
    }

    public void setShowMutualBadge(boolean show) {
        this.showMutualBadge = show;
    }

    /** 「分享至消息 → 更多」选人模式：只回传选中的用户，不打开用户主页 */
    public interface OnUserPickListener {
        void onUserPick(UserInfo user);
    }

    private OnUserPickListener onUserPickListener;

    public void setOnUserPickListener(OnUserPickListener listener) {
        this.onUserPickListener = listener;
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

        //与 FollowGroupAdapter 同根因：互关徽章底是 @drawable/background_badge，
        //RecyclerView 行不在 setContentView 那次 ThemeApplier.apply 的覆盖范围内
        ThemeApplier.apply(holder.itemView);

        holder.name.setText(user.name);
        //非 vip 必须恢复主题默认色（textwhite #ebe0e2，不能显式设纯白，否则普通用户全部变色）；
        //vip 分支统一走 parseVipColor（坏色值兜底白色），此前缺 else 导致复用后残留上一条 vip 色
        if (user.vip_nickname_color != null && !user.vip_nickname_color.isEmpty()) {
            holder.name.setTextColor(StringUtil.parseVipColor(user.vip_nickname_color));
        } else {
            holder.name.setTextColor(holder.defaultNameColor);
        }
        holder.desc.setText(user.sign);

        //互关徽章：Holder 复用必须每次重置显隐，非互关项不能残留上一条的徽章。
        //UpListAdapter（详情页联合投稿列表）复用本 Holder 但 cell_up_list 没有 userBadge，
        //badge 为 null——必须判空，否则联合投稿视频一进详情页就 NPE（CustomLinearManager
        //会兜成"列表报错"，列表内容直接丢失）
        if (holder.badge != null) {
            holder.badge.setVisibility(showMutualBadge && user.mutualFollow ? View.VISIBLE : View.GONE);
        }

        boolean hasAvatar = user.avatar != null && !user.avatar.isEmpty();
        if (!hasAvatar) {
            holder.avatar.setVisibility(View.GONE);
        } else {
            //统一走 requestRound：内含宿主判活 + url() 拼接 + circleCrop + transition + RGB_565，
            //与 ElectricUserAdapter 等头像列表同一条路径；此前这里每项都新建一份 RequestOptions
            GlideUtil.requestRound(holder.avatar, user.avatar, R.mipmap.akari);
            holder.avatar.setVisibility(View.VISIBLE);
        }
        //setSingleLine 每次调用都会 requestLayout：关注列表每页 20 条、签名长短不一，
        //每次 bind 都切一次会在低端机上叠成布局峰值。只在状态真的变化时改
        if (holder.singleLineDesc != hasAvatar) {
            holder.desc.setSingleLine(hasAvatar);
            holder.singleLineDesc = hasAvatar;
        }

        if (onUserPickListener != null) {
            holder.itemView.setOnClickListener(view -> onUserPickListener.onUserPick(user));
        } else if (user.mid != -1) {
            holder.itemView.setOnClickListener(view -> {
                Intent intent = new Intent()
                        .setClass(context, UserInfoActivity.class)
                        .putExtra("mid", user.mid);
                context.startActivity(intent);
            });
        } else {
            //复用 Holder 时必须清掉上一条数据的点击回调
            holder.itemView.setOnClickListener(null);
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
        final TextView badge;
        //构造时捕获 XML/主题默认色，供非 vip 复用恢复
        final int defaultNameColor;
        //userDesc 当前是否单行：按布局实际值初始化（cell_user_list 默认 singleLine，
        //复用本 Holder 的 cell_up_list 以自身 maxLines 为准），bind 只切真实变化
        boolean singleLineDesc;

        public Holder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.userName);
            desc = itemView.findViewById(R.id.userDesc);
            avatar = itemView.findViewById(R.id.userAvatar);
            badge = itemView.findViewById(R.id.userBadge);
            defaultNameColor = name.getTextColors().getDefaultColor();
            singleLineDesc = desc.getMaxLines() == 1;
        }
    }
}
