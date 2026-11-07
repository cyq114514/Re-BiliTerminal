package com.RobinNotBad.BiliClient.adapter.message;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.model.MessageCard;

import java.util.List;

public class NoticeAdapter extends RecyclerView.Adapter<NoticeHolder> {
    Context context;
    List<MessageCard> messageList;
    /** 通知页类型（reply/like/at/system）。like 页按原版口径只显示头像不显示昵称（用户反馈 2026-11-07） */
    final String pageType;

    public NoticeAdapter(Context context, List<MessageCard> messageList) {
        this(context, messageList, null);
    }

    public NoticeAdapter(Context context, List<MessageCard> messageList, String pageType) {
        this.context = context;
        this.messageList = messageList;
        this.pageType = pageType;
    }

    @NonNull
    @Override
    public NoticeHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(this.context).inflate(R.layout.cell_message, parent, false);
        return new NoticeHolder(view);
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onBindViewHolder(@NonNull NoticeHolder holder, int position) {
        if (position < 0 || position >= messageList.size())
            return;
        MessageCard message = messageList.get(position);
        if (message == null)
            return;
        holder.hideNick = "like".equals(pageType);
        holder.showMessage(message, context);
    }

    @Override
    public void onViewRecycled(@NonNull NoticeHolder holder) {
        if (holder.extraCard != null) {
            holder.extraCard.removeAllViews();
        }
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() {
        return messageList != null ? messageList.size() : 0;
    }
}
