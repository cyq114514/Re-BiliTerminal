package com.RobinNotBad.BiliClient.adapter.message;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.user.info.UserInfoActivity;
import com.RobinNotBad.BiliClient.adapter.video.VideoCardHolder;
import com.RobinNotBad.BiliClient.api.ReplyApi;
import com.RobinNotBad.BiliClient.model.MessageCard;
import com.RobinNotBad.BiliClient.model.Reply;
import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.util.GlideUtil;
import com.RobinNotBad.BiliClient.util.MsgLinkParser;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.StringUtil;
import com.RobinNotBad.BiliClient.util.TerminalContext;
import com.RobinNotBad.BiliClient.util.ToolsUtil;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;

import java.text.SimpleDateFormat;

public class NoticeHolder extends RecyclerView.ViewHolder {
    public final LinearLayout avaterList;
    public final TextView action;
    public final TextView pubdate;
    public final ConstraintLayout extraCard;
    public final View itemView;

    public NoticeHolder(@NonNull View itemView) {
        super(itemView);
        this.itemView = itemView;
        avaterList = itemView.findViewById(R.id.avatar_list);
        action = itemView.findViewById(R.id.action);
        pubdate = itemView.findViewById(R.id.pubdate);
        extraCard = itemView.findViewById(R.id.extraCard);
    }

    @SuppressLint("SetTextI18n")
    public void showMessage(MessageCard message, Context context) {
        avaterList.removeAllViews();
        if (message.user.isEmpty()) avaterList.setVisibility(View.GONE);
        else avaterList.setVisibility(View.VISIBLE);
        for (int i = 0; i < message.user.size(); i++) {
            ImageView imageView = new ImageView(context);
            Glide.with(BiliTerminal.context)
                    .asDrawable()
                    .load(GlideUtil.url(message.user.get(i).avatar))
                    .transition(GlideUtil.getTransitionOptions())
                    .placeholder(R.mipmap.akari)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .apply(RequestOptions.circleCropTransform())
                    .into(imageView);
            imageView.setLayoutParams(new ViewGroup.LayoutParams(ToolsUtil.dp2px(32), ToolsUtil.dp2px(32)));
            imageView.setLeft(ToolsUtil.dp2px(3));
            int finalI = i;
            imageView.setOnClickListener(view1 -> {
                Intent intent = new Intent();
                intent.setClass(context, UserInfoActivity.class);
                intent.putExtra("mid", message.user.get(finalI).mid);
                context.startActivity(intent);
            });
            avaterList.addView(imageView);

            TextView nickView = new TextView(context);
            nickView.setText(message.user.get(i).name);
            nickView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            nickView.setGravity(Gravity.CENTER_VERTICAL);
            nickView.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ToolsUtil.dp2px(32)));
            avaterList.addView(nickView);

            //这个View什么都没有，用来当间隔的
            View view = new View(context);
            view.setLayoutParams(new ViewGroup.LayoutParams(ToolsUtil.dp2px(3), ToolsUtil.dp2px(32)));
            avaterList.addView(view);
        }

        if (message.timeStamp != 0) {
            @SuppressLint("SimpleDateFormat") SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
            pubdate.setText(sdf.format(message.timeStamp * 1000));
        } else pubdate.setText(message.timeDesc);

        action.setText(message.content);
        StringUtil.setCopy(action);

        if (message.videoCard != null) {
            VideoCard childVideoCard = message.videoCard;
            VideoCardHolder holder = new VideoCardHolder(View.inflate(context, R.layout.cell_dynamic_video, extraCard));
            holder.showVideoCard(childVideoCard, context);
            holder.itemView.findViewById(R.id.videoCardView).setOnClickListener(view ->
                    TerminalContext.getInstance().enterVideoDetailPage(context, 0, childVideoCard.bvid)
            );
        }
        if (message.replyInfo != null || message.dynamicInfo != null) {
            Reply childReply = message.replyInfo != null ? message.replyInfo : message.dynamicInfo;
            ReplyCardHolder holder = new ReplyCardHolder(View.inflate(context, R.layout.cell_message_reply, extraCard));
            holder.showReplyCard(childReply);
            holder.itemView.findViewById(R.id.cardView).setOnClickListener(view -> {
                try {
                    // 所有消息类型统一用 contentUri 导航（完全参照 PiliPlus URI 路由）
                    long seekReply = -1;
                    if ("reply".equals(message.itemType) || message.getType == MessageCard.GET_TYPE_REPLY) {
                        seekReply = message.rootId == 0 ? message.sourceId : message.rootId;
                    }
                    navigateByUri(context, message, seekReply);
                } catch (Exception e) {
                    MsgUtil.err("跳转出错？", e);
                }
            });
        }
    }

    /**
     * 用 B站消息的 uri / native_uri 判断内容类型并跳转。
     *
     * <p>解析规则集中在 {@link MsgLinkParser}。旧实现只认 {@code /video/BV}、{@code t.bilibili.com/}、
     * {@code bilibili://opus/}，漏了「回复我的」实际给出的
     * {@code https://www.bilibili.com/opus/<dynId>#reply<rpid>}；解析失败后回退到
     * {@code subjectId > 1亿}，但实测 business_id=11 的 subject_id 是<b>评论区 oid</b>
     * （如 413634671），拿它查动态只能得到 DYNAMIC_TYPE_NONE 空壳，
     * 于是 comment_id=0，评论接口回 invalid oid。
     */
    private static void navigateByUri(Context context, MessageCard msg, long seekReply) {
        MsgLinkParser.Target target = MsgLinkParser.parse(
                msg.contentUri, msg.nativeUri, msg.businessId, msg.subjectId);
        // 子评论 rpid 优先作为 seek：实测 reply/main 只有收到子评论 rpid，才会把它排进
        // 主评论预览的第一位；传主评论 rpid / source_id 时预览里没有这条子评论，
        // 表现就是"跳到主评论了，但看不到被回复的子评论"
        long seek = target.childRpid > 0 ? target.childRpid : seekReply;
        switch (target.type) {
            case MsgLinkParser.TYPE_VIDEO_BVID:
                TerminalContext.getInstance().enterVideoDetailPage(context, 0, target.bvid, null, seek);
                return;
            case MsgLinkParser.TYPE_VIDEO_AID:
                TerminalContext.getInstance().enterVideoDetailPage(context, target.id, null, null, seek);
                return;
            case MsgLinkParser.TYPE_DYNAMIC:
                //动态链路额外把子评论 rpid 传下去，评论列表据此高亮目标行
                TerminalContext.getInstance().enterDynamicDetailPage(context, target.id, 0, seek, target.childRpid);
                return;
            case MsgLinkParser.TYPE_ARTICLE:
                TerminalContext.getInstance().enterArticleDetailPage(context, target.id, seek);
                return;
            default:
                break;
        }
        // 最后兜底：视频类消息可以直接用卡片里的 bvid
        if ("video".equals(msg.itemType) && msg.videoCard != null && !TextUtils.isEmpty(msg.videoCard.bvid)) {
            TerminalContext.getInstance().enterVideoDetailPage(context, 0, msg.videoCard.bvid);
            return;
        }
        MsgUtil.showMsg("原内容已失效，无法跳转");
    }

}
