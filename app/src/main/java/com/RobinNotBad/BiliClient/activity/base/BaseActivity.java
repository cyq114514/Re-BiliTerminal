package com.RobinNotBad.BiliClient.activity.base;

import static com.RobinNotBad.BiliClient.activity.dynamic.DynamicActivity.getRelayDynamicLauncher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.event.SnackEvent;
import com.RobinNotBad.BiliClient.ui.widget.AmbientBackground;
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomGridManager;
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager;
import com.RobinNotBad.BiliClient.util.AsyncLayoutInflaterX;
import com.RobinNotBad.BiliClient.util.BackgroundManager;
import com.RobinNotBad.BiliClient.util.CrashReportUtil;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.RobinNotBad.BiliClient.util.ResumePageUtil;
import com.RobinNotBad.BiliClient.util.ToolsUtil;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;


public class BaseActivity extends AppCompatActivity {
    public int window_width, window_height;
    public Context old_context;
    public final ActivityResultLauncher<Intent> relayDynamicLauncher = getRelayDynamicLauncher(this);
    public boolean force_single_column = false;

    //调整应用内dpi的代码，其他Activity要继承于BaseActivity才能调大小
    @Override
    protected void attachBaseContext(Context newBase) {
        old_context = newBase;
        super.attachBaseContext(BiliTerminal.getFitDisplayContext(newBase));
    }

