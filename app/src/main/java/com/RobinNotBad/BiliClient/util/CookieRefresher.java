package com.RobinNotBad.BiliClient.util;

import com.RobinNotBad.BiliClient.api.CookieRefreshApi;
import com.RobinNotBad.BiliClient.api.CookieRefreshResult;

import org.json.JSONObject;

import java.util.Timer;
import java.util.TimerTask;

/**
 * Cookie 自动刷新的调度与结果处理。
 *
 * <p>三个触发点：
 * <ol>
 *   <li>App 启动 —— {@link #check(boolean)} 且 force=true（与修复前的行为一致，每次启动都查）；</li>
 *   <li>长驻周期 —— {@link #startPeriodicCheck()}，进程存活期间按 tick 判断是否到点；</li>
 *   <li>关键接口明确返回未登录 —— {@link #notifyAuthFailure(String)}。</li>
 * </ol>
 *
 * <p>失败处理原则（本次修复的核心）：<b>可重试的失败绝不清理本地登录态</b>。
 * 修复前只要 {@code refreshCookie()} 返回 false 就调用 resetLogin() 清空
 * mid/cookies/csrf/refresh_token，而 false 的成因包含一次网络抖动、服务端 5xx、风控
 * 或对应页面解析失败——等于一次抖动就把用户登出，并且丢掉了唯一能自救的 refresh_token。
 * 现在只有服务端明确否定凭证（{@link CookieRefreshResult#requiresRelogin()}）才清理。
 */
public class CookieRefresher {

    /**
     * 两次后台检查之间的最小间隔。Cookie 的续期窗口以天计，1 小时足够；
     * 启动那次是 force=true 不受此限制。
     */
    private static final long MIN_CHECK_INTERVAL_MS = 60 * 60 * 1000L;

    /** 长驻周期的 tick 间隔：只判断「到点了没」，到点才真的发请求 */
    private static final long PERIODIC_TICK_MS = 60 * 60 * 1000L;

    /** 长驻周期的首次执行延迟：避开启动时已经做过的那次检查 */
    private static final long PERIODIC_INITIAL_DELAY_MS = 30 * 60 * 1000L;

    /**
     * 「关键请求报告未登录」这条路径的自节流间隔。它绕过常规节流（服务端说未登录是权威信号，
     * 不能再等 1 小时），但仍要防止某个接口反复报错把刷新打成风暴。
     */
    private static final long MIN_AUTH_FAIL_INTERVAL_MS = 5 * 60 * 1000L;

    private static final String KEY_LAST_CHECK = "cookie_refresh_last_check";
    private static final String KEY_LAST_AUTH_FAIL = "cookie_refresh_last_auth_fail";

    private static Timer periodicTimer;
    /** 同一时刻只允许一次刷新在跑：并发刷新会互相轮换 refresh_token，把两边的状态都搞坏 */
    private static volatile boolean checking;

    private CookieRefresher() {
    }

