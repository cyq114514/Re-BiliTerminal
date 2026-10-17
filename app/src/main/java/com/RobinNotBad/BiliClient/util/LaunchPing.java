package com.RobinNotBad.BiliClient.util;

import android.content.Context;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 活跃设备统计：每天最多上报一次「匿名安装标识 + 应用版本 + 系统版本 + 机型」。
 *
 * <p>为什么需要它：{@code crash_reports} 表里只有崩溃记录，
 * 按 {@code install_id} 去重只能得出「崩溃过的设备数」，不是用户数。
 * 每天一条 launch 记录之后，就能算日活（当日去重 install_id）与月活。
 *
 * <p>节流：本地记 {@code usage_stats_last_day}（yyyyMMdd），同一天不重复上报。
 * <b>不走待发队列</b>：崩溃队列上限只有 5 条，不能被活跃统计挤掉；
 * 当天发不出去就等明天（数据本身是可丢的统计量）。
 */
public final class LaunchPing {

    private LaunchPing() {
    }

    /** 冷启动调用（后台线程）。任何异常都不外抛 */
    public static void reportIfNeeded(Context context) {
        try {
            if (!SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.USAGE_STATS_ENABLE, true)) {
                CrashTrace.log("活跃统计: 已关闭");
                return;
            }
            String today = new SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(new Date());
            String lastDay = SharedPreferencesUtil.getString(SharedPreferencesUtil.USAGE_STATS_LAST_DAY, "");
            if (today.equals(lastDay)) return;

            //先写日期再发：宁可当天丢一条，也不能因为长期发不出去而每次启动都重试
            SharedPreferencesUtil.putString(SharedPreferencesUtil.USAGE_STATS_LAST_DAY, today);

            JSONObject report = CrashUploader.buildEvent(context, "launch", "", "", "main", "", "");
            //tryUpload 不做入队，避免污染崩溃待发队列
            boolean ok = CrashUploader.tryUpload(report, null);
            CrashTrace.log("活跃统计: " + (ok ? "已上报 " : "上报失败 ") + today
                    + " HTTP " + CrashUploader.lastHttpCode());
            Logu.e("crash", "活跃统计 " + (ok ? "成功" : "失败") + " HTTP " + CrashUploader.lastHttpCode());
        } catch (Throwable t) {
            CrashTrace.log("活跃统计: 异常 " + t.getClass().getSimpleName());
        }
    }
}
