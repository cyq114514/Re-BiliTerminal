package com.RobinNotBad.BiliClient.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.widget.TextView;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity;
import com.RobinNotBad.BiliClient.activity.settings.setup.SetupUIActivity;
import com.RobinNotBad.BiliClient.activity.video.RecommendActivity;
import com.RobinNotBad.BiliClient.activity.video.local.LocalListActivity;
import com.RobinNotBad.BiliClient.api.AppInfoApi;
import com.RobinNotBad.BiliClient.api.CookiesApi;
import com.RobinNotBad.BiliClient.ui.widget.AmbientBackground;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.CookieRefresher;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.ResumePageUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.RobinNotBad.BiliClient.util.UpdateManager;

import org.json.JSONException;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;

//启动页面
//一切的一切的开始

@SuppressLint("CustomSplashScreen")
public class SplashActivity extends Activity {

    private TextView splashTextView;
    private int splashFrame;
    private Timer splashTimer;
    private String splashText = "欢迎使用\nRe：哔哩终端";

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(BiliTerminal.getFitDisplayContext(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(R.style.Theme_BiliClient);

        //不是任务根 = 应用还在后台任务里活着，桌面图标又把 MAIN/LAUNCHER intent 发了一遍。
        //不拦住的话这里会再压一个新的开屏页到已有页面栈顶上，走完动画就 startMainPage()，
        //用户看到的就是「切后台再回来直接回到推荐、刚才那页没了」（返回键还能翻回去，更像 bug）。
        //标准做法：不是任务根就立刻关掉自己，把前台让回原来的页面
        if (!isTaskRoot()) {
            Intent reentry = getIntent();
            if (reentry != null && Intent.ACTION_MAIN.equals(reentry.getAction())
                    && reentry.hasCategory(Intent.CATEGORY_LAUNCHER)) {
                Log.d("startup", "开屏页不是任务根：应用仍在栈中，直接归还给原页面");
                finish();
                return;
            }
        }

        //开屏页跟随「新版美学设计」总开关（与页面内氛围背景同一口径）：
        //新版用黑底 + 粉色氛围光晕 + 图标 + 粉色短线，旧版保持原来的纯黑 + 文字。
        //两个布局共用 @id/splashText，所以下面的打字机逻辑无需分支。
        //在此基础上再乘一个「新版开屏动画」独立开关：图标/光晕在部分设备上观感不协调时，
        //可以只关开屏、保留其它页面的新版美学（总开关关闭时一律走旧版）
        boolean newUi = SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.NEW_UI_DESIGN, true)
                && SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.SPLASH_NEW_UI, true);
        setContentView(newUi ? R.layout.activity_splash_new : R.layout.activity_splash);
        Log.e("debug", "进入应用");

        splashTextView = findViewById(R.id.splashText);

        if (newUi) {
            //背景风格全局统一（顶部粉色径向光晕，圆屏适配也一样，详见 AmbientBackground），
            //加载期间做 alpha 呼吸，和列表页初始加载的观感对齐
            AmbientBackground ambient = findViewById(R.id.splash_ambient);
            if (ambient != null) ambient.setBreathing(true);
        }
        splashText = SharedPreferencesUtil.getString("ui_splashtext", "欢迎使用\nRe：哔哩终端");

