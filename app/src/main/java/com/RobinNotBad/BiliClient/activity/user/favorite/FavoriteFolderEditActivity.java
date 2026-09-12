package com.RobinNotBad.BiliClient.activity.user.favorite;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.api.FavoriteApi;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;

public class FavoriteFolderEditActivity extends BaseActivity {

    private long mediaId;
    private String originalTitle;
    private boolean isDefault;
    private EditText editTitle;
    private EditText editIntro;
    private com.google.android.material.card.MaterialCardView btnSave;
    private com.google.android.material.card.MaterialCardView btnDelete;
    private int deleteClickCount = 0;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_favorite_folder_edit);

        Intent intent = getIntent();
        mediaId = intent.getLongExtra("mediaId", 0);
        originalTitle = intent.getStringExtra("title");
        String intro = intent.getStringExtra("intro");
        isDefault = intent.getBooleanExtra("isDefault", false);

        editTitle = findViewById(R.id.editTitle);
        editIntro = findViewById(R.id.editIntro);
        btnSave = findViewById(R.id.btnSave);
        btnDelete = findViewById(R.id.btnDelete);

        if (originalTitle != null) {
            editTitle.setText(originalTitle);
        }
        if (intro != null) {
            editIntro.setText(intro);
        }

        if (isDefault) {
            editTitle.setEnabled(false);
            editIntro.setEnabled(false);
            btnSave.setClickable(false);
            btnSave.setAlpha(0.5f);
            btnDelete.setVisibility(android.view.View.GONE);
            MsgUtil.showMsg("默认收藏夹不能编辑或删除");
        } else {
            btnSave.setOnClickListener(v -> saveFolder());
            btnDelete.setOnClickListener(v -> handleDeleteClick());
        }
    }

    private void saveFolder() {
        String title = editTitle.getText().toString().trim();
        if (title.isEmpty()) {
            MsgUtil.showMsg("请输入收藏夹名称");
            return;
        }

        String intro = editIntro.getText().toString().trim();
        btnSave.setClickable(false);

        CenterThreadPool.run(() -> {
            try {
                int result = FavoriteApi.editFolder(mediaId, title, intro, 0);
                runOnUiThread(() -> {
                    btnSave.setClickable(true);
                    if (result == 0) {
                        MsgUtil.showMsg("保存成功");
                        setResult(RESULT_OK);
                        finish();
                    } else {
                        MsgUtil.showMsg("保存失败，错误码：" + result);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    btnSave.setClickable(true);
                    report(e);
                });
            }
        });
    }

    private void handleDeleteClick() {
        deleteClickCount++;
        if (deleteClickCount == 1) {
            MsgUtil.showMsg("再次点击删除按钮确认删除");
            android.os.Handler handler = new android.os.Handler();
            handler.postDelayed(() -> deleteClickCount = 0, 3000);
        } else if (deleteClickCount >= 2) {
            deleteClickCount = 0;
            deleteFolder();
        }
    }

    private void deleteFolder() {
        btnDelete.setClickable(false);

        CenterThreadPool.run(() -> {
            try {
                int result = FavoriteApi.deleteFolder(mediaId);
                runOnUiThread(() -> {
                    btnDelete.setClickable(true);
                    if (result == 0) {
                        MsgUtil.showMsg("删除成功");
                        setResult(RESULT_OK);
                        finish();
                    } else {
                        MsgUtil.showMsg("删除失败，错误码：" + result);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    btnDelete.setClickable(true);
                    report(e);
                });
            }
        });
    }
}

