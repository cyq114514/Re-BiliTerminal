package com.RobinNotBad.BiliClient.adapter.video;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * 在 {@link VideoCardAdapter} 前面挂一个不可选中的头部 item。
 *
 * <p>收藏夹的「听全部 / 下载全部 + 排序胶囊」原先是一层浮在 RecyclerView 上方的 View，
 * 靠 translationY 跟手滚动、靠 FrameLayout.clipChildren 裁掉超出部分；实测裁剪不住，
 * 滚动后整块压到标题栏和列表卡片上（2026-11-21 反馈截图）。
 * 改成真正的 RecyclerView item 后，滚动与裁剪都交给列表，结构上不可能再盖住标题栏。
 *
 * <p>委托方每次 notify 都要 +1 偏移；不直接把 VideoCardAdapter 当 header 用，
 * 是因为它被 10 多个页面共用，改它的 position 语义回归面太大。
 */
public class VideoCardWithHeaderAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = Integer.MIN_VALUE;

    private final View header;
    private final VideoCardAdapter delegate;

    private final RecyclerView.AdapterDataObserver observer = new RecyclerView.AdapterDataObserver() {
        @Override
        public void onChanged() {
            notifyDataSetChanged();
        }

        @Override
        public void onItemRangeChanged(int positionStart, int itemCount) {
            notifyItemRangeChanged(positionStart + 1, itemCount);
        }

        @Override
        public void onItemRangeChanged(int positionStart, int itemCount, Object payload) {
            notifyItemRangeChanged(positionStart + 1, itemCount, payload);
        }

        @Override
        public void onItemRangeInserted(int positionStart, int itemCount) {
            notifyItemRangeInserted(positionStart + 1, itemCount);
        }

        @Override
        public void onItemRangeRemoved(int positionStart, int itemCount) {
            notifyItemRangeRemoved(positionStart + 1, itemCount);
        }

        @Override
        public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) {
            //收藏夹不用 move；真有 move 时逐条转，避免一次移动多条时索引对不上
            for (int i = 0; i < itemCount; i++) {
                int from = fromPosition + i + 1;
                int to = toPosition + i + 1;
                notifyItemMoved(from, to);
            }
        }
    };

    public VideoCardWithHeaderAdapter(@NonNull View header, @NonNull VideoCardAdapter delegate) {
        this.header = header;
        this.delegate = delegate;
        delegate.registerAdapterDataObserver(observer);
    }

    @NonNull
    public View getHeader() {
        return header;
    }

    @NonNull
    public VideoCardAdapter getDelegate() {
        return delegate;
    }

    @Override
    public int getItemCount() {
        return delegate.getItemCount() + 1;
    }

    @Override
    public int getItemViewType(int position) {
        return position == 0 ? TYPE_HEADER : delegate.getItemViewType(position - 1);
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            //header 是代码里 new 出来的 LinearLayout，没有 LayoutParams；交给 RecyclerView
            //默认的 WRAP_CONTENT 去量，里面的 match_parent 卡片 + 左右 margin 会被量成
            //「屏宽 + 两侧 margin」，在窄屏/大字号下左右各裁掉一条（2026-11-21 截图）。
            //显式钉成 MATCH_PARENT × WRAP_CONTENT，卡片宽度才会按 RecyclerView 的可用宽度算。
            header.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new HeaderHolder(header);
        }
        return delegate.onCreateViewHolder(parent, viewType);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof HeaderHolder) return;
        //noinspection unchecked
        delegate.onBindViewHolder((VideoCardHolder) holder, position - 1);
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof HeaderHolder) return;
        delegate.onViewRecycled((VideoCardHolder) holder);
    }

    @Override
    public void onViewAttachedToWindow(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof HeaderHolder) return;
        delegate.onViewAttachedToWindow((VideoCardHolder) holder);
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof HeaderHolder) return;
        delegate.onViewDetachedFromWindow((VideoCardHolder) holder);
    }

    private static final class HeaderHolder extends RecyclerView.ViewHolder {
        HeaderHolder(@NonNull View itemView) {
            super(itemView);
        }
    }
}