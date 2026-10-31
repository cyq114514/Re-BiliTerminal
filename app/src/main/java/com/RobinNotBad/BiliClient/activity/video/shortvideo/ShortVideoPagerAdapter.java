package com.RobinNotBad.BiliClient.activity.video.shortvideo;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.ui.theme.ThemeApplier;
import com.RobinNotBad.BiliClient.model.ShortVideoItem;
import com.RobinNotBad.BiliClient.util.GlideUtil;
import com.RobinNotBad.BiliClient.util.StringUtil;

import java.util.List;

/**
 * 竖屏短视频的一屏一条：
 * 每条只负责「封面 / 文案 / 右侧操作栏 / 底部进度」，播放器由 {@link ShortVideoFeedFragment} 统一持有。
 *
 * <p>封面默认是盖住的（不透明），当前那页在首帧渲染后才把封面隐藏、露出播放器画面；
 * 这样翻页过程中不会出现"新页面先露出上一条视频最后一帧"的穿帮。
 */
public class ShortVideoPagerAdapter extends RecyclerView.Adapter<ShortVideoPagerAdapter.Holder> {

    /** 交互全部回给 Fragment：它才知道播放器状态与分页进度 */
    public interface Callback {
        void onLike(ShortVideoItem item, int position);

        void onFav(ShortVideoItem item, int position);

        void onComment(ShortVideoItem item, int position);

        void onMore(ShortVideoItem item, int position);

        /** 用户开始拖进度条：暂停自动进度刷新，避免和手指抢 */
        void onSeekStart();

        void onSeek(ShortVideoItem item, int position, int progressMs);

        void onSeekStop();

        /** 点了操作栏/底栏：重置自动隐藏计时 */
        void onControlsTouched();
    }

    private final List<ShortVideoItem> items;
    private final Callback callback;

    /**
     * 操作栏显隐由 Fragment 决定，adapter 只负责"在（可能被复用的）holder 上落地"。
     *
     * <p>不这么做的话：holder 回收复用时会带着上一条的显隐状态，翻几条之后就会出现
     * "有的页控件在、有的页控件不在"，而 {@code applyControlsToCurrent()} 只作用在当前的
     * holder 上，补不回来。
     */
    private boolean controlsVisible = true;

    public ShortVideoPagerAdapter(List<ShortVideoItem> items, Callback callback) {
        this.items = items;
        this.callback = callback;
    }

    /** 供 Fragment 在显隐切换时同步；新绑定/复用的 holder 会按这个值套用 */
    public void setControlsVisibleState(boolean visible) {
        this.controlsVisible = visible;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.cell_short_video_page, parent, false);
        //播放进度条 tint 跟随主题色
        ThemeApplier.apply(view);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        ShortVideoItem item = items.get(position);
        holder.bind(item);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public ShortVideoItem getItem(int position) {
        return position >= 0 && position < items.size() ? items.get(position) : null;
    }

    public class Holder extends RecyclerView.ViewHolder {

        public final View pageRoot, actionColumn, bottomInfo;
        public final ImageView coverImage, playIcon;
        public final ProgressBar buffering;
        public final ImageView iconLike, iconFav;
        public final TextView textLike, textFav, textComment, textAuthor, textTitle, textMeta, textTime;
        public final SeekBar seekBar;

        public boolean liked = false;
        public boolean faved = false;
        /** 用户正在拖进度条：此时不让播放器的进度回写抢走滑块 */
        boolean seeking = false;

        public Holder(@NonNull View itemView) {
            super(itemView);
            pageRoot = itemView.findViewById(R.id.pageRoot);
            coverImage = itemView.findViewById(R.id.coverImage);
            playIcon = itemView.findViewById(R.id.playIcon);
            buffering = itemView.findViewById(R.id.buffering);
            actionColumn = itemView.findViewById(R.id.actionColumn);
            bottomInfo = itemView.findViewById(R.id.bottomInfo);
            iconLike = itemView.findViewById(R.id.iconLike);
            iconFav = itemView.findViewById(R.id.iconFav);
            textLike = itemView.findViewById(R.id.textLike);
            textFav = itemView.findViewById(R.id.textFav);
            textComment = itemView.findViewById(R.id.textComment);
            textAuthor = itemView.findViewById(R.id.textAuthor);
            textTitle = itemView.findViewById(R.id.textTitle);
            textMeta = itemView.findViewById(R.id.textMeta);
            textTime = itemView.findViewById(R.id.textTime);
            seekBar = itemView.findViewById(R.id.seekBar);

            itemView.findViewById(R.id.actionLike).setOnClickListener(v -> {
                callback.onControlsTouched();
                int pos = getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                callback.onLike(items.get(pos), pos);
            });
            itemView.findViewById(R.id.actionFav).setOnClickListener(v -> {
                callback.onControlsTouched();
                int pos = getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                callback.onFav(items.get(pos), pos);
            });
            itemView.findViewById(R.id.actionComment).setOnClickListener(v -> {
                callback.onControlsTouched();
                int pos = getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                callback.onComment(items.get(pos), pos);
            });
            itemView.findViewById(R.id.actionMore).setOnClickListener(v -> {
                callback.onControlsTouched();
                int pos = getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                callback.onMore(items.get(pos), pos);
            });

            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    int pos = getAdapterPosition();
                    if (pos == RecyclerView.NO_POSITION) return;
                    callback.onSeek(items.get(pos), pos, progress * 1000);
                }

