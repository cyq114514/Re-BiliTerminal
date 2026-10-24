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
import com.RobinNotBad.BiliClient.api.ImageApi;
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
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
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

        //手表/精简系统上常常没有相册（没有处理 ACTION_PICK 的 Activity），
        //直接 startActivityForResult 会抛 ActivityNotFoundException 把页面崩掉——
        //线上 4 条崩溃都是这个原因（sdk27 的手表机型）。这里先探测，再降级到系统文件选择器
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        if (intent.resolveActivity(getPackageManager()) == null) {
            intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            if (intent.resolveActivity(getPackageManager()) == null) {
                MsgUtil.showMsg("这台设备没有可用的图片选择器");
                return;
            }
        }
        try {
            startActivityForResult(intent, REQUEST_CODE_PICK_IMAGE);
        } catch (Exception e) {
            //探测通过但仍可能被系统拦下（部分厂商裁掉了返回的 Activity）
            MsgUtil.err("打开图片选择器失败", e);
        }
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
                //原图全尺寸解码在低内存设备/手表上必然 OOM（OutOfMemoryError 是 Error，
                //外层 catch(Exception) 接不住，表现就是"选张照片就闪退"）。
                //头像统一按最长边 1080 采样并重编码为 jpeg。
                ImageApi.PreparedImage prepared = ImageApi.prepareAvatar(this, imageUri);
                byte[] imageData = prepared.data;
                String fileName = prepared.fileName;

                Log.e("AvatarUpload", "File name: " + fileName + ", size: " + imageData.length);
                JSONObject result = UserInfoApi.uploadAvatar(imageData, fileName);
                Log.e("AvatarUpload", "Result: " + result.toString());
                int code = result.getInt("code");
                String message = result.optString("message", "");

                runOnUiThread(() -> {
                    isUploading = false;
                    //上传期间页面可能已经销毁：此时再动 View / 起 Glide 加载没有意义，
                    //Glide 还会抛 "You cannot start a load for a destroyed activity"
                    if (isFinishing() || isDestroyed()) return;

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
                                        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
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
            } catch (java.io.IOException e) {
                runOnUiThread(() -> {
                    isUploading = false;
                    MsgUtil.showMsgLong("上传头像失败：" + e.getMessage());
                });
            } catch (Throwable e) {
                //含 OutOfMemoryError：兜住后至少给出提示，不让进程直接死掉
                runOnUiThread(() -> {
                    isUploading = false;
                    MsgUtil.err("上传头像失败（图片可能过大）", e);
                });
            }
        });
    }
}