    //调整页面边距，参考了hankmi的方式
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        //关闭"新版美学设计"时，向当前主题追加 Classic 覆盖层恢复旧版卡片/按钮/激活色；
        //必须在 setContentView 之前，applyStyle 不会替换主题（Splash 等窗口背景得以保留）。
        //NoSwipe 全屏页（播放器/看图）不追加：与 ambientEnabled() 同一口径，
        //Classic 的 colorControlActivated 等属性会污染播放器自有视觉体系
        if (!SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.NEW_UI_DESIGN, true) && !isNoSwipeThemedPage()) {
            getTheme().applyStyle(R.style.ThemeOverlay_BiliClient_Classic, true);
        }

        setRequestedOrientation(SharedPreferencesUtil.getBoolean("ui_landscape", false)
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        super.onCreate(savedInstanceState);

        //冷启动页面栈恢复：可恢复页面在创建时入栈，销毁时出栈（见 ResumePageUtil），
        //进程被杀后由 Splash 按栈重建整个返回链，返回键才能逐级回退而不是直接退出。
        //token 是本实例的出栈凭证：同类页叠放时按凭证精确出栈，防止删错条目留幽灵页
        if (isRestorablePage() && SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.RESUME_PAGE_ENABLE, false))
            resumeToken = ResumePageUtil.push(this);

        int paddingH_percent = SharedPreferencesUtil.getInt("paddingH_percent", 0);
        int paddingV_percent = SharedPreferencesUtil.getInt("paddingV_percent", 0);

        WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Display display = windowManager.getDefaultDisplay();
        DisplayMetrics metrics = new DisplayMetrics();
        if (Build.VERSION.SDK_INT >= 17) display.getRealMetrics(metrics);
        else display.getMetrics(metrics);

        int scrW = metrics.widthPixels;
        int scrH = metrics.heightPixels;
        if (paddingH_percent != 0 || paddingV_percent != 0) {
            Logu.d("debug", "调整边距");
            int paddingH = scrW * paddingH_percent / 100;
            int paddingT = scrH * paddingV_percent / 100;
            int paddingB = paddingT;
            if (SharedPreferencesUtil.getBoolean("player_ui_round", false))
                paddingB += scrH * 0.03;
            window_width = scrW - paddingH * 2;
            window_height = scrH - paddingT - paddingB;
            applyContentPadding(paddingH, paddingT, paddingH, paddingB);
        } else {
            window_width = scrW;
            window_height = scrH;
        }

        // 随便加的
        int density;
        if ((density = SharedPreferencesUtil.getInt("density", -1)) >= 72) {
            setDensity(density);
        }

        installAmbient();

        //崩溃上报授权：挂在基类而不是引导页——引导页只有新用户会经过。
        //用 decorView.post 等到窗口真正可用再弹，避免在 onCreate 期间弹窗拿到失效的 window token；
        //CatchActivity（崩溃页）本身继承本类，必须在崩溃现场之外把这次询问挡掉
        if (!(this instanceof com.RobinNotBad.BiliClient.activity.CatchActivity)) {
            getWindow().getDecorView().post(() -> CrashReportUtil.askNoticeIfNeeded(this));
        }
    }

    //氛围背景：新版美学下在每个页面内容层之下铺一层装饰（黑底上的粉色氛围），
    //加到 DecorView 上而不是 content 上——setContentView 会清空 content 的子 View 但不会动 DecorView
    private AmbientBackground ambientBackground;

    //默认跟随"新版美学设计"总开关；播放器/图片查看器等 NoSwipe 全屏页通过主题判断排除，子类也可覆写关闭
    protected boolean ambientEnabled() {
        if (!SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.NEW_UI_DESIGN, true)) return false;
        try {
            ActivityInfo info = getPackageManager().getActivityInfo(getComponentName(), 0);
            return info.theme != R.style.Theme_NoSwipe && info.theme != R.style.Theme_NoSwipe_AppCompat;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 当前页面是否挂 NoSwipe 系主题（播放器/看图等全屏页）。
     * 查询失败返回 false：Classic 覆盖层与氛围背景的排除口径都以"明确命中"为准，
     * 失败时维持各自原本的保守行为（覆盖层照加 / 氛围背景不装）。
     */
    private boolean isNoSwipeThemedPage() {
        try {
            ActivityInfo info = getPackageManager().getActivityInfo(getComponentName(), 0);
            return info.theme == R.style.Theme_NoSwipe || info.theme == R.style.Theme_NoSwipe_AppCompat;
        } catch (Throwable e) {
            return false;
        }
    }

    private void installAmbient() {
        ViewGroup decor = (ViewGroup) getWindow().getDecorView();
        //自定义背景底图优先：装上了就不再叠氛围层，
        //否则用户自己的图会被那层粉色氛围整体染色，等于白设
        if (BackgroundManager.install(this, decor)) return;
        if (!ambientEnabled()) return;
        if (decor.getChildAt(0) instanceof AmbientBackground) return;
        //背景风格不随「圆屏适配」变化：圆屏适配只改布局，视觉沿用直屏那套粉色径向光晕
        ambientBackground = new AmbientBackground(this);
        decor.addView(ambientBackground, 0,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    protected void setAmbientBreathing(boolean breathing) {
        if (ambientBackground != null) ambientBackground.setBreathing(breathing);
    }

    /**
     * 「界面边距」加到内容容器上，而不是 DecorView。
     *
     * <p>氛围背景层与自定义背景底图都是挂在 DecorView 上的装饰层，而 ViewGroup 的子 View
     * 一律排布在自己的 padding 之内——边距一旦加在 DecorView 上，装饰层就跟着被压进内容区，
     * 页面四周只剩窗口底色（黑），看上去就是"新版美学 UI 没铺满屏幕"：左右各露出边距宽的黑边，
     * 圆屏模式下内容区底边还会把左下那圈装饰圆硬切一刀（底部边距比顶部多 3%，切得最明显）。
     *
     * <p>把边距下沉到 {@code android.R.id.content}：内容依旧内缩，装饰层恢复整屏铺满。
     * 另：Snackbar 也改挂到内容容器上（见 {@link #onEvent(SnackEvent)}），否则它会跟着跑到
     * 贴屏底的位置，在圆屏设备上正好落进"下巴"。
     */
    private void applyContentPadding(int left, int top, int right, int bottom) {
        View content = null;
        try {
            //AppCompat 页面靠这一次 findViewById 把 subDecor 装好（后面的 setContentView 才有地方放）。
            //但主题不是 AppCompat 后代时它会抛 IllegalStateException（看图页那种 NoSwipe 页），
            //一个"界面边距"设置就能让整页启动即崩——这里必须兜住。
            content = findViewById(android.R.id.content);
        } catch (Throwable t) {
            Logu.e("padding", "取内容容器失败（主题可能不是 AppCompat）：" + t);
        }
        if (content == null) {
            try {
                //退化路径：不经主题代理，直接从窗口取内容容器。非 AppCompat 页面本来也就只有这一个。
                View decor = getWindow() != null ? getWindow().getDecorView() : null;
                if (decor != null) content = decor.findViewById(android.R.id.content);
            } catch (Throwable ignored) {
            }
        }
        if (content == null) {
            //理论上 super.onCreate 之后内容容器一定存在，兜底退回原来的做法
            getWindow().getDecorView().getRootView().setPadding(left, top, right, bottom);
            return;
        }
        content.setPadding(left, top, right, bottom);
    }

    @Override
    protected void onPause() {
        //离开页面/切后台时停掉呼吸动画，不留空转的 ValueAnimator
        setAmbientBreathing(false);
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (!SharedPreferencesUtil.getBoolean("back_disable", false)) super.onBackPressed();
    }

    public void setPageName(String name) {
        TextView textView = findViewById(R.id.pageName);
        if (textView != null) textView.setText(name);
    }

    public void setTopbarExit() {
        View view = findViewById(R.id.top);
        if (view == null) return;
        if (Build.VERSION.SDK_INT > 17 && view.hasOnClickListeners()) return;
        view.setOnClickListener(view1 -> {
            if (Build.VERSION.SDK_INT < 17 || !isDestroyed()) {
                finish();
            }
        });
        Logu.d("debug", "set_exit");
    }

    public void setRound() {
        //圆屏适配

        TextView pagename = findViewById(R.id.pageName);
        TextView clock = findViewById(R.id.timeText);
        if (pagename != null) {
            pagename.setMaxLines(1);
            pagename.setEllipsize(TextUtils.TruncateAt.END);
            if (SharedPreferencesUtil.getBoolean("player_ui_round", false)) {
                try {
                    ViewGroup.LayoutParams params = pagename.getLayoutParams();
                    int paddingH = (int) (window_width * 0.18);
                    int paddingV = (int) (window_width * 0.03);
                    pagename.setPadding(paddingH, paddingV, paddingH, 0);
                    if (params instanceof RelativeLayout.LayoutParams) {
                        RelativeLayout.LayoutParams clockParams = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        clockParams.addRule(RelativeLayout.CENTER_HORIZONTAL);
                        clock.setLayoutParams(clockParams);
                        clock.setAlpha(0.85f);
                        clock.setTextSize(12);

                        RelativeLayout.LayoutParams pnParams = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        pnParams.addRule(RelativeLayout.CENTER_HORIZONTAL);
                        pnParams.topMargin = (int) (window_height * 0.01) + ToolsUtil.sp2px(12);
                        pnParams.bottomMargin = (int) (window_height * 0.01);
                        pagename.setLayoutParams(pnParams);
                        pagename.setPadding(0, 0, ToolsUtil.dp2px(5), 0);

                        //顶栏上的「刷新」按钮也必须重排：它原本靠 layout_toStartOf="@id/timeText" 定位，
                        //而圆屏下时钟被居中，按钮会跟着落到屏幕中间、正好压住同样居中的标题。
                        //圆屏下标题左右各留 18% 内边距（上面的 paddingH），那片区域是空的，
                        //把按钮对齐到父容器左侧并垂直居中即可既落在可视圆内、又不遮字
                        View btnRefresh = findViewById(R.id.btn_refresh);
                        if (btnRefresh != null) {
                            RelativeLayout.LayoutParams brParams = new RelativeLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                            brParams.addRule(RelativeLayout.ALIGN_PARENT_START);
                            brParams.addRule(RelativeLayout.CENTER_VERTICAL);
                            btnRefresh.setLayoutParams(brParams);
                        }
                        Logu.d("round", "ok");
                    }
                } catch (Throwable e) {
                    MsgUtil.err("圆屏适配执行错误：", e);
                }
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (Build.VERSION.SDK_INT < 17 || !isDestroyed()) {
                finish();
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    public void report(Exception e) {
        runOnUiThread(() -> MsgUtil.err(getClassName(), e));
    }

    private boolean eventBusInit = false;
    //本页面在恢复栈里的出栈凭证（push 时由 ResumePageUtil 发放）
    private long resumeToken = 0;

    @Override
    protected void onStart() {
        super.onStart();
        if (!(this instanceof InstanceActivity)) setTopbarExit();
        setRound();
        if (eventBusEnabled() && !eventBusInit) {
            EventBus.getDefault().register(this);
            eventBusInit = true;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (eventBusEnabled()) {
            SnackEvent snackEvent;
            if ((snackEvent = EventBus.getDefault().getStickyEvent(SnackEvent.class)) != null)
                onEvent(snackEvent);
        }
    }

    //冷启动时本页面能否被 Splash 恢复；默认否，可恢复的页面其全部状态必须能仅凭 intent 重建，
    //播放器/发帖编辑器/崩溃页这类有运行时状态或临时的页面不要打开这个开关
    public boolean isRestorablePage() {
        return false;
    }

    @Override
    protected void onDestroy() {
        if (isRestorablePage()) ResumePageUtil.pop(this, resumeToken);
        super.onDestroy();
        if (eventBusInit) {
            EventBus.getDefault().unregister(this);
            eventBusInit = false;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN, sticky = true)
    public void onEvent(SnackEvent event) {
        if (isDestroyed()) return;
        //挂到内容容器上而不是 DecorView：内容容器带着「界面边距」的 padding，提示条才会跟着内缩，
        //不会掉进圆屏设备的下巴里（DecorView 现在没有 padding 了，见 applyContentPadding）
        View host = findViewById(android.R.id.content);
        MsgUtil.processSnackEvent(event, host != null ? host : getWindow().getDecorView().getRootView());
    }

    protected boolean eventBusEnabled() {
        return SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.SNACKBAR_ENABLE, true);
    }

    public void setDensity(int targetDensityDpi) {
        if (Build.VERSION.SDK_INT < 17) return;
        Resources resources = getResources();

        if (resources.getConfiguration().densityDpi == targetDensityDpi) return;

        Configuration configuration = resources.getConfiguration();
        configuration.densityDpi = targetDensityDpi;
        configuration.fontScale = 1f;
        resources.updateConfiguration(configuration, resources.getDisplayMetrics());
    }

    protected void asyncInflate(int id, InflateCallBack callBack) {
        setContentView(R.layout.activity_loading);
        setAmbientBreathing(true); //加载期间氛围背景呼吸，内容就绪后停止
        new AsyncLayoutInflaterX(this).inflate(id, null, (view, layoutId, parent) -> {
            //低配设备 inflate 期间页面可能已被销毁，对已销毁窗口 setContentView 会崩
            if (isDestroyed()) return;
            setContentView(view);

            //低性能设备（手表/低端机）上布局加载耗时可能超过转场动画时长：
            //转场期间显示的是加载占位屏，内容就绪时直接setContentView是硬切，
            //观感即"过渡动画丢失、短暂显示后直接跳到下一个界面"。
            //这里对内容做淡入把硬切变成过渡。
            //注意必须按"帧数"而非"时间"驱动：单帧绘制耗时可能超过动画时长（W527手表实测），
            //时间型淡入会在首帧绘制完成时就已经结束，依然表现为硬切；
            //时间上限仅用于防止极端情况下长时间半透明
            view.setAlpha(0f);
            final View fadeInView = view;
            android.view.Choreographer.getInstance().postFrameCallback(new android.view.Choreographer.FrameCallback() {
                final long startMs = android.os.SystemClock.uptimeMillis();
                int frameCount = 0;

                @Override
                public void doFrame(long frameTimeNanos) {
                    frameCount++;
                    long elapsed = android.os.SystemClock.uptimeMillis() - startMs;
                    float alpha = Math.min(Math.min(frameCount / 3f, elapsed / 300f), 1f);
                    fadeInView.setAlpha(alpha);
                    if (alpha < 1f && !isDestroyed()) {
                        android.view.Choreographer.getInstance().postFrameCallback(this);
                    } else {
                        fadeInView.setAlpha(1f);
                        setAmbientBreathing(false);
                    }
                }
            });

            if (this instanceof InstanceActivity) ((InstanceActivity) this).setMenuClick();
            else setTopbarExit();

            setRound();
            callBack.finishInflate(view, layoutId);
        });
    }

    protected interface InflateCallBack {
        void finishInflate(View view, int id);
    }

    public RecyclerView.LayoutManager getLayoutManager() {
        return SharedPreferencesUtil.getBoolean("ui_landscape", false) && !force_single_column
                ? new CustomGridManager(this, 3)
                : new CustomLinearManager(this);
    }

    public void setForceSingleColumn() {
        force_single_column = true;
    }

    @Override
    public boolean isDestroyed() {
        return getLifecycle().getCurrentState().equals(Lifecycle.State.DESTROYED) || isFinishing();
    }

    public String getClassName() {
        return this.getClass().getSimpleName();
    }

    // ---------------------------------------------------------------- 关闭界面过渡动画

    /**
     * 「关闭界面过渡动画」总开关（默认关闭，即动画照常）。
     *
     * <p>低配设备/晕动症用户可以在偏好设置里关掉：Activity 转场与页面渐入渐出都不再播放。
     * 覆写 {@code startActivity} 的各个重载而不是去几十个调用点逐个加判断——
     * {@code registerForActivityResult} 走的是三参版本，漏掉它就会出现"某些入口还有动画"。
     */
    private boolean animationsDisabled() {
        return SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.DISABLE_ANIMATION, false);
    }

    private void applyNoTransition() {
        if (!animationsDisabled()) return;
        try {
            overridePendingTransition(0, 0);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void startActivity(Intent intent) {
        super.startActivity(intent);
        applyNoTransition();
    }

    @Override
    public void startActivity(Intent intent, @Nullable Bundle options) {
        super.startActivity(intent, options);
        applyNoTransition();
    }

    @Override
    public void startActivityForResult(Intent intent, int requestCode) {
        super.startActivityForResult(intent, requestCode);
        applyNoTransition();
    }

    @Override
    public void startActivityForResult(Intent intent, int requestCode, @Nullable Bundle options) {
        super.startActivityForResult(intent, requestCode, options);
        applyNoTransition();
    }

    @Override
    public void finish() {
        super.finish();
        applyNoTransition();
    }
}