        splashTimer = new Timer();
        splashTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                runOnUiThread(() -> showSplashText(splashFrame));
                splashFrame++;
                if (splashFrame > splashText.length()) this.cancel();
            }
        }, 100, 100);

        //只有从桌面图标正常冷启动才做页面恢复；
        //崩溃兜底重启（CatchActivity 用显式 intent、无 ACTION_MAIN）不走恢复，避免回到崩溃现场
        final boolean fromLauncher = getIntent() != null && Intent.ACTION_MAIN.equals(getIntent().getAction());

        final long splashStartMs = android.os.SystemClock.uptimeMillis();
        CenterThreadPool.run(() -> {
            // ===== 第一步：只做本地决策，不碰网络 =====
            // 旧实现把「Cookie 续期」和「bilibili.com 取 Cookie」两个 HTTP 请求串在 startActivity 之前，
            // 冷启动要多等两个 RTT（连接超时 8s、读超时 16s），弱网/手表上就是开屏慢的主因。
            List<Intent> resumeIntents = null;
            if (fromLauncher) {
                List<Intent> restore = ResumePageUtil.takeRestoreIntents();
                if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.RESUME_PAGE_ENABLE, false))
                    resumeIntents = restore;
            }

            //有 Cookie 时是纯本地（首启才会联网生成 buvid），必须赶在首屏请求之前
            NetWorkUtil.refreshHeaders();

            if (!SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.setup, false)) {
                //没完成初次设置：直接进设置页，不必等任何网络请求
                runOnUiThread(() -> {
                    startActivity(new Intent(SplashActivity.this, SetupUIActivity.class));
                    applyNoTransition();
                    interruptSplash();
                    finish();
                });
                return;
            }

            final boolean online = NetWorkUtil.isNetworkAvailable(SplashActivity.this);
            //首启 buvid 还没生成：首个页面的请求需要它，这一次仍然先补齐再进（一次性代价）
            final boolean needCookieBootstrap = SharedPreferencesUtil.getString("buvid3", "").isEmpty();

            if (!online) {
                //离线：以前要等上面两个请求各自超时才走到兜底，现在瞬时判定，直接进恢复链/本地库
                Log.d("startup", "离线，直接进入恢复链/本地库");
                runOnUiThread(() -> MsgUtil.showMsg(getString(R.string.err_network)));
                startRestoreChain(resumeIntents);
                CenterThreadPool.run(() -> AppInfoApi.checkVersion(SplashActivity.this));
                return;
            }

            if (needCookieBootstrap) {
                try {
                    if (SharedPreferencesUtil.getLong("mid", 0) != 0) checkCookieRefresh();
                    CookiesApi.checkCookies();
                } catch (IOException e) {
                    startFallbackAfterNetworkError(e, resumeIntents);
                    return;
                } catch (JSONException e) {
                    runOnUiThread(() -> {
                        MsgUtil.err(e);
                        interruptSplash();
                    });
                    startRestoreChain(resumeIntents);
                    return;
                } catch (Exception e) {
                    startFallbackAfterNetworkError(e, resumeIntents);
                    return;
                }
            }

            //「启动时等待请求完成」：默认关闭，开屏秒进（1.2.3 起的行为）。
            //打开后推迟进首屏，等下面第三步的三项启动校验跑完 + 开屏打字动画播完再走，
            //弱网/断网最多多停 6 秒，不会把人扣在开屏页上。
            final boolean waitForRequests = SharedPreferencesUtil.getBoolean("splash_wait_requests", false);
            if (waitForRequests) {
                waitStartupChecksAndSplash(splashStartMs);
                if (isFinishing() || isDestroyed()) return;
            }

            // ===== 第二步：本地决策完成就进首屏（不再 postDelayed 100ms） =====
            if (resumeIntents != null && !resumeIntents.isEmpty()) startRestoreChain(resumeIntents);
            else startMainPage();

            Log.d("startup", "进入首屏：距离开屏 onCreate "
                    + (android.os.SystemClock.uptimeMillis() - splashStartMs) + "ms（online=" + online
                    + "，bootstrap=" + needCookieBootstrap + "，wait=" + waitForRequests + "）");

            // ===== 第三步：网络校验全部挪到首屏之后，不再挡开屏（等待模式下这里已经跑过了） =====
            if (!waitForRequests) CenterThreadPool.run(this::runStartupChecks);
        });
    }

    /**
     * 按栈底→栈顶顺序整链重建返回链（正常恢复与断网/异常兜底共用这一条路径）。
     * 逐条 try：第 N 条起不来（如组件被禁用）时前 N-1 条已经入栈，若此时整体转进
     * 本地库会留下"半截原链+缓存页"的混合栈；全部失败才兜底进本地库，绝不能闪退在启动页。
     */
    private void startRestoreChain(final List<Intent> resumeIntents) {
        interruptSplash();
        runOnUiThread(() -> {
            //必须抢在 startActivity 之前挂上：首屏一 resumed 就可能触发，
            //放后面（甚至在后台校验线程里挂）会漏掉这一次
            scheduleStartupDialogs();
            boolean startedAny = false;
            if (resumeIntents != null) {
                for (Intent resume : resumeIntents) {
                    try {
                        startActivity(resume);
                        startedAny = true;
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
            if (!startedAny) {
                try {
                    startActivity(new Intent(SplashActivity.this, LocalListActivity.class));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            applyNoTransition();
            finish();
        });
    }

    /** 按「菜单排序」进入默认首页（原来这一步要等两个网络检查做完才执行）。 */
    private void startMainPage() {
        String firstActivity = null;
        String sortConf = SharedPreferencesUtil.getString(SharedPreferencesUtil.MENU_SORT, "");
        if (!TextUtils.isEmpty(sortConf)) {
            String[] splitName = sortConf.split(";");
            for (String name : splitName) {
                if (!MenuActivity.btnNames.containsKey(name)) {
                    for (Map.Entry<String, Pair<String, Class<? extends InstanceActivity>>> entry : MenuActivity.btnNames.entrySet()) {
                        firstActivity = entry.getKey();
                        break;
                    }
                } else {
                    firstActivity = name;
                }
                break;
            }
        } else {
            for (Map.Entry<String, Pair<String, Class<? extends InstanceActivity>>> entry : MenuActivity.btnNames.entrySet()) {
                firstActivity = entry.getKey();
                break;
            }
        }

        Class<? extends InstanceActivity> activityClass = Objects.requireNonNull(MenuActivity.btnNames.get(firstActivity)).second;
        final Intent intent = new Intent();
        intent.setClass(SplashActivity.this, (activityClass != null ? activityClass : RecommendActivity.class));
        intent.putExtra("from", firstActivity);

        interruptSplash();
        runOnUiThread(() -> {
            scheduleStartupDialogs();
            startActivity(intent);
            applyNoTransition();
            finish();
        });
    }

    /** 网络异常时保持旧行为：提示 + 整链恢复（恢复点已在联网检查前被消费清空）。 */
    private void startFallbackAfterNetworkError(Exception e, final List<Intent> resumeIntents) {
        runOnUiThread(() -> {
            MsgUtil.err(e);
            interruptSplash();
            splashTextView.setText("网络错误");
        });
        startRestoreChain(resumeIntents);
        CenterThreadPool.run(() -> AppInfoApi.checkVersion(SplashActivity.this));
    }

    /**
     * 首屏之后的启动校验：Cookie 续期、Cookie 刷新、更新检查。
     *
     * <p>这三件事原来全部串在 startActivity 之前，是开屏慢的直接原因：首屏要先等两个 B 站接口
     * 往返。挪到这里之后它们只影响提示与红点，不影响进入速度；任一失败也只在日志里留痕。
     */
    private void runStartupChecks() {
        try {
            if (SharedPreferencesUtil.getLong("mid", 0) != 0) {
                //force=false：1 小时节流。续期窗口以天计，而 CookieRefresher.startPeriodicCheck()
                //已经每小时查一次，启动时再强制查一遍只是白等一个 RTT
                CookieRefresher.check(false);
            }
        } catch (Throwable t) {
            Log.e("startup", "Cookie 续期检查失败", t);
        }
        try {
            CookiesApi.checkCookies();
        } catch (Throwable t) {
            Log.e("startup", "Cookie 刷新失败", t);
        }
        try {
            AppInfoApi.checkVersion(SplashActivity.this);
        } catch (Throwable t) {
            Log.e("startup", "更新检查失败", t);
        }
    }

    /**
     * 「启动时等待请求完成」：先把启动校验（Cookie 续期 / Cookie 刷新 / 终端接口）跑完，
     * 再保证开屏打字动画播完，然后才进首屏。
     *
     * <p>校验放到临时线程并带硬超时：三个请求串行、各自还有 OkHttp 自己的超时，
     * 弱网下能拖到几十秒。超时就先走，校验线程继续在后台跑完（只往日志里留痕），
     * 绝不能因为一个接口把用户扣在开屏页。
     *
     * <p>动画时长按「每个字 100ms、封顶 4 秒」估算，与 onCreate 里的打字 Timer 同一口径。
     */
    private void waitStartupChecksAndSplash(long splashStartMs) {
        final long requestTimeoutMs = 6000L;
        try {
            Thread worker = new Thread(this::runStartupChecks, "splash-wait-checks");
            worker.start();
            worker.join(requestTimeoutMs);
        } catch (Throwable t) {
            Log.e("startup", "等待启动校验失败", t);
        }
        long animMs = Math.min(4000L, splashText.length() * 100L + 200L);
        long remain = animMs - (android.os.SystemClock.uptimeMillis() - splashStartMs);
        if (remain > 0) {
            try {
                Thread.sleep(remain);
            } catch (InterruptedException ignored) {
            }
        }
        Log.d("startup", "等待模式：校验与开屏动画结束，距离开屏 onCreate "
                + (android.os.SystemClock.uptimeMillis() - splashStartMs) + "ms");
    }

    /**
     * 把启动弹窗挂到「首屏 Activity resumed 之后」执行。
     *
     * <p>这里（Splash 的后台线程）直接 startActivity(DialogActivity) 会和首屏的 startActivity
     * 抢先后：弹窗先入栈、首屏后入栈，弹窗就被压在下面，表现为"要再点一下菜单才弹出来"。
     */
    private void scheduleStartupDialogs() {
        BiliTerminal.runAfterFirstPageResumed(() -> AppInfoApi.showStartupDialogs(BiliTerminal.context));
        //「启动时检查更新」开关：默认开启。与检查更新页走同一条链路
        //（GitHub 直连优先，失败依次试内置镜像），发现新版本时弹同一套自适应弹窗。
        //放在首屏之后执行：不占启动关键路径，也不会和首屏抢着弹窗；弱网失败只记日志，不打扰用户
        BiliTerminal.runAfterFirstPageResumed(activity -> {
            if (!SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.UPDATE_CHECK_ON_START, true)) return;
            UpdateManager.autoCheck(activity);
        });
    }

    /** 「关闭界面过渡动画」打开时，离开开屏页也不要转场动画 */
    private void applyNoTransition() {
        if (!SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.DISABLE_ANIMATION, false)) return;
        try {
            overridePendingTransition(0, 0);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 启动时的刷新检查。
     *
     * <p>调度与失败分流全部收敛到 {@link CookieRefresher}：force=true 保持与修复前一致的行为
     * （每次启动都查一次），但「刷新失败」不再清空本地凭证——只有服务端明确否定凭证时才清理。
     * 修复前这里是 refreshCookie() 返回 false 就直接 resetLogin()，一次网络抖动即等于登出。
     */
    private void checkCookieRefresh() {
        //force=false：Cookie 续期窗口以天计，CookieRefresher.startPeriodicCheck() 每小时已经查一次，
        //原来每次启动都 force 等于白等一个 RTT。首屏之后的后台校验也走节流版本
        CookieRefresher.check(false);
    }

    @SuppressLint("SetTextI18n")
    private void showSplashText(int i) {
        if (i > splashText.length()) splashTextView.setText(splashText);
        else splashTextView.setText(splashText.substring(0, i) + "_");
    }

    private void interruptSplash() {
        if (splashTimer != null) splashTimer.cancel();
        splashTimer = null;
        runOnUiThread(() -> splashTextView.setText(splashText));
    }

    @Override
    protected void onDestroy() {
        //走不到 interruptSplash 的路径（如启动中途按返回）也要停掉打字计时器
        if (splashTimer != null) {
            splashTimer.cancel();
            splashTimer = null;
        }
        super.onDestroy();
    }
}