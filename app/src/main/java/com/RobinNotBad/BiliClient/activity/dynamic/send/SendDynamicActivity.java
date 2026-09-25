package com.RobinNotBad.BiliClient.activity.dynamic.send;

import android.annotation.SuppressLint;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.TimePicker;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.EmoteActivity;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.adapter.dynamic.DynamicHolder;
import com.RobinNotBad.BiliClient.adapter.video.VideoCardHolder;
import com.RobinNotBad.BiliClient.api.DynamicApi;
import com.RobinNotBad.BiliClient.api.EmoteApi;
import com.RobinNotBad.BiliClient.api.ImageApi;
import com.RobinNotBad.BiliClient.model.Dynamic;
import com.RobinNotBad.BiliClient.model.VideoInfo;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.RobinNotBad.BiliClient.util.TerminalContext;
import com.RobinNotBad.BiliClient.util.ToolsUtil;
import com.bumptech.glide.Glide;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 发送动态输入Activity，直接copy的WriteReplyActivity
 * 换成了ActivityResult
 * （我并不怎么会写）
 * 支持带图（upload_bfs上传后scene=2发布）、转发自动引用、
 * 发布选项（可见范围/评论设置/定时发布）与编辑已有文字动态（editId）。
 */
public class SendDynamicActivity extends BaseActivity {

    private static final int MAX_IMAGE_COUNT = 9;

    EditText editText;

    private final List<Uri> imageUris = new ArrayList<>();
    private final Map<Uri, ImageApi.UploadedImage> uploadedCache = new HashMap<>();
    private LinearLayout picsLayout;
    private HorizontalScrollView picsPreview;
    private boolean sending = false;

    //发布选项状态
    private boolean privatePub = false;
    private boolean closeComment = false;
    private String timerPubTime = null;
    private TextView optVisibilityText, optCommentText, optTimerText;

    //编辑模式（>0时为编辑已有文字动态）
    private long editId = 0;

