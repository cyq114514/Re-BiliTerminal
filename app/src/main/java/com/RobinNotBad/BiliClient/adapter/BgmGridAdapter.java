package com.RobinNotBad.BiliClient.adapter;

import android.content.Context;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.api.BgmApi;
import com.RobinNotBad.BiliClient.model.bgm.BgmSubject;
import com.RobinNotBad.BiliClient.util.ClickGuard;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.util.List;

/**
 * 热门番组网格（列数与单格像素宽度由页面算好后传进来）。
 *
 * <p><b>图片策略对齐 Kazumi</b>：Kazumi 的 {@code BangumiCardV} 取的是
 * {@code images['large']}（原图），再用 {@code memCacheWidth = 显示宽 × devicePixelRatio}
 * 让解码器按目标像素出一张——既清晰又不爆内存。这里照做：
 * ①取图顺序 large → common → grid；
 * ②向图片代理按目标像素宽请求（{@code &w=cellPx}）；
 * ③Glide {@code override(cellPx, cellPx/0.65)} 显式指定解码尺寸。
 *
 * <p>之前用 {@code images.grid}（100x100 方图）是这个页面糊掉的根本原因。
 */
public class BgmGridAdapter extends RecyclerView.Adapter<BgmGridAdapter.Holder> {

    /** 与布局里的 coverRatio 保持一致（宽/高），用于算解码高度 */
    public static final float COVER_RATIO = 0.65f;

    public interface OnItemClickListener {
        void onItemClick(BgmSubject subject);
    }

    private final Context context;
    private final List<BgmSubject> list;
    private final int spanCount;
    private final int cellWidthPx;
    private OnItemClickListener listener;

    public BgmGridAdapter(Context context, List<BgmSubject> list, int spanCount, int cellWidthPx) {
        this.context = context;
        this.list = list;
        this.spanCount = spanCount;
        this.cellWidthPx = Math.max(120, cellWidthPx);
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    @SuppressWarnings("NotifyDataSetChanged")
    public void update(List<BgmSubject> data) {
        list.clear();
        list.addAll(data);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.cell_bgm_grid, parent, false);
        Holder holder = new Holder(view);
        //字号随列数自适应：2 列（手表）格子大、字给 13sp；3 列以上给 12sp 防挤压
        holder.title.setTextSize(TypedValue.COMPLEX_UNIT_SP, spanCount <= 2 ? 13 : 12);
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        final BgmSubject subject = list.get(position);
        holder.title.setText(subject.title());

        Glide.with(context)
                .load(BgmApi.imageFor(bestCover(subject), cellWidthPx))
                .override(cellWidthPx, Math.round(cellWidthPx / COVER_RATIO))
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .placeholder(R.mipmap.placeholder)
                .centerCrop()
                .into(holder.cover);

        holder.itemView.setOnClickListener(view -> ClickGuard.protect(view, () -> {
            if (listener != null) listener.onItemClick(subject);
        }));
    }

    /** 由清晰到糊依次回退：large（原图）→ common(400) → grid(100，仅兜底) */
    public static String bestCover(BgmSubject subject) {
        if (subject.coverLarge != null && !subject.coverLarge.isEmpty()) return subject.coverLarge;
        if (subject.coverCommon != null && !subject.coverCommon.isEmpty()) return subject.coverCommon;
        return subject.coverGrid == null ? "" : subject.coverGrid;
    }

    @Override
    public int getItemCount() {
        return list.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ImageView cover;
        final TextView title;

        Holder(@NonNull View itemView) {
            super(itemView);
            cover = itemView.findViewById(R.id.img_cover);
            title = itemView.findViewById(R.id.text_title);
        }
    }
}
