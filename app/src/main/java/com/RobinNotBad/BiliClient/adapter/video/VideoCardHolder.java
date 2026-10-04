package com.RobinNotBad.BiliClient.adapter.video;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.util.GlideUtil;
import com.RobinNotBad.BiliClient.util.StringUtil;

public class VideoCardHolder extends RecyclerView.ViewHolder {
    TextView title, upName, viewCount;
    ImageView cover;
    String lastCoverUrl;   //包私有：同包 Adapter 在 onViewRecycled 时复位去重标记

    public VideoCardHolder(@NonNull View itemView) {
        super(itemView);
        title = itemView.findViewById(R.id.text_title);
        upName = itemView.findViewById(R.id.text_upname);
        viewCount = itemView.findViewById(R.id.text_viewcount);
        cover = itemView.findViewById(R.id.img_cover);
    }

    @SuppressLint("SetTextI18n")
    public void showVideoCard(VideoCard videoCard, Context context) {
        String str_upName = videoCard.upName;
        if (str_upName == null || str_upName.isEmpty()) {
            upName.setVisibility(View.GONE);
        } else {
            //置 GONE 的分支必须有 VISIBLE 恢复：ViewHolder 复用后 UP 主名会永久消失
            upName.setVisibility(View.VISIBLE);
            upName.setText(str_upName);
        }

        String str_viewCount = videoCard.view;
        if (videoCard.progress > 0) {
            //观看进度优先展示（历史/稍后再看），覆盖播放量文案
            viewCount.setVisibility(View.VISIBLE);
            viewCount.setText("看到" + StringUtil.toTime(videoCard.progress));
        } else if (str_viewCount == null || str_viewCount.isEmpty()) {
            viewCount.setVisibility(View.GONE);
        } else {
            viewCount.setVisibility(View.VISIBLE);
            viewCount.setText(str_viewCount);
        }

        try {
            String coverUrl = GlideUtil.url(videoCard.cover);
            if (!coverUrl.equals(lastCoverUrl)) {
                lastCoverUrl = coverUrl;
                //新版美学：16:10 CenterCrop + 6dp 圆角；旧版 fitCenter（requestCover 内按开关切换）
                GlideUtil.requestCover(cover, videoCard.cover, R.mipmap.placeholder);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        switch (videoCard.type) {
            case "live":
                SpannableString sstr_live = new SpannableString("[直播]" + StringUtil.htmlToString(videoCard.title));
                sstr_live.setSpan(new ForegroundColorSpan(ContextCompat.getColor(context, R.color.bili_pink)), 0, 4,
                        Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
                title.setText(sstr_live);
                break;
            case "series":
                SpannableString sstr_series = new SpannableString("[系列]" + StringUtil.htmlToString(videoCard.title));
                sstr_series.setSpan(new ForegroundColorSpan(ContextCompat.getColor(context, R.color.bili_pink)), 0, 4,
                        Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
                title.setText(sstr_series);
                break;
            default:
                title.setText(StringUtil.htmlToString(videoCard.title));
        }

    }
}
