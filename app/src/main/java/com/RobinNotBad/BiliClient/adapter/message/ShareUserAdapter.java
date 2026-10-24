package com.RobinNotBad.BiliClient.adapter.message;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.util.GlideUtil;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * 「分享给」横向头像列表。
 *
 * <p>与 PiliPlus 的 SharePanel 一致：头像 + 昵称，多选，选中后盖一层半透明粉色圆圈 + 对勾。
 * 数据只来自最近会话与「更多」选人，不在这里发请求。
 */
public class ShareUserAdapter extends RecyclerView.Adapter<ShareUserAdapter.Holder> {

    /** 一个可分享的对象（私信会话/关注用户） */
    public static class Target {
        public final long mid;
        public final String name;
        public final String avatar;
        public boolean selected;

        public Target(long mid, String name, String avatar) {
            this.mid = mid;
            this.name = name == null ? "" : name;
            this.avatar = avatar == null ? "" : avatar;
        }
    }

    private final List<Target> targets;
    private final Runnable onSelectionChanged;

    public ShareUserAdapter(List<Target> targets, Runnable onSelectionChanged) {
        this.targets = targets;
        this.onSelectionChanged = onSelectionChanged;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.cell_share_user, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        if (position < 0 || position >= targets.size()) return;
        Target target = targets.get(position);
        holder.name.setText(target.name);

        Glide.with(BiliTerminal.context)
                .asDrawable()
                .load(GlideUtil.url(target.avatar))
                .transition(GlideUtil.getTransitionOptions())
                .placeholder(R.mipmap.akari)
                .apply(RequestOptions.circleCropTransform())
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .into(holder.avatar);

        holder.check.setVisibility(target.selected ? View.VISIBLE : View.GONE);
        holder.itemView.setOnClickListener(view -> {
            target.selected = !target.selected;
            int current = holder.getAdapterPosition();
            if (current != RecyclerView.NO_POSITION) notifyItemChanged(current);
            if (onSelectionChanged != null) onSelectionChanged.run();
        });
    }

    @Override
    public int getItemCount() {
        return targets == null ? 0 : targets.size();
    }

    /** 当前选中的会话（发送时用） */
    public ArrayList<Target> getSelected() {
        ArrayList<Target> selected = new ArrayList<>();
        if (targets != null) {
            for (Target target : targets) if (target.selected) selected.add(target);
        }
        return selected;
    }

    public boolean hasSelection() {
        if (targets != null) {
            for (Target target : targets) if (target.selected) return true;
        }
        return false;
    }

    public static class Holder extends RecyclerView.ViewHolder {
        final ImageView avatar;
        final TextView name;
        final TextView check;

        public Holder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.share_avatar);
            name = itemView.findViewById(R.id.share_name);
            check = itemView.findViewById(R.id.share_check);
        }
    }
}
