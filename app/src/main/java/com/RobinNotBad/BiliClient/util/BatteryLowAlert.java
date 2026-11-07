package com.RobinNotBad.BiliClient.util;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;

import com.RobinNotBad.BiliClient.R;

/**
 * 低电量提醒（用户反馈 2026-11-07）：电量掉到设定值以下时提示充电。
 *
 * <p>只注册 {@code ACTION_BATTERY_CHANGED} 粘性广播——系统调用，不轮询、不唤醒、不加权限
 * （targetSdk 26 也不需要通知运行时权限）。用通知而不是 Toast：后台/熄屏时只有通知看得见。
 *
 * <p>滞回：提醒过一次后要等电量回到「阈值 + 5%」以上（或开始充电）才会再次提醒，
 * 避免在阈值附近来回抖动时反复弹。
 */
public final class BatteryLowAlert {

    private static final String CHANNEL_ID = "battery_low_channel";
    private static final int NOTIFY_ID = 1030;

    /** 阈值档位：与设置里的「低电量阈值」选项一一对应 */
    private static final int[] THRESHOLDS = {5, 10, 15, 20, 30};

    private static boolean alerted = false;
    private static boolean installed = false;

    private static final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) return;
            try {
                handle(context, intent);
            } catch (Throwable t) {
                Logu.e("battery-alert", "低电量检查失败：" + t);
            }
        }
    };

    private BatteryLowAlert() {
    }

    /** 在 Application 里调一次即可（重复调用无副作用） */
    public static void install(Context context) {
        if (installed || context == null) return;
        installed = true;
        try {
            context.getApplicationContext().registerReceiver(receiver,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (Throwable t) {
            installed = false;
            Logu.e("battery-alert", "注册电量广播失败：" + t);
        }
    }

    /** 阈值档位下标 → 实际百分比 */
    public static int thresholdPercent() {
        //多选项设置由 SettingsAdapter.bindMulti 按 int 下标存（不是字符串），读侧必须同口径
        int index = SharedPreferencesUtil.getInt("battery_low_threshold", 3);
        if (index < 0) index = 0;
        if (index >= THRESHOLDS.length) index = THRESHOLDS.length - 1;
        return THRESHOLDS[index];
    }

    private static void handle(Context context, Intent intent) {
        if (!SharedPreferencesUtil.getBoolean("battery_low_alert_enable", true)) return;

        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level < 0 || scale <= 0) return;
        int percent = Math.round(level * 100f / scale);

        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        int threshold = thresholdPercent();

        if (charging) {
            alerted = false;
            return;
        }
        if (percent > threshold + 5) {
            alerted = false;
            return;
        }
        if (percent <= threshold && !alerted) {
            alerted = true;
            notify(context, percent);
        }
    }

    private static void notify(Context context, int percent) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "电量提醒",
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("电量过低时提醒充电");
            nm.createNotificationChannel(channel);
        }

        Intent open = new Intent();
        open.setClassName(context, "com.RobinNotBad.BiliClient.activity.MenuActivity");
        PendingIntent content = PendingIntent.getActivity(context, 0, open,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        builder.setSmallIcon(R.mipmap.icon)
                .setContentTitle("该充电了")
                .setContentText("当前电量 " + percent + "%，插上充电器继续刷吧")
                .setAutoCancel(true)
                .setContentIntent(content);
        try {
            nm.notify(NOTIFY_ID, builder.build());
        } catch (Throwable t) {
            Logu.e("battery-alert", "发通知失败：" + t);
        }
    }
}
