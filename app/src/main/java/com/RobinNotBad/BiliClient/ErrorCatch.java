package com.RobinNotBad.BiliClient;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import com.RobinNotBad.BiliClient.activity.CatchActivity;
import com.RobinNotBad.BiliClient.util.ResumePageUtil;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;

public class ErrorCatch implements Thread.UncaughtExceptionHandler {
    @SuppressLint("StaticFieldLeak")
    public static ErrorCatch instance;
    private Context context;

    public static ErrorCatch getInstance() {
        if (instance == null) instance = new ErrorCatch();
        return instance;
    }

    public void init(Context context) {
        this.context = context;
        Thread.setDefaultUncaughtExceptionHandler(this);
    }

    @Override
    public void uncaughtException(@NonNull Thread thread, @NonNull Throwable throwable) {
        //崩溃即现场已不可信：清掉冷启动恢复记录，
        //否则用户在崩溃页点"重启"后又会回到刚才崩溃的页面，形成崩溃循环
        try {
            ResumePageUtil.clear();
        } catch (Exception ignored) {
        }

        Writer writer = new StringWriter();
        PrintWriter printWriter = new PrintWriter(writer);
        throwable.printStackTrace(printWriter);

        try {
            Intent intent = new Intent(context, CatchActivity.class);
            intent.putExtra("stack", writer.toString());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); //这句是安卓4必须有的
            context.startActivity(intent);
        } catch (Throwable t) {
            t.printStackTrace();
        }

        throwable.printStackTrace();
        //startActivity 是异步的，立即杀进程崩溃页来不及起来；给系统一小段时间完成页面启动
        try {
            Thread.sleep(300);
        } catch (InterruptedException ignored) {
        }
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
