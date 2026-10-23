package com.RobinNotBad.BiliClient.activity;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Process;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.api.AppInfoApi;
import com.RobinNotBad.BiliClient.model.ApiResult;
import com.RobinNotBad.BiliClient.service.DownloadService;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.CrashTrace;
import com.RobinNotBad.BiliClient.util.CrashUploader;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.RobinNotBad.BiliClient.util.StringUtil;
import com.google.android.material.button.MaterialButton;

public class CatchActivity extends BaseActivity {
    private boolean openStack = false;

    @SuppressLint({"MissingInflatedId", "SetTextI18n"})
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_catch);

        TextView reason_view = findViewById(R.id.catch_reason);
        TextView stack_view = findViewById(R.id.stack);
        MaterialButton btn_upload = findViewById(R.id.upload_btn);

        Intent intent = getIntent();
        String stack = intent.getStringExtra("stack");

        stack_view.setText(stack);

        //原来是 System.exit(-1)：非 0 退出码会被系统判成异常退出并弹「应用已停止运行」，
        //用户看到的就是"点退出又崩了一次"。这里改成正常结束 :crash 进程，不再触发系统弹窗
        findViewById(R.id.exit_btn).setOnClickListener(view -> {
            finish();
            Process.killProcess(Process.myPid());
        });

        SpannableString reason_str = null;

        if (stack != null) {

            if (stack.contains("java.lang.NumberFormatException"))
                reason_str = new SpannableString("可能的崩溃原因：\n数值转换出错");
            else if (stack.contains("java.lang.UnsatisfiedLinkError"))
                reason_str = new SpannableString("可能的崩溃原因：\n外部库加载出错，可能设备太老或修改了安装包");
            else if (stack.contains("org.json.JSONException"))
                reason_str = new SpannableString("可能的崩溃原因：\n数据解析错误");
            else if (stack.contains("java.lang.OutOfMemoryError"))
                reason_str = new SpannableString("可能的崩溃原因：\n内存爆了，这在小内存设备上很正常");
            else
                reason_str = new SpannableString("未知的崩溃原因");

        } else finish();

        //原来正常崩溃时这个按钮被 GONE 掉，用户没有任何手动重传入口，
        //队列里积压的记录只能干等下次启动。现在改成常显的「重新上报未发出的记录」
        btn_upload.setVisibility(android.view.View.VISIBLE);
        btn_upload.setText("重新上报未发出的记录");
        btn_upload.setOnClickListener(view -> {
            btn_upload.setEnabled(false);
            btn_upload.setText("正在重新上报…");
            CenterThreadPool.run(() -> {
                int sent = CrashUploader.pendingCount(CatchActivity.this);
                CrashUploader.flushPending(CatchActivity.this);
                int left = CrashUploader.pendingCount(CatchActivity.this);
                int http = CrashUploader.lastHttpCode();
                CenterThreadPool.runOnUiThread(() -> {
                    btn_upload.setEnabled(true);
                    btn_upload.setText("重新上报未发出的记录（待发 " + left + "）\n上次结果：HTTP "
                            + (http < 0 ? "未连上" : http) + "，原待发 " + sent + " 条");
                });
            });
        });
        btn_upload.setText("重新上报未发出的记录（待发 " + CrashUploader.pendingCount(this) + "）");

        findViewById(R.id.restart_btn).setOnClickListener(view -> {
            finish();
            stopService(new Intent(this, DownloadService.class));
            startActivity(new Intent(this, SplashActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Process.killProcess(Process.myPid());
        });

        if (reason_str != null) {
            //加粗前 8 个字符（"可能的崩溃原因："）。必须按实际长度截断：
            //未知异常走的是 7 个字的"未知的崩溃原因"，写死 0..8 会越界，
            //崩溃页自己再崩一次 → 系统重新拉起 → 再崩，用户看到的就是无限黑屏
            int boldEnd = Math.min(8, reason_str.length());
            if (boldEnd > 0) {
                reason_str.setSpan(new StyleSpan(Typeface.BOLD), 0, boldEnd, Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
            }
            reason_view.setText(reason_str);
        } else reason_view.setText("未知的崩溃原因");

        //把上报链路的结论直接印在页面上。
        //不能指望用户去取追踪文件：清单里没有 WRITE_EXTERNAL_STORAGE，公开 Download 目录写不进去，
        //追踪会回落到 Android/media 或 Android/data 下，很多文件管理器根本看不到
        //追踪内容优先取 Intent 里带的（主进程在崩溃现场实时写入的），
        //:crash 进程的 SP 缓存可能停留在上一次崩溃，直接读会拿到旧数据
        //上报状态 + 本地文件路径：即使网络不通、后台收不到，现场也已经留在本机了
        boolean reportEnabled = intent.getBooleanExtra("reportEnabled", false);
        String crashFile = intent.getStringExtra("crashFile");
        String filterReason = intent.getStringExtra("filterReason");
        reason_view.append("\n\n崩溃上报：" + (reportEnabled ? "已开启" : "未开启"));
        if (filterReason != null && !filterReason.isEmpty()) {
            //让用户知道"这次为什么没自动上传"：系统压力类/无本仓库帧/24h 重复等
            reason_view.append("（本次未自动上传，原因：" + filterReason + "，仅本地留档）");
        }
        if (crashFile != null && !crashFile.isEmpty()) {
            reason_view.append("\n本地记录：" + crashFile);
        }

        String trace = intent.getStringExtra("trace");
        if (trace == null || trace.isEmpty()) trace = CrashTrace.readTail(20);
        if (trace != null && !trace.isEmpty()) {
            stack_view.append("\n\n==== 崩溃链路追踪 ====\n" + trace);
        }

        stack_view.setOnClickListener(view -> {
            openStack = !openStack;
            //展开上限从 200 提到 400：堆栈后面还追了一段崩溃链路追踪，
            //上限太小会把追踪过程截掉，恰好截掉最需要看的那几行
            if (openStack) stack_view.setMaxLines(400);
            else stack_view.setMaxLines(5);
        });

        StringUtil.setCopy(stack_view);
    }

    @Override
    protected boolean eventBusEnabled() {
        return false;
    }
}