    private final ActivityResultLauncher<Intent> emoteLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), (result) -> {
        int code = result.getResultCode();
        Intent data = result.getData();
        if (code == RESULT_OK && data != null && data.hasExtra("text")) {
            editText.append(data.getStringExtra("text"));
        }
    });

    private final ActivityResultLauncher<String> pickImageLauncher = registerForActivityResult(new ActivityResultContracts.GetMultipleContents(), (List<Uri> uris) -> {
        if (uris == null || uris.isEmpty()) return;
        for (Uri uri : uris) {
            if (imageUris.size() >= MAX_IMAGE_COUNT) {
                MsgUtil.showMsg("一次最多带" + MAX_IMAGE_COUNT + "张图喵~");
                break;
            }
            if (!imageUris.contains(uri)) {
                imageUris.add(uri);
                addPicPreview(uri);
            }
        }
        picsPreview.setVisibility(imageUris.isEmpty() ? View.GONE : View.VISIBLE);
    });

    @SuppressLint("InflateParams")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        asyncInflate(R.layout.activity_send_dynamic, (layoutView, resId) -> {

            if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) == 0) {
                setResult(RESULT_CANCELED);
                finish();
                MsgUtil.showMsg("还没有登录喵~");
            }

            editText = findViewById(R.id.editText);
            MaterialCardView send = findViewById(R.id.send);
            MaterialCardView addPic = findViewById(R.id.addPic);
            MaterialCardView optVisibility = findViewById(R.id.optVisibility);
            MaterialCardView optComment = findViewById(R.id.optComment);
            MaterialCardView optTimer = findViewById(R.id.optTimer);
            optVisibilityText = findViewById(R.id.optVisibilityText);
            optCommentText = findViewById(R.id.optCommentText);
            optTimerText = findViewById(R.id.optTimerText);
            picsPreview = findViewById(R.id.picsPreview);
            picsLayout = findViewById(R.id.picsLayout);

            editId = getIntent().getLongExtra("editId", 0);
            if (editId != 0) {
                //编辑模式：预填原文，仅支持改文字，隐藏图片与发布选项
                editText.setText(getIntent().getStringExtra("editText"));
                addPic.setVisibility(View.GONE);
                optVisibility.setVisibility(View.GONE);
                optComment.setVisibility(View.GONE);
                optTimer.setVisibility(View.GONE);
            }

            FrameLayout extraCard = findViewById(R.id.forwardCard);
            VideoInfo video = null;
            Dynamic forward = null;
            if (TerminalContext.getInstance().getForwardContent() instanceof VideoInfo) {
                video = (VideoInfo) TerminalContext.getInstance().getForwardContent();
            } else {
                forward = (Dynamic) TerminalContext.getInstance().getForwardContent();
            }
            if (forward != null) {
                View childCard = View.inflate(this, R.layout.cell_dynamic, extraCard);
                DynamicHolder holder = new DynamicHolder(childCard, this, false);
                holder.showDynamic(this, forward, false);
            } else if (video != null) {
                VideoCardHolder holder = new VideoCardHolder(LayoutInflater.from(this).inflate(R.layout.cell_video_list, extraCard));
                holder.showVideoCard(video.toCard(), this);
            }
            //转发与编辑场景不支持带图和发布选项，隐藏入口
            boolean normalPublish = forward == null && video == null && editId == 0;
            addPic.setVisibility(normalPublish ? View.VISIBLE : View.GONE);
            optVisibility.setVisibility(normalPublish ? View.VISIBLE : View.GONE);
            optComment.setVisibility(normalPublish ? View.VISIBLE : View.GONE);
            optTimer.setVisibility(normalPublish ? View.VISIBLE : View.GONE);

            //发布选项：点击切换/选择
            optVisibility.setOnClickListener(view -> {
                privatePub = !privatePub;
                optVisibilityText.setText(privatePub ? "仅自己可见" : "所有人可见");
            });
            optComment.setOnClickListener(view -> {
                closeComment = !closeComment;
                optCommentText.setText(closeComment ? "关闭评论" : "允许评论");
            });
            optTimer.setOnClickListener(view -> {
                if (timerPubTime != null) {
                    timerPubTime = null;
                    optTimerText.setText("定时发布：关");
                    return;
                }
                Calendar now = Calendar.getInstance();
                new DatePickerDialog(this, (DatePicker dp, int year, int month, int day) -> {
                    Calendar picked = Calendar.getInstance();
                    picked.set(year, month, day, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE));
                    new TimePickerDialog(this, (TimePicker tp, int hour, int minute) -> {
                        picked.set(Calendar.HOUR_OF_DAY, hour);
                        picked.set(Calendar.MINUTE, minute);
                        //B站限制：6分钟后～7天内
                        long diff = picked.getTimeInMillis() - System.currentTimeMillis();
                        if (diff < 5 * 60 * 1000L) {
                            MsgUtil.showMsg("定时时间至少要在6分钟后喵~");
                            return;
                        }
                        if (diff > 7 * 24 * 3600 * 1000L) {
                            MsgUtil.showMsg("定时时间不能超过7天喵~");
                            return;
                        }
                        timerPubTime = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(picked.getTime());
                        optTimerText.setText("定时发布：" + timerPubTime);
                    }, picked.get(Calendar.HOUR_OF_DAY), picked.get(Calendar.MINUTE), true).show();
                }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show();
            });

            send.setOnClickListener(view -> {
                // 不了解遂直接保留cookie刷新判断了
                if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.cookie_refresh, true)) {
                    if (sending) {
                        MsgUtil.showMsg("正在发送中");
                        return;
                    }
                    String text = editText.getText().toString();
                    if (editId != 0) {
                        //编辑已有动态
                        if (text.isEmpty()) {
                            MsgUtil.showMsg("还没输入内容呢~");
                            return;
                        }
                        sending = true;
                        MsgUtil.showMsg("正在保存修改...");
                        CenterThreadPool.run(() -> {
                            try {
                                Set<String> emoteTexts = EmoteApi.getEmoteTexts(EmoteApi.BUSINESS_DYNAMIC);
                                long result = DynamicApi.editDynamic(editId, text, emoteTexts);
                                runOnUiThread(() -> {
                                    if (result != -1) {
                                        setResult(RESULT_OK, new Intent().putExtra("editOk", "1"));
                                        finish();
                                    } else {
                                        sending = false;
                                    }
                                });
                            } catch (Exception e) {
                                runOnUiThread(() -> {
                                    sending = false;
                                    MsgUtil.err(e);
                                });
                            }
                        });
                    } else if (imageUris.isEmpty()) {
                        Intent result = new Intent();
                        // 原神级的传数据
                        Bundle bundle = SendDynamicActivity.this.getIntent().getExtras();
                        if (bundle != null) result.putExtras(bundle);
                        result.putExtra("text", text);
                        result.putExtra("options", buildOptionsJson());
                        setResult(RESULT_OK, result);
                        finish();
                    } else {
                        sending = true;
                        MsgUtil.showMsg("正在上传图片...");
                        CenterThreadPool.run(() -> {
                            try {
                                //快照避免上传期间用户移除图片导致并发修改异常
                                List<Uri> toUpload = new ArrayList<>(imageUris);
                                JSONArray pics = new JSONArray();
                                for (Uri uri : toUpload) {
                                    ImageApi.UploadedImage uploaded = uploadedCache.get(uri);
                                    if (uploaded == null) {
                                        ImageApi.PreparedImage prepared = ImageApi.prepareImage(SendDynamicActivity.this, uri);
                                        uploaded = ImageApi.uploadImage(prepared.data, prepared.fileName, prepared.mimeType, ImageApi.BIZ_DYNAMIC);
                                        uploadedCache.put(uri, uploaded);
                                    }
                                    pics.put(uploaded.toDynamicPicJson());
                                }
                                Intent result = new Intent();
                                Bundle bundle = SendDynamicActivity.this.getIntent().getExtras();
                                if (bundle != null) result.putExtras(bundle);
                                result.putExtra("text", text);
                                result.putExtra("pics", pics.toString());
                                result.putExtra("options", buildOptionsJson());
                                runOnUiThread(() -> {
                                    setResult(RESULT_OK, result);
                                    finish();
                                });
                            } catch (Exception e) {
                                runOnUiThread(() -> {
                                    sending = false;
                                    MsgUtil.err("上传图片", e);
                                });
                            }
                        });
                    }
                } else
                    MsgUtil.showDialog("无法发送", "上一次的Cookie刷新失败了，\n您可能需要重新登录以进行敏感操作", -1);
            });

            addPic.setOnClickListener(view ->
                    pickImageLauncher.launch("image/*"));

            findViewById(R.id.emote).setOnClickListener(view ->
                    emoteLauncher.launch(new Intent(this, EmoteActivity.class).putExtra("from", EmoteApi.BUSINESS_DYNAMIC)));
        });
    }

    private String buildOptionsJson() {
        try {
            JSONObject option = DynamicApi.buildPublishOption(privatePub,
                    closeComment ? 1 : null, null, timerPubTime);
            return option.length() > 0 ? option.toString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private void addPicPreview(Uri uri) {
        int size = ToolsUtil.dp2px(72);
        int margin = ToolsUtil.dp2px(4);

        ImageView imageView = new ImageView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.setMargins(margin, 0, margin, 0);
        imageView.setLayoutParams(params);
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageView.setBackgroundColor(Color.argb(0x20, 0x80, 0x80, 0x80));
        imageView.setOnClickListener(view -> {
            if (sending) {
                MsgUtil.showMsg("正在发送中");
                return;
            }
            int index = imageUris.indexOf(uri);
            if (index >= 0) {
                imageUris.remove(index);
                uploadedCache.remove(uri);
                picsLayout.removeView(imageView);
                picsPreview.setVisibility(imageUris.isEmpty() ? View.GONE : View.VISIBLE);
                MsgUtil.showMsg("已移除该图片");
            }
        });
        picsLayout.addView(imageView);
        Glide.with(this).load(uri).override(size).centerCrop().into(imageView);
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        TerminalContext.getInstance().setForwardContent(null);
    }
}
