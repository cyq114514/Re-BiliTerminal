package com.RobinNotBad.BiliClient.adapter.video;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.api.BangumiApi;
import com.RobinNotBad.BiliClient.api.PlayerApi;
import com.RobinNotBad.BiliClient.listener.OnItemLongClickListener;
import com.RobinNotBad.BiliClient.model.PlayerData;
import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.RobinNotBad.BiliClient.util.TerminalContext;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

//视频卡片Adapter 适用于各种场景（迫真
//日期不记得了

//2023-10-01 把一些公用代码移动到VideoCardHolder里了

public class VideoCardAdapter extends RecyclerView.Adapter<VideoCardHolder> {

    final Context context;
    final List<VideoCard> videoCardList;
    OnItemLongClickListener longClickListener;

    // ==================== 多选模式（仅需要的页面开启，如收藏夹批量管理） ====================
    private boolean selectionMode = false;
    private final Set<Long> selectedAids = new HashSet<>();

    /** 进入/退出多选模式，退出时清空选择。默认关闭，其它使用方不受任何影响 */
    public void setSelectionMode(boolean selectionMode) {
        if (this.selectionMode == selectionMode) return;
        this.selectionMode = selectionMode;
        selectedAids.clear();
        notifyDataSetChanged();
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public void toggleSelected(long aid) {
        if (!selectedAids.add(aid)) selectedAids.remove(aid);
        notifyDataSetChanged();
    }

    public int getSelectedCount() {
        return selectedAids.size();
    }

    public Set<Long> getSelectedAids() {
        return selectedAids;
    }

    public VideoCardAdapter(Context context, List<VideoCard> videoCardList) {
        this.context = context;
        this.videoCardList = videoCardList;
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
        if (position < 0 || position >= videoCardList.size())
            return;
        VideoCard videoCard = videoCardList.get(position);
        if (videoCard == null)
            return;

        holder.showVideoCard(videoCard, context);

        //多选模式：点按/长按都是切换选中。视觉用透明度区分，不做勾选框——
        //这张卡片布局被推荐/历史/稍后再看共用，动布局回归面太大
        if (selectionMode) {
            holder.itemView.setAlpha(selectedAids.contains(videoCard.aid) ? 0.45f : 1f);
            holder.itemView.setOnClickListener(view -> toggleSelected(videoCard.aid));
            holder.itemView.setOnLongClickListener(view -> {
                toggleSelected(videoCard.aid);
                return true;
            });
            return;
        }
        holder.itemView.setAlpha(1f);

        holder.itemView.setOnClickListener(view -> {
            switch (videoCard.type) {
                case "video":
                    TerminalContext.getInstance().enterVideoDetailPage(context, videoCard.aid, videoCard.bvid, "video");
                    break;
                case "media_bangumi":
                    //历史记录里的番剧条目带的是剧集id：直接反查剧集后经跳转页进播放器续播，
                    //取流与"从上次进度播放"由既有的 JumpToPlayerActivity → getBangumi 链路完成；
                    //追番/搜索/动态等来源的卡片 epid 为 0，aid 本身就是 media_id，仍进详情页
                    if (videoCard.epid > 0) {
                        CenterThreadPool.run(() -> {
                            try {
                                BangumiApi.EpisodeResult ep = BangumiApi.getEpisodeFromEpid(videoCard.epid);
                                PlayerData data = new PlayerData(PlayerData.TYPE_BANGUMI);
                                data.aid = ep.episode.aid;
                                data.cid = ep.episode.cid;
                                //bvid 必须带上：番剧心跳是被服务端按 bvid 认稿件的，
                                //过去这条入口不设 bvid，从观看记录进播放器时上报体里 aid/bvid 只有一个有效
                                data.bvid = ep.episode.bvid != null ? ep.episode.bvid : "";
                                data.title = videoCard.title;
                                data.mid = SharedPreferencesUtil.getLong("mid", 0);
                                data.epid = videoCard.epid;
                                data.seasonId = ep.seasonId;
                                data.seasonType = ep.seasonType;
                                PlayerApi.startGettingUrl(data);
                            } catch (Exception e) {
                                //反查失败（接口波动/epid 异常）时回退进详情页，详情页内的自动定位仍可兜底；
                                //注意历史 PGC 记录的 oid 是稿件 avid 而非 media_id，不能直接当 media_id 用
                                Logu.e("history-jump", "epid 反查失败，回退详情页: " + e.getMessage());
                                long mediaId = BangumiApi.getMdidFromEpid(videoCard.epid);
                                if (mediaId > 0)
                                    TerminalContext.getInstance().enterVideoDetailPage(context, mediaId, null, "media");
                                else
                                    MsgUtil.showMsg("番剧信息获取失败");
                            }
                        });
                    } else {
                        TerminalContext.getInstance().enterVideoDetailPage(context, videoCard.aid, null, "media");
                    }
                    break;
                default:
                    MsgUtil.showMsg("该类型暂不支持打开");
                    break;
            }
        });

        holder.itemView.setOnLongClickListener(view -> {
            if (longClickListener != null) {
                longClickListener.onItemLongClick(position);
                return true;
            } else
                return false;
        });
    }

    @Override
    public int getItemCount() {
        return videoCardList != null ? videoCardList.size() : 0;
    }

}
