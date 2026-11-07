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
        CenterThreadPool.run(() -> {
            try {
                UserInfo userInfo = UserInfoApi.getUserInfo(mid);
                if (userInfo == null) {
                    runOnUiThread(() -> {
                        MsgUtil.showMsg("用户不存在");
                        requireActivity().finish();
                    });
                    return;
                }
                Log.e("debug", "获取到用户信息");

                try {
                    //刷新=重新开始：游标与列表复位，下拉刷新复用同一入口
                    offset = 0;
                    dynamicList.clear();
                    offset = DynamicApi.getDynamicList(dynamicList, offset, mid, null);
                    bottom = (offset == -1);
                    Log.e("debug", "获取到用户动态");
                } catch (Exception e) {
                    loadFail(e);
                }

                if (isAdded()) {
                    adapter = new UserDynamicAdapter(requireContext(), dynamicList, userInfo);
                    //空动态不再整页替换成空态（用户反馈 2026-11-07：那样连个人信息都看不见了），
                    //改成在列表末尾追加一条占位，头像/昵称/签名/关注数始终留在屏幕上
                    adapter.setEmptyTip(dynamicList.isEmpty());
                    setAdapter(adapter);
                    setRefreshing(false);
                    hideEmptyView();
                }
            } catch (Exception e) {
                loadFail(e);
                showEmptyView("加载失败，请下拉重试");
            }
        });
    }

    @SuppressLint("NotifyDataSetChanged")
    private void continueLoading() {
        CenterThreadPool.run(() -> {
            try {
                List<Dynamic> list = new ArrayList<>();
                offset = DynamicApi.getDynamicList(list, offset, mid, null);
                runOnUiThread(() -> {
                    //首个加载是在 onViewCreated 的后台线程里建 adapter 的，用户在那之前就上拉时
                    //adapter 还是 null，直接 notify 会 NPE（线上 1 例）。这一页无处可放，
                    //丢掉即可——首屏那次加载会带全量数据
                    if (adapter == null) return;
                    dynamicList.addAll(list);
                    adapter.notifyItemRangeInserted(dynamicList.size() - list.size() + 1, list.size());
                });
                bottom = (offset == -1);
                setRefreshing(false);
            } catch (Exception e) {
                loadFail(e);
            }
        });
    }

    public void onDynamicRemove(int position) {
        try {
            DynamicHolder.removeDynamicFromList(dynamicList, position, adapter);
        } catch (Throwable ignored) {
        }
    }
}