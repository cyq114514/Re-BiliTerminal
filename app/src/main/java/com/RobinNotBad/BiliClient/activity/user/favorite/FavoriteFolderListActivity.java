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

        loadFolders();
    }

    private void loadFolders() {
        CenterThreadPool.run(() -> {
            try {
                folderList.clear();
                folderList.addAll(FavoriteApi.getFavoriteFolders(mid));
                adapter = new FavoriteFolderAdapter(this, folderList, mid);
                adapter.setOnCreateClickListener(() -> showCreateDialog());
                adapter.setOnLongClickListener(position -> {
                    if (position >= 0 && position < folderList.size()) {
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
                });
                setAdapter(adapter);
                setRefreshing(false);
            } catch (Exception e) {
                loadFail(e);
            }
        });
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