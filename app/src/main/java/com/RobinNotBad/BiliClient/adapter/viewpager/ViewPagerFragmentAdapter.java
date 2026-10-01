package com.RobinNotBad.BiliClient.adapter.viewpager;

import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

//ViewPagerAdapter，适用于各类需要翻页的场景

//基类用 FragmentPagerAdapter 而不是 FragmentStatePagerAdapter：
//1. 这里的翻页都是固定少量页（登录/详情/搜索等 2~4 个 tab），本就该常驻；
//2. FragmentStatePagerAdapter 在页面销毁重建或恢复时会重新调 getItem()，
//   而 getItem 返回的是调用方共享的实例，重复 add 会抛 IllegalStateException("Fragment already added")；
//   FragmentPagerAdapter 会先按 tag 找回 FragmentManager 里的已有实例并 attach，不会重复添加。
public class ViewPagerFragmentAdapter extends FragmentPagerAdapter {

    private final List<Fragment> fragmentList;
    final FragmentManager fm;
    private final Map<Integer, Fragment> instantiatedFragments = new HashMap<>();

    public ViewPagerFragmentAdapter(@NonNull FragmentManager fm, List<Fragment> fragmentList) {
        super(fm, BEHAVIOR_SET_USER_VISIBLE_HINT);
        this.fragmentList = fragmentList;
        this.fm = fm;
    }

    @NonNull
    @Override
    public Fragment getItem(int position) {
        if (position < 0 || position >= fragmentList.size()) {
            return new Fragment();
        }
        return fragmentList.get(position);
    }

    @NonNull
    @Override
    public Object instantiateItem(@NonNull ViewGroup container, int position) {
        Fragment fragment = (Fragment) super.instantiateItem(container, position);
        instantiatedFragments.put(position, fragment);
        return fragment;
    }

    @Override
    public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
        instantiatedFragments.remove(position);
        super.destroyItem(container, position, object);
    }

    public Fragment getFragment(int position) {
        return instantiatedFragments.get(position);
    }

    @Override
    public int getCount() {
        return fragmentList != null ? fragmentList.size() : 0;
    }
}
