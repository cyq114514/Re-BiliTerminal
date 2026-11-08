package com.RobinNotBad.BiliClient.adapter.dynamic;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.api.DynamicApi;
import com.RobinNotBad.BiliClient.util.GlideUtil;

import java.util.List;

public class RecentUpAdapter extends RecyclerView.Adapter<RecentUpAdapter.ViewHolder> {

    private final Context context;
    private List<DynamicApi.UpInfo> upList;

    public RecentUpAdapter(Context context, List<DynamicApi.UpInfo> upList) {
        this.context = context;
        this.upList = upList;
    }

    public List<DynamicApi.UpInfo> getUpList() {
        return upList;
    }

    /**
     * 换数据源并重绑。
     *
     * <p>必须支持换源：动态页手动刷新时是 {@code recentUpList = 新 List}（整体换对象），
     * 而这个 Adapter 构造时把当时的 List 引用存成字段。不换源的话外层
     * {@code notifyItemChanged(1)} 只是重新绑定、读的还是旧 List，
     * 旧 List 里的 has_update 仍是 true——就是「点刷新小红点不消失」的原因。
     */
    public void setUpList(List<DynamicApi.UpInfo> upList) {
        this.upList = upList;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.cell_up_avatar, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        DynamicApi.UpInfo upInfo = upList.get(position);
        
        GlideUtil.requestRound(holder.avatar, upInfo.face, R.mipmap.akari);
        holder.name.setText(upInfo.uname);
        
        if (upInfo.has_update) {
            holder.updateIndicator.setVisibility(View.VISIBLE);
        } else {
            holder.updateIndicator.setVisibility(View.GONE);
        }
        
        holder.itemView.setClickable(true);
        holder.itemView.setFocusable(true);
        holder.itemView.setOnClickListener(v -> {
            BiliTerminal.jumpToUser(context, upInfo.mid);
        });
        holder.avatar.setOnClickListener(v -> {
            BiliTerminal.jumpToUser(context, upInfo.mid);
        });
    }

    @Override
    public int getItemCount() {
        return upList != null ? upList.size() : 0;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView avatar;
        TextView name;
        View updateIndicator;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.avatar);
            name = itemView.findViewById(R.id.name);
            updateIndicator = itemView.findViewById(R.id.updateIndicator);
        }
    }
}
