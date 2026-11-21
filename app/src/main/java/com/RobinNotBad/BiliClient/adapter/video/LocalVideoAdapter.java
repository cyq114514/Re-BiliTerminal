package com.RobinNotBad.BiliClient.adapter.video;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.video.local.DownloadListActivity;
import com.RobinNotBad.BiliClient.activity.video.local.LocalPageChooseActivity;
import com.RobinNotBad.BiliClient.api.PlayerApi;
import com.RobinNotBad.BiliClient.listener.OnItemLongClickListener;
import com.RobinNotBad.BiliClient.model.LocalVideo;
import com.RobinNotBad.BiliClient.model.PlayerData;
import com.RobinNotBad.BiliClient.ui.theme.ThemeManager;
import com.RobinNotBad.BiliClient.util.GlideUtil;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.Locale;

//本地视频Adapter
//2023-10-04

public class LocalVideoAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    final Context context;
    final ArrayList<LocalVideo> localVideoList;
    final boolean audioOnlyMode;
    OnItemLongClickListener longClickListener;
    OnAudioItemClickListener audioClickListener;
    OnAudioEntryClickListener audioEntryClickListener;

    /**
     * 列表头的「听缓存（音频）」入口：把缓存里的音频按列表顺序灌进听歌队列。
     *
     * <p>2026-11-21 反馈：缓存完一批音频后，用户得先绕到收藏夹页面才能进听歌模式；
     * 这里把入口直接放到缓存页，不必再进收藏夹。
     */
    public interface OnAudioEntryClickListener {
        void onAudioEntryClick();
    }

    /**
     * 纯音频条目被点击：交给宿主的听歌队列，从这一条往下连播。
     *
     * <p>为什么单独开一个回调：内置播放器是单曲的，点缓存条目进去播完就停，
     * 用户以为"缓存不能自动接下一首"（1.3.5 反馈）。音频没有画面，走队列页更合适。
     */
    public interface OnAudioItemClickListener {
        void onAudioItemClick(int position);
    }

    public LocalVideoAdapter(Context context, ArrayList<LocalVideo> localVideoList) {
        this(context, localVideoList, false);
    }

    public LocalVideoAdapter(Context context, ArrayList<LocalVideo> localVideoList, boolean audioOnlyMode) {
        this.context = context;
        this.localVideoList = localVideoList;
        this.audioOnlyMode = audioOnlyMode;
    }

    public void setOnLongClickListener(OnItemLongClickListener listener) {
        this.longClickListener = listener;
    }

    public void setOnAudioItemClickListener(OnAudioItemClickListener listener) {
        this.audioClickListener = listener;
    }

    public void setOnAudioEntryClickListener(OnAudioEntryClickListener listener) {
        this.audioEntryClickListener = listener;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == 0) {
            View view = LayoutInflater.from(this.context).inflate(R.layout.cell_video_local, parent, false);
            return new LocalVideoHolder(view);
        } else {
            View view = LayoutInflater.from(this.context).inflate(R.layout.cell_local_header, parent, false);
            return new LocalHeaderHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (position != 0) {
            int realPosition = position - 1;
            if (realPosition < 0 || realPosition >= localVideoList.size())
                return;
            LocalVideo localVideo = localVideoList.get(realPosition);
            if (localVideo == null)
                return;

            ((LocalVideoHolder) holder).showLocalVideo(localVideo, context, audioOnlyMode);

            holder.itemView.setOnClickListener(view -> {
                //纯音频缓存条目：交给宿主的听歌队列，从这条开始连播（1.3.5 反馈 R1）
                if (localVideo.videoFileList != null && localVideo.videoFileList.size() == 1
                        && localVideo.videoFileList.get(0).endsWith("audio.m4a")
                        && audioClickListener != null) {
                    audioClickListener.onAudioItemClick(realPosition);
                    return;
                }
                if (localVideo.videoFileList != null && localVideo.videoFileList.size() == 1) {
                    PlayerData playerData = new PlayerData(PlayerData.TYPE_LOCAL);
                    String mediaPath = localVideo.videoFileList.get(0);
                    playerData.videoUrl = mediaPath;
                    if (localVideo.danmakuFileList != null && !localVideo.danmakuFileList.isEmpty()) {
                        playerData.danmakuUrl = localVideo.danmakuFileList.get(0);
                    }
                    playerData.title = localVideo.title;

                    try {
                        Intent player = PlayerApi.jumpToPlayer(playerData);
                        // 如果是仅音频文件，标记为音频模式
                        if (mediaPath.endsWith("audio.m4a")) {
                            player.putExtra("audio_only", true);
                        }
                        context.startActivity(player);
                    } catch (ActivityNotFoundException e) {
                        MsgUtil.showMsg("跳转失败");
                        e.printStackTrace();
                    }
                } else if (localVideo.videoFileList != null && localVideo.videoFileList.size() > 1) {
                    Intent intent = new Intent();
                    intent.setClass(context, LocalPageChooseActivity.class);
                    intent.putExtra("pageList", localVideo.pageList);
                    intent.putExtra("videoFileList", localVideo.videoFileList);
                    intent.putExtra("danmakuFileList", localVideo.danmakuFileList);
                    intent.putExtra("title", localVideo.title);
                    context.startActivity(intent);
                }
            });

            holder.itemView.setOnLongClickListener(view -> {
                if (longClickListener != null) {
                    longClickListener.onItemLongClick(realPosition);
                    return true;
                } else
                    return false;
            });
        } else
            ((LocalHeaderHolder) holder).show(context, audioEntryClickListener, audioOnlyMode,
                    localVideoList == null || localVideoList.isEmpty());
    }

    @Override
    public int getItemCount() {
        return localVideoList != null ? localVideoList.size() + 1 : 1;
    }

    public static class LocalHeaderHolder extends RecyclerView.ViewHolder {

        final View audioEntry;
        final View downloadCard;
        final TextView audioTitle;
        final TextView audioSub;
        final TextView localEmpty;
        final ImageView audioIcon;

        public LocalHeaderHolder(@NonNull View itemView) {
            super(itemView);
            //下载列表：保持原来的位置与行为（第一个 header 行）；听歌模式页不需要它
            downloadCard = itemView.findViewById(R.id.downloadListCard);
            itemView.findViewById(R.id.downloadListText).setOnClickListener(v ->
                    v.getContext().startActivity(new Intent(v.getContext(), DownloadListActivity.class)));
            audioEntry = itemView.findViewById(R.id.local_audio_entry);
            audioTitle = itemView.findViewById(R.id.audioEntryTitle);
            audioSub = itemView.findViewById(R.id.audioEntrySub);
            localEmpty = itemView.findViewById(R.id.local_empty);
            audioIcon = itemView.findViewById(R.id.audioEntryIcon);
        }

        public void show(Context context, OnAudioEntryClickListener listener, boolean audioOnlyMode,
                         boolean empty) {
            if (downloadCard != null)
                downloadCard.setVisibility(audioOnlyMode ? View.GONE : View.VISIBLE);
            if (localEmpty != null) {
                localEmpty.setText(audioOnlyMode ? "还没有缓存音频~" : "还没有视频缓存~");
                localEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
            }
            if (audioOnlyMode) {
                audioTitle.setText("播放全部");
                audioSub.setText("从第一首开始连续播放");
            } else {
                audioTitle.setText("听歌模式");
                audioSub.setText("缓存音频单独列表，点进去连播");
            }
            //小屏/手表与收藏页同一口径：隐藏副标题、收小图标，避免入口卡占掉太多高度
            float density = context.getResources().getDisplayMetrics().density;
            int screenHeightDp = (int) (context.getResources().getDisplayMetrics().heightPixels / density);
            int screenWidthDp = context.getResources().getConfiguration().screenWidthDp;
            if (screenHeightDp < 420 || screenWidthDp < 360) {
                audioSub.setVisibility(View.GONE);
                ViewGroup.LayoutParams lp = audioIcon.getLayoutParams();
                int side = (int) (24 * density + 0.5f);
                lp.width = side;
                lp.height = side;
                audioIcon.setLayoutParams(lp);
            } else {
                audioSub.setVisibility(View.VISIBLE);
            }
            //图标跟随主题色（API 21+ 才能直接 tint；低版本保持矢量原色）
            if (Build.VERSION.SDK_INT >= 21) {
                audioIcon.setImageTintList(ColorStateList.valueOf(ThemeManager.getPrimary()));
            }
            audioEntry.setOnClickListener(v -> {
                if (listener != null) listener.onAudioEntryClick();
            });
        }
    }

    public static class LocalVideoHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView extra;
        final TextView audioBadge;
        final ImageView cover;

        public LocalVideoHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.text_title);
            extra = itemView.findViewById(R.id.text_extra);
            audioBadge = itemView.findViewById(R.id.audio_badge);
            cover = itemView.findViewById(R.id.img_cover);
        }

        @SuppressLint("SetTextI18n")
        public void showLocalVideo(LocalVideo videoCard, Context context, boolean audioOnlyMode) {
            title.setText(videoCard.title);
            String size = String.format(Locale.CHINA, "%.2f", videoCard.size / 1000000f) + "MB";
            extra.setText(size);
            if (audioBadge != null)
                audioBadge.setVisibility(videoCard.audioOnly || audioOnlyMode
                        ? View.VISIBLE : View.GONE);

            try {
                //本地封面非 http 路径，GlideUtil.url 内部原样返回，requestCover 可安全复用
                GlideUtil.requestCover(cover, videoCard.cover, R.mipmap.placeholder);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public int getItemViewType(int position) {
        return position == 0 ? 1 : 0;
    }
}
