package com.RobinNotBad.BiliClient.activity;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.RobinNotBad.BiliClient.util.StringUtil;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 回声洞翻页页（1.3.8 反馈：原来逐条长按弹窗改成进界面翻页）。
 * 正文与翻页按钮都排在 RotaryScrollView 的文本流里、贴在文案正下方，
 * 不定高不焊底栏——应用内缩放倍数（dpi）与系统字号放大时按钮随内容一起缩放，
 * 长文下随内容滚动，不会被裁掉。进度仍存 egg_seen_count（已读条数，
 * 不存下标，防跨版本数组变长错位），重置入口常驻页面内。
 */
public class EchoCaveActivity extends BaseActivity {

    private String[] eggs;
    private int current = 0;        //当前页下标
    private int seenCount;          //已读条数（egg_seen_count）
    private int resetClick = 0;     //「重置进度」的二次确认计数

    private TextView textView;
    private LinearLayout extraInsertContainer;
    private TextView progressText;
    private MaterialButton btnPrev, btnNext, btnReset;

    private GestureDetector swipeDetector;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_echo_cave);
        setTopbarExit();
        setPageName("回声洞");

        eggs = getResources().getStringArray(R.array.eggs);
        seenCount = SharedPreferencesUtil.getInt("egg_seen_count", 0);
        if (seenCount > eggs.length) seenCount = eggs.length;   //跨版本条数变化时夹取
        if (seenCount < 0) seenCount = 0;
        //未看完落在第一条未读；看完了从头再看
        current = seenCount >= eggs.length ? 0 : seenCount;

        textView = findViewById(R.id.textView);
        StringUtil.setCopy(textView);
        extraInsertContainer = findViewById(R.id.extraInsertContainer);
        progressText = findViewById(R.id.progressText);
        btnPrev = findViewById(R.id.btnPrev);
        btnNext = findViewById(R.id.btnNext);
        btnReset = findViewById(R.id.btnReset);

        btnPrev.setOnClickListener(view -> showPage(current - 1));
        btnNext.setOnClickListener(view -> showPage(current + 1));
        btnReset.setOnClickListener(view -> {
            if (resetClick++ > 0) {
                resetClick = 0;
                seenCount = 0;
                SharedPreferencesUtil.putInt("egg_seen_count", 0);
                MsgUtil.showMsg("回声洞进度已重置");
                showPage(0);
            } else {
                btnReset.setText("再点一次重置进度");
                MsgUtil.showMsg("再点一次重置回声洞进度");
            }
        });

        //左右滑动翻页：事件在 dispatchTouch 旁路喂给 GestureDetector，不消费、
        //不拦截，RotaryScrollView 的上下滚动手势不受影响
        swipeDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                //横向明显占优才当翻页手势，避免和竖向滚动打架
                if (Math.abs(dx) < 80 || Math.abs(dx) < Math.abs(dy) * 2 || Math.abs(velocityX) < 600)
                    return false;
                if (dx < 0) showPage(current + 1);   //左滑=下一条
                else showPage(current - 1);          //右滑=上一条
                return true;
            }
        });

        showPage(current);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        swipeDetector.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    private void showPage(int index) {
        if (index < 0 || index >= eggs.length) return;
        if (resetClick > 0 && index != current) {   //翻页撤销重置确认态
            resetClick = 0;
            btnReset.setText("重置进度");
        }
        current = index;

        //只有越过已读进度才算「读到」，往回翻不动进度
        if (index + 1 > seenCount) {
            seenCount = index + 1;
            SharedPreferencesUtil.putInt("egg_seen_count", seenCount);
            if (seenCount >= eggs.length) MsgUtil.showMsg("回声洞已看完，可点下方「重置进度」从头再看");
        }

        progressText.setText("第 " + (current + 1) + "/" + eggs.length + " 条");
        btnPrev.setEnabled(current > 0);
        btnNext.setEnabled(current < eggs.length - 1);

        //正文渲染，[extra_insert] 视频卡片的处理与 ShowTextActivity 同口径
        String content = eggs[current];
        extraInsertContainer.removeAllViews();
        content = content.replace("[extra_insert]", "<extra_insert>");
        int extra_start = content.indexOf("<extra_insert>");
        if (extra_start != -1) {
            try {
                JSONObject jsonObject = new JSONObject(content.substring(extra_start + 14));
                if (jsonObject.getString("type").equals("video")) {
                    View videoCard = View.inflate(this, R.layout.cell_message_reply, extraInsertContainer);
                    MaterialCardView cardView = videoCard.findViewById(R.id.cardView);
                    cardView.setOnClickListener(view ->
                            BiliTerminal.jumpToVideo(this, jsonObject.optString("content")));
                    TextView title_video = videoCard.findViewById(R.id.content);
                    title_video.setText(jsonObject.optString("title"));
                    content = content.substring(0, extra_start);
                } else {
                    content = content + "\n文本中存在无法识别的附加信息，请更新版本查看";
                }
            } catch (JSONException e) {
                content = content + "\n文本中存在无法识别的附加信息，请更新版本查看";
            }
        }
        textView.setText(content);

        ScrollView scroll = findViewById(R.id.scroll);
        if (scroll != null) scroll.scrollTo(0, 0);   //翻页后从正文顶部读起
    }
}
