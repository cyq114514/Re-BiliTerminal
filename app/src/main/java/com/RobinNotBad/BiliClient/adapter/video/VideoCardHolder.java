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
import com.RobinNotBad.BiliClient.ui.theme.ThemeManager;
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
            //第三方源条目没有封面：直接占位图，不走 GlideUtil.url（它会无条件拼 B 站 CDN 后缀，
            //对空地址会生成一个必然 404 的 URL，每次绑定都白发一次请求）
            if (videoCard.cover == null || videoCard.cover.isEmpty()) {
                lastCoverUrl = null;
                cover.setImageResource(R.mipmap.placeholder);
            } else if (videoCard.type != null && videoCard.type.startsWith("third")) {
                //第三方/Bangumi 封面是原始 URL：GlideUtil.request 内部仍会调 url() 拼 B 站 CDN 后缀
                //（实测表现就是「卡片一直是占位图」），这里必须直接走 Glide
                if (!videoCard.cover.equals(lastCoverUrl)) {
                    lastCoverUrl = videoCard.cover;
                    com.bumptech.glide.Glide.with(cover)
                            .load(videoCard.cover)
                            .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.AUTOMATIC)
                            .placeholder(R.mipmap.placeholder)
                            .error(R.mipmap.placeholder)
                            .centerCrop()
                            .into(cover);
                }
            } else {
                String coverUrl = GlideUtil.url(videoCard.cover);
                if (!coverUrl.equals(lastCoverUrl)) {
                    lastCoverUrl = coverUrl;
                    //新版美学：16:10 CenterCrop + 6dp 圆角；旧版 fitCenter（requestCover 内按开关切换）
                    GlideUtil.requestCover(cover, videoCard.cover, R.mipmap.placeholder);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        switch (videoCard.type) {
            case "live":
                SpannableString sstr_live = new SpannableString("[直播]" + StringUtil.htmlToString(videoCard.title));
                sstr_live.setSpan(new ForegroundColorSpan(ThemeManager.getPrimary()), 0, 4,
                        Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
                title.setText(sstr_live);
                break;
            case "series":
                SpannableString sstr_series = new SpannableString("[系列]" + StringUtil.htmlToString(videoCard.title));
                sstr_series.setSpan(new ForegroundColorSpan(ThemeManager.getPrimary()), 0, 4,
                        Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
                title.setText(sstr_series);
                break;
            case "third_source":
                //第三方源条目：标题前加标记，避免和 B 站结果混淆（用户点进去才知道是外站）
                SpannableString sstr_third = new SpannableString("[第三方] " + StringUtil.htmlToString(videoCard.title));
                sstr_third.setSpan(new ForegroundColorSpan(ThemeManager.getPrimary()), 0, 5,
                        Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
                title.setText(sstr_third);
                break;
            case "third_bgm":
                //Bangumi 条目补漏：B 站没有的番剧，点进去再选站点片源
                SpannableString sstr_bgm = new SpannableString("[番剧库] " + StringUtil.htmlToString(videoCard.title));
                sstr_bgm.setSpan(new ForegroundColorSpan(ThemeManager.getPrimary()), 0, 5,
                        Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
                title.setText(sstr_bgm);
                break;
            default:
                title.setText(StringUtil.htmlToString(videoCard.title));
        }

    }
}
