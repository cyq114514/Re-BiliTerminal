package com.RobinNotBad.BiliClient.activity.user;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity;
import com.RobinNotBad.BiliClient.adapter.user.FollowGroupAdapter;
import com.RobinNotBad.BiliClient.adapter.user.UserListAdapter;
import com.RobinNotBad.BiliClient.api.FollowApi;
import com.RobinNotBad.BiliClient.model.FollowTag;
import com.RobinNotBad.BiliClient.model.UserInfo;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import java.util.ArrayList;
import java.util.List;

//关注列表
//2023-07-22
//2024-05-01

public class FollowUsersActivity extends RefreshListActivity {

    private long mid;
    private ArrayList<UserInfo> userList;
    private UserListAdapter adapter;
    private FollowGroupAdapter groupAdapter;
    private int mode;
    private boolean groupMode;
    /** 「分享至消息 → 更多」：点某个用户直接回传，不打开主页 */
    private boolean pickMode;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mode = getIntent().getIntExtra("mode", 0);
        mid = getIntent().getLongExtra("mid", -1);
        pickMode = getIntent().getBooleanExtra("pick_mode", false);

        if (mode < 0 || mode > 1 || mid == -1) {
            finish();
            return;
        }

        long currentUserMid = SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0);
        //选人模式固定用平铺列表（分组模式是折叠面板，点选语义不同）
        groupMode = !pickMode && mode == 0 && mid == currentUserMid
                && SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.FOLLOW_GROUP_MODE, false);

        setPageName(pickMode ? "选择用户" : (mode == 0 ? "关注列表" : "粉丝列表"));

        recyclerView.setHasFixedSize(true);

        userList = new ArrayList<>();

        if (groupMode) {
            loadGroupMode();
        } else {
            loadNormalMode();
        }
    }

    private void loadNormalMode() {
        CenterThreadPool.run(() -> {
            try {
                int result = mode == 0 ? FollowApi.getFollowingList(mid, page, userList) : FollowApi.getFollowerList(mid, page, userList);
                adapter = new UserListAdapter(this, userList);
                if (pickMode) {
                    adapter.setOnUserPickListener(user -> {
                        Intent data = new Intent();
                        data.putExtra("mid", user.mid);
                        data.putExtra("name", user.name);
                        data.putExtra("avatar", user.avatar);
                        setResult(RESULT_OK, data);
                        finish();
                    });
                }
                setOnLoadMoreListener(this::continueLoading);
                setRefreshing(false);
                setAdapter(adapter);

                if (result == 1) {
                    Log.e("debug", "到底了");
                    setBottom(true);
                }
            } catch (Exception e) {
                if (e.getMessage() != null && (e.getMessage().startsWith("22115") || e.getMessage().startsWith("22118"))) {
                    finish();
                    MsgUtil.showMsg(e.getMessage());
                } else {
                    loadFail(e);
                }
            }
        });
    }

    private void loadGroupMode() {
        CenterThreadPool.run(() -> {
            try {
                List<FollowTag> tagList = FollowApi.getFollowTags();
                runOnUiThread(() -> {
                    groupAdapter = new FollowGroupAdapter(this);
                    groupAdapter.setOnGroupExpandListener(tagid -> loadGroupUsers(tagid));
                    setAdapter(groupAdapter);
                    for (FollowTag tag : tagList) {
                        if (tag.count > 0) {
                            groupAdapter.addGroup(tag, new ArrayList<>());
                        }
                    }
                    groupAdapter.notifyDataSetChanged();
                    setRefreshing(false);
                });
            } catch (Exception e) {
                if (e.getMessage() != null && (e.getMessage().startsWith("22115") || e.getMessage().startsWith("22118"))) {
                    finish();
                    MsgUtil.showMsg(e.getMessage());
                } else {
                    loadFail(e);
                }
            }
        });
    }

    public void loadGroupUsers(int tagid) {
        CenterThreadPool.run(() -> {
            try {
                List<UserInfo> tagUsers = new ArrayList<>();
                int result = FollowApi.getFollowTagUsers(tagid, 1, tagUsers);
                runOnUiThread(() -> {
                    groupAdapter.updateGroupUsers(tagid, tagUsers);
                });
                if (result == 0 && tagUsers.size() == 20) {
                    loadMoreGroupUsers(tagid, tagUsers.size());
                }
            } catch (Exception e) {
                Log.e("debug", "加载分组用户失败", e);
            }
        });
    }

    private void loadMoreGroupUsers(int tagid, int currentCount) {
        CenterThreadPool.run(() -> {
            try {
                int page = (currentCount / 20) + 1;
                List<UserInfo> tagUsers = new ArrayList<>();
                int result = FollowApi.getFollowTagUsers(tagid, page, tagUsers);
                runOnUiThread(() -> {
                    groupAdapter.addGroupUsers(tagid, tagUsers);
                });
                if (result == 0 && tagUsers.size() == 20) {
                    loadMoreGroupUsers(tagid, currentCount + tagUsers.size());
                }
            } catch (Exception e) {
                Log.e("debug", "加载分组用户失败", e);
            }
        });
    }

    private void continueLoading(int page) {
        if (groupMode) {
            setRefreshing(false);
            return;
        }
        CenterThreadPool.run(() -> {
            try {
                List<UserInfo> list = new ArrayList<>();
                int result = mode == 0 ? FollowApi.getFollowingList(mid, page, list) : FollowApi.getFollowerList(mid, page, list);
                Log.e("debug", "下一页");
                runOnUiThread(() -> {
                    userList.addAll(list);
                    adapter.notifyItemRangeInserted(userList.size() - list.size(), list.size());
                });
                if (result == 1) {
                    Log.e("debug", "到底了");
                    setBottom(true);
                }
                setRefreshing(false);
            } catch (Exception e) {
                if (e.getMessage() != null && (e.getMessage().startsWith("22115") || e.getMessage().startsWith("22118"))) {
                    finish();
                    MsgUtil.showMsg(e.getMessage());
                } else {
                    loadFail(e);
                }
            }
        });
    }
}