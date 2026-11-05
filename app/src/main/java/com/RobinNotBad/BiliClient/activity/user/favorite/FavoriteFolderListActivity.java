package com.RobinNotBad.BiliClient.activity.user.favorite;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;


import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity;
import com.RobinNotBad.BiliClient.adapter.favorite.FavoriteFolderAdapter;
import com.RobinNotBad.BiliClient.api.FavoriteApi;
import com.RobinNotBad.BiliClient.model.FavoriteFolder;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import java.util.ArrayList;

public class FavoriteFolderListActivity extends RefreshListActivity {

    private FavoriteFolderAdapter adapter;
    private ArrayList<FavoriteFolder> folderList;
    private long mid;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPageName("收藏");

        mid = SharedPreferencesUtil.getLong("mid", 0);
        folderList = new ArrayList<>();

        //注册下拉刷新：基类默认禁用且此前未注册，收藏夹数量此前只在创建/编辑回调后才刷新（X-03）
        setOnRefreshListener(this::loadFolders);
        loadFolders();
    }

    private void loadFolders() {
        CenterThreadPool.run(() -> {
            final java.util.List<FavoriteFolder> loaded;
            try {
                loaded = FavoriteApi.getFavoriteFolders(mid);
            } catch (Exception e) {
                loadFail(e);
                return;
            }
            //列表变更必须和 adapter 通知在同一个主线程消息里完成。
            //此前是在后台线程 folderList.clear()，而旧 adapter 仍持有同一个 list 引用：
            //itemCount 会从 N+2 瞬间掉到 2，而 RecyclerView 手里还留着 position=5 的 ViewHolder，
            //下一次 layout 就抛 "Inconsistency detected. Invalid view holder adapter position"
            //（2026-11-05 线上崩溃的直接原因）
            runOnUiThread(() -> applyFolders(loaded));
        });
    }

    private void applyFolders(java.util.List<FavoriteFolder> loaded) {
        if (isFinishing() || isDestroyed()) return;
        folderList.clear();
        folderList.addAll(loaded);
        if (adapter == null) {
            adapter = new FavoriteFolderAdapter(this, folderList, mid);
            adapter.setOnCreateClickListener(this::showCreateDialog);
            adapter.setOnLongClickListener(this::onFolderLongClick);
            setAdapter(adapter);
        } else {
            //刷新时复用同一个 adapter：数据与通知在同一帧内完成，RecyclerView 不会读到中间态
            adapter.notifyDataSetChanged();
        }
        setRefreshing(false);
    }

    private void onFolderLongClick(int position) {
        if (position < 0 || position >= folderList.size()) return;
        FavoriteFolder folder = folderList.get(position);
        if (folder.mediaId == 0) {
            MsgUtil.showMsg("无法获取收藏夹信息，请稍后重试");
            return;
        }
        Intent intent = new Intent(this, FavoriteFolderEditActivity.class);
        intent.putExtra("mediaId", folder.mediaId);
        intent.putExtra("title", folder.name);
        intent.putExtra("intro", "");
        intent.putExtra("isDefault", folder.isDefault);
        startActivityForResult(intent, 1);
    }

    private void showCreateDialog() {
        Intent intent = new Intent(this, FavoriteFolderCreateActivity.class);
        startActivityForResult(intent, 2);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode == 1 || requestCode == 2) && resultCode == RESULT_OK) {
            loadFolders();
        }
    }
}