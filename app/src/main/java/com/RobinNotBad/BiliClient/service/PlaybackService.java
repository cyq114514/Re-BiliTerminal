package com.RobinNotBad.BiliClient.service;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.player.PlayerActivity;

import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

/**
 * 后台音频播放的前台保活服务。
 *
 * 播放器本体仍由 PlayerActivity 持有（设置“后台/熄屏继续播放”打开时，退后台或熄屏不暂停），
 * 本服务只负责两件事：
 * 1. 前台服务提高整个进程的优先级，避免退后台后 Activity 连同播放器被系统回收；
 * 2. 通知栏遥控（播放/暂停、关闭），点正文回到正在播放的任务。
 *
 * 生命周期是单向交接：Activity 退后台（onPause）时 start，回前台（onResume）或销毁（onDestroy）
 * 时 stop。服务里用静态引用读 Activity 的播放状态并每秒刷新通知；
 * 引用为空或页面正在结束时服务自杀，不会挂着一个空壳通知。
 */
public class PlaybackService extends Service {

    public static final String ACTION_TOGGLE = "com.RobinNotBad.BiliClient.action.PLAYBACK_TOGGLE";
    public static final String ACTION_STOP = "com.RobinNotBad.BiliClient.action.PLAYBACK_STOP";

    private static final String CHANNEL_ID = "playback_channel";
    private static final int NOTIFICATION_ID = 1028;    //下载服务占了 1027

    //与 BiliTerminal.context 同一套约定：本服务只在 Activity 主动交接时持有引用，
    //Activity 的 onResume/onDestroy 都会调 stop() 清引用。用弱引用兜底：服务被系统
    //杀掉而没走到 stop() 时，静态强引用会把整个 Activity 连同 View 树钉在内存里
    private static WeakReference<PlayerActivity> sPlayerActivityRef;

    private static PlayerActivity getPlayerActivity() {
        WeakReference<PlayerActivity> ref = sPlayerActivityRef;
        return ref != null ? ref.get() : null;
    }

    private NotificationManager notifyManager;
    private Timer updateTimer;

    /**Activity 退后台时调用；重复调用无害，只是多走一次 onStartCommand 刷新通知。*/
    public static void start(Context context, PlayerActivity activity) {
        sPlayerActivityRef = new WeakReference<>(activity);
        Intent intent = new Intent(context, PlaybackService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    /**Activity 回前台或销毁时调用；服务没在跑也无害。*/
    public static void stop(Context context) {
        sPlayerActivityRef = null;
        context.stopService(new Intent(context, PlaybackService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notifyManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "后台播放",
                    NotificationManager.IMPORTANCE_LOW);    //常驻通知用低优先级，不响不打扰
            channel.setShowBadge(false);
            notifyManager.createNotificationChannel(channel);
        }
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        //onStartCommand 必须立刻进前台（5 秒规则），先发一帧再处理动作
        startForeground(NOTIFICATION_ID, refresh());

        if (intent != null && ACTION_TOGGLE.equals(intent.getAction())) {
            PlayerActivity activity = getPlayerActivity();
            if (activity != null) activity.serviceTogglePlay();
        } else if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            PlayerActivity activity = getPlayerActivity();
            if (activity != null) {
                //走 Activity 的 finish()：最终进度上报、定时器/播放器清理都由 onDestroy 统一做，
                //服务由 onDestroy 里的 stop() 撤掉，这里不能直接 stopSelf 抢在清理前面
                activity.serviceStopPlayback();
            } else {
                stopSelf();
            }
            return START_NOT_STICKY;
        }

        startUpdateTimer();
        //进程被杀等于播放已断，重启一个没有播放器的服务只会挂空通知，不重启
        return START_NOT_STICKY;
    }

    private void startUpdateTimer() {
        if (updateTimer != null) return;
        updateTimer = new Timer();
        updateTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                PlayerActivity activity = getPlayerActivity();
                if (activity == null || activity.isFinishing() || activity.serviceGone()) {
                    stopSelf();
                    this.cancel();
                    return;
                }
                notifyManager.notify(NOTIFICATION_ID, refresh());
            }
        }, 1000, 1000);
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private android.app.Notification refresh() {
        //每次新建 Builder：本方法会被主线程（onStartCommand）与 Timer 线程并发调用，
        //复用成员 Builder 时两线程交错 setContentTitle/clearActions/addAction 会产出内容撕裂的通知
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.icon)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        PlayerActivity activity = getPlayerActivity();
        boolean playing = activity != null && activity.serviceIsPlaying();
        String title = activity != null ? activity.serviceTitle() : "";

        builder.setContentTitle(title.isEmpty() ? "Re：哔哩终端正在播放" : title);
        if (activity != null) {
            int pos = activity.servicePositionMs();
            int all = activity.serviceDurationMs();
            builder.setContentText(formatTime(pos) + (all > 0 ? " / " + formatTime(all) : ""));
            if (all > 0) builder.setProgress(all, Math.min(Math.max(pos, 0), all), false);
            else builder.setProgress(0, 0, false);
        } else {
            builder.setContentText("");
            builder.setProgress(0, 0, false);
        }

        builder.clearActions();
        builder.addAction(playing ? R.drawable.btn_player_pause : R.drawable.btn_player_play,
                playing ? "暂停" : "播放",
                PendingIntent.getService(this, 0,
                        new Intent(this, PlaybackService.class).setAction(ACTION_TOGGLE),
                        PendingIntent.FLAG_UPDATE_CURRENT));
        builder.addAction(0, "关闭",
                PendingIntent.getService(this, 1,
                        new Intent(this, PlaybackService.class).setAction(ACTION_STOP),
                        PendingIntent.FLAG_UPDATE_CURRENT));

        //点正文把现有任务调回前台（REORDER_TO_FRONT 不新建实例，PlayerActivity 的 onResume 会停掉服务）
        Intent content = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (content != null) {
            content.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            builder.setContentIntent(PendingIntent.getActivity(this, 2, content,
                    PendingIntent.FLAG_UPDATE_CURRENT));
        }
        return builder.build();
    }

    private static String formatTime(int ms) {
        int totalSec = Math.max(ms, 0) / 1000;
        return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        //从最近任务划掉时页面即将销毁，onDestroy 会做最终上报并停服务；这里兜底补一次上报
        PlayerActivity activity = getPlayerActivity();
        if (activity != null) activity.serviceReportNow();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        if (updateTimer != null) {
            updateTimer.cancel();
            updateTimer = null;
        }
        sPlayerActivityRef = null;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
