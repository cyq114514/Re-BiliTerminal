package com.RobinNotBad.BiliClient.adapter.video;

import android.content.Context;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.listener.OnItemClickListener;
import com.RobinNotBad.BiliClient.listener.OnItemLongClickListener;

import java.util.ArrayList;

//分页视频选集
//2023-08-29

public class PageChooseAdapter extends RecyclerView.Adapter<PageChooseAdapter.Holder> {

    final Context context;
    final ArrayList<String> nameList;
    //上次观看的分P下标（-1 表示无记录），bind 时必须双向赋色，避免复用残影
    private int historyIndex = -1;
    private final int defaultTextColor;
    private static final int HISTORY_COLOR = 0xfffb7299;

    OnItemClickListener onItemClickListener;
    OnItemLongClickListener onItemLongClickListener;

    public PageChooseAdapter(Context context, ArrayList<String> nameList) {
        this.context = context;
        this.nameList = nameList;
        TypedValue typedValue = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.textColorPrimary, typedValue, true);
        defaultTextColor = typedValue.resourceId != 0
                ? ContextCompat.getColor(context, typedValue.resourceId)
                : typedValue.data;
    }

    public void setHistoryIndex(int historyIndex) {
        this.historyIndex = historyIndex;
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.onItemClickListener = listener;
    }

    public void setOnItemLongClickListener(OnItemLongClickListener listener) {
        this.onItemLongClickListener = listener;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(this.context).inflate(R.layout.cell_choose, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        if (position < 0 || position >= nameList.size())
            return;

        holder.folder_name.setText(nameList.get(position));
        holder.folder_name.setTextColor(position == historyIndex ? HISTORY_COLOR : defaultTextColor);

        //点击时取 ViewHolder 当前位置，不捕获 bind 时的 position：删除后未重绑的 ViewHolder 会带回旧下标
        holder.itemView.setOnClickListener(view -> {
            int currentPosition = holder.getAdapterPosition();
            if (onItemClickListener != null && currentPosition != RecyclerView.NO_POSITION)
                onItemClickListener.onItemClick(currentPosition);
        });

        holder.itemView.setOnLongClickListener(view -> {
            int currentPosition = holder.getAdapterPosition();
            if (onItemLongClickListener != null && currentPosition != RecyclerView.NO_POSITION) {
                onItemLongClickListener.onItemLongClick(currentPosition);
                return true;
            } else
                return false;
        });
    }

    @Override
    public int getItemCount() {
        return nameList != null ? nameList.size() : 0;
    }

    public static class Holder extends RecyclerView.ViewHolder {
        final TextView folder_name;

        public Holder(@NonNull View itemView) {
            super(itemView);
            folder_name = itemView.findViewById(R.id.text);
        }
    }
}
