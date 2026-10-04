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
import com.RobinNotBad.BiliClient.api.CookieRefreshApi;
import com.RobinNotBad.BiliClient.api.CookiesApi;
import com.RobinNotBad.BiliClient.util.AccountManager;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.ResumePageUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import org.json.JSONException;
import org.json.JSONObject;

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
        setContentView(R.layout.activity_splash);
        Log.e("debug", "进入应用");

        splashTextView = findViewById(R.id.splashText);
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

        CenterThreadPool.run(() -> {

            //FileUtil.clearCache(this);  //先清个缓存（为了防止占用过大）
            //不需要了，我把大部分图片的硬盘缓存都关闭了，只有表情包保留，这样既可以缩减缓存占用又能在一定程度上减少流量消耗

            //应用切后台后进程/任务被系统回收时，再次打开就是全新冷启动；
            //先一次性取出"上次停留页面链"的恢复点（取出即清除，恢复页若崩溃不会成循环），
            //后续无论走正常流程还是错误兜底流程，都优先回到用户上次停留的页面。
            //恢复的是完整返回链（栈底→栈顶），只恢复栈顶单个页面会导致恢复页下没有上级，点返回直接退出应用
            List<Intent> resumeIntents = fromLauncher ? ResumePageUtil.takeRestoreIntents() : null;

            NetWorkUtil.refreshHeaders();

            if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.setup, false)) {//判断是否设置完成
                try {
                    // 未登录时请求bilibili.com
                    if (SharedPreferencesUtil.getLong("mid", 0) != 0) {
                        checkCookieRefresh();
                    }

                    CookiesApi.checkCookies();

                    if (resumeIntents != null && !resumeIntents.isEmpty()) {
                        startRestoreChain(resumeIntents);
                        return;
                    }

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

                    Intent intent = new Intent();
                    intent.setClass(SplashActivity.this, (activityClass != null ? activityClass : RecommendActivity.class));
                    intent.putExtra("from", firstActivity);

                    interruptSplash();

                    splashTextView.postDelayed(() -> {
                        startActivity(intent);
                        CenterThreadPool.run(() -> AppInfoApi.check(SplashActivity.this));
                        finish();
                    }, 100);

                } catch (IOException e) {
                    //断网时同样整链恢复（缓存等本地功能仍可用）。恢复点在联网检查前已被消费清空，
                    //这里若只回栈顶单页，用户点返回就直接退出应用——离线冷启动恰是目标设备
                    //（手表/弱网）的高频路径，功能立项要解决的就是这个形态
                    runOnUiThread(() -> {
                        MsgUtil.err(e);
                        interruptSplash();
                        splashTextView.setText("网络错误");
                    });
                    startRestoreChain(resumeIntents);
                } catch (JSONException e) {
                    runOnUiThread(() -> {
                        MsgUtil.err(e);
                        interruptSplash();
                    });
                    startRestoreChain(resumeIntents);
                } catch (Exception e) {
                    //原来只捕 IOException/JSONException，其余异常会被线程池吞掉，启动页会永远停在打字动画上；
                    //兜底恢复页面链，保证任何情况下都能离开启动页
                    e.printStackTrace();
                    runOnUiThread(() -> {
                        MsgUtil.err(e);
                        interruptSplash();
                    });
                    startRestoreChain(resumeIntents);
                }
            } else {
                Intent intent = new Intent();
                intent.setClass(SplashActivity.this, SetupUIActivity.class);   //没登录，去初次设置
                startActivity(intent);
                interruptSplash();
                finish();
            }

        });
    }

    /**
     * 按栈底→栈顶顺序整链重建返回链（正常恢复与断网/异常兜底共用这一条路径）。
     * 逐条 try：第 N 条起不来（如组件被禁用）时前 N-1 条已经入栈，若此时整体转进
     * 本地库会留下"半截原链+缓存页"的混合栈；全部失败才兜底进本地库，绝不能闪退在启动页。
     */
    private void startRestoreChain(List<Intent> resumeIntents) {
        interruptSplash();
        splashTextView.postDelayed(() -> {
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
            CenterThreadPool.run(() -> AppInfoApi.check(SplashActivity.this));
            finish();
        }, 100);
    }

    private void checkCookieRefresh() throws IOException {
        JSONObject cookieInfo;
        try {
            cookieInfo = CookieRefreshApi.cookieInfo();
        } catch (JSONException e) {
            //解析失败大概率是服务端临时故障（code!=0 时 data 也可能缺失），
            //绝不能因此清空本地登录态——真正确认“已过期”会走下面 refreshCookie() 返回 false 的分支
            Log.e("Cookies", "cookieInfo 解析失败，跳过本次刷新检查");
            return;
        }
        try {
            if (cookieInfo.optBoolean("refresh")) {
                Log.e("Cookies", "需要刷新");
                if (!Objects.equals(SharedPreferencesUtil.getString(SharedPreferencesUtil.refresh_token, ""), "")) {
                    String correspondPath = CookieRefreshApi.getCorrespondPath(cookieInfo.getLong("timestamp"));
                    String refreshCsrf = CookieRefreshApi.getRefreshCsrf(correspondPath);
                    if (CookieRefreshApi.refreshCookie(refreshCsrf)) {
                        MsgUtil.showMsg("Cookies已刷新");
                        //多账号：刷新后的 cookie 同步回账号列表快照，否则切走再切回会拿到旧凭证
                        CenterThreadPool.run(AccountManager::saveCurrentAccount);
                    } else {
                        MsgUtil.showMsgLong("登录信息过期，请重新登录！");
                        resetLogin();
                    }
                }
            }
        } catch (JSONException e) {
            //刷新流程内的解析异常只放弃本次刷新，不清登录态
            Log.e("Cookies", "刷新流程解析异常，跳过本次刷新");
        }
    }

    private void resetLogin() {
        SharedPreferencesUtil.putLong(SharedPreferencesUtil.mid, 0L);
        SharedPreferencesUtil.putString(SharedPreferencesUtil.csrf, "");
        SharedPreferencesUtil.putString(SharedPreferencesUtil.cookies, "");
        SharedPreferencesUtil.putString(SharedPreferencesUtil.refresh_token, "");
        NetWorkUtil.refreshHeaders();
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