                @Override
                public void onStartTrackingTouch(SeekBar bar) {
                    seeking = true;
                    callback.onSeekStart();
                    callback.onControlsTouched();
                }

                @Override
                public void onStopTrackingTouch(SeekBar bar) {
                    seeking = false;
                    callback.onSeekStop();
                    callback.onControlsTouched();
                }
            });
        }

        public void bind(ShortVideoItem item) {
            //回收复用的 holder 会带着上一条的点赞/收藏态，按条目自己的会话状态重新套
            liked = item.likeState == 1;
            faved = item.favState == 1;
            seeking = false;
            textAuthor.setText(item.authorName);
            textTitle.setText(item.title);
            StringBuilder meta = new StringBuilder();
            if (!android.text.TextUtils.isEmpty(item.viewCount)) meta.append(item.viewCount).append("播放");
            if (!android.text.TextUtils.isEmpty(item.danmakuCount)) {
                if (meta.length() > 0) meta.append(" · ");
                meta.append(item.danmakuCount).append("弹幕");
            }
            textMeta.setText(meta.toString());
            //三个按钮统一口径：有数字就显示数字（8.7万 / 1234），没有才退回文字标签
            textLike.setText(item.likeCount > 0 ? ShortVideoItem.formatCount(item.likeCount) : "点赞");
            textFav.setText(item.favoriteCount > 0 ? ShortVideoItem.formatCount(item.favoriteCount) : "收藏");
            textComment.setText(item.replyCount > 0 ? ShortVideoItem.formatCount(item.replyCount) : "评论");
            iconLike.setImageResource(liked ? R.drawable.icon_like_1 : R.drawable.icon_like_0);
            iconFav.setImageResource(faved ? R.drawable.icon_fav_1 : R.drawable.icon_fav_0);
            seekBar.setMax(0);
            seekBar.setProgress(0);
            textTime.setText("00:00 / 00:00");
            playIcon.setVisibility(View.GONE);
            buffering.setVisibility(View.GONE);
            showCover(true);
            //复用的 holder 会带着上一条的显隐状态，绑定时要按当前状态重新套一遍
            setControlsVisible(controlsVisible);

            GlideUtil.request(coverImage, item.cover, 0, R.mipmap.placeholder);
        }

        /**
         * 当前页首帧渲染完成前必须盖住封面，否则会先看到上一条视频的画面。
         *
         * <p>页面根布局的黑色底也要一起去掉：播放画面是盖在 RecyclerView <b>下面</b>的一层
         * SurfaceView，这一页不透明的话，就算封面隐藏了也看不到画面。
         */
        public void showCover(boolean show) {
            coverImage.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
            pageRoot.setBackgroundColor(show ? 0xFF000000 : 0x00000000);
        }

        /**
         * 操作栏/底栏显隐。
         *
         * <p>用 {@code INVISIBLE} 而不是 {@code alpha=0}：透明度为 0 的 View 依然能接收点击，
         * 用户看不见却会误触到"赞"，而且点了以后图标在看不见的地方变，看起来就是"点了没反应"。
         * INVISIBLE 的 View 本来就不参与触摸派发，不需要再逐个 setEnabled ——
         * 之前那版一旦漏掉一次恢复，按钮就会 permanent 处于不可点状态，很难查。
         */
        public void setControlsVisible(boolean visible) {
            actionColumn.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
            bottomInfo.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        }

        public void setPlaying(boolean playing) {
            playIcon.setVisibility(playing ? View.GONE : View.VISIBLE);
        }

        public void setBuffering(boolean bufferingVisible) {
            buffering.setVisibility(bufferingVisible ? View.VISIBLE : View.GONE);
        }

        public void updateProgress(int progressMs, int durationMs) {
            if (seeking) return;
            int total = Math.max(0, durationMs) / 1000;
            if (seekBar.getMax() != total) seekBar.setMax(total);
            int current = Math.max(0, progressMs) / 1000;
            if (current > total) current = total;
            seekBar.setProgress(current);
            //StringUtil.toTime 收的是秒
            textTime.setText(StringUtil.toTime(current) + " / " + StringUtil.toTime(total));
        }
    }
}
