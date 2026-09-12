package com.RobinNotBad.BiliClient.adapter.article;

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
import com.RobinNotBad.BiliClient.model.Opus;
import com.RobinNotBad.BiliClient.util.GlideUtil;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.TerminalContext;
import com.RobinNotBad.BiliClient.util.ToolsUtil;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;

import java.util.ArrayList;

public class OpusAdapter extends RecyclerView.Adapter<OpusAdapter.OpusHolder> {

    Context context;
    ArrayList<Opus> opusList;

    public OpusAdapter(Context context, ArrayList<Opus> opusList) {
        this.context = context;
        this.opusList = opusList;
    }

    @NonNull
    @Override
    public OpusHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(this.context).inflate(R.layout.cell_opus, parent, false);
        return new OpusHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull OpusHolder holder, int position) {
        if (position < 0 || position >= opusList.size())
            return;
        Opus opus = opusList.get(position);
        if (opus == null)
            return;

        holder.favTimeText.setText(opus.pubTime);
        holder.titleText.setText(opus.title);

        String coverUrl = GlideUtil.url(opus.cover);
        if (!coverUrl.equals(holder.lastCoverUrl)) {
            holder.lastCoverUrl = coverUrl;
            Glide.with(BiliTerminal.context).load(coverUrl)
                    .transition(GlideUtil.getTransitionOptions())
                    .placeholder(R.mipmap.placeholder)
                    .apply(RequestOptions.bitmapTransform(new RoundedCorners(ToolsUtil.dp2px(5))))
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .into(holder.coverView);
        }

        if (opus.content != null && opus.content.equals("内容失效")) {
            holder.itemView.setOnClickListener(v -> MsgUtil.showMsg("内容失效，无法打开"));
        } else {
            holder.itemView
                    .setOnClickListener(v -> TerminalContext.getInstance().enterOpusDetailPage(context, opus.id));
        }
    }

    @Override
    public void onViewRecycled(@NonNull OpusHolder holder) {
        holder.lastCoverUrl = null;
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() {
        return opusList != null ? opusList.size() : 0;
    }

    public static class OpusHolder extends RecyclerView.ViewHolder {
        ImageView coverView;
        TextView favTimeText;
        TextView titleText;
        String lastCoverUrl;

        public OpusHolder(View itemView) {
            super(itemView);
            coverView = itemView.findViewById(R.id.img_cover);
            favTimeText = itemView.findViewById(R.id.text_favTime);
            titleText = itemView.findViewById(R.id.text_title);
        }

    }
}