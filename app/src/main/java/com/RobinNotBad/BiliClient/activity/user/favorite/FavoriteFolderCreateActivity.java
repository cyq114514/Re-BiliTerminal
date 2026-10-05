package com.RobinNotBad.BiliClient.activity.user.favorite;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.RadioGroup;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.api.FavoriteApi;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;

import com.google.android.material.card.MaterialCardView;

public class FavoriteFolderCreateActivity extends BaseActivity {

    private EditText editTitle;
    private EditText editIntro;
    private RadioGroup radioPrivacy;
    private MaterialCardView btnSave;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_favorite_folder_edit);

        editTitle = findViewById(R.id.editTitle);
        editIntro = findViewById(R.id.editIntro);
        radioPrivacy = findViewById(R.id.radioPrivacy);
        btnSave = findViewById(R.id.btnSave);
        MaterialCardView btnDelete = findViewById(R.id.btnDelete);

        btnDelete.setVisibility(android.view.View.GONE);
        setPageName("创建收藏夹");

        btnSave.setOnClickListener(v -> createFolder());
    }

    private int selectedPrivacy() {
        return radioPrivacy.getCheckedRadioButtonId() == R.id.radioPrivate ? 1 : 0;
    }

    private void createFolder() {
        String title = editTitle.getText().toString().trim();
        if (title.isEmpty()) {
            MsgUtil.showMsg("请输入收藏夹名称");
            return;
        }

        String intro = editIntro.getText().toString().trim();
        int privacy = selectedPrivacy();
        btnSave.setClickable(false);

        CenterThreadPool.run(() -> {
            try {
                int result = FavoriteApi.addFolder(title, intro, privacy);
                runOnUiThread(() -> {
                    btnSave.setClickable(true);
                    if (result == 0) {
                        MsgUtil.showMsg("创建成功");
                        setResult(RESULT_OK);
                        finish();
                    } else {
                        MsgUtil.showMsg("创建失败，错误码：" + result);
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
}

