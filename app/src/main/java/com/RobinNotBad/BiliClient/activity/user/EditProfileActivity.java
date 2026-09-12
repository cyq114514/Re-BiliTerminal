package com.RobinNotBad.BiliClient.activity.user;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.ImageView;
import android.widget.TextView;
import android.util.Log;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.api.UserInfoApi;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.GlideUtil;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class EditProfileActivity extends BaseActivity {

    private static final int REQUEST_CODE_PICK_IMAGE = 1001;

    private ImageView avatarIcon;
    private MaterialCardView uploadAvatarCard, editSignCard;
    private boolean isUploading = false;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_profile);

        if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) == 0) {
            MsgUtil.showMsg("还没有登录喵~");
            finish();
            return;
        }

        avatarIcon = findViewById(R.id.upload_avatar_icon);
        uploadAvatarCard = findViewById(R.id.upload_avatar);
        editSignCard = findViewById(R.id.edit_sign);
        MaterialCardView editUserInfoCard = findViewById(R.id.edit_user_info);

        // 加载当前头像
        String currentAvatar = SharedPreferencesUtil.getString("avatar", "");
        if (!currentAvatar.isEmpty()) {
            Glide.with(this)
                    .load(GlideUtil.url(currentAvatar))
                    .apply(RequestOptions.circleCropTransform())
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .into(avatarIcon);
        }

        uploadAvatarCard.setOnClickListener(view -> selectImage());

        editSignCard.setOnClickListener(view -> {
            Intent intent = new Intent(EditProfileActivity.this, EditSignActivity.class);
            startActivity(intent);
        });

        editUserInfoCard.setOnClickListener(view -> {
            Intent intent = new Intent(EditProfileActivity.this, EditUserInfoActivity.class);
            startActivity(intent);
        });

        // 返回按钮
        findViewById(R.id.pageName).setOnClickListener(view -> finish());
    }

    private void selectImage() {
        if (isUploading) {
            MsgUtil.showMsg("正在上传中...");
            return;
        }

        if (!SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.cookie_refresh, true)) {
            MsgUtil.showDialog("无法上传", "上一次的Cookie刷新失败了，\n您可能需要重新登录以进行敏感操作", -1);
            return;
        }

        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        startActivityForResult(intent, REQUEST_CODE_PICK_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            Uri imageUri = data.getData();
            if (imageUri != null) {
                uploadAvatar(imageUri);
            }
        }
    }

    private void uploadAvatar(Uri imageUri) {
        isUploading = true;
        MsgUtil.showMsg("正在上传头像...");

        CenterThreadPool.run(() -> {
            try {
                InputStream inputStream = getContentResolver().openInputStream(imageUri);
                if (inputStream == null) {
                    runOnUiThread(() -> {
                        isUploading = false;
                        MsgUtil.showMsg("无法读取图片");
                    });
                    return;
                }

                Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
                inputStream.close();
                
                if (bitmap == null) {
                    runOnUiThread(() -> {
                        isUploading = false;
                        MsgUtil.showMsg("无法解码图片");
                    });
                    return;
                }

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos);
                byte[] imageData = baos.toByteArray();
                String fileName = "avatar_" + System.currentTimeMillis() + ".jpg";

                Log.e("AvatarUpload", "File name: " + fileName + ", size: " + imageData.length);
                JSONObject result = UserInfoApi.uploadAvatar(imageData, fileName);
                Log.e("AvatarUpload", "Result: " + result.toString());
                int code = result.getInt("code");
                String message = result.optString("message", "");

                runOnUiThread(() -> {
                    isUploading = false;

                    if (code == 0) {
                        MsgUtil.showMsg("头像上传成功，等待审核");
                        // 可以尝试更新本地缓存的头像URL
                        JSONObject data = result.optJSONObject("data");
                        if (data != null) {
                            String faceUrl = data.optString("url", "");
                            if (!faceUrl.isEmpty()) {
                                SharedPreferencesUtil.putString("avatar", faceUrl);
                                Glide.with(EditProfileActivity.this)
                                        .load(GlideUtil.url(faceUrl))
                                        .apply(RequestOptions.circleCropTransform())
                                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                                        .into(avatarIcon);
                            }
                        }
                    } else {
                        String errorMsg;
                        if (code == -101) {
                            errorMsg = "账号未登录";
                        } else if (code == -102) {
                            errorMsg = "CSRF校验失败";
                        } else if (code == -111) {
                            errorMsg = "图片格式不支持";
                        } else if (code == -112) {
                            errorMsg = "图片过大";
                        } else if (code == -400) {
                            errorMsg = "请求参数错误";
                        } else if (code == -403) {
                            errorMsg = "CSRF验证失败";
                        } else if (!message.isEmpty()) {
                            errorMsg = message;
                        } else {
                            errorMsg = "上传失败";
                        }
                        MsgUtil.showMsg(errorMsg + " (错误码:" + code + ")");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    isUploading = false;
                    MsgUtil.err("上传头像失败", e);
                });
            }
        });
    }
}
