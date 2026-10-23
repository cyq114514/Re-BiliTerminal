package com.RobinNotBad.BiliClient;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import com.RobinNotBad.BiliClient.activity.CatchActivity;
import com.RobinNotBad.BiliClient.util.CrashFilter;
import com.RobinNotBad.BiliClient.util.CrashReportUtil;
import com.RobinNotBad.BiliClient.util.CrashStats;
import com.RobinNotBad.BiliClient.util.CrashTrace;
import com.RobinNotBad.BiliClient.util.CrashUploader;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.ResumePageUtil;

import org.json.JSONObject;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 默认未捕获异常处理器：Java 崩溃的唯一捕获点。
 *
 * <p>流程固定为「先留痕 → 再显示 → 最后上报」：
 * <ol>
 *   <li>写本地文件（用户可直接取出）</li>
 *   <li>写待发队列（上传失败或进程提前被杀都不丢）</li>
 *   <li>启动崩溃页（跑在 :crash 独立进程，主进程死掉不影响它）</li>
 *   <li>后台线程上传，最多等 1.5 秒</li>
 *   <li>杀主进程，让系统把 :crash 进程的崩溃页顶到前台</li>
 * </ol>
 *
 * <p>上报失败是常态（实测部分网络会重置该域名），所以第 2 步必须先于第 4 步。
 */
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

    /**
     * 启动最早期（Application.onCreate 之前）的兜底处理器。
     *
     * <p>原来要等 onCreate 走到 {@link #init(Context)} 才装 handler，
     * 这之间的 MultiDex、SP 初始化、getFitDisplayContext、主题资源加载一旦抛异常，
     * 进程直接消失且<b>什么都不留</b>——正是"闪退但零记录"的一种来源。
     * 这一层拿不到任何配置，只能把堆栈落到 files/crash_early 下，
     * 下次冷启动由 {@link com.RobinNotBad.BiliClient.util.CrashUploader#flushEarly(Context)} 转成正式上报。
     */
    public static void initEarly(Context context) {
        final Context appContext = context;
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                StringWriter writer = new StringWriter();
                throwable.printStackTrace(new PrintWriter(writer));
                String stack = "早期崩溃（Application.onCreate 之前）\n线程: " + thread.getName()
                        + "\n\n" + writer;
                CrashUploader.saveEarly(appContext, stack);
            } catch (Throwable ignored) {
            }
            android.os.Process.killProcess(android.os.Process.myPid());
        });
    }

    /**
     * {@code :crash} 进程（崩溃页自己）的处理器：只留痕，不再拉起崩溃页。
     *
     * <p>崩溃页自己崩溃时如果还去 startActivity(CatchActivity)，会形成无限重启循环
     * （旧代码注释里已经踩过这个坑），所以这里写完本地文件与计数后直接杀本进程。
     */
    public static void initCrashProcess(Context context) {
        final Context appContext = context;
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                CrashTrace.log("崩溃页: 自身崩溃 " + throwable);
                StringWriter writer = new StringWriter();
                throwable.printStackTrace(new PrintWriter(writer));
                JSONObject report = CrashUploader.buildEvent(appContext, "crash-page",
                        throwable.getClass().getName(), "崩溃页自身崩溃（:crash 进程）",
                        thread.getName(), "CatchActivity", writer.toString());
                CrashUploader.saveLocal(report);
                CrashStats.record("crash-page");
                Logu.e("crash", "崩溃页自身崩溃: " + throwable);
            } catch (Throwable ignored) {
            }
            android.os.Process.killProcess(android.os.Process.myPid());
        });
    }

    @Override
    public void uncaughtException(@NonNull Thread thread, @NonNull Throwable throwable) {
        //崩溃即现场已不可信：清掉冷启动恢复记录，
        //否则用户在崩溃页点"重启"后又会回到刚才崩溃的页面，形成崩溃循环
        try {
            ResumePageUtil.clear();
        } catch (Exception ignored) {
        }

        CrashTrace.log("崩溃: 捕获 " + throwable);

        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        String stack = writer.toString();

        boolean enabled = CrashReportUtil.isEnabled();
        JSONObject report = CrashUploader.buildReport(context, throwable, thread.getName(), stack);
        //计数日志跨进程可读，崩溃页的「按时间统计」靠它（SP 在 :crash 进程读不到最新值）
        CrashStats.record("java");

        //第 1 步：本地文本，落点不可写时返回空串，不影响后续（过滤只影响上传，本地永远留）
        String localPath = CrashUploader.saveLocal(report);
        CrashTrace.log("崩溃: 本地记录 " + (localPath.isEmpty() ? "写入失败" : localPath));

        //第 1.5 步：上报判定（系统压力类/无本仓库帧/测试崩溃/24h 重复 → 只留本地）
        CrashFilter.Decision decision = CrashFilter.decide(report);
        CrashTrace.log("崩溃: 上报判定 " + (decision.upload ? "上传" : "仅本地") + "（" + decision.reason + "）");
        boolean upload = enabled && decision.upload;

        //第 2 步：入队（仅授权且判定通过时）。放在上传之前，进程提前被杀也不会丢
        File pending = upload ? CrashUploader.enqueue(context, report) : null;
        CrashTrace.log("崩溃: 待发队列 " + (pending == null ? "未入队" : pending.getName()));

        //第 3 步：先交崩溃页，任何可能失败的步骤都不能挡在它前面
        try {
            Intent intent = new Intent(context, CatchActivity.class);
            intent.putExtra("stack", stack);
            intent.putExtra("reportEnabled", enabled);
            intent.putExtra("crashFile", localPath);
            //过滤原因带给崩溃页：用户能看到"这次为什么没自动上传"，本地文件路径也在同一页
            intent.putExtra("filterReason", decision.upload ? "" : decision.reason);
            //追踪随 Intent 传：SP 多进程不共享缓存，:crash 进程直接读会拿到旧数据
            intent.putExtra("trace", CrashTrace.readTail(20));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); //这句是安卓4必须有的
            context.startActivity(intent);
            CrashTrace.log("崩溃: 崩溃页已提交");
        } catch (Throwable t) {
            CrashTrace.log("崩溃: 崩溃页提交失败 " + t);
        }

        throwable.printStackTrace();

        //第 4 步：上报放到独立线程。这里运行在刚抛异常的那个线程上（通常已死的主线程），
        //任何阻塞都会推迟杀进程，用户就停在冻屏上
        if (upload && pending != null) {
            final File pendingFile = pending;
            Thread uploader = new Thread(
                    () -> CrashUploader.tryUpload(report, pendingFile), "crash-upload");
            uploader.setDaemon(true);
            uploader.start();
            try {
                uploader.join(1500);
                CrashTrace.log("崩溃: 上报线程已结束=" + !uploader.isAlive());
            } catch (InterruptedException ignored) {
            }
        }

        //第 5 步：崩溃页在 :crash 独立进程，主进程什么时候死都不影响它显示
        try {
            Thread.sleep(300);
        } catch (InterruptedException ignored) {
        }
        CrashTrace.log("崩溃: 杀进程");
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
