package com.RobinNotBad.BiliClient.adapter.message;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.message.PrivateMsgActivity;
import com.RobinNotBad.BiliClient.activity.user.info.UserInfoActivity;
import com.RobinNotBad.BiliClient.model.PrivateMessage;
import com.RobinNotBad.BiliClient.model.PrivateMsgSession;
import com.RobinNotBad.BiliClient.model.UserInfo;
import com.RobinNotBad.BiliClient.ui.widget.RadiusBackgroundSpan;
import com.RobinNotBad.BiliClient.api.PrivateMsgApi;
import com.RobinNotBad.BiliClient.util.AdaptiveDialog;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.GlideUtil;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.HashMap;

public class PrivateMsgSessionsAdapter
        extends RecyclerView.Adapter<PrivateMsgSessionsAdapter.PrivateMsgSessionsHolder> {

    final Context context;
    final ArrayList<PrivateMsgSession> sessionsList;
    final HashMap<Long, UserInfo> userMap;
    private final int cardRoundRadius;
    private static final int BADGE_TEXT_COLOR = Color.WHITE;
    private static final int BADGE_BG_COLOR = Color.rgb(207, 75, 95);
    private static final String BADGE_TEXT = "  未读 ";
    /** 置顶/删除成功后的回调，宿主用它重拉会话列表（服务端才是唯一真相） */
    private Runnable onSessionsChanged;

    public void setOnSessionsChanged(Runnable onSessionsChanged) {
        this.onSessionsChanged = onSessionsChanged;
    }

    public PrivateMsgSessionsAdapter(Context context, ArrayList<PrivateMsgSession> sessionsList,
                                     HashMap<Long, UserInfo> userMap) {
        this.context = context;
        this.sessionsList = sessionsList;
        this.userMap = userMap;
        this.cardRoundRadius = (int) context.getResources().getDimension(R.dimen.card_round);
    }

    @NonNull
    @Override
    public PrivateMsgSessionsHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(this.context).inflate(R.layout.cell_user_list, parent, false);
        return new PrivateMsgSessionsHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PrivateMsgSessionsHolder holder, int position) {
        if (position < 0 || position >= sessionsList.size())
            return;
        PrivateMsgSession msgContent = sessionsList.get(position);
        if (msgContent == null)
            return;

        try {
            if (msgContent.content != null)
                switch (msgContent.contentType) {
                    case PrivateMessage.TYPE_TEXT:
                        holder.contentText.setText(msgContent.content.getString("content"));
                        break;
                    case PrivateMessage.TYPE_PIC:
                        holder.contentText.setText("[图片消息]");
                        break;

                    case PrivateMessage.TYPE_VIDEO:
                    case PrivateMessage.TYPE_PIC_CARD:
                    case PrivateMessage.TYPE_NOMAL_CARD:
                        holder.contentText.setText(msgContent.content.getString("title"));
                        break;

                    case PrivateMessage.TYPE_TEXT_WITH_VIDEO:
                        holder.contentText.setText(msgContent.content.getString("reply_content"));
                        break;
                    case PrivateMessage.TYPE_RETRACT:
                        holder.contentText.setText("[撤回消息]");
                        break;

                    default:
                        holder.contentText.setText("");
                }
            else
                holder.contentText.setText("");

            holder.contentText.setEllipsize(TextUtils.TruncateAt.END);

            UserInfo user = userMap != null ? userMap.get(msgContent.talkerUid) : null;
            String avatarUrl = null;
            String displayName;

            if (user != null) {
                avatarUrl = user.avatar;
                displayName = user.name;
            } else if (msgContent.talkerName != null && !msgContent.talkerName.isEmpty()) {
                avatarUrl = msgContent.talkerFace;
                displayName = msgContent.talkerName;
            } else {
                displayName = "用户_" + msgContent.talkerUid;
            }

            if (msgContent.unread > 0 && SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.PRIVATE_MSG_UNREAD_BADGE_ENABLE, false)) {
                SpannableStringBuilder nameStr = new SpannableStringBuilder(displayName);
                int nameLength = displayName.length();
                nameStr.append(BADGE_TEXT);
                nameStr.setSpan(
                        new RadiusBackgroundSpan(1, cardRoundRadius, BADGE_TEXT_COLOR, BADGE_BG_COLOR),
                        nameLength + 1, nameStr.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
                holder.nameText.setText(nameStr);
            } else {
                holder.nameText.setText(displayName);
            }

            if (avatarUrl != null && !avatarUrl.isEmpty()) {
                Glide.with(BiliTerminal.context).asDrawable().load(GlideUtil.url(avatarUrl))
                        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                        .placeholder(R.mipmap.akari)
                        .apply(RequestOptions.circleCropTransform())
                        .into(holder.avatarView);
            } else {
                holder.avatarView.setImageResource(R.mipmap.akari);
            }

            holder.itemView.setOnClickListener(view -> {
                Intent intent = new Intent(context, PrivateMsgActivity.class);
                intent.putExtra("uid", msgContent.talkerUid);
                context.startActivity(intent);
            });
            holder.itemView.setOnLongClickListener(view -> {
                showSessionMenu(msgContent);
                return true;
            });
        } catch (JSONException err) {
            Log.e("PrivateMsgUserAdapter", err.toString());
        }
    }

    @Override
    public int getItemCount() {
        return sessionsList != null ? sessionsList.size() : 0;
    }

    /**
     * 会话长按菜单：置顶/取消置顶、删除会话、查看用户主页。
     *
     * <p>原来长按直接跳用户主页；置顶/删除没有别的入口，只能收编成长按菜单，
     * 查看主页保留为菜单项（原行为没丢）。
     * 两个接口都只改服务端的会话列表（删除不清聊天记录），成功后统一重拉列表，
     * 置顶状态以服务端 top_ts 为准，本地不手工挪位置。
     */
    private void showSessionMenu(PrivateMsgSession session) {
        String[] actions = new String[]{
                session.isTop() ? "取消置顶" : "置顶会话",
                "删除会话",
                "查看用户主页"};
        new AdaptiveDialog.Builder(context)
                .setTitle("会话操作")
                .setSingleChoiceItems(actions, -1, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == 0) {
                        setSessionTop(session, !session.isTop());
                    } else if (which == 1) {
                        confirmRemoveSession(session);
                    } else {
                        Intent intent = new Intent(context, UserInfoActivity.class);
                        intent.putExtra("mid", session.talkerUid);
                        context.startActivity(intent);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmRemoveSession(PrivateMsgSession session) {
        new AdaptiveDialog.Builder(context)
                .setTitle("删除会话")
                .setMessage("只会把会话从列表里移除，不会删除聊天记录。")
                .setPositiveButton("删除", (dialog, which) -> removeSession(session))
                .setNegativeButton("取消", null)
                .show();
    }

    private void setSessionTop(PrivateMsgSession session, boolean top) {
        CenterThreadPool.run(() -> {
            try {
                int code = PrivateMsgApi.setSessionTop(session.talkerUid, PrivateMsgApi.SESSION_TYPE_USER, top);
                ((Activity) context).runOnUiThread(() -> {
                    String error = PrivateMsgApi.sessionErrorMsg(code);
                    if (error.isEmpty()) {
                        MsgUtil.showMsg(top ? "已置顶" : "已取消置顶");
                        if (onSessionsChanged != null) onSessionsChanged.run();
                    } else {
                        MsgUtil.showMsg(error);
                    }
                });
            } catch (Exception e) {
                ((Activity) context).runOnUiThread(() -> MsgUtil.err(e));
            }
        });
    }

    private void removeSession(PrivateMsgSession session) {
        CenterThreadPool.run(() -> {
            try {
                int code = PrivateMsgApi.removeSession(session.talkerUid, PrivateMsgApi.SESSION_TYPE_USER);
                ((Activity) context).runOnUiThread(() -> {
                    String error = PrivateMsgApi.sessionErrorMsg(code);
                    if (error.isEmpty()) {
                        int index = sessionsList.indexOf(session);
                        if (index >= 0 && index < sessionsList.size()) {
                            sessionsList.remove(index);
                            notifyItemRemoved(index);
                        }
                        MsgUtil.showMsg("已从会话列表移除");
                        if (onSessionsChanged != null) onSessionsChanged.run();
                    } else {
                        MsgUtil.showMsg(error);
                    }
                });
            } catch (Exception e) {
                ((Activity) context).runOnUiThread(() -> MsgUtil.err(e));
            }
        });
    }

    public static class PrivateMsgSessionsHolder extends RecyclerView.ViewHolder {
        final ImageView avatarView;
        final TextView nameText;
        final TextView contentText;

        public PrivateMsgSessionsHolder(View itemView) {
            super(itemView);
            avatarView = itemView.findViewById(R.id.userAvatar);
            nameText = itemView.findViewById(R.id.userName);
            contentText = itemView.findViewById(R.id.userDesc);
        }

    }
}