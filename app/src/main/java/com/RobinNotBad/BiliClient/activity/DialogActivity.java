package com.RobinNotBad.BiliClient.activity;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.google.android.material.button.MaterialButton;

import java.util.Timer;
import java.util.TimerTask;

public class DialogActivity extends BaseActivity {

    int wait_time = 0;
    private Timer waitTimer;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dialog);

        MaterialButton close_btn = findViewById(R.id.close_btn);

        Intent intent = getIntent();

        ((TextView) findViewById(R.id.tip_title)).setText(intent.getStringExtra("title"));
        ((TextView) findViewById(R.id.content)).setText(intent.getStringExtra("content"));

        if (intent.getIntExtra("wait_time", -1) > 0) {
            close_btn.setEnabled(false);
            wait_time = intent.getIntExtra("wait_time", 0);
            waitTimer = new Timer();
            Timer timer = waitTimer;
            timer.scheduleAtFixedRate(new TimerTask() {
                @SuppressLint("SetTextI18n")
                @Override
                public void run() {
                    runOnUiThread(() -> {
                        if (wait_time-- > 0) {
                            close_btn.setText("知道了(" + wait_time + "s)");
                            close_btn.setEnabled(false);
                        } else {
                            close_btn.setText("知道了");
                            close_btn.setEnabled(true);
                            timer.cancel();
                        }
                    });
                }
            }, 0, 1000);
        } else close_btn.setEnabled(true);
        close_btn.setOnClickListener(view -> finish());
    }

    @Override
    public void onBackPressed() {
    }

    @Override
    protected void onDestroy() {
        //倒计时用的 Timer 此前只在倒数完才 cancel：页面被提前关闭时仍会 post 一轮 runOnUiThread（批次8）
        if (waitTimer != null) waitTimer.cancel();
        super.onDestroy();
    }
}