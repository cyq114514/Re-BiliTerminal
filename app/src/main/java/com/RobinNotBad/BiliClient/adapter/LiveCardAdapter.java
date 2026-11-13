package com.RobinNotBad.BiliClient.adapter;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.adapter.video.VideoCardHolder;
import com.RobinNotBad.BiliClient.listener.OnItemLongClickListener;
import com.RobinNotBad.BiliClient.model.LiveRoom;
import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.util.StringUtil;
import com.RobinNotBad.BiliClient.util.TerminalContext;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public class LiveCardAdapter extends RecyclerView.Adapter<VideoCardHolder> {

    final Context context;
    final List<LiveRoom> roomList;
    OnItemLongClickListener longClickListener;

    /**
     * 「房间 → 去掉 HTML 的标题」缓存。
     *
     * <p>B 站直播接口的 title 带 {@code <em class="keyword">} 高亮标签，原来每次
     * onBindViewHolder 都调一次 StringUtil.removeHtml()，那是一次完整的 Jsoup DOM 解析，
     * 列表滚动复用重绑就等于在主线程反复建树。这里按房间对象缓存，同一间房只解析一次。
     *
     * <p>没有放在构造期一次性预处理，是因为三个使用方（推荐/关注/搜索）都会分页 addAll，
     * 构造期遍历覆盖不到后续页。WeakHashMap 的 key 是弱引用，列表刷新丢弃旧房间后条目自动回收
     * （LiveRoom 未覆写 equals/hashCode，比较即实例身份，正是这里要的语义）。
     * 只在 onBindViewHolder 里读写，全部在主线程。
     */
    private final Map<LiveRoom, String> titleCache = new WeakHashMap<>();

    public LiveCardAdapter(Context context, List<LiveRoom> roomList) {
        this.context = context;
        this.roomList = roomList;
    }

    public void setOnLongClickListener(OnItemLongClickListener listener) {
        this.longClickListener = listener;
    }

    @NonNull
    @Override
    public VideoCardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(this.context).inflate(R.layout.cell_video_list, parent, false);
        return new VideoCardHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VideoCardHolder holder, int position) {
        if (position < 0 || position >= roomList.size())
            return;
        LiveRoom room = roomList.get(position);
        if (room == null)
            return;

        VideoCard videoCard = new VideoCard();
        videoCard.title = strippedTitle(room);
        if (room.user_cover != null && !room.user_cover.startsWith("http"))
            videoCard.cover = "http:" + room.user_cover;
        else
            videoCard.cover = room.user_cover;
        if (TextUtils.isEmpty(videoCard.cover) || videoCard.cover.equals("http:"))
            videoCard.cover = room.cover;
        videoCard.upName = room.uname;
        videoCard.view = StringUtil.toWan(room.online) + "人观看";
        videoCard.type = "live";

        holder.showVideoCard(videoCard, context);

        holder.itemView
                .setOnClickListener(view -> TerminalContext.getInstance().enterLiveDetailPage(context, room.roomid));

        holder.itemView.setOnLongClickListener(view -> {
            if (longClickListener != null) {
                longClickListener.onItemLongClick(position);
                return true;
            } else
                return false;
        });
    }

    /** 取去标签后的标题：同一间房只做一次 Jsoup 解析，后续重绑直接命中缓存 */
    private String strippedTitle(LiveRoom room) {
        String cached = titleCache.get(room);
        if (cached != null) return cached;
        //title 为空时给空串：原实现直接把 null 交给 Jsoup.parse 会抛 IllegalArgumentException
        String stripped = StringUtil.removeHtml(room.title != null ? room.title : "");
        titleCache.put(room, stripped);
        return stripped;
    }

    @Override
    public int getItemCount() {
        return roomList != null ? roomList.size() : 0;
    }

}
