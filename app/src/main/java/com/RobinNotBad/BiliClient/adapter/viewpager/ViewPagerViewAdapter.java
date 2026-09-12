package com.RobinNotBad.BiliClient.adapter.viewpager;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.viewpager.widget.PagerAdapter;

import java.util.List;

//图片查看专用Adapter

public class ViewPagerViewAdapter extends PagerAdapter {

    private final List<? extends View> viewList;

    public ViewPagerViewAdapter(List<? extends View> viewList) {
        this.viewList = viewList;
    }

    @Override
    public int getCount() {
        return viewList != null ? viewList.size() : 0;
    }

    @Override
    public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
        return view == object;
    }

    @NonNull
    @Override
    public Object instantiateItem(@NonNull ViewGroup container, int position) {
        if (viewList == null || position < 0 || position >= viewList.size()) {
            return new View(container.getContext());
        }
        View view = viewList.get(position);
        if (view != null && view.getParent() == null) {
            container.addView(view);
        }
        return view != null ? view : new View(container.getContext());
    }

    @Override
    public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
        if (object instanceof View) {
            container.removeView((View) object);
        }
    }
}
