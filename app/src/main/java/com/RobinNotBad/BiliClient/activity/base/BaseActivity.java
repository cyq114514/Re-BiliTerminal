package com.RobinNotBad.BiliClient.activity.base;

import static com.RobinNotBad.BiliClient.activity.dynamic.DynamicActivity.getRelayDynamicLauncher;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
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
import com.RobinNotBad.BiliClient.activity.player.AudioPlayerActivity;
import com.RobinNotBad.BiliClient.event.SnackEvent;
import com.RobinNotBad.BiliClient.model.AudioQueue;
import com.RobinNotBad.BiliClient.service.AudioPlayerService;
import com.RobinNotBad.BiliClient.ui.theme.ThemeApplier;
import com.RobinNotBad.BiliClient.ui.widget.AmbientBackground;
import com.RobinNotBad.BiliClient.ui.widget.MarqueeTextView;
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomGridManager;
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager;
import com.RobinNotBad.BiliClient.util.AsyncLayoutInflaterX;
import com.RobinNotBad.BiliClient.util.AdaptiveDialog;
import com.RobinNotBad.BiliClient.util.BackgroundManager;
import com.RobinNotBad.BiliClient.util.CrashReportUtil;
import com.RobinNotBad.BiliClient.util.CrashTrace;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.RobinNotBad.BiliClient.util.ResumePageUtil;
import com.RobinNotBad.BiliClient.util.RoundGeometry;
import com.RobinNotBad.BiliClient.util.ToolsUtil;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;


public class BaseActivity extends AppCompatActivity {
    public int window_width, window_height;
    public Context old_context;
    public final ActivityResultLauncher<Intent> relayDynamicLauncher = getRelayDynamicLauncher(this);
    public boolean force_single_column = false;

    /** 真正按圆屏几何处理：既开了「圆屏适配」，系统也报告圆屏 */
    private boolean roundUi;
    /** 默认圆屏内缩（px）。roundInsetT 用于没有顶栏的全屏提示页 */
    private int roundInsetH, roundInsetB, roundInsetT;
    /** 用户手设了「界面边距」时沿用旧逻辑，不再叠加默认圆屏内缩 */
    private boolean customPaddingApplied;

