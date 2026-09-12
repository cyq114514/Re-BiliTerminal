package com.RobinNotBad.BiliClient.ui.widget.recycler;

import android.annotation.SuppressLint;
import android.content.Context;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public abstract class BaseAdapter<M, VH extends BaseHolder> extends AbstractAdapter<VH> {
    private final List<M> dataList;

    public int getViewType(int position) {
        return 0;
    }

    public BaseAdapter(Context context) {
        super(context);
        this.dataList = new ArrayList<>();
    }

    public BaseAdapter(Context context, List<M> dataList) {
        super(context);
        this.dataList = new ArrayList<>();
        this.dataList.addAll(dataList);
    }

    @SuppressLint("NotifyDataSetChanged")
    public boolean fillList(List<M> list) {
        this.dataList.clear();
        boolean result = this.dataList.addAll(list);
        notifyDataSetChanged();
        return result;
    }

    public boolean appendItem(M item) {
        if (item == null)
            return false;
        int size = this.dataList.size();
        boolean result = this.dataList.add(item);
        if (result) {
            notifyItemInserted(size + getHeaderViewCount());
        }
        return result;
    }

    public boolean appendList(List<M> list) {
        if (list == null || list.isEmpty())
            return false;
        int size = this.dataList.size();
        boolean result = this.dataList.addAll(list);
        if (result) {
            notifyItemRangeInserted(size + getHeaderViewCount(), list.size());
        }
        return result;
    }

    public void preposeItem(M item) {
        if (item == null)
            return;
        this.dataList.add(0, item);
        notifyItemInserted(getHeaderViewCount());
        notifyItemRangeChanged(getHeaderViewCount(), getItemCount());
    }

    public void preposeList(List<M> list) {
        if (list == null || list.isEmpty())
            return;
        this.dataList.addAll(0, list);
        notifyItemRangeInserted(getHeaderViewCount(), list.size());
    }

    public void updateItem(int position, M item) {
        if (position < 0 || position >= this.dataList.size() || item == null)
            return;
        this.dataList.set(position, item);
        notifyItemChanged(getHeaderViewCount() + position);
    }

    public void updateItem(M originalItem, M newItem) {
        if (originalItem == null || newItem == null)
            return;
        int index = this.dataList.indexOf(originalItem);
        if (index >= 0 && index < this.dataList.size()) {
            this.dataList.set(index, newItem);
            notifyItemChanged(getHeaderViewCount() + index);
        }
    }

    public void removeItem(int position) {
        int realPosition = position - getHeaderViewCount();
        if (realPosition < 0 || realPosition >= this.dataList.size())
            return;
        this.dataList.remove(realPosition);
        notifyItemRemoved(position);
        notifyItemRangeChanged(position, getItemCount() - position);
    }

    public void removeItem(M item) {
        if (item == null)
            return;
        int index = this.dataList.indexOf(item);
        if (index >= 0 && index < this.dataList.size()) {
            this.dataList.remove(index);
            int position = getHeaderViewCount() + index;
            notifyItemRemoved(position);
            notifyItemRangeChanged(position, getItemCount() - position);
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    public void clearData() {
        this.dataList.clear();
        notifyDataSetChanged();
    }

    @Override
    public final int getItemViewType(int position) {
        if (this.headerView != null && position == 0) {
            return VIEW_TYPE_HEADER;
        } else if (this.footerView != null && position == this.dataList.size() + getHeaderViewCount()) {
            return VIEW_TYPE_FOOTER;
        }
        return getViewType(position);
    }

    @Override
    public int getItemCount() {
        return this.dataList.size() + getExtraViewCount();
    }

    @Nullable
    public M getItem(int position) {
        if (position < 0)
            return null;
        int realPosition = position - getHeaderViewCount();
        if (realPosition < 0 || realPosition >= this.dataList.size()) {
            return null;
        }
        return this.dataList.get(realPosition);
    }

    @Nullable
    public M getItem(VH vh) {
        if (vh == null)
            return null;
        int position = vh.getAdapterPosition();
        if (position == RecyclerView.NO_POSITION)
            return null;
        return getItem(position);
    }

    public List<M> getAllData() {
        return this.dataList;
    }
}
