package com.RobinNotBad.BiliClient.adapter.dynamic;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.ListChooseActivity;
import com.RobinNotBad.BiliClient.activity.dynamic.DynamicActivity;
import com.RobinNotBad.BiliClient.activity.dynamic.send.SendDynamicActivity;
import com.RobinNotBad.BiliClient.activity.live.FollowLiveActivity;
import com.RobinNotBad.BiliClient.api.DynamicApi;
import com.RobinNotBad.BiliClient.model.Dynamic;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

//动态Adapter 显示部分在单独的DynamicHolder里
//2023-09-28

public class DynamicAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    final Context context;
    final List<Dynamic> dynamicList;
    final RecyclerView recyclerView;
    final DynamicActivity dynamicActivity;
    final ActivityResultLauncher<Intent> writeDynamicLauncher;
    public List<DynamicApi.UpInfo> recentUpList;

    public DynamicAdapter(Context context, List<Dynamic> dynamicList, RecyclerView recyclerView, List<DynamicApi.UpInfo> recentUpList) {
        this.context = context;
        this.dynamicList = dynamicList;
        this.recyclerView = recyclerView;
        dynamicActivity = (DynamicActivity) context;
        this.writeDynamicLauncher = dynamicActivity.writeDynamicLauncher;
        this.recentUpList = recentUpList;
    }

    private boolean showRecentUp() {
        return SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.RECENT_UP_DISPLAY_ENABLE, true)
                && recentUpList != null && !recentUpList.isEmpty();
    }

    @Override
    public int getItemViewType(int position) {
        if (position == 0) {
            return 0;
        } else if (position == 1 && showRecentUp()) {
            return 2;
        } else {
            return 1;
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == 0) {
            View view = LayoutInflater.from(this.context).inflate(R.layout.cell_dynamic_action, parent, false);
            return new WriteDynamic(view);
        } else if (viewType == 2) {
            View view = LayoutInflater.from(this.context).inflate(R.layout.cell_recent_up_list, parent, false);
            return new RecentUpListHolder(view);
        } else {
            return new DynamicHolder(LayoutInflater.from(this.context).inflate(R.layout.cell_dynamic, parent, false),
                    dynamicActivity, false);
        }
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof WriteDynamic) {
            WriteDynamic writeDynamic = (WriteDynamic) holder;
            writeDynamic.write_dynamic.setOnClickListener((view) -> {
                Intent intent = new Intent();
                intent.setClass(context, SendDynamicActivity.class);
                writeDynamicLauncher.launch(intent);
            });
            writeDynamic.type.setOnClickListener((view) -> dynamicActivity.selectTypeLauncher
                    .launch(new Intent().setClass(context, ListChooseActivity.class).putExtra("title", "选择类型")
                            .putExtra("items", new ArrayList<>(Arrays.asList("全部", "视频投稿", "追番", "专栏")))));
            writeDynamic.live.setOnClickListener(view -> {
                Intent intent = new Intent(context, FollowLiveActivity.class);
                context.startActivity(intent);
            });
        } else if (holder instanceof RecentUpListHolder) {
            RecentUpListHolder recentUpListHolder = (RecentUpListHolder) holder;
            if (recentUpListHolder.recentUpAdapter == null) {
                recentUpListHolder.recentUpRecyclerView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
                recentUpListHolder.recentUpAdapter = new RecentUpAdapter(context, recentUpList);
                recentUpListHolder.recentUpRecyclerView.setAdapter(recentUpListHolder.recentUpAdapter);
            }
        } else if (holder instanceof DynamicHolder) {
            int realPosition = position - (showRecentUp() ? 2 : 1);
            if (realPosition < 0 || realPosition >= dynamicList.size())
                return;

            Dynamic dynamic = dynamicList.get(realPosition);
            if (dynamic == null)
                return;

            DynamicHolder dynamicHolder = (DynamicHolder) holder;
            dynamicHolder.showDynamic(context, dynamic, true);

            if (dynamic.dynamic_forward != null) {
                View childCard = dynamicHolder.cell_dynamic_child;
                if (dynamicHolder.childDynamicHolder == null) {
                    dynamicHolder.childDynamicHolder = new DynamicHolder(childCard, dynamicActivity, true);
                }
                dynamicHolder.childDynamicHolder.showDynamic(context, dynamic.dynamic_forward, true);
                childCard.setVisibility(View.VISIBLE);
            } else {
                dynamicHolder.cell_dynamic_child.setVisibility(View.GONE);
            }

            View.OnLongClickListener onDeleteLongClick = DynamicHolder.getDeleteListener(dynamicActivity, dynamicList,
                    realPosition, this, showRecentUp());
            dynamicHolder.item_dynamic_delete.setOnLongClickListener(onDeleteLongClick);
            if (dynamic.canDelete)
                dynamicHolder.item_dynamic_delete.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() {
        int baseCount = dynamicList != null ? dynamicList.size() + 1 : 1;
        return showRecentUp() ? baseCount + 1 : baseCount;
    }

    public static class WriteDynamic extends RecyclerView.ViewHolder {
        final MaterialButton write_dynamic, type, live;

        public WriteDynamic(@NonNull View itemView) {
            super(itemView);
            write_dynamic = itemView.findViewById(R.id.write_dynamic);
            type = itemView.findViewById(R.id.type);
            live = itemView.findViewById(R.id.live);
        }
    }

    public static class RecentUpListHolder extends RecyclerView.ViewHolder {
        final RecyclerView recentUpRecyclerView;
        RecentUpAdapter recentUpAdapter;

        public RecentUpListHolder(@NonNull View itemView) {
            super(itemView);
            recentUpRecyclerView = itemView.findViewById(R.id.recentUpRecyclerView);
        }
    }
}
