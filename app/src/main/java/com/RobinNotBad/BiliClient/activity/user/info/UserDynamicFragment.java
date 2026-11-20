package com.RobinNotBad.BiliClient.activity.user.info;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;

import com.RobinNotBad.BiliClient.activity.base.RefreshListFragment;
import com.RobinNotBad.BiliClient.adapter.dynamic.DynamicHolder;
import com.RobinNotBad.BiliClient.adapter.dynamic.UserDynamicAdapter;
import com.RobinNotBad.BiliClient.api.DynamicApi;
import com.RobinNotBad.BiliClient.api.UserInfoApi;
import com.RobinNotBad.BiliClient.model.Dynamic;
import com.RobinNotBad.BiliClient.model.UserInfo;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;

import java.util.ArrayList;
import java.util.List;

//用户动态
//2023-09-30
//2024-05-03

public class UserDynamicFragment extends RefreshListFragment {

    private long mid;
    private ArrayList<Dynamic> dynamicList;
    private UserDynamicAdapter adapter;
    private long offset = 0;
    /**
     * 刷新世代：每次 loadDynamics 自增，在途的旧请求回来时直接丢弃。
     * 用户主页下拉刷新/加载更多会并发，只有一代数据允许落到 adapter。
     */
    private int loadGeneration = 0;

    public UserDynamicFragment() {

    }

    public static UserDynamicFragment newInstance(long mid) {
        UserDynamicFragment fragment = new UserDynamicFragment();
        Bundle args = new Bundle();
        args.putLong("mid", mid);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            mid = getArguments().getLong("mid");
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        dynamicList = new ArrayList<>();
        setOnLoadMoreListener(page -> continueLoading());
        //注册下拉刷新+空态/失败态（X-26）
        setOnRefreshListener(() -> loadDynamics());
        loadDynamics();
    }

    private void loadDynamics() {
        //刷新=新世代：先作废所有在途请求，后台只构建局部 list，主线程一次性替换引用
        final int gen = ++loadGeneration;
        CenterThreadPool.run(() -> {
            try {
                UserInfo userInfo = UserInfoApi.getUserInfo(mid);
                if (userInfo == null) {
                    runOnUiThread(() -> {
                        if (gen != loadGeneration || !isAdded()) return;
                        MsgUtil.showMsg("用户不存在");
                        requireActivity().finish();
                    });
                    return;
                }
                Log.e("debug", "获取到用户信息");

                final ArrayList<Dynamic> fresh = new ArrayList<>();
                final long newOffset;
                try {
                    newOffset = DynamicApi.getDynamicList(fresh, 0L, mid, null);
                    Log.e("debug", "获取到用户动态");
                } catch (Exception e) {
                    loadFail(e);
                    return;
                }

                runOnUiThread(() -> {
                    //必须同时校验世代与 isAdded：页面/刷新已换代时，旧结果直接丢弃
                    if (!isAdded() || getContext() == null || gen != loadGeneration) return;
                    offset = newOffset;
                    bottom = (newOffset == -1);
                    //换引用而不是 clear：旧 adapter 仍挂载期间它的 list 一个字节都不动，
                    //RecyclerView 不会看到 itemCount 在两次 layout 之间突变（线上 3 例 Inconsistency 的根因）
                    dynamicList = fresh;
                    UserDynamicAdapter next = new UserDynamicAdapter(requireContext(), fresh, userInfo);
                    //空动态不再整页替换成空态（用户反馈 2026-11-07：那样连个人信息都看不见了），
                    //改成在列表末尾追加一条占位，头像/昵称/签名/关注数始终留在屏幕上
                    next.setEmptyTip(fresh.isEmpty());
                    adapter = next;
                    setAdapter(next);
                    setRefreshing(false);
                    if (fresh.isEmpty()) showEmptyView("TA 还没有发布过动态~");
                    else hideEmptyView();
                });
            } catch (Exception e) {
                //旧世代失败的提示不能盖掉新世代：只有仍是当前刷新时才报错
                if (gen == loadGeneration) {
                    loadFail(e);
                    showEmptyView("加载失败，请下拉重试");
                }
            }
        });
    }

    @SuppressLint("NotifyDataSetChanged")
    private void continueLoading() {
        //绑定当前世代；刷新一旦发生，这一页结果必须整页丢弃，不能插到新表里
        final int gen = loadGeneration;
        final long reqOffset = offset;
        CenterThreadPool.run(() -> {
            try {
                final List<Dynamic> list = new ArrayList<>();
                final long newOffset = DynamicApi.getDynamicList(list, reqOffset, mid, null);
                runOnUiThread(() -> {
                    //adapter 身份 + 世代双守卫：Fragment 的 adapter 字段可能已被刷新换代，
                    //而 RecyclerView 还挂着旧 adapter；两者不一致时不能 notify。
                    if (!isAdded() || gen != loadGeneration) return;
                    if (adapter == null || recyclerView == null
                            || recyclerView.getAdapter() != adapter) return;
                    if (list.isEmpty()) {
                        bottom = (newOffset == -1);
                        setRefreshing(false);
                        return;
                    }
                    boolean tipWasShown = adapter.isEmptyTip();
                    int dataStart = dynamicList.size();          //插入前的数据条数
                    dynamicList.addAll(list);
                    if (tipWasShown) {
                        //0→N：原空态占位在 last position，插入会把它顶出边界，
                        //必须复位 emptyTip 并整表刷新（否则位置校验会失败）
                        adapter.setEmptyTip(false);
                        adapter.notifyDataSetChanged();
                    } else {
                        //+1 = 列表第 0 项的个人信息头
                        adapter.notifyItemRangeInserted(dataStart + 1, list.size());
                    }
                    offset = newOffset;
                    bottom = (newOffset == -1);
                    setRefreshing(false);
                });
            } catch (Exception e) {
                if (gen == loadGeneration) loadFail(e);
            }
        });
    }

    public void onDynamicRemove(int position) {
        try {
            //返回时列表可能已经刷新换代：只允许在“当前 adapter 仍挂在列表上”时接受旧下标
            if (adapter == null || recyclerView == null || recyclerView.getAdapter() != adapter) return;
            if (position < 0 || position >= dynamicList.size()) return;
            DynamicHolder.removeDynamicFromList(dynamicList, position, adapter);
        } catch (Throwable ignored) {
        }
    }
}