    //顶部音频悬浮胶囊（音频服务活跃时显示，点击拉起全屏播放页）
    private View audioCapsule;
    private TextView audioCapsuleText;
    private Handler audioCapsuleHandler;
    private Runnable audioCapsuleRefresh;



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
        //圆屏内容区安全边距。旧的 8% 水平 / 6% 底部在 384 圆屏上必然让 324px 宽卡片四角出圆
        //（首卡顶边 y=68 时四角已到 194px，半径只有 192px）。这里改为：
        //水平 12%，底部按内接圆算（约 18%）；顶部留给顶栏，没有顶栏的全屏提示页再补同样的顶边距。
        //用户手设「界面边距」时仍以用户值为准（只叠加历史的底部 3%）。
        boolean roundPref = SharedPreferencesUtil.getBoolean("player_ui_round", false);
        roundUi = roundPref && getResources().getConfiguration().isScreenRound();
        if (paddingH_percent != 0 || paddingV_percent != 0) {
            Logu.d("debug", "调整边距");
            int paddingH = scrW * paddingH_percent / 100;
            int paddingT = scrH * paddingV_percent / 100;
            int paddingB = paddingT;
            //圆屏底部 3% 补偿从 padding_percent 分支里挪出独立判断（此前不开「界面边距」就完全失效）
            if (roundPref)
                paddingB += scrH * 0.03;
            window_width = scrW - paddingH * 2;
            window_height = scrH - paddingT - paddingB;
            applyContentPadding(paddingH, paddingT, paddingH, paddingB);
            customPaddingApplied = true;
        } else if (roundPref && roundContentInset()) {
            int paddingH;
            int paddingB;
            if (roundUi) {
                //按内容宽度反算：让卡片顶边落在顶栏下方时四角仍在圆内。
                //再留 3dp 安全边距：0.4px 的纯几何余量在描边/抗锯齿下看起来仍是"贴着圆边被切"
                paddingH = Math.max(1, Math.round(scrW * 0.12f) + ToolsUtil.dp2px(3));
                paddingB = Math.max(1, RoundGeometry.verticalInsetForWidth(scrW, scrH, scrW - paddingH * 2)
                        + ToolsUtil.dp2px(3));
                roundInsetH = paddingH;
                roundInsetB = paddingB;
                roundInsetT = paddingB;
            } else {
                //方屏/手机开了「圆屏适配」：保持旧观感，避免这次改动影响非圆设备
                paddingH = (int) (scrW * 0.08);
                paddingB = (int) (scrH * 0.06);
            }
            Logu.d("round", "内容区默认安全边距 " + paddingH + "/" + paddingB);
            window_width = scrW - paddingH * 2;
            window_height = scrH - paddingB;
            applyContentPadding(paddingH, 0, paddingH, paddingB);
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
    //圆屏适配已经处理过的 pageName 视图：onStart 每次返回都会重调 setRound，用它判重
    private View roundAppliedOn;

    /**
     * 主题判定的进程内缓存：{@code getActivityInfo} 是 binder IPC（低端机单次 0.3~2ms），
     * 而结果只跟 Activity 类有关。原来 onCreate 与每次 onStart 各查一次，返回页面时
     * 这笔 IPC 正好压在转场动画上。
     *
     * <p>缓存不需要失效：唯一会改主题的「新版美学设计」开关在设置里是"重启应用生效"。
     * 只缓存查询成功的结果——查询失败（如通过 activity-alias 启动时按别名查不到）
     * 不写入，避免一次失败把某个类永久判死。
     */
    private static final java.util.HashMap<String, Boolean> noSwipeThemeCache = new java.util.HashMap<>();

    //默认跟随"新版美学设计"总开关；播放器/图片查看器等 NoSwipe 全屏页通过主题判断排除，子类也可覆写关闭
    protected boolean ambientEnabled() {
        return SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.NEW_UI_DESIGN, true)
                && !isNoSwipeThemedPage();
    }