    /**
     * 执行一次刷新检查（同步，必须在后台线程调用）。
     *
     * @param force true 时忽略节流（启动路径用），false 时按 {@link #MIN_CHECK_INTERVAL_MS} 节流
     * @return 刷新结果；未登录、被节流、服务端说不需要刷新时返回 null
     */
    public static CookieRefreshResult check(boolean force) {
        if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) == 0) return null;

        long now = System.currentTimeMillis();
        if (!force) {
            long last = SharedPreferencesUtil.getLong(KEY_LAST_CHECK, 0);
            if (now - last < MIN_CHECK_INTERVAL_MS) return null;
        }
        SharedPreferencesUtil.putLong(KEY_LAST_CHECK, now);

        //并发保护：第二个调用直接放弃本次（已由进行中的那次覆盖），避免 refresh_token 互相轮换
        synchronized (CookieRefresher.class) {
            if (checking) {
                Logu.d("cookie-refresh", "已有刷新在进行，跳过本次");
                return null;
            }
            checking = true;
        }
        try {
            return doCheck();
        } finally {
            checking = false;
        }
    }

    private static CookieRefreshResult doCheck() {
        JSONObject cookieInfo;
        try {
            cookieInfo = CookieRefreshApi.cookieInfo();
        } catch (Exception e) {
            //解析失败/网络异常大概率是服务端临时故障（code!=0 时 data 也可能缺失），
            //绝不能因此清空本地登录态——真正确认「已失效」只走 refreshCookie 的 REJECTED 分支
            Logu.e("cookie-refresh", "cookie/info 查询失败，跳过本次刷新检查");
            return null;
        }
        if (!cookieInfo.optBoolean("refresh")) return null;

        Logu.d("cookie-refresh", "服务端提示需要刷新 Cookie");

        if (SharedPreferencesUtil.getString(SharedPreferencesUtil.refresh_token, "").isEmpty()) {
            //修复点（原 P0-D）：这里原来是"什么都不做"的死区，
            //凭证不完整却一直静默，直到 Cookie 到期直接掉登录且毫无提示
            Logu.e("cookie-refresh", "缺少 refresh_token，本次无法续期");
            MsgUtil.showMsgLong("登录凭证不完整，无法自动续期，建议重新登录一次");
            return CookieRefreshResult.NO_REFRESH_TOKEN;
        }

        String correspondPath = CookieRefreshApi.getCorrespondPath(cookieInfo.optLong("timestamp", 0));
        String refreshCsrf = "";
        try {
            refreshCsrf = CookieRefreshApi.getRefreshCsrf(correspondPath);
        } catch (Exception e) {
            //取校验口令失败属瞬时故障，交给 refreshCookie 归类为可重试，不再向上抛
            Logu.e("cookie-refresh", "refresh_csrf 获取失败（按可重试处理）");
        }

        CookieRefreshResult result = CookieRefreshApi.refreshCookie(refreshCsrf);
        handleResult(result);
        return result;
    }

    private static void handleResult(CookieRefreshResult result) {
        if (result == null) return;
        if (result == CookieRefreshResult.OK) {
            MsgUtil.showMsg("Cookies已刷新");
            //多账号：刷新后的 cookie 同步回账号列表快照，否则切走再切回会拿到旧凭证
            CenterThreadPool.run(AccountManager::saveCurrentAccount);
            return;
        }
        if (result.isRetryable()) {
            //瞬时失败：保留全部凭证，留待下次触发。绝不 resetLogin
            Logu.e("cookie-refresh", "刷新失败（可重试）：" + result.name() + "，保留本地凭证待下次重试");
            return;
        }
        if (result == CookieRefreshResult.NO_REFRESH_TOKEN) {
            //已在 doCheck() 里提示过，这里不重复弹
            return;
        }
        if (result.requiresRelogin()) {
            Logu.e("cookie-refresh", "服务端明确判定凭证失效：" + result.name());
            MsgUtil.showMsgLong("登录信息已失效，请重新登录！");
            resetLogin();
        }
    }

    /**
     * 后台执行一次检查（带节流的默认入口）。供长驻周期与「关键请求报告未登录」调用。
     */
    public static void checkAsync(boolean force) {
        CenterThreadPool.run(() -> {
            try {
                check(force);
            } catch (Throwable e) {
                //刷新是尽力而为的后台任务，任何异常都不应该冒泡到线程池之外
                Logu.e("cookie-refresh", "刷新检查异常：" + e);
            }
        });
    }

    /**
     * 关键接口明确返回「未登录」(-101) 时调用。
     *
     * <p>服务端说未登录是权威信号，所以绕过常规节流立即续期；
     * 但为防止某接口反复报错造成请求风暴，这里另加 {@link #MIN_AUTH_FAIL_INTERVAL_MS} 的自节流。
     */
    public static void notifyAuthFailure(String apiTag) {
        long now = System.currentTimeMillis();
        if (now - SharedPreferencesUtil.getLong(KEY_LAST_AUTH_FAIL, 0) < MIN_AUTH_FAIL_INTERVAL_MS) return;
        SharedPreferencesUtil.putLong(KEY_LAST_AUTH_FAIL, now);
        Logu.d("cookie-refresh", "关键请求报告未登录，立即尝试续期：" + apiTag);
        checkAsync(true);
    }

    /**
     * 启动长驻周期检查，进程存活期间每小时 tick 一次、到点才真的发请求。
     * 可重复调用，只会启动一个定时器。
     *
     * <p>修复点（原 P0-C）：修复前刷新只在启动页触发一次，
     * App 常驻前台/后台一整天则从不刷新，Cookie 到期即掉登录。
     */
    public static synchronized void startPeriodicCheck() {
        if (periodicTimer != null) return;
        periodicTimer = new Timer("cookie-refresh", true);
        try {
            periodicTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    try {
                        checkAsync(false);
                    } catch (Throwable ignored) {
                    }
                }
            }, PERIODIC_INITIAL_DELAY_MS, PERIODIC_TICK_MS);
        } catch (Throwable e) {
            //定时器创建失败只影响后台续期，不能影响应用启动
            Logu.e("cookie-refresh", "启动长驻刷新失败：" + e);
            periodicTimer = null;
        }
    }

    /**
     * 清空本地登录态。只在服务端明确判定凭证失效时调用，不可用于「刷新失败」的兜底。
     */
    public static void resetLogin() {
        //App 卡片 access_key 也是账号凭证，网页登录态失效/退出时一并清掉
        AppAuthStore.clear(SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0L));
        SharedPreferencesUtil.putLong(SharedPreferencesUtil.mid, 0L);
        SharedPreferencesUtil.putString(SharedPreferencesUtil.csrf, "");
        SharedPreferencesUtil.putString(SharedPreferencesUtil.cookies, "");
        SharedPreferencesUtil.putString(SharedPreferencesUtil.refresh_token, "");
        //凭证已不可信：置 false，让依赖该开关的敏感操作给出明确提示而不是发出必然失败的请求
        SharedPreferencesUtil.putBoolean(SharedPreferencesUtil.cookie_refresh, false);
        NetWorkUtil.refreshHeaders();
    }
}
