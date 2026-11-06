package com.RobinNotBad.BiliClient.adapter.source;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.RobinNotBad.BiliClient.source.model.SourceSubject;
import com.RobinNotBad.BiliClient.util.ClickGuard;

import java.util.ArrayList;
import java.util.List;

/**
 * 第三方源搜索结果。
 *
 * <p>结果里<b>没有封面</b>：Animeko 的条目格式只约定「名称 + 详情链接」，站点列表页的图片
 * 不在规则里，硬凑一张图只能靠猜。所以统一用占位图，不给用户「这站有封面但没加载出来」的错觉。
 */
public class SourceSubjectAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_SUBJECT = 1;

    public interface OnItemClickListener {
        void onItemClick(SourceSubject subject);
    }

    private final List<SourceSubject> list = new ArrayList<>();
    private OnItemClickListener listener;
    /** 站点没给封面时用这张（调用方传 Bangumi 封面），避免整列都是占位图 */
    private String fallbackCover = "";
    /**
     * 列表头部视图（源匹配页的番剧信息区）。
     *
     * <p>做成第一个 item 而不是页面的固定节点：手表小屏上固定头会吃掉一半可视区，
     * 且列表往下翻时它一动不动。做成 item 后跟着列表一起滚走。
     *
     * <p>必须在 {@code setAdapter()} 之前设置，之后不再替换（换头会让固定 View 实例
     * 在复用池里重新 attach，存在父节点冲突的风险，且没必要）。
     */
    private View headerView;

    public void setHeaderView(View view) {
        this.headerView = view;
    }

    private int headerCount() {
        return headerView == null ? 0 : 1;
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void setFallbackCover(String url) {
        this.fallbackCover = url == null ? "" : url;
    }

    public void clear() {
        int size = list.size();
        list.clear();
        if (size > 0) notifyItemRangeRemoved(headerCount(), size);
    }

    public void addAll(List<SourceSubject> data) {
        if (data == null || data.isEmpty()) return;
        int start = headerCount() + list.size();
        list.addAll(data);
        //局部插入而不是全量重绑：63 个源逐个返回时，全量重绑会触发 63 次整列表 re-bind + 重发图片请求，
        //手表上直接卡成幻灯片
        notifyItemRangeInserted(start, data.size());
    }

    public int size() {
        return list.size();
    }

    @Override
    public int getItemViewType(int position) {
        return position < headerCount() ? TYPE_HEADER : TYPE_SUBJECT;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            //头部是外部传进来的固定 View 实例。万一它还被旧父容器占着（复用池被清空后重建
            //holder 的情形），RecyclerView 的 addView 会抛「already has a parent」，先摘下来
            ViewGroup owner = (ViewGroup) headerView.getParent();
            if (owner != null) owner.removeView(headerView);
            return new HeaderHolder(headerView);
        }
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.cell_source_subject, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        //头部的内容与点击全部由 SourceMatchActivity 直接绑定，这里不需要再处理
        if (holder instanceof HeaderHolder) return;
        final Holder real = (Holder) holder;
        final SourceSubject subject = list.get(position - headerCount());
        real.title.setText(subject.title);
        StringBuilder meta = new StringBuilder();
        meta.append(subject.sourceName);
        if (subject.sourceSubjectId != null && !subject.sourceSubjectId.isEmpty()) {
            meta.append(" · ").append(subject.sourceSubjectId);
        }
        real.meta.setText(meta.toString());

        String image = subject.coverUrl == null || subject.coverUrl.isEmpty()
                ? fallbackCover : subject.coverUrl;
        //直接走 Glide：站点封面是原始 URL，GlideUtil.url() 会拼上 B 站 CDN 后缀（必然 404）
        Glide.with(real.cover)
                .load(image)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .placeholder(R.mipmap.placeholder)
                .error(R.mipmap.placeholder)
                .centerCrop()
                .into(real.cover);

        real.itemView.setOnClickListener(view -> ClickGuard.protect(view, () -> {
            if (listener != null) listener.onItemClick(subject);
        }));
    }

    @Override
    public int getItemCount() {
        return headerCount() + list.size();
    }

    static class HeaderHolder extends RecyclerView.ViewHolder {
        HeaderHolder(@NonNull View itemView) {
            super(itemView);
        }
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ImageView cover;
        final TextView title;
        final TextView meta;

        Holder(@NonNull View itemView) {
            super(itemView);
            cover = itemView.findViewById(R.id.img_cover);
            title = itemView.findViewById(R.id.text_title);
            meta = itemView.findViewById(R.id.text_meta);
        }
    }
}