    /**
     * 当前页面是否挂 NoSwipe 系主题（播放器/看图等全屏页）。
     * 查询失败返回 false：Classic 覆盖层与氛围背景的排除口径都以"明确命中"为准，
     * 失败时维持各自原本的保守行为（覆盖层照加 / 氛围背景不装）。
     */
    private boolean isNoSwipeThemedPage() {
        String key = getClass().getName();
        Boolean cached = noSwipeThemeCache.get(key);
        if (cached != null) return cached;
        try {
            ActivityInfo info = getPackageManager().getActivityInfo(getComponentName(), 0);
            boolean noSwipe = info.theme == R.style.Theme_NoSwipe
                    || info.theme == R.style.Theme_NoSwipe_AppCompat;
            noSwipeThemeCache.put(key, noSwipe);
            return noSwipe;
        } catch (Throwable e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 主题配色（强调色自定义）

    /**
     * 布局里的 {@code @color/bili_pink} 保持不动作为默认兜底，这里在内容就绪后统一换成用户主题色。
     * 默认主题下 {@link ThemeApplier#apply(View)} 首行即返回，等于没有这次调用。
     *
     * <p>只覆写 setContentView 而不是在几十个页面里各加一行：所有页面都走这三个入口，
     * 漏改的风险比重载一次更大。
     */
    @Override
    public void setContentView(int layoutResID) {
        super.setContentView(layoutResID);
        applyRoundContentPadding();
        applyThemeColors();
        installAudioCapsule();
    }

    @Override
    public void setContentView(View view) {
        super.setContentView(view);
        applyRoundContentPadding();
        applyThemeColors();
        installAudioCapsule();
    }

    @Override
    public void setContentView(View view, ViewGroup.LayoutParams params) {
        super.setContentView(view, params);
        applyRoundContentPadding();
        applyThemeColors();
        installAudioCapsule();
    }

    /**
     * 顶部音频悬浮胶囊：挂在 DecorView 右上角、标题栏下方，音频服务活跃时显示。
     * 只作入口（点击拉起全屏播放页），不承载播放控制；播放器页/音频页/启动页不装。
     */
    private void installAudioCapsule() {
        if (audioCapsule != null) {
            //asyncInflate 场景：胶囊在加载占位布局时装上（无顶栏，停在原位），
            //真内容 setContentView 后必须重新定位，否则仍盖在标题上
            repositionAudioCapsule();
            return;
        }
        String cls = getClass().getSimpleName();
        //全屏视频/音频队列页也不装：胶囊会盖住短视频画面右上角与队列页顶栏（X-18）
        if ("AudioPlayerActivity".equals(cls) || "PlayerActivity".equals(cls)
                || "SplashActivity".equals(cls) || "ShortVideoPlayerActivity".equals(cls)
                || "AudioQueueActivity".equals(cls)) return;
        try {
            ViewGroup decor = (ViewGroup) getWindow().getDecorView();
            View capsule = LayoutInflater.from(this).inflate(R.layout.view_audio_capsule, decor, false);
            //胶囊底色跟随主题色：shape 里是实色打底，这里 setTint（SRC_IN 保留原 alpha 的 85% 观感）——
            //此前硬编码旧版粉 #D9FB7299，自定义主题下胶囊永远是旧粉
            android.graphics.drawable.Drawable capsuleBg = capsule.getBackground();
            if (capsuleBg != null) {
                capsuleBg.mutate().setTint(com.RobinNotBad.BiliClient.ui.theme.ThemeManager.getPrimary());
                capsule.setBackground(capsuleBg);
            }
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            //水平居中：落在左侧标题与右侧时间之间；初始垂直位置在顶栏上方（4dp），
            //布局完成后由 repositionAudioCapsule 下移到顶栏底边之下
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.setMargins(0, (int) (4 * getResources().getDisplayMetrics().density + 0.5f), 0, 0);
            decor.addView(capsule, lp);
            audioCapsule = capsule;
            audioCapsuleText = capsule.findViewById(R.id.capsuleText);
            repositionAudioCapsule();
            //胶囊总宽上限 = 屏宽 38%：文字过长时省略，避免窄屏/大字号下被撑成一条横贯屏幕的横条，
            //把顶栏的标题和时钟挤掉（文本可用的宽度已扣掉 6dp×2 内边距 + 12dp 图标 + 4dp 间距）
            if (audioCapsuleText != null) {
                int reserved = (int) (22 * getResources().getDisplayMetrics().density + 0.5f);
                int maxText = (int) (getResources().getDisplayMetrics().widthPixels * 0.38f) - reserved;
                if (maxText > 0) audioCapsuleText.setMaxWidth(maxText);
            }
            capsule.setOnClickListener(v -> {
                if (AudioPlayerService.isRunning())
                    startActivity(new Intent(this, AudioPlayerActivity.class));
            });

            audioCapsuleHandler = new Handler(Looper.getMainLooper());
            audioCapsuleRefresh = new Runnable() {
                @Override
                public void run() {
                    refreshAudioCapsule();
                    if (audioCapsuleHandler != null) audioCapsuleHandler.postDelayed(this, 1500);
                }
            };
            audioCapsuleHandler.post(audioCapsuleRefresh);
        } catch (Throwable t) {
            Logu.e("audio-capsule", "安装音频胶囊失败：" + t);
        }
    }

    /**
     * 把已挂上的音频胶囊下移到顶栏底边之下 2dp（适配审计 20261107 P2-5）：
     * 胶囊原来与顶栏同高，实色底会盖住跑马灯标题的滚动路径——音频播放期间
     * 全站带顶栏页面的标题中段被遮。页面没有顶栏（R.id.top 不存在）时维持原位。
     * 必须在 post 里做：调用时顶栏可能还没完成布局，拿不到真实高度。
     */
    private void repositionAudioCapsule() {
        if (audioCapsule == null) return;
        View capsule = audioCapsule;
        ViewGroup decor = (ViewGroup) getWindow().getDecorView();
        float density = getResources().getDisplayMetrics().density;
        capsule.post(() -> {
            if (audioCapsule != capsule || isDestroyed()) return;
            View topBar = findViewById(R.id.top);
            if (topBar == null || topBar.getHeight() <= 0) return;
            int[] topLoc = new int[2];
            topBar.getLocationOnScreen(topLoc);
            int[] decorLoc = new int[2];
            decor.getLocationOnScreen(decorLoc);
            FrameLayout.LayoutParams capsuleLp = (FrameLayout.LayoutParams) capsule.getLayoutParams();
            capsuleLp.topMargin = topLoc[1] - decorLoc[1] + topBar.getHeight() + (int) (2 * density + 0.5f);
            capsule.setLayoutParams(capsuleLp);
        });
    }

    private void refreshAudioCapsule() {
        if (audioCapsule == null) return;
        try {
            boolean visible = AudioPlayerService.isRunning();
            audioCapsule.setVisibility(visible ? View.VISIBLE : View.GONE);
            if (visible && audioCapsuleText != null) {
                String text = AudioQueue.get().positionText();
                if (AudioPlayerService.isResolving()) text = "…" + text;
                audioCapsuleText.setText(text);
            }
        } catch (Throwable ignored) {
        }
    }

    private void applyThemeColors() {
        try {
            View content = findViewById(android.R.id.content);
            if (content != null) ThemeApplier.apply(content);
        } catch (Throwable t) {
            //换色失败只影响观感，绝不能连累页面启动
            Logu.e("theme", "应用主题色失败：" + t);
        }
    }

    /**
     * 安装氛围背景层（幂等：已在树上就复用，不重复 addView）。
     *
     * <p>onCreate 与 onStart 各调一次：装饰层一旦被系统摘掉（部分 ROM 在页面被覆盖期间
     * 会重建窗口装饰），只在 onCreate 装配就再也回不来——用户看到的就是"从别的页面返回后
     * 新版 UI 的背景没了、整页纯黑"。
     */
    private void installAmbient() {
        ViewGroup decor = (ViewGroup) getWindow().getDecorView();
        //快路径：装饰层第 0 层已经是我们铺的层（氛围背景 / 自定义底图）→ 直接复用。
        //onStart 每次返回都会调本方法（用于"层被系统摘掉"的自愈），走快路径时
        //只做两次 getChildAt，不做 PackageManager IPC、不做文件探测。
        View first = decor.getChildCount() > 0 ? decor.getChildAt(0) : null;
        if (first instanceof AmbientBackground) {
            //字段必须指回它，否则 setAmbientBreathing 会作用在空引用上
            ambientBackground = (AmbientBackground) first;
            return;
        }
        if (BackgroundManager.isInstalledOn(decor)) return;

        //慢路径：每页只在 onCreate（以及"层被摘掉"后的第一次 onStart）走一次
        //自定义背景底图优先：装上了就不再叠氛围层，
        //否则用户自己的图会被那层粉色氛围整体染色，等于白设
        if (BackgroundManager.install(this, decor)) return;
        if (!ambientEnabled()) return;
        //背景风格不随「圆屏适配」变化：圆屏适配只改布局，视觉沿用直屏那套粉色径向光晕
        ambientBackground = new AmbientBackground(this);
        decor.addView(ambientBackground, 0,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Logu.d("背景诊断", getClassName() + " 新装氛围背景层");
    }

    protected void setAmbientBreathing(boolean breathing) {
        if (ambientBackground != null) ambientBackground.setBreathing(breathing);
    }

    /**
     * 圆屏内容区默认安全边距的页面级豁免口。
     *
     * <p>只豁免真正自己管圆屏布局的全屏页（播放器/短视频/看图），不再按 NoSwipe 主题一刀切：
     * DialogActivity（免责声明等提示框）与 TutorialActivity 也挂 NoSwipe 主题，但它们没有
     * 自己的圆屏布局，之前被误豁免后 0 内缩，标题直接顶到 12px、关闭按钮在首屏外。
     * 伪装计算器页刻意满屏，继续挡掉。
     */
    protected boolean roundContentInset() {
        String cls = getClass().getSimpleName();
        if ("PlayerActivity".equals(cls) || "ShortVideoPlayerActivity".equals(cls)
                || "ImageViewerActivity".equals(cls)) return false;
        //伪装计算器页刻意满屏（套边距反而露馅）。它 extends Activity 不经过本类，正常到不了这里，
        //防御性按类名挡一道（instanceof 会因类型不相干直接编译失败）
        return !"CalculatorActivity".equals(cls);
    }

    /**
     * 内容真正 inflate 之后再套一次圆屏内缩：没有顶栏的提示页需要补顶部安全边距，
     * 而 {@code onCreate} 里还没法判断 R.id.top 是否存在。setContentView 的每个入口都会调。
     */
    private void applyRoundContentPadding() {
        if (customPaddingApplied || !roundUi || !roundContentInset()) return;
        View top = findViewById(R.id.top);
        boolean hasTopBar = top != null && top.getVisibility() == View.VISIBLE;
        applyContentPadding(roundInsetH, hasTopBar ? 0 : roundInsetT, roundInsetH, roundInsetB);
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

    /**
     * 顶栏是否挂「渐隐黑」页头遮罩（2026-11-14 反馈：私信页/下载页效果好，全局化）。
     * 沉浸式页面（图片查看/直播/听歌）重写返回 false 排除；播放页直接继承 Activity 天然不受影响。
     */
    protected boolean titlebarFadeEnabled() {
        return true;
    }

    public void setRound() {
        //顶栏渐隐黑全局化（2026-11-14 反馈）：私信页/下载页同款页头遮罩挂到所有普通页面。
        //放判重之前每次 onStart 都设：设置页切换开关后返回页面立即生效，setBackground 同值
        //重复调用开销可忽略。开关关闭时清掉背景，回到原先的透明顶栏
        View topBar = findViewById(R.id.top);
        if (topBar != null) {
            if (titlebarFadeEnabled() && SharedPreferencesUtil.getBoolean("titlebar_fade", true)) {
                topBar.setBackgroundResource(R.drawable.background_titlebar_fade);
            } else {
                topBar.setBackground(null);
            }
        }

        //圆屏适配
        TextView pagename = findViewById(R.id.pageName);
        if (pagename == null) return;
        //onStart 每次返回都会调到这里，asyncInflate 的页面还会在内容 setContentView 后再调一次。
        //同一个 pageName 视图只处理一遍：换成新视图（内容真正 inflate 出来）才重做，
        //避免每次返回都 findViewById + 读 SP + 重设 LayoutParams（后者会触发 requestLayout）。
        //顶栏动作的「更多」收纳要在每次 onStart 都复核（批量/听歌等按钮可能晚于首帧才点亮）。
        if (pagename != roundAppliedOn) {
            roundAppliedOn = pagename;
            //顶栏标题溢出时跑马灯（1.3.3 反馈：私信页效果好评，全局加入）；
            //口径与 MarqueeTextView 完全一致，短标题不溢出不会滚动
            MarqueeTextView.applyTo(pagename);

            TextView clock = findViewById(R.id.timeText);
            pagename.setMaxLines(1);
            pagename.setEllipsize(TextUtils.TruncateAt.END);
            if (SharedPreferencesUtil.getBoolean("player_ui_round", false)) {
                if (clock == null) {
                    //布局没有 timeText（如投票/音频页旧版顶栏）：圆屏重排依赖时钟居中，
                    //缺它就跳过整套重排，下面仍要执行顶栏动作收纳
                    Logu.d("round", "skip (no timeText)");
                } else {
                    try {
                        ViewGroup.LayoutParams params = pagename.getLayoutParams();
                        int paddingH = (int) (window_width * 0.18);
                        int paddingV = (int) (window_width * 0.03);
                        pagename.setPadding(paddingH, paddingV, paddingH, 0);
                        if (params instanceof RelativeLayout.LayoutParams) {
                            //时钟原先贴着 y=0，圆屏顶部会切掉 1dp；整体下移 2dp，标题跟着下移同样距离，
                            //两者相对间距不变
                            int topShift = ToolsUtil.dp2px(2);
                            RelativeLayout.LayoutParams clockParams = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                            clockParams.addRule(RelativeLayout.CENTER_HORIZONTAL);
                            clockParams.topMargin = topShift;
                            clock.setLayoutParams(clockParams);
                            clock.setAlpha(0.85f);
                            clock.setTextSize(12);

                            RelativeLayout.LayoutParams pnParams = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                            pnParams.addRule(RelativeLayout.CENTER_HORIZONTAL);
                            pnParams.topMargin = (int) (window_height * 0.01) + ToolsUtil.sp2px(12) + topShift;
                            pnParams.bottomMargin = (int) (window_height * 0.01);
                            pagename.setLayoutParams(pnParams);
                            pagename.setPadding(0, 0, ToolsUtil.dp2px(5), 0);

                            //顶栏左右两端的动作按钮（刷新/回首页/批量/听歌…）在圆屏上落在圆弧外，
                            //再往下挪也放不进 68px 高的顶栏。统一在 setRound 末尾收进圆内「更多」按钮，
                            //这里不再逐个把按钮对齐到父容器左右。
                            Logu.d("round", "ok");
                        }
                    } catch (Throwable e) {
                        MsgUtil.err("圆屏适配执行错误：", e);
                    }
                }
            }
        }

        if (roundUi && roundContentInset()) {
            //没有顶栏的提示页（Dialog/Tutorial/Catch）在这里补顶部安全边距；
            //有顶栏的页面 hasTopBar=true，不会重复内缩。
            applyRoundContentPadding();
            refreshRoundTopbarOverflow();
        } else if (roundOverflowButton != null) {
            //自己管圆屏布局的全屏页（播放器/看图/短视频）不接管顶栏动作
            roundOverflowButton.setVisibility(View.GONE);
        }
    }

    // ---------------------------------------------------------------- 圆屏顶栏「更多」

    /** 圆内「更多」按钮：32dp 视觉 + 48dp 触摸区，落在底部安全带（距底 3dp） */
    private View roundOverflowButton;
    private ViewGroup roundOverflowTop;
    private final ArrayList<View> roundOverflowActions = new ArrayList<>();
    private final ArrayList<String> roundOverflowLabels = new ArrayList<>();
    private boolean roundOverflowRefreshScheduled;

    private void ensureRoundOverflowButton() {
        if (roundOverflowButton != null || !(getWindow().getDecorView() instanceof ViewGroup)) return;
        final ViewGroup decor = (ViewGroup) getWindow().getDecorView();
        TextView fab = new TextView(this);
        fab.setText("⋮");
        fab.setTextSize(20);
        fab.setTextColor(0xFFFFFFFF);
        fab.setGravity(Gravity.CENTER);
        fab.setContentDescription("更多操作");
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x66000000);
        bg.setStroke(ToolsUtil.dp2px(1), 0x40FFFFFF);
        fab.setBackground(bg);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ToolsUtil.dp2px(32), ToolsUtil.dp2px(32),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        //18% 底边距（384 下 68px）刚好容下 32dp 按钮 + 3dp 边距，不压列表最后一行
        lp.bottomMargin = ToolsUtil.dp2px(3);
        fab.setVisibility(View.GONE);
        fab.setOnClickListener(v -> showRoundOverflowMenu());
        decor.addView(fab, lp);
        roundOverflowButton = fab;
        //32dp 视觉按钮触摸区补到 48dp（Wear 最小点击目标），视觉不变
        decor.post(() -> {
            if (roundOverflowButton == null) return;
            Rect hit = new Rect();
            roundOverflowButton.getHitRect(hit);
            int expand = ToolsUtil.dp2px(8);
            hit.inset(-expand, -expand);
            decor.setTouchDelegate(new TouchDelegate(hit, roundOverflowButton));
        });
    }

    private void refreshRoundTopbarOverflow() {
        ViewGroup top = findViewById(R.id.top);
        if (top == null) {
            if (roundOverflowButton != null) roundOverflowButton.setVisibility(View.GONE);
            return;
        }
        ensureRoundOverflowButton();
        if (top != roundOverflowTop) {
            roundOverflowTop = top;
            top.addOnLayoutChangeListener((v, left, topEdge, right, bottom,
                                           oldLeft, oldTop, oldRight, oldBottom) ->
                    scheduleRoundOverflowRefresh());
        }
        scheduleRoundOverflowRefresh();
    }

    private void scheduleRoundOverflowRefresh() {
        if (roundOverflowRefreshScheduled) return;
        final ViewGroup top = roundOverflowTop;
        if (top == null) return;
        roundOverflowRefreshScheduled = true;
        top.post(() -> {
            roundOverflowRefreshScheduled = false;
            collectRoundOverflowActions();
        });
    }

    /**
     * 把顶栏里落到圆弧外的可见动作收进「更多」。
     *
     * <p>只收 clickable 的子 View，pageName/timeText 按引用排除；已经收过的按钮用 tag 记住，
     * 避免 onStart 复核时把"自己收走的 GONE"当成"页面隐藏"而丢出菜单。
     */
    private void collectRoundOverflowActions() {
        ViewGroup top = roundOverflowTop;
        if (top == null) return;
        TextView pageName = findViewById(R.id.pageName);
        TextView clock = findViewById(R.id.timeText);
        View decor = getWindow().getDecorView();
        int screenW = decor.getWidth();
        int screenH = decor.getHeight();
        if (screenW <= 0 || screenH <= 0) return;
        int cx = screenW / 2;
        int cy = screenH / 2;
        int radius = RoundGeometry.radius(screenW, screenH);
        float tolerance = ToolsUtil.dp2px(2);

        ArrayList<View> actions = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();
        for (int i = 0; i < top.getChildCount(); i++) {
            View child = top.getChildAt(i);
            if (child == pageName || child == clock || child == roundOverflowButton) continue;
            boolean hiddenByUs = Boolean.TRUE.equals(child.getTag(R.id.round_overflow_hidden));
            if (child.getVisibility() != View.VISIBLE && !hiddenByUs) continue;
            if (!child.isClickable()) continue;
            if (hiddenByUs) {
                actions.add(child);
                labels.add(roundOverflowLabel(child, labels.size()));
                continue;
            }
            Rect rect = new Rect();
            if (!child.getGlobalVisibleRect(rect) || rect.isEmpty()) continue;
            if (RoundGeometry.maxCornerDepth(rect, cx, cy, radius) > tolerance) {
                actions.add(child);
                labels.add(roundOverflowLabel(child, labels.size()));
            }
        }

        //页面后来把某个动作放到圆内（或自己隐藏了）时，清掉"我们收走"的标记，不强行改可见性
        for (View old : roundOverflowActions) {
            if (!actions.contains(old)) old.setTag(R.id.round_overflow_hidden, null);
        }
        for (View action : actions) {
            action.setTag(R.id.round_overflow_hidden, Boolean.TRUE);
            if (action.getVisibility() == View.VISIBLE) action.setVisibility(View.GONE);
        }

        boolean changed = actions.size() != roundOverflowActions.size();
        if (!changed) {
            for (int i = 0; i < actions.size(); i++) {
                if (actions.get(i) != roundOverflowActions.get(i)
                        || !labels.get(i).equals(roundOverflowLabels.get(i))) {
                    changed = true;
                    break;
                }
            }
        }
        if (changed) {
            roundOverflowActions.clear();
            roundOverflowActions.addAll(actions);
            roundOverflowLabels.clear();
            roundOverflowLabels.addAll(labels);
        }
        if (roundOverflowButton != null) {
            roundOverflowButton.setVisibility(roundOverflowActions.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private String roundOverflowLabel(View view, int index) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (!TextUtils.isEmpty(text)) return text.toString();
        }
        CharSequence desc = view.getContentDescription();
        if (!TextUtils.isEmpty(desc)) return desc.toString();
        return "操作 " + (index + 1);
    }

    private void showRoundOverflowMenu() {
        if (roundOverflowActions.isEmpty()) return;
        final ArrayList<View> actions = new ArrayList<>(roundOverflowActions);
        //标签现场取：批量按钮会从「批量」变成「取消/删除(n)」，用收集时的旧标签会误导
        String[] labels = new String[actions.size()];
        for (int i = 0; i < actions.size(); i++) labels[i] = roundOverflowLabel(actions.get(i), i);
        AdaptiveDialog.Builder builder = new AdaptiveDialog.Builder(this).setTitle("页面操作");
        builder.setItems(labels, (dialog, which) -> {
                    dialog.dismiss();
                    if (which >= 0 && which < actions.size()) actions.get(which).performClick();
                })
                .setNegativeButton("取消", null);
        builder.show();
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
        //每次回到前台都补一次（幂等）：装饰层若被系统摘掉/重建，这里能把它补回去
        installAmbient();
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
        //胶囊随页面销毁停止轮询（音频播放本身由 Service 承担，不受影响）
        if (audioCapsuleHandler != null) {
            audioCapsuleHandler.removeCallbacksAndMessages(null);
            audioCapsuleHandler = null;
        }
        audioCapsuleRefresh = null;
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
        //sticky 事件只应被消费一次：此前 onStart 注册与 onResume 各取一次，换页后还会把
        //上一页的"下载列表为空"之类提示重新弹出来（番剧详情页实测）
        EventBus.getDefault().removeStickyEvent(event);
    }

    protected boolean eventBusEnabled() {
        return SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.SNACKBAR_ENABLE, true);
    }

    public void setDensity(int targetDensityDpi) {
        if (Build.VERSION.SDK_INT < 17) return;
        Resources resources = getResources();

        if (resources.getConfiguration().densityDpi == targetDensityDpi) return;

        Configuration configuration = resources.getConfiguration();
        //只在缩小密度时锁字体缩放（适配审计 P2-15）：缩小 UI 的意图是"更小的界面"，
        //保留系统字体放大会让文字相对界面显得巨大溢出；放大/不变密度时尊重系统字体缩放，
        //此前无条件锁 1.0 导致系统字体放大对整应用失效。注意比较必须在覆盖 densityDpi 之前
        boolean downscale = targetDensityDpi < configuration.densityDpi;
        configuration.densityDpi = targetDensityDpi;
        if (downscale) configuration.fontScale = 1f;
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
        try {
            super.startActivity(intent);
            applyNoTransition();
        } catch (ActivityNotFoundException e) {
            handleActivityNotFound(intent);
        }
    }

    @Override
    public void startActivity(Intent intent, @Nullable Bundle options) {
        try {
            super.startActivity(intent, options);
            applyNoTransition();
        } catch (ActivityNotFoundException e) {
            handleActivityNotFound(intent);
        }
    }

    @Override
    public void startActivityForResult(Intent intent, int requestCode) {
        try {
            super.startActivityForResult(intent, requestCode);
            applyNoTransition();
        } catch (ActivityNotFoundException e) {
            handleActivityNotFound(intent);
        }
    }

    @Override
    public void startActivityForResult(Intent intent, int requestCode, @Nullable Bundle options) {
        try {
            super.startActivityForResult(intent, requestCode, options);
            applyNoTransition();
        } catch (ActivityNotFoundException e) {
            handleActivityNotFound(intent);
        }
    }

    /**
     * 隐式 Intent 在精简 ROM/手表上可能没有任何处理者，startActivity* 会同步抛
     * ActivityNotFoundException（线上本地视频选择器一条即此）。全局兜底只保证不闪退，
     * 需要业务回退的入口仍应在调用点做 resolveActivity/降级链。
     *
     * <p>只接 ActivityNotFoundException，SecurityException/业务异常照常抛出。
     */
    private void handleActivityNotFound(Intent intent) {
        String action = intent == null ? "null" : String.valueOf(intent.getAction());
        String type = intent == null ? null : intent.getType();
        CrashTrace.log("无处理者: " + action + (type == null || type.isEmpty() ? "" : "/" + type));
        MsgUtil.showMsg("这台设备没有可用的选择器/应用");
    }

    @Override
    public void finish() {
        super.finish();
        applyNoTransition();
    }
}
