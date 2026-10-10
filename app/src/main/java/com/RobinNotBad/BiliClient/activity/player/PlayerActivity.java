package com.RobinNotBad.BiliClient.activity.player;

import static android.media.AudioManager.STREAM_MUSIC;
import static com.RobinNotBad.BiliClient.util.NetWorkUtil.USER_AGENT_WEB;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.AnimationDrawable;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.InteractionDebugActivity;
import com.RobinNotBad.BiliClient.adapter.QualitySelectorAdapter;
import com.RobinNotBad.BiliClient.adapter.ViewPointAdapter;
import com.RobinNotBad.BiliClient.api.ConfInfoApi;
import com.RobinNotBad.BiliClient.api.BangumiApi;
import com.RobinNotBad.BiliClient.api.DanmakuApi;
import com.RobinNotBad.BiliClient.api.HistoryApi;
import com.RobinNotBad.BiliClient.api.InteractionVideoApi;
import com.RobinNotBad.BiliClient.api.PlayerApi;
import com.RobinNotBad.BiliClient.api.VideoInfoApi;
import com.RobinNotBad.BiliClient.event.SnackEvent;
import com.RobinNotBad.BiliClient.model.DmSegMobileReply;
import com.RobinNotBad.BiliClient.model.HighEnergyData;
import com.RobinNotBad.BiliClient.model.InteractionVideoData;
import com.RobinNotBad.BiliClient.model.PlayerData;
import com.RobinNotBad.BiliClient.model.Subtitle;
import com.RobinNotBad.BiliClient.model.SubtitleLink;
import com.RobinNotBad.BiliClient.model.ViewPoint;
import com.RobinNotBad.BiliClient.service.PlaybackService;
import com.RobinNotBad.BiliClient.ui.widget.BatteryView;
import com.RobinNotBad.BiliClient.ui.widget.HighEnergyProgressBar;
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.CookieGenerator;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.ProgressDiag;
import com.RobinNotBad.BiliClient.util.ProtobufParser;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.RobinNotBad.BiliClient.util.StringUtil;
import com.RobinNotBad.BiliClient.util.ToolsUtil;
import com.google.android.material.snackbar.Snackbar;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

import master.flame.danmaku.controller.DrawHandler;
import master.flame.danmaku.controller.IDanmakuView;
import master.flame.danmaku.danmaku.loader.ILoader;
import master.flame.danmaku.danmaku.loader.android.DanmakuLoaderFactory;
import master.flame.danmaku.danmaku.model.BaseDanmaku;
import master.flame.danmaku.danmaku.model.DanmakuTimer;
import master.flame.danmaku.danmaku.model.IDisplayer;
import master.flame.danmaku.danmaku.model.android.DanmakuContext;
import master.flame.danmaku.danmaku.model.android.Danmakus;
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser;
import master.flame.danmaku.danmaku.parser.IDataSource;
import master.flame.danmaku.danmaku.parser.android.BiliDanmukuParser;
import master.flame.danmaku.danmaku.parser.android.BiliProtobufDanmakuParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okio.BufferedSink;
import okio.Okio;
import okio.Sink;
import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

public class PlayerActivity extends Activity implements IjkMediaPlayer.OnPreparedListener {
    private boolean destroyed = false;

    private IjkMediaPlayer ijkPlayer;
    private IDanmakuView mDanmakuView;
    private DanmakuContext mContext;

    private SurfaceView surfaceView;
    private TextureView textureView;
    private SurfaceTexture mSurfaceTexture;
    //TextureView 模式下包给 IjkMediaPlayer 的 Surface 必须显式释放：
    //每次换源/切P/清晰度都会 new 一个，只依赖 finalizer 回收会累积 BufferQueue（native 显存 + fd），
    //长时间切换后进程地址空间被吃光，新线程的 4MB 栈 mmap 失败即 OOM: pthread_create failed
    private Surface ijkSurface;

    private SubtitleLink[] subtitleLinks = null;
    private Subtitle[] subtitles = null;
    private int subtitle_curr_index, subtitle_count;
    private float subtitle_delta;

    private RelativeLayout layout_control, layout_top, layout_video, layout_card_bg, layout_audio_only;
    private LinearLayout layout_speed, right_control, loading_info;
    private RelativeLayout bottom_buttons;
    private HorizontalScrollView right_second;
    private LinearLayout card_subtitle, card_danmaku_send, card_page_selector, card_quality_selector, card_viewpoint_selector;

    private ImageView img_loading;
    private AnimationDrawable anim_loading;
    private ImageButton btn_control, btn_danmaku, btn_loop, btn_rotate, btn_menu, btn_subtitle, btn_danmaku_send,
            btn_audio_only, btn_page_selector, btn_auto_next, btn_quality, btn_viewpoint, btn_debug;
    private HighEnergyProgressBar seekbar_progress;
    private SeekBar seekbar_speed;
    private TextView text_progress, text_online, text_volume, loading_text0, loading_text1, text_speed, text_newspeed;
    public TextView text_title, text_subtitle, text_audio_title, text_audio_subtitle;

    private Timer progressTimer, speedTimer, loadingTimer, onlineTimer, surfaceTimer;
    private Handler mainHandler;
    private String video_url, danmaku_url;
    private MediaSession mediaSession;

    private boolean isPlaying, isPrepared, hasDanmaku,
            isOnlineVideo, isLiveMode, isSeeking, isDanmakuVisible;
    private boolean menu_opened = false;
    private boolean isAudioOnlyMode = false;
    private boolean isLocalAudioFile = false; // 标记是否为本地音频文件

    private int video_all, video_now_last;
    //video_now 由 progressTimer 在后台线程写，退出路径（onPause/onStop/onDestroy，主线程）要读它做兜底上报，
    //必须 volatile，否则主线程可能读到 0 或旧值，上报的位置就是错的
    private volatile int video_now;
    private long progress_history;
    private String progress_str;

    //番剧维度随 Intent 进入，播放中周期上报需要（epid=0 即普通视频）
    private long epid = 0;
    private long seasonId = 0;
    private int seasonType = 0;
    private String bvid = "";
    //周期上报节流：记录上次上报的视频位置，推进超过阈值才再报，避免 250ms tick 打爆接口
    private long lastReportedProgressMs = -1;
    //5 秒与 PiliPlus 一致（它按播放位置每 +5s 发一次心跳），服务端记录更接近"退出那一刻"
    private static final long PROGRESS_REPORT_INTERVAL_MS = 5000;
    //兜底上报（onPause/onStop/onDestroy）去重：记录已上报的秒数，避免三连发写同一个位置
    private long lastReportedProgressSec = -1;
    //未登录只提示一次，否则每隔一个上报周期刷一条日志
    private boolean notLoggedInWarned = false;
    //进度端到端自检：播放到 25 秒时回读一次服务端，确认上报是否真的落库（诊断用，只做一次）
    private boolean diagReadbackDone = false;

    //弹幕跳转请求：弹幕尚未 prepare 时 DanmakuView.seekTo 会被丢弃，先记住位置等 prepared 回调补做
    private long pendingDanmakuSeekMs = -1;
    //弹幕与播放器位置允许的最大偏差，超过则纠正一次
    private static final long DANMAKU_SYNC_TOLERANCE_MS = 2000;
    //弹幕校正冷却，避免偏差持续存在时每 250ms 触发一次 seek
    private long lastDanmakuSyncMs = 0;
    private static final long DANMAKU_SYNC_COOLDOWN_MS = 3000;
    //卡死判定：上次观测到的弹幕时间轴位置与观测时刻
    private long lastObservedDanmakuMs = -1;
    private long lastObservedWallMs = 0;

    private int screen_width, screen_height;
    private int video_width, video_height;

    private AudioManager audioManager;

    private ScaleGestureDetector scaleGestureDetector;
    private ViewScaleGestureListener scaleGestureListener;
    private GestureDetector doubleTapGestureDetector;
    private float previousX, previousY;
    private boolean gesture_moved, gesture_scaled, gesture_click_disabled;
    private float video_origX, video_origY;
    private long timestamp_click;
    private long doubleTapSeekTimestamp;
    private boolean onLongClick = false;

    private final float[] speed_values = {0.5F, 0.75F, 1.0F, 1.25F, 1.5F, 1.75F, 2.0F, 3.0F};
    private final String[] speed_strs = {"x 0.5", "x 0.75", "x 1.0", "x 1.25", "x 1.5", "x 1.75", "x 2.0", "x 3.0"};

    private boolean finishWatching = false;
    //播放器错误态：onError 置位，点播放按钮触发重新载入（见 controlVideo / retryAfterPlayerError）
    private boolean playerError = false;
    private boolean loop_enabled;
    private boolean auto_next_enabled = false;

    private BatteryView batteryView;
    private BatteryManager batteryManager;

    private File danmakuFile;

    private boolean screen_landscape, screen_round;

    public String online_number = "0";

    private long aid, cid;
    //mid 在 getExtras（主线程）与上传前兜底解析（主线程）里写，却会被 progressTimer 线程读，故 volatile
    private volatile long mid;

    private ArrayList<String> pagenames;
    private ArrayList<Long> cids;
    private int currentPageIndex = 0;
    private String videoTitle;

    private String[] qnStrList;
    private int[] qnValueList;
    private int currentQuality = 0;

    private InteractionVideoData interactionData;
    private long interactionGraphVersion = 0;
    private long currentEdgeId = 0;
    private long initialEdgeId = 0;
    private InteractionVideoData.InteractionQuestion currentQuestion = null;
    private boolean questionShown = false;
    private LinearLayout interactionChoiceLayout;

    @Override
    public void onBackPressed() {
        if (!SharedPreferencesUtil.getBoolean("back_disable", false))
            super.onBackPressed();
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(BiliTerminal.getFitDisplayContext(newBase));
    }

    private boolean getExtras() {
        Intent intent = getIntent();
        if (intent == null)
            return false;

        video_url = intent.getStringExtra("url");
        danmaku_url = intent.getStringExtra("danmaku");
        String title = intent.getStringExtra("title");

        if (video_url == null)
            return false;
        if (danmaku_url != null)
            Logu.v("弹幕", danmaku_url);
        Logu.v("视频", video_url);
        Logu.v("标题", title);
        text_title.setText(title);
        videoTitle = title;

        aid = intent.getLongExtra("aid", 0);
        cid = intent.getLongExtra("cid", 0);
        mid = intent.getLongExtra("mid", 0);
        //番剧维度（jumpToPlayer 补传），播放中的周期进度上报依赖这几个值
        epid = intent.getLongExtra("epid", 0);
        seasonId = intent.getLongExtra("seasonId", 0);
        seasonType = intent.getIntExtra("seasonType", 0);
        bvid = intent.getStringExtra("bvid");
        if (bvid == null) bvid = "";

        progress_history = intent.getIntExtra("progress", 0);
        Logu.d("history", String.valueOf(progress_history));

        isLiveMode = intent.getBooleanExtra("live_mode", false);
        isOnlineVideo = video_url.contains("http");
        hasDanmaku = danmaku_url != null && !danmaku_url.isEmpty();   //本地视频/直播路径可能不传 danmaku extra，getStringExtra 可为 null

        if (intent.hasExtra("pagenames") && intent.hasExtra("cids")) {
            pagenames = intent.getStringArrayListExtra("pagenames");
            ArrayList<Long> cidList = new ArrayList<>();
            long[] cidArray = intent.getLongArrayExtra("cids");
            if (cidArray != null) {
                for (long c : cidArray) {
                    cidList.add(c);
                }
            }
            cids = cidList;
            currentPageIndex = intent.getIntExtra("currentPageIndex", 0);
        }

        initialEdgeId = intent.getLongExtra("edgeId", 0);
        if (initialEdgeId > 0) {
            currentEdgeId = initialEdgeId;
        }

        if (intent.hasExtra("qnStrList") && intent.hasExtra("qnValueList")) {
            qnStrList = intent.getStringArrayExtra("qnStrList");
            qnValueList = intent.getIntArrayExtra("qnValueList");
            currentQuality = intent.getIntExtra("currentQuality", SharedPreferencesUtil.getInt("play_qn", 16));
        }

        return true;
    }

    @SuppressLint("SimpleDateFormat")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Logu.v("加载", "加载");
        super.onCreate(savedInstanceState);

        screen_landscape = SharedPreferencesUtil.getBoolean("player_autolandscape", false)
                || SharedPreferencesUtil.getBoolean("ui_landscape", false);
        if (SharedPreferencesUtil.getBoolean("dev_player_rotate_software", false) && screen_landscape) {
            MsgUtil.showMsg("不支持默认横屏！");
            screen_landscape = false;
        } else {
            setRequestedOrientation(screen_landscape ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }

        setContentView(R.layout.activity_player);
        findview();
        if (!getExtras()) {
            finish();
            return;
        }

        initUI();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.PLAYER_MEDIA_SESSION_ENABLE, false)) {
            initMediaSession();
        }

        IjkMediaPlayer.loadLibrariesOnce(null);

        ijkPlayer = new IjkMediaPlayer();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            batteryManager = (BatteryManager) getSystemService(BATTERY_SERVICE);
            batteryView.setPower(batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
        } else
            batteryView.setVisibility(View.GONE);

        loop_enabled = SharedPreferencesUtil.getBoolean("player_loop", false);
        // 从设置读取听视频模式的默认值
        isAudioOnlyMode = SharedPreferencesUtil.getBoolean("player_audio_only", false);
        // 从Intent读取是否为仅音频模式（用于播放本地音频文件）
        isLocalAudioFile = getIntent().getBooleanExtra("audio_only", false);
        if (isLocalAudioFile) {
            isAudioOnlyMode = true;
        }
        img_loading.setImageResource(R.drawable.loading_tv_shaking);
        anim_loading = (AnimationDrawable) img_loading.getDrawable();
        anim_loading.start();

        File cachepath = getCacheDir();
        if (!cachepath.exists())
            cachepath.mkdirs();

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        mainHandler = new Handler(Looper.getMainLooper());

        setVideoGestures();
        autohideReset();

        initSeekbars();

        if (isLiveMode) {
            btn_control.setVisibility(View.GONE); // 直播模式隐藏暂停按钮，使用GONE而不是INVISIBLE以保持UI布局正确
            seekbar_progress.setVisibility(View.GONE);
            seekbar_progress.setEnabled(false);
            streamDanmaku(null); // 用来初始化一下弹幕层
            // danmuSocketConnect();
            // 先把弹幕连接注释掉
        }

        layout_control.postDelayed(() -> CenterThreadPool.run(() -> { // 等界面加载完成
            if (isLiveMode) {
                runOnUiThread(() -> {
                    btn_menu.setVisibility(View.GONE);
                    // 直播模式隐藏清晰度按钮
                    btn_quality.setVisibility(View.GONE);
                    // 直播模式隐藏循环按钮
                    btn_loop.setVisibility(View.GONE);
                    // 直播模式隐藏听视频模式按钮
                    btn_audio_only.setVisibility(View.GONE);
                    // 直播模式隐藏自动下一个按钮
                    btn_auto_next.setVisibility(View.GONE);
                    // 直播模式隐藏分P选择器按钮
                    btn_page_selector.setVisibility(View.GONE);
                });
                setDisplay();
                return;
            }

            runOnUiThread(() -> {
                loading_text0.setText("装填弹幕中");
                loading_text1.setText("(≧∇≦)");
            });
            if (isOnlineVideo) {
                danmakuFile = new File(cachepath, "danmaku.xml");
                downdanmu();
            } else {
                runOnUiThread(() -> btn_danmaku_send.setVisibility(View.GONE));
                danmakuFile = new File(danmaku_url);
                if (danmakuFile.exists())
                    streamDanmaku(danmakuFile.toString());
                else
                    hasDanmaku = false;
            }

            if (!destroyed && SharedPreferencesUtil.getBoolean("player_subtitle_autoshow", true))
                downSubtitle(false);

            // 加载高能进度条数据
            if (!destroyed && isOnlineVideo && aid > 0 && cid > 0) {
                loadHighEnergyData();
            }

            if (!destroyed && isOnlineVideo && aid > 0 && cid > 0 && SharedPreferencesUtil.getBoolean("player_show_viewpoints", false)) {
                loadViewPoints();
            }

            if (!destroyed && isOnlineVideo && aid > 0 && cid > 0) {
                loadInteractionVideo();
            }

            if (!destroyed)
                setDisplay();
        }), 60);
    }

    private void findview() {
        layout_control = findViewById(R.id.control_layout);
        layout_top = findViewById(R.id.top);
        right_control = findViewById(R.id.right_control);
        right_second = findViewById(R.id.right_second);
        layout_card_bg = findViewById(R.id.card_bg);
        card_subtitle = findViewById(R.id.subtitle_card);
        card_danmaku_send = findViewById(R.id.danmaku_send_card);
        card_page_selector = findViewById(R.id.page_selector_card);
        card_quality_selector = findViewById(R.id.quality_selector_card);
        card_viewpoint_selector = findViewById(R.id.viewpoint_selector_card);
        layout_audio_only = findViewById(R.id.audio_only_layout);

        loading_info = findViewById(R.id.loading_info);

        img_loading = findViewById(R.id.circle);
        text_progress = findViewById(R.id.text_progress);
        text_online = findViewById(R.id.text_online);
        btn_danmaku = findViewById(R.id.danmaku_btn);
        btn_loop = findViewById(R.id.loop_btn);
        btn_rotate = findViewById(R.id.rotate_btn);
        btn_menu = findViewById(R.id.menu_btn);
        btn_danmaku_send = findViewById(R.id.danmaku_send_btn);
        btn_subtitle = findViewById(R.id.subtitle_btn);
        btn_audio_only = findViewById(R.id.audio_only_btn);
        btn_control = findViewById(R.id.button_video);
        btn_page_selector = findViewById(R.id.button_page_selector);
        btn_auto_next = findViewById(R.id.auto_next_btn);
        btn_quality = findViewById(R.id.button_quality);
        btn_viewpoint = findViewById(R.id.viewpoint_btn);
        seekbar_progress = findViewById(R.id.videoprogress);
        loading_text0 = findViewById(R.id.loading_text0);
        loading_text1 = findViewById(R.id.loading_text1);
        text_title = findViewById(R.id.text_title);
        text_volume = findViewById(R.id.showsound);
        layout_video = findViewById(R.id.videoArea);
        mDanmakuView = findViewById(R.id.sv_danmaku);
        batteryView = findViewById(R.id.battery);

        text_speed = findViewById(R.id.text_speed);
        layout_speed = findViewById(R.id.layout_speed);
        seekbar_speed = findViewById(R.id.seekbar_speed);
        text_newspeed = findViewById(R.id.text_newspeed);
        bottom_buttons = findViewById(R.id.bottom_buttons);
        btn_debug = findViewById(R.id.btn_debug);

        text_subtitle = findViewById(R.id.text_subtitle);
        text_audio_title = findViewById(R.id.audio_title);
        text_audio_subtitle = findViewById(R.id.audio_subtitle);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setVideoGestures() {
        boolean doubleTapSeekEnabled = SharedPreferencesUtil.getBoolean("player_doubletap_seek", false);
        int doubleTapSeekSeconds = SharedPreferencesUtil.getInt("player_doubletap_seek_seconds", 10);
        boolean doubleTapResetFirst = SharedPreferencesUtil.getBoolean("player_doubletap_reset_first", true);
        
        if (doubleTapSeekEnabled) {
            doubleTapGestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    if (ijkPlayer != null && isPrepared && !isLiveMode) {
                        float x = e.getX();
                        float viewWidth = layout_control.getWidth();
                        long currentPosition = video_now;   //进度条由 progressTimer 后台维护，读它不取原生锁
                        long seekOffset = doubleTapSeekSeconds * 1000L;

                        gesture_click_disabled = true;
                        doubleTapSeekTimestamp = System.currentTimeMillis();

                        if (isDoubleTapCenter(e)) {
                            boolean canReset = SharedPreferencesUtil.getBoolean("player_scale", true)
                                    && scaleGestureListener != null
                                    && scaleGestureListener.can_reset;
                            if (canReset && doubleTapResetFirst) {
                                scaleGestureListener.can_reset = false;
                                layout_video.setX(video_origX);
                                layout_video.setY(video_origY);
                                layout_video.setScaleX(1.0f);
                                layout_video.setScaleY(1.0f);
                            } else {
                                if (isPlaying)
                                    playerPause();
                                else
                                    playerResume();
                            }
                            return true;
                        }

                        if (x > viewWidth / 2.0f) {
                            long newPosition = currentPosition + seekOffset;
                            long duration = ijkPlayer.getDuration();
                            if (newPosition > duration) {
                                newPosition = duration;
                            }
                            seekToPosition(newPosition);
                        } else {
                            long newPosition = currentPosition - seekOffset;
                            if (newPosition < 0) {
                                newPosition = 0;
                            }
                            seekToPosition(newPosition);
                        }
                        return true;
                    }
                    return false;
                }
            });
        }
        
        if (SharedPreferencesUtil.getBoolean("player_scale", true)) {
            scaleGestureListener = new ViewScaleGestureListener(layout_video);
            scaleGestureDetector = new ScaleGestureDetector(this, scaleGestureListener);

            boolean doublemove_enabled = SharedPreferencesUtil.getBoolean("player_doublemove", true);

            layout_control.setOnTouchListener((v, event) -> {
                if (doubleTapSeekEnabled && doubleTapGestureDetector != null) {
                    doubleTapGestureDetector.onTouchEvent(event);
                }
                int action = event.getActionMasked();
                int pointerCount = event.getPointerCount();
                boolean singleTouch = pointerCount == 1;
                boolean doubleTouch = pointerCount == 2;

                // Logu.v("gesture", event.getEventTime() + "");
                scaleGestureDetector.onTouchEvent(event);
                boolean gesture_scaling = scaleGestureListener.scaling;

                if (!gesture_scaled && gesture_scaling)
                    gesture_scaled = true;

                // Logu.v("gesture", (scaling ? "scaled-yes" : "scaled-no"));

                switch (action) {
                    case MotionEvent.ACTION_MOVE:
                        if (singleTouch) {
                            if (gesture_scaling) {
                                videoMoveBy(0, 0); // 防止单指缩放出框
                            } else if (!(gesture_scaled && !doublemove_enabled)) {
                                float currentX = event.getX(0); // 单指移动
                                float currentY = event.getY(0);
                                float deltaX = currentX - previousX;
                                float deltaY = currentY - previousY;
                                if (deltaX != 0f || deltaY != 0f) {
                                    videoMoveBy(deltaX, deltaY);
                                    previousX = currentX;
                                    previousY = currentY;
                                }
                            }
                        }
                        if (doubleTouch && doublemove_enabled) {
                            float currentX = (event.getX(0) + event.getX(1)) / 2;
                            float currentY = (event.getY(0) + event.getY(1)) / 2;
                            float deltaX = currentX - previousX;
                            float deltaY = currentY - previousY;
                            if (deltaX != 0f || deltaY != 0f) {
                                videoMoveBy(deltaX, deltaY);
                                previousX = currentX;
                                previousY = currentY;
                            }
                        }
                        break;

                    case MotionEvent.ACTION_DOWN:
                        if (singleTouch) { // 如果是单指按下，设置起始位置为当前手指位置
                            previousX = event.getX(0);
                            previousY = event.getY(0);
                            // Logu.v("gesture", "touch_start:" + previousX + "," + previousY);
                        }
                        break;

                    case MotionEvent.ACTION_POINTER_DOWN:
                        if (doubleTouch) { // 如果是双指按下，设置起始位置为两指连线的中心点
                            previousX = (event.getX(0) + event.getX(1)) / 2;
                            previousY = (event.getY(0) + event.getY(1)) / 2;
                            // Logu.v("gesture","double_touch");
                        }
                        break;

                    case MotionEvent.ACTION_POINTER_UP:
                        if (doubleTouch) {
                            int index = event.getActionIndex(); // actionIndex是抬起来的手指位置
                            previousX = event.getX((index == 0 ? 1 : 0));
                            previousY = event.getY((index == 0 ? 1 : 0));
                            // Logu.v("gesture","single_touch");
                        }
                        break;

                    case MotionEvent.ACTION_UP:
                        if (onLongClick) {
                            onLongClick = false;
                            float normalSpeed = speed_values[seekbar_speed.getProgress()];
                            if (ijkPlayer != null)
                                ijkPlayer.setSpeed(normalSpeed);
                            if (mDanmakuView != null)
                                mDanmakuView.setSpeed(normalSpeed);
                            text_speed.setText(speed_strs[seekbar_speed.getProgress()]);
                        }
                        if (gesture_moved)
                            gesture_moved = false;
                        if (gesture_scaled)
                            gesture_scaled = false;
                        break;
                }

                if (!gesture_click_disabled && (gesture_moved || gesture_scaled)) {
                    gesture_click_disabled = true;
                    hidecon.run();
                }

                return false;
            });
        } else {
            layout_control.setOnTouchListener((view, motionEvent) -> {
                if (doubleTapSeekEnabled && doubleTapGestureDetector != null) {
                    doubleTapGestureDetector.onTouchEvent(motionEvent);
                }
                if (motionEvent.getAction() == MotionEvent.ACTION_UP && onLongClick) {
                    onLongClick = false;
                    float normalSpeed = speed_values[seekbar_speed.getProgress()];
                    if (ijkPlayer != null)
                        ijkPlayer.setSpeed(normalSpeed);
                    if (mDanmakuView != null)
                        mDanmakuView.setSpeed(normalSpeed);
                    text_speed.setText(speed_strs[seekbar_speed.getProgress()]);
                }
                return false;
            });
        }

        // 这个管普通点击
        layout_control.setOnClickListener(view -> {
            if (gesture_click_disabled) {
                gesture_click_disabled = false;
                return;
            }
            if (doubleTapGestureDetector != null && System.currentTimeMillis() - doubleTapSeekTimestamp < 400) {
                return;
            }
            clickUI();
        });
        // 这个管长按开始
        layout_control.setOnLongClickListener(view -> {
            if (SharedPreferencesUtil.getBoolean("player_longclick", true) && ijkPlayer != null && isPlaying
                    && !isLiveMode) {
                if (!onLongClick && !gesture_click_disabled) {
                    hidecon.run();
                    if (ijkPlayer != null)
                        ijkPlayer.setSpeed(3.0F);
                    if (mDanmakuView != null)
                        mDanmakuView.setSpeed(3.0f);
                    text_speed.setText("x 3.0");
                    onLongClick = true;
                    Logu.v("gesture", "longclick_down");
                    return true;
                }
                return false;
            }
            return false;
        });

    }

    private void autohideReset() {
        layout_control.removeCallbacks(hidecon);
        layout_control.postDelayed(hidecon, 4000);
    }

    private void clickUI() {
        long now_timestamp = System.currentTimeMillis();
        if (now_timestamp - timestamp_click < 300) {
            boolean canReset = SharedPreferencesUtil.getBoolean("player_scale", true)
                    && scaleGestureListener != null
                    && scaleGestureListener.can_reset;
            boolean doubleTapResetFirst = SharedPreferencesUtil.getBoolean("player_doubletap_reset_first", true);

            if (canReset && doubleTapResetFirst) {
                scaleGestureListener.can_reset = false;
                layout_video.setX(video_origX);
                layout_video.setY(video_origY);
                layout_video.setScaleX(1.0f);
                layout_video.setScaleY(1.0f);
            } else if (!isLiveMode) {
                if (isPlaying)
                    playerPause();
                else
                    playerResume();
                showcon();
            }
        } else {
            timestamp_click = now_timestamp;
            if (!controlsShown)
                showcon();
            else
                hidecon.run();
        }
    }

    private boolean isDoubleTapCenter(MotionEvent event) {
        float viewWidth = layout_control.getWidth();
        float viewHeight = layout_control.getHeight();
        float x = event.getX();
        float y = event.getY();
        float centerWidth = viewWidth * 0.4f;
        float centerHeight = viewHeight * 0.4f;
        float centerLeft = (viewWidth - centerWidth) / 2.0f;
        float centerTop = (viewHeight - centerHeight) / 2.0f;
        return x >= centerLeft && x <= centerLeft + centerWidth
                && y >= centerTop && y <= centerTop + centerHeight;
    }

    //控制栏显隐统一走 150ms 透明度过渡（ViewPropertyAnimator 硬件合成）：
    //此前是硬切换 GONE/VISIBLE，观感生硬；取消挂起动画再切，快速连点不串
    private static final long CONTROL_FADE_MS = 150;

    //淡入淡出的"目标"状态：淡出动画期间容器可见性仍是 VISIBLE（endAction 未跑），
    //所有读 layout_top.getVisibility() 做决策的地方（点击切换/onPrepared/debug按钮）
    //必须读这个字段而不是真实可见性，否则会拿到中间态
    private boolean controlsShown = true;

    private void fadeControls(boolean show) {
        controlsShown = show;
        View[] controls = {right_control, layout_top, bottom_buttons, seekbar_progress};
        for (View control : controls) {
            if (control == null) continue;
            control.animate().cancel();
            if (show) {
                if (control.getVisibility() != View.VISIBLE) {
                    control.setAlpha(0f);
                    control.setVisibility(View.VISIBLE);
                }
                control.animate().alpha(1f).setDuration(CONTROL_FADE_MS);
            } else {
                if (control.getVisibility() == View.VISIBLE && control.getAlpha() > 0f) {
                    control.animate().alpha(0f).setDuration(CONTROL_FADE_MS)
                            .withEndAction(() -> control.setVisibility(View.GONE));
                } else {
                    control.setVisibility(View.GONE);
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private void showcon() {
        fadeControls(true);
        seekbar_progress.setEnabled(false);
        seekbar_progress.postDelayed(progressbarEnable, 200);
        if (isPrepared && (!isLiveMode) && (!isAudioOnlyMode)) {
            text_speed.setVisibility(View.VISIBLE);
            updateDebugButtonVisibility();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            batteryView.setPower(batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
        }
        if (screen_round) {
            text_progress.setGravity(Gravity.NO_GRAVITY);
            text_progress.setPadding(ToolsUtil.dp2px(24f), 0, 0, 0);
            if (onlineTimer != null)
                text_online.setVisibility(View.VISIBLE);
        }

        autohideReset();
    }

    private final Runnable progressbarEnable = () -> seekbar_progress.setEnabled(true);

    private final Runnable hidecon = () -> {
        fadeControls(false);
        if (isPrepared && (!isAudioOnlyMode)) {
            text_speed.setVisibility(View.GONE);
            btn_debug.setVisibility(View.GONE);
        }
        if (screen_round) {
            text_progress.setGravity(Gravity.CENTER);
            text_progress.setPadding(0, 0, 0, ToolsUtil.dp2px(8f));
            if (onlineTimer != null)
                text_online.setVisibility(View.GONE);
        }
        if (menu_opened)
            btn_menu.performClick();
    };

    // ===== 播放器生命周期专用线程 =====
    // native 的 release 内部要停掉解码/消息线程并 join，低端手表上可能秒级阻塞——
    // 此前 stop/release 全部在主线程同步调用，是"退出播放页/切换清晰度 ANR"的直接来源。
    // 改为捕获引用置空后投递到本线程执行；静态共享保证全进程只此一条线程（32 位进程的
    // 线程/栈地址空间红线，见 pthread OOM 事故）。单线程 FIFO 同时保证顺序：
    // 旧实例 release 完成（归还 ijkSurface）之后，才允许经 runAfterPlayerReleased 重建新实例。
    private static final ExecutorService PLAYER_OPS = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "player-ops");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    /**回收当前播放器：立即置空字段（后续主线程代码与各定时器读到的都是 null），
     * native release 在 player-ops 线程执行。release 内部含 stop 的清理动作，
     * 不必再单独调 stop（少一次 native join，阻塞面减半）。*/
    private void retirePlayer() {
        final IjkMediaPlayer old = ijkPlayer;
        if (old == null) return;
        ijkPlayer = null;
        PLAYER_OPS.execute(() -> {
            try {
                old.release();
            } catch (Throwable t) {
                Logu.e("player-ops", "release 异常: " + t);
            }
        });
    }

    /**在旧播放器 release 完成（FIFO）之后回到主线程执行 r。
     * ijkSurface 是单实例，两个 native 播放器不能同时挂同一 Surface，
     * 重建必须排在 release 之后；放回主线程是因为重建要触碰 View。*/
    private void runAfterPlayerReleased(Runnable r) {
        PLAYER_OPS.execute(() -> runOnUiThread(r));
    }

    private void setDisplay() {
        Logu.v("创建播放器");
        Logu.v("url", video_url);

        runOnUiThread(() -> loading_text0.setText("初始化播放"));

        if (isAudioOnlyMode) {
            ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "vn", 1); // 禁用视频
            ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_frame", 48); // 跳过所有视频帧
            ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 48);
        } else {
            ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec",
                    (SharedPreferencesUtil.getBoolean("player_codec", true) ? 1 : 0));
            ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 48);
        }

        ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles",
                (SharedPreferencesUtil.getBoolean("player_audio", false) ? 1 : 0));
        ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1);
        ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1);
        ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 4);
        ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1);
        ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 100);
        ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "soundtouch", 1);
        ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 1);

        ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags", "flush_packets");
        ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1);

        // 这个坑死我！请允许我为解决此问题而大大地兴奋一下ohhhhhhhhhhhhhhhhhhhhhhhhhhhh
        // ijkplayer是自带一个useragent的，要把默认的改掉才能用！
        if (isOnlineVideo) {
            ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1);
            ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", 15 * 1024 * 1024);
            ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user_agent", USER_AGENT_WEB);
            Logu.v("设置ua");
        }

        Logu.v("准备设置显示");
        //setDisplay 会被切P/切清晰度/错误重试多次触发：新建前必须取消旧实例，
        //否则每次都遗留一个 200ms 轮询 Timer 线程（cancelAllTimers 只能取消最后一次赋值的那个）
        if (surfaceTimer != null) surfaceTimer.cancel();
        if (SharedPreferencesUtil.getBoolean("player_display", Build.VERSION.SDK_INT < 26)) { // Texture
            Logu.v("使用texture模式");
            surfaceTimer = newTimerOrNull("surface检测");
            if (surfaceTimer == null) return;   //线程耗尽时不崩播放页，宁可本次无画面
            surfaceTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    try {
                        Logu.v("循环检测");
                        //播放器可能已被换P/换清晰度/退出路径释放，这里的空值与销毁判断不能省：
                        //TimerTask 内未捕获的异常会终止 Timer 甚至带崩进程
                        if (destroyed || ijkPlayer == null) {
                            this.cancel();
                            return;
                        }
                        if (mSurfaceTexture != null) {
                            this.cancel();
                            Surface surface = new Surface(mSurfaceTexture);
                            Surface oldSurface = ijkSurface;
                            ijkSurface = surface;          //先换新再放旧：播放器不会短暂持有已释放的 Surface
                            ijkPlayer.setSurface(surface);
                            if (oldSurface != null) oldSurface.release();
                            MPPrepare(video_url);
                            Logu.v("设置surfaceTexture成功！");
                        }
                    } catch (Throwable t) {
                        Logu.e("surface检测", t.toString());
                        this.cancel();
                    }
                }
            }, 0, 200);
        } else {
            Logu.v("使用surface模式");
            SurfaceHolder surfaceHolder = surfaceView.getHolder(); // Surface
            Logu.v("获取surfaceHolder成功！");
            surfaceTimer = newTimerOrNull("surface检测");
            if (surfaceTimer == null) return;   //同 Texture 分支：不因线程耗尽崩播放页
            surfaceTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    try {
                        Logu.v("循环检测");
                        //同 TextureView 分支：播放器可能已被释放，必须判空，否则异常会直接终止 Timer
                        if (destroyed || ijkPlayer == null) {
                            this.cancel();
                            return;
                        }
                        if (!surfaceHolder.isCreating()) {
                            this.cancel();
                            Logu.v("定时器结束！");
                            ijkPlayer.setDisplay(surfaceHolder);
                            Logu.v("设置surfaceHolder成功！");
                            surfaceHolder.addCallback(new SurfaceHolder.Callback() {
                                @Override
                                public void surfaceCreated(@NonNull SurfaceHolder surfaceHolder) {
                                    if (!destroyed) {
                                        Logu.v("surface", "重新设置Holder");
                                        ijkPlayer.setDisplay(surfaceHolder);
                                        if (isPrepared) {
                                            ijkPlayer.seekTo(seekbar_progress.getProgress());
                                        }
                                    }
                                }

                                @Override
                                public void surfaceChanged(@NonNull SurfaceHolder surfaceHolder, int i, int i1, int i2) {
                                }

                                @Override
                                public void surfaceDestroyed(@NonNull SurfaceHolder surfaceHolder) {
                                    Logu.v("surface", "Holder没了");
                                    if (isPrepared && !destroyed)
                                        ijkPlayer.setDisplay(null);
                                }
                            });
                            Logu.v("添加callback成功！");
                            MPPrepare(video_url);
                        }
                    } catch (Throwable t) {
                        Logu.e("surface检测", t.toString());
                        this.cancel();
                    }
                }
            }, 0, 200);
        }
    }

    /**B 站媒体域的 http:// 播放地址升级为 https；非 B 站域或已是 https 的原样返回。*/
    private static String upgradeMediaUrlToHttps(String url) {
        if (url == null) return null;
        okhttp3.HttpUrl parsed = okhttp3.HttpUrl.parse(url);
        if (parsed == null || parsed.isHttps()) return url;
        if (NetWorkUtil.isBilibiliHost(parsed.host()))
            return parsed.newBuilder().scheme("https").build().toString();
        return url;
    }

    private void MPPrepare(String nowurl) {
        ijkPlayer.setOnPreparedListener(this);
        //事件门：release 已异步化的旧实例仍可能滞后回调（onCompletion/onError/onInfo），
        //按"回调实例 != 当前字段"丢弃，防止旧事件触发换P/错误重试等作用到新实例
        final IjkMediaPlayer thisPlayer = ijkPlayer;
        playerError = false;   //任何新的载入（切P/切清晰度/重试）都清除错误态

        if (isLiveMode) {
            runOnUiThread(() -> loading_text0.setText("载入直播中"));
            danmuSocketConnect();
        } else
            runOnUiThread(() -> loading_text0.setText("载入视频中"));
        try {
            if (isOnlineVideo) {
                //ijkplayer 走 native 网络栈，不受 networkSecurityConfig 约束：http 播放地址会以明文拉流，
                //而请求头带着全量登录 Cookie，同网段嗅探即可截获 SESSDATA。B 站媒体 CDN（bilivideo/
                //bilivideo.cn/akamai 镜像）均支持 https，这里统一升级，老视频返回的 http:// 地址不再明文传输
                String playUrl = upgradeMediaUrlToHttps(nowurl);
                Map<String, String> headers = new HashMap<>();
                headers.put("Referer", "https://www.bilibili.com/");
                headers.put("Cookie", CookieGenerator.getCookieString(true));
                ijkPlayer.setDataSource(playUrl, headers);
            } else
                ijkPlayer.setDataSource(nowurl);
        } catch (IOException e) {
            e.printStackTrace();
        }

        ijkPlayer.setOnCompletionListener(iMediaPlayer -> {
            if (iMediaPlayer != thisPlayer) return;
            finishWatching = true;
            
            if (interactionData != null && interactionData.edges != null && 
                interactionData.edges.questions != null && !questionShown) {
                checkEndInteractionQuestions();
                if (questionShown) {
                    isPlaying = false;
                    if (hasDanmaku && mDanmakuView != null) {
                        mDanmakuView.pause();
                    }
                    btn_control.setImageResource(R.drawable.btn_player_play);
                    return;
                }
            }
            
            if (loop_enabled) {
                ijkPlayer.seekTo(0);
                seekDanmakuTo(0L);
                ijkPlayer.start();
            } else if (auto_next_enabled && hasMultiplePages() && currentPageIndex < pagenames.size() - 1) {
                switchToPage(currentPageIndex + 1);
            } else {
                isPlaying = false;
                //看完上报：官方口径 played_time=-1（已看完）。不报的话服务端只知道"看到最后一秒"，
                //官方客户端的"已看完/未看完"标记与续播入口都会不一致。
                //只对番剧(PGC)用这个约定值，普通投稿视频的 history/report 没有 -1 语义。
                if (epid != 0) {
                    lastReportedProgressSec = HistoryApi.PROGRESS_FINISHED;
                    lastReportedProgressMs = video_now;
                    sendProgressReport(HistoryApi.PROGRESS_FINISHED, "看完上报");
                }
                if (hasDanmaku && mDanmakuView != null) {
                    mDanmakuView.pause();
                }
                btn_control.setImageResource(R.drawable.btn_player_play);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mediaSession != null) {
                    updateMediaSessionPlaybackState();
                }
            }
        });

        ijkPlayer.setOnErrorListener((iMediaPlayer, what, extra) -> {
            if (iMediaPlayer != thisPlayer) return true;
            String EReport = "播放器可能遇到错误！\n错误码：" + what + "\n附加：" + extra;
            Logu.e("ijk-err", EReport);
            //原来返回 false，错误会继续走到 onCompletion 被当成“播放完毕”，用户会误以为视频播完了；
            //改为标记错误态并提示，点播放按钮重新载入重试（controlVideo 里分发）
            playerError = true;
            if (!destroyed) {
                MsgUtil.showMsgLong(EReport);
                runOnUiThread(() -> {
                    isPlaying = false;
                    if (hasDanmaku && mDanmakuView != null) {
                        mDanmakuView.pause();
                    }
                    btn_control.setImageResource(R.drawable.btn_player_play);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mediaSession != null) {
                        updateMediaSessionPlaybackState();
                    }
                });
            }
            return true;
        });

        ijkPlayer.setOnBufferingUpdateListener(
                (mp, percent) -> {
                    if (mp != thisPlayer) return;
                    seekbar_progress.setSecondaryProgress(percent * video_all / 100);
                });

        if (isOnlineVideo || isLiveMode)
            ijkPlayer.setOnInfoListener((mp, what, extra) -> {
                if (mp != thisPlayer) return false;
                if (what == IMediaPlayer.MEDIA_INFO_BUFFERING_START) {
                    runOnUiThread(() -> {
                        loading_info.setVisibility(View.VISIBLE);
                        anim_loading.start();
                        loading_text0.setText("正在缓冲");
                        showLoadingSpeed();
                        if (hasDanmaku && mDanmakuView != null && isPlaying) {
                            mDanmakuView.pause();
                        }
                    });
                } else if (what == IMediaPlayer.MEDIA_INFO_BUFFERING_END) {
                    runOnUiThread(() -> {
                        if (loadingTimer != null)
                            loadingTimer.cancel();
                        loading_info.setVisibility(View.GONE);
                        anim_loading.stop();
                        if (hasDanmaku && mDanmakuView != null && isPlaying) {
                            mDanmakuView.resume();
                        }
                    });
                }

                return false;
            });

        ijkPlayer.setScreenOnWhilePlaying(true);
        ijkPlayer.prepareAsync();
        Logu.v("开始准备");
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onPrepared(IMediaPlayer mediaPlayer) {
        //旧实例的滞后回调：它的 release 已在 player-ops 排队，直接忽略，防止旧事件作用到新实例
        if (mediaPlayer != ijkPlayer) return;
        if (destroyed) {
            retirePlayer();
            return;
        }

        isPrepared = true;
        video_all = (int) ijkPlayer.getDuration();

        changeVideoSize();

        //记住倍速：上个会话手动调过速的话，新实例（含换P/换清晰度重建）自动应用；
        //长按 3x 属临时加速不走这里，不会污染记忆值；直播流不应用倍速（倍速按钮在直播本来就隐藏）
        if (!isLiveMode && SharedPreferencesUtil.getBoolean("player_speed_remember", false)) {
            float savedSpeed = SharedPreferencesUtil.getFloat("player_speed_value", 1.0f);
            for (int i = 0; i < speed_values.length; i++) {
                if (speed_values[i] == savedSpeed && i != 2) {
                    ijkPlayer.setSpeed(speed_values[i]);
                    if (mDanmakuView != null) mDanmakuView.setSpeed(speed_values[i]);
                    text_speed.setText(speed_strs[i]);
                    seekbar_speed.setProgress(i);   //fromUser=false，监听器不会重复应用
                    break;
                }
            }
        }

        if ((isLiveMode || hasDanmaku) && mDanmakuView != null) {
            mDanmakuView.start();
        }
        if (SharedPreferencesUtil.getBoolean("player_ui_showDanmakuBtn", true)) {
            isDanmakuVisible = !SharedPreferencesUtil.getBoolean("pref_switch_danmaku", true);
            btn_danmaku.setOnClickListener(view -> {
                if (mDanmakuView == null)
                    return;
                if (isDanmakuVisible) {
                    mDanmakuView.hide();
                } else {
                    mDanmakuView.show();
                    //DFM 在隐藏期间时钟停走，重新显示时必须按当前播放位置重新对齐，否则弹幕会整体错位
                    if (isPrepared && ijkPlayer != null) {
                        seekDanmakuTo(video_now);   //同上：主线程读原生锁有卡死风险
                    }
                }
                btn_danmaku.setImageResource((isDanmakuVisible ? R.mipmap.danmakuoff : R.mipmap.danmakuon));
                isDanmakuVisible = !isDanmakuVisible;
                SharedPreferencesUtil.putBoolean("pref_switch_danmaku", isDanmakuVisible);
            });
            btn_danmaku.performClick();
            //按钮图标与真实状态对齐：mDanmakuView 尚未就绪时上面的点击回调会在第一行提前 return，
            //图标会停在 XML 默认的"弹幕关"上，与实际是否显示弹幕不一致
            btn_danmaku.setImageResource(isDanmakuVisible ? R.mipmap.danmakuon : R.mipmap.danmakuoff);

            btn_danmaku.setVisibility(View.VISIBLE);
        } else {
            //按钮隐藏时也要把状态位与用户开关对齐，否则弹幕卡死校正会因为"以为弹幕关着"而不生效
            isDanmakuVisible = !SharedPreferencesUtil.getBoolean("pref_switch_danmaku", true);
            btn_danmaku.setVisibility(View.GONE);
        }
        // 原作者居然把旋转按钮命名为danmaku_btn，也是没谁了...我改过来了 ----RobinNotBad
        // 他大抵是觉得能用就行

        if (!isLiveMode) {
            if (loop_enabled)
                btn_loop.setImageResource(R.mipmap.loopon);
            else
                btn_loop.setImageResource(R.mipmap.loopoff);
            btn_loop.setOnClickListener(view -> {
                btn_loop.setImageResource((loop_enabled ? R.mipmap.loopoff : R.mipmap.loopon));
                loop_enabled = !loop_enabled;
            });
            btn_loop.setVisibility(View.VISIBLE);

            // 听视频模式按钮
            // 如果是本地音频文件，隐藏听视频开关（因为已经是纯音频了）
            if (isLocalAudioFile) {
                btn_audio_only.setVisibility(View.GONE);
            } else {
                updateAudioOnlyButton();
                btn_audio_only.setOnClickListener(view -> toggleAudioOnlyMode());
                btn_audio_only.setVisibility(View.VISIBLE);
            }

            if (hasMultiplePages()) {
                btn_page_selector.setVisibility(View.VISIBLE);
                btn_page_selector.setOnClickListener(view -> showPageSelectorCard());
                btn_auto_next.setVisibility(View.VISIBLE);
                updateAutoNextButton();
                btn_auto_next.setOnClickListener(view -> toggleAutoNext());
            } else {
                btn_page_selector.setVisibility(View.GONE);
                btn_auto_next.setVisibility(View.GONE);
            }

            if (SharedPreferencesUtil.getBoolean("player_ui_showQualityBtn", true) && isOnlineVideo) {
                btn_quality.setVisibility(View.VISIBLE);
                btn_quality.setOnClickListener(view -> showQualitySelectorCard());
            } else {
                btn_quality.setVisibility(View.GONE);
            }

            if (!SharedPreferencesUtil.getBoolean("player_ui_showPageBtn", true))
                btn_page_selector.setVisibility(View.GONE);
        } else {
            // 直播模式下隐藏这些按钮
            btn_loop.setVisibility(View.GONE);
            btn_audio_only.setVisibility(View.GONE);
            btn_page_selector.setVisibility(View.GONE);
            btn_auto_next.setVisibility(View.GONE);
            btn_quality.setVisibility(View.GONE);
        }

        seekbar_progress.setMax(video_all);
        progress_str = StringUtil.toTime(video_all / 1000);

        if (isAudioOnlyMode) {
            updateAudioOnlyUI();
        }

        if (SharedPreferencesUtil.getBoolean("player_from_last", true) && !isLiveMode) {
            if (progress_history > 5) {
                ijkPlayer.seekTo(progress_history);
                seekDanmakuTo(progress_history);
                Logu.d("进度跳转", String.valueOf(progress_history));
                runOnUiThread(() -> MsgUtil.showMsg("已从上次的位置播放"));
            }
        }

        loading_info.setVisibility(View.GONE);
        anim_loading.stop();
        isPlaying = true;
        btn_control.setImageResource(R.drawable.btn_player_pause);

        text_speed.setVisibility(controlsShown ? View.VISIBLE : View.GONE);
        if (isLiveMode)
            text_speed.setVisibility(View.GONE);
        text_speed.setOnClickListener(view -> layout_speed.setVisibility(View.VISIBLE));
        layout_speed.setOnClickListener(view -> layout_speed.setVisibility(View.GONE));

        btn_debug.setOnClickListener(view -> showInteractionDebugDialog());
        updateDebugButtonVisibility();

        progressChange();
        onlineChange();

        ijkPlayer.start();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mediaSession != null) {
            updateMediaSessionMetadata();
            updateMediaSessionPlaybackState();
        }

        btn_control.setOnClickListener(view -> controlVideo());
        btn_subtitle.setOnClickListener(view -> CenterThreadPool.run(() -> downSubtitle(true)));
    }

    private void showLoadingSpeed() {
        //缓冲可以反复开始，先取消上一个定时器，避免攒出一堆读播放器的任务
        if (loadingTimer != null) {
            loadingTimer.cancel();
            loadingTimer = null;
        }
        loadingTimer = newTimerOrNull("缓冲速度");
        if (loadingTimer == null) return;
        loadingTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    if (destroyed || ijkPlayer == null) return;
                    String text = String.format(Locale.CHINA, "%.1f", ijkPlayer.getTcpSpeed() / 1024f) + "KB/s";
                    runOnUiThread(() -> loading_text1.setText(text));
                } catch (Throwable t) {
                    Logu.e("缓冲速度", t.toString());
                }
            }
        }, 0, 500);
    }

    private void changeVideoSize() {
        if (!isPrepared || ijkPlayer == null)
            return;
        int width = ijkPlayer.getVideoWidth();
        int height = ijkPlayer.getVideoHeight();
        Logu.v("screen", screen_width + "x" + screen_height);
        Logu.v("video", width + "x" + height);

        // 在听视频模式下，视频宽高可能为0，跳过尺寸调整
        if (width == 0 || height == 0) {
            Logu.v("视频尺寸", "视频宽高为0，跳过尺寸调整（可能处于听视频模式）");
            return;
        }

        if (SharedPreferencesUtil.getBoolean("player_ui_round", false)) {
            float video_mul = (float) height / (float) width;
            double sqrt = Math.sqrt(screen_width * screen_width / ((double) (height * height) / (width * width) + 1));
            video_height = (int) (sqrt * video_mul + 0.5);
            video_width = (int) (sqrt + 0.5);
        } else {
            int width_case1 = width * screen_height / height;
            int height_case2 = height * screen_width / width;

            if (width_case1 <= screen_width) {
                video_width = width_case1;
                video_height = screen_height;
            } else {
                video_width = screen_width;
                video_height = height_case2;
            }
        }

        runOnUiThread(() -> {
            layout_video.setLayoutParams(new RelativeLayout.LayoutParams(video_width, video_height));
            Logu.v("改变视频区域大小", video_width + "x" + video_height);
            video_origX = (screen_width - video_width) / 2f;
            video_origY = (screen_height - video_height) / 2f;

            layout_video.postDelayed(() -> {
                layout_video.setX(video_origX);
                layout_video.setY(video_origY);
                Logu.v("改变视频位置", ((screen_width - video_width) / 2) + "," + ((screen_height - video_height) / 2));
            }, 60); // 别问为什么，问就是必须这么写，要等上面的绘制完成
        });
    }

    private void progressChange() {
        //换P/换清晰度/切听视频模式都会再次触发 onPrepared，不先取消旧 Timer 就会同时跑起多个定时任务，
        //旧任务还持有已释放的播放器实例，一旦抛异常整条进度与上报链路就废了
        if (progressTimer != null) {
            progressTimer.cancel();
            progressTimer = null;
        }
        progressTimer = newTimerOrNull("进度定时器");
        if (progressTimer == null) return;   //线程耗尽的最后一道防线：目标线程本就不该崩（详见 newTimerOrNull 注释）
        TimerTask task = new TimerTask() {
            @SuppressLint("SetTextI18n")
            @Override
            public void run() {
                try {
                    if (destroyed || ijkPlayer == null || !isPrepared || !isPlaying || isSeeking) return;
                    video_now = (int) ijkPlayer.getCurrentPosition();
                    if (video_now_last != video_now) { // 检测进度是否在变动
                        video_now_last = video_now;
                        maybeReportProgress();
                        diagReadbackIfNeeded();
                        syncDanmakuIfDrifted(video_now);
                        float curr_sec = video_now / 1000f;
                        runOnUiThread(() -> {
                            if (isLiveMode) {
                                text_progress.setText(StringUtil.toTime((int) curr_sec));
                                text_online.setText(online_number);
                            } else {
                                seekbar_progress.setProgress(video_now);
                                // progressBar上有一个onProgressChange的监听器，文字更改在那里
                            }
                        });
                        if (subtitles != null)
                            showSubtitle(curr_sec + subtitle_delta);
                        else
                            runOnUiThread(() -> text_subtitle.setVisibility(View.GONE));
                        
                        if (viewPointAdapter != null && viewPoints != null && !viewPoints.isEmpty()) {
                            viewPointAdapter.updateCurrentPosition((int) curr_sec);
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mediaSession != null) {
                            updateMediaSessionPlaybackState();
                        }
                    }
                } catch (Throwable t) {
                    //TimerTask 抛出未捕获异常会直接终止整个 Timer（此后进度条与上报静默失效），必须兜住
                    Logu.e("进度定时器", t.toString());
                }
            }
        };
        progressTimer.schedule(task, 0, 250);
    }

    /**
     * 播放中周期性上报观看进度：进程被杀/异常退出时最多丢 5 秒进度（v6 起由 15s 收紧，对齐 PiliPlus）。
     * 复用 progressTimer 的 tick 做节流判断（按视频推进位置而非墙钟），网络 IO 抛到公共线程池，不拖慢 UI 更新。
     * 番剧(epid!=0)走心跳接口，普通视频走 history/report，与 JumpToPlayerActivity 的退出上报口径一致。
     */
    private void maybeReportProgress() {
        if (!isOnlineVideo || isLiveMode) return;
        if (!canReportProgress()) return;
        if (Math.abs((long) video_now - lastReportedProgressMs) < PROGRESS_REPORT_INTERVAL_MS) return;
        lastReportedProgressMs = video_now;
        long progressSec = video_now / 1000;
        lastReportedProgressSec = progressSec;
        sendProgressReport(progressSec, "周期上报");
    }

    /**
     * 退出/切后台时的兜底上报。
     * 这几条路径过去完全没有上报：只要不是"返回键 → 跳转页回调"，进度就永远写不进服务端，
     * 表现就是"看完了但观看记录与续播进度没更新"。
     */
    private void reportProgressNow(boolean force) {
        if (!isOnlineVideo || isLiveMode) return;
        if (!canReportProgress()) return;
        //看完之后再退出/切后台：必须继续报 -1（已看完），否则会把"已看完"覆盖成"看到最后一秒"，
        //官方客户端的看完标记就丢了。去重避免退出链路三连发（onPause/onStop/onDestroy）
        if (finishWatching && epid != 0) {
            if (lastReportedProgressSec != HistoryApi.PROGRESS_FINISHED) {
                lastReportedProgressSec = HistoryApi.PROGRESS_FINISHED;
                sendProgressReport(HistoryApi.PROGRESS_FINISHED, "看完·退出上报");
            }
            return;
        }
        //位置只取 progressTimer 在后台线程维护的 video_now，绝不能在主线程调 ijkPlayer.getCurrentPosition()：
        //那是会取播放器原生锁的 JNI 调用，seek/重新缓冲期间可能长时间不返回，
        //而本方法跑在 onPause/onStop/onDestroy 上——一旦卡住就是"退出播放后整个应用卡死"（ANR）。
        //250ms 的刷新粒度对进度上报完全够用。
        long positionMs = video_now;
        long progressSec = positionMs / 1000;
        if (progressSec <= 0) return;
        //非强制路径（onPause/onStop）只在进度确实前进时才写，避免一次退出重复写同一个位置
        if (!force && progressSec <= lastReportedProgressSec) return;
        lastReportedProgressSec = progressSec;
        lastReportedProgressMs = positionMs;
        sendProgressReport(progressSec, force ? "退出上报" : "切后台上报");
    }

    /**
     * 端到端自检（诊断用，一次播放只做一次，实验室里可用"进度上报回读自检"关闭）。
     *
     * 番剧进度上报走心跳接口，而该接口对"未登录/参数不对"的请求同样返回 code:0——
     * 只看返回码无法判断是否真的写进了服务端。这里在播放满 25 秒后回读一次观看记录、
     * 季级状态、v2 集级进度与 wbi 接口，把结果写进诊断文件：只要回读能读回刚上报的位置，
     * 就说明上报真的落库了；读不回来就是"静默失败"，据此才能定位。
     *
     * 判定必须把 v2 集级进度算进去（审计 P3-1）：观看记录 cursor 的 pgc 条目是"季级那一条"，
     * 本季最近看的是别的集时它读不回本集的位置，那是正常现象而不是上报失败——
     * v2 的 current_watch_progress 才是本集自己的位置。
     */
    private void diagReadbackIfNeeded() {
        if (diagReadbackDone || epid == 0) return;
        if (!SharedPreferencesUtil.getBoolean("diag_readback", true)) return;
        if (video_now < 25000) return;
        diagReadbackDone = true;
        final long fAid = aid, fCid = cid, fEpid = epid, fSeasonId = seasonId;
        final long playedSec = video_now / 1000;
        CenterThreadPool.run(() -> {
            try {
                //触发点在播放 25 秒，而周期上报第一次发生在播放 15 秒，
                //中间隔了 10 秒，足够服务端落库，不需要再 sleep 占着线程池
                //观看记录只扫一页：刚上报的记录一定在第一页（审计 P3-6，把自检成本从最多 8 个请求压到 4 个）
                long historyMs = HistoryApi.findEpisodeProgressMs(fCid, fAid, fEpid, 1);
                BangumiApi.SeasonProgress sp = fSeasonId != 0
                        ? BangumiApi.getSeasonProgress(fSeasonId) : new BangumiApi.SeasonProgress();
                long wbiMs = PlayerApi.getLastPlayProgress(fAid, fCid, true);
                //v2 取流接口的 watch_progress 是本集自己的位置：它能否读回刚播到的位置，
                //直接决定下一次打开这一集能不能续播（current_watch_progress 就是续播数据源）
                PlayerApi.WatchProgress wp = fEpid != 0
                        ? PlayerApi.queryPgcWatchProgress(fAid, fCid, fEpid, fSeasonId) : null;
                boolean v2Landed = wp != null && wp.current_watch_progress > 0;
                ProgressDiag.log("回读自检", "epid=" + fEpid + " 本次播放位置=" + playedSec + "s"
                        + " → 观看记录=" + historyMs + "ms"
                        + " / 季级(last_ep_id=" + sp.lastEpid + ", " + sp.lastProgressMs + "ms)"
                        + " / v2进度=" + PlayerApi.describeWatchProgress(wp)
                        + " / wbi=" + wbiMs + "ms"
                        + (historyMs > 0 || wbiMs > 0 || v2Landed
                        ? "  [上报已落库]" : "  [上报疑似未落库]"));
            } catch (Exception e) {
                ProgressDiag.log("回读自检", "失败: " + e);
            }
        });
    }

    /**
     * 上报前置条件检查：不满足时留下可见日志（Logu.e/w 不受调试开关控制），
     * 避免"静默没上报"这种在设备上完全无从排查的故障。
     */
    private boolean canReportProgress() {
        if (aid == 0 || cid == 0) {
            Logu.e("进度上报", "跳过：aid/cid 缺失 aid=" + aid + " cid=" + cid);
            return false;
        }
        //登录态一律以实时 Cookie 为准（本地快照 mid 会因切号/刷新 Cookie 而错位）
        mid = NetWorkUtil.getLoginMid();
        if (mid == 0) {
            if (!notLoggedInWarned) {
                notLoggedInWarned = true;
                Logu.e("进度上报", "跳过：未登录（mid=0），观看记录与续播进度无法写入");
            }
            return false;
        }
        return true;
    }

    private void sendProgressReport(long progressSec, String reason) {
        final long fAid = aid, fCid = cid, fEpid = epid, fSeasonId = seasonId;
        final int fSeasonType = seasonType;
        final String fBvid = bvid;
        Logu.w("进度上报", reason + " aid=" + fAid + " cid=" + fCid + " epid=" + fEpid
                + " sid=" + fSeasonId + " subType=" + fSeasonType + " progress=" + progressSec + "s");
        ProgressDiag.log("播放器上报", reason + " aid=" + fAid + " cid=" + fCid + " bvid=" + fBvid
                + " epid=" + fEpid + " sid=" + fSeasonId + " subType=" + fSeasonType
                + " progress=" + progressSec + "s mid=" + mid);
        CenterThreadPool.run(() -> {
            try {
                if (fEpid != 0)
                    HistoryApi.reportHistoryPgc(fBvid, fAid, fCid, fEpid, fSeasonId, fSeasonType, progressSec);
                else
                    HistoryApi.reportHistory(fAid, fCid, progressSec);
            } catch (Exception e) {
                MsgUtil.err("进度上报：", e);
            }
        });
    }

    private void onlineChange() {
        if (!SharedPreferencesUtil.getBoolean("player_show_online", false) || isLiveMode || aid == 0 || cid == 0)
            return;

        if (onlineTimer != null) {
            onlineTimer.cancel();   //onPrepared 在切P/切清晰度/重试时会反复触发，不取消旧实例会叠出多个轮询线程
            onlineTimer = null;
        }
        onlineTimer = newTimerOrNull("在线人数");
        if (onlineTimer == null) return;
        TimerTask task = new TimerTask() {
            @SuppressLint("SetTextI18n")
            @Override
            public void run() {
                if (ijkPlayer != null) {
                    try {
                        online_number = VideoInfoApi.getWatching(aid, cid);
                        runOnUiThread(() -> {
                            if (!online_number.isEmpty())
                                text_online.setText(online_number + "人在看");
                            else
                                text_online.setText("");
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> {
                            MsgUtil.err(e);
                            text_online.setVisibility(View.GONE);
                        });
                        this.cancel();
                    }
                }
            }
        };
        onlineTimer.schedule(task, 0, 5000);
    }

    private void getSubtitle(String subtitle_url) {
        if (subtitle_url == null || subtitle_url.isEmpty())
            return;
        try {
            //先解析到局部变量、下标准备好后最后整体替换引用：
            //showSubtitle 在 progressTimer 线程读 subtitles/subtitle_curr_index，
            //直接三步赋值会让它读到"新数组+旧下标"的中间态
            Subtitle[] loaded = isOnlineVideo
                    ? PlayerApi.getSubtitle(subtitle_url)
                    : PlayerApi.getSubtitle(new File(subtitle_url));

            if (loaded == null)
                return;

            subtitle_count = loaded.length;
            subtitle_curr_index = 0;
            subtitles = loaded;
            runOnUiThread(() -> btn_subtitle.setImageResource(R.mipmap.subtitle_on));
        } catch (Exception e) {
            MsgUtil.err(e);
        }
    }

    private void showSubtitle(float curr_sec) {
        //本地快照：subtitles 会在 UI 线程被切P/互动跳转整体置 null 或换新数组，
        //showSubtitle 由 progressTimer 线程调用，三步赋值读到中间态就是主线程 NPE/数组越界
        final Subtitle[] snapshot = subtitles;
        if (snapshot == null || snapshot.length == 0) {
            runOnUiThread(() -> text_subtitle.setVisibility(View.GONE));
            return;
        }
        if (subtitle_curr_index < 0 || subtitle_curr_index >= snapshot.length)
            subtitle_curr_index = 0;

        Subtitle subtitle_curr = snapshot[subtitle_curr_index];

        boolean need_adjust = true;
        boolean need_show = true;

        while (need_adjust) {
            if (curr_sec < subtitle_curr.from) { // 进度在当前字幕的起始位置之前
                // 如果不是第一条字幕，且进度在上一条字幕的结束位置之前，那么字幕前移一位
                // 否则字幕不显示且退出校准（当前进度在两条字幕之间）
                if (subtitle_curr_index != 0 && curr_sec < snapshot[subtitle_curr_index - 1].to) {
                    subtitle_curr_index--;
                } else {
                    need_adjust = false;
                    need_show = false;
                }
            } else if (curr_sec > subtitle_curr.to) { // 在当前字幕的结束位置之后
                // 如果不是最后一条字幕，且进度在下一条字幕的开始位置之后，那么字幕后移一位
                // 否则字幕不显示且退出校准（当前进度在两条字幕之间）
                if (subtitle_curr_index + 1 < snapshot.length && curr_sec > snapshot[subtitle_curr_index + 1].from) {
                    subtitle_curr_index++;
                } else {
                    need_adjust = false;
                    need_show = false;
                }
            } else
                need_adjust = false; // 在当前字幕的时间段内，则退出校准
        }

        if (need_show) {
            //UI Runnable 里再次取快照并校验下标：期间字幕数组可能已被整体替换
            final int showIndex = subtitle_curr_index;
            runOnUiThread(() -> {
                Subtitle[] current = subtitles;
                if (current != null && showIndex < current.length) {
                    text_subtitle.setText(current[showIndex].content);
                    text_subtitle.setVisibility(View.VISIBLE);
                } else text_subtitle.setVisibility(View.GONE);
            });
        } else
            runOnUiThread(() -> text_subtitle.setVisibility(View.GONE));
    }

    private int subtitle_selected = -1;

    private void downSubtitle(boolean from_btn) {
        try {
            if (subtitleLinks == null) { // 首次运行，获取字幕
                if (isOnlineVideo)
                    subtitleLinks = PlayerApi.getSubtitleLinks(aid, cid);
                else
                    subtitleLinks = PlayerApi.getSubtitleLinks(new File(danmakuFile.getParentFile(), "subtitles"));
            }

            if (subtitleLinks.length == 1) {
                if (from_btn)
                    MsgUtil.showMsg("本视频无字幕");
                return;
            }

            subtitle_delta = SharedPreferencesUtil.getFloat("player_subtitle_delta", 0.3f);

            boolean ai_not_only = (subtitleLinks.length > 2 || (subtitleLinks.length == 2 && !subtitleLinks[0].isAI));
            boolean ai_allowed = (from_btn || SharedPreferencesUtil.getBoolean("player_subtitle_ai_allowed", false));

            if (ai_not_only || ai_allowed) {
                if (subtitle_selected == -1)
                    subtitle_selected = subtitleLinks.length;

                runOnUiThread(() -> {
                    RecyclerView subtitleRecycler = findViewById(R.id.subtitle_list);
                    SubtitleAdapter adapter = new SubtitleAdapter();
                    adapter.setData(subtitleLinks);
                    adapter.setSelectedItemIndex(subtitle_selected);
                    adapter.setOnItemClickListener(index -> {
                        layout_card_bg.setVisibility(View.GONE);
                        card_subtitle.setVisibility(View.GONE);
                        subtitle_selected = index;

                        if (subtitleLinks[index].id == -1) {
                            subtitles = null;
                            btn_subtitle.setImageResource(R.mipmap.subtitle_off);
                        } else
                            CenterThreadPool.run(() -> getSubtitle(subtitleLinks[index].url));
                    });
                    subtitleRecycler
                            .setLayoutManager(new CustomLinearManager(this, LinearLayoutManager.HORIZONTAL, false));
                    subtitleRecycler.setHasFixedSize(true);
                    subtitleRecycler.setAdapter(adapter);
                    layout_card_bg.setVisibility(View.VISIBLE);
                    card_subtitle.setVisibility(View.VISIBLE);
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
            MsgUtil.err(e);
        }
    }

    private void downdanmu() {
        if (danmaku_url.isEmpty())
            return;

        boolean useNewApi = SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.NEW_DANMAKU_API, true);

        if (useNewApi) {
            downdanmuNew();
        } else {
            downdanmuOld();
        }
    }

    private void downdanmuOld() {
        try {
            Response response = NetWorkUtil.get(danmaku_url, NetWorkUtil.webHeaders);
            BufferedSink bufferedSink = null;
            try {
                if (!danmakuFile.exists())
                    danmakuFile.createNewFile();
                Sink sink = Okio.sink(danmakuFile);
                byte[] decompressBytes = decompress(Objects.requireNonNull(response.body()).bytes());// 调用解压函数进行解压，返回包含解压后数据的byte数组
                bufferedSink = Okio.buffer(sink);
                bufferedSink.write(decompressBytes);// 将解压后数据写入文件（sink）中
                bufferedSink.close();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (bufferedSink != null) {
                    bufferedSink.close();
                }
            }
            streamDanmaku(danmakuFile.toString(), null);
        } catch (Exception e) {
            runOnUiThread(() -> MsgUtil.err(e));
        }
    }

    //弹幕磁盘缓存时效：30 分钟内重进/换P不再重新下载（1小时视频逐段串行要 11 个请求）
    private static final long DANMAKU_CACHE_TTL_MS = 30 * 60 * 1000L;

    private void downdanmuNew() {
        try {
            int estimatedDuration = 3600;

            if (ijkPlayer != null) {
                long duration = ijkPlayer.getDuration();
                if (duration > 0) {
                    estimatedDuration = (int) (duration / 1000);
                }
            }

            //磁盘缓存命中：直接读本地，省掉逐段网络请求
            File cacheFile = getDanmakuCacheFile();
            if (cacheFile != null) {
                List<DmSegMobileReply> cached = readDanmakuCache(cacheFile);
                if (cached != null && !cached.isEmpty()) {
                    Logu.d("弹幕缓存", "命中磁盘缓存：" + cached.size() + " 段");
                    streamDanmaku(null, cached);
                    return;
                }
            }

            Logu.d("新版弹幕", "开始获取新版弹幕，aid=" + aid + ", cid=" + cid);

            DanmakuApi.DanmakuSegments downloaded = DanmakuApi.getAllVideoDanmakuWithRaw(aid, cid, estimatedDuration);

            if (downloaded.parsed.isEmpty()) {
                Logu.w("新版弹幕", "未获取到弹幕，尝试使用旧版接口");
                CenterThreadPool.run(() -> downdanmuOld());
                return;
            }

            if (cacheFile != null) writeDanmakuCache(cacheFile, downloaded.rawSegments);

            Logu.d("新版弹幕", "成功获取 " + downloaded.parsed.size() + " 个弹幕分段");

            streamDanmaku(null, downloaded.parsed);
        } catch (Exception e) {
            e.printStackTrace();
            Logu.e("新版弹幕", "获取失败: " + e.getMessage() + "，回退到旧版接口");
            runOnUiThread(() -> MsgUtil.toast("新版弹幕获取失败，使用旧版接口"));
            CenterThreadPool.run(() -> downdanmuOld());
        }
    }

    /**
     * 当前视频的弹幕缓存文件（含 cid 隔离）。取用时顺带清理其他视频的过期缓存。
     */
    private File getDanmakuCacheFile() {
        if (!isOnlineVideo || aid <= 0 || cid <= 0) return null;
        File dir = getCacheDir();
        cleanExpiredDanmakuCache(dir);
        return new File(dir, "danmaku_pb_" + cid);
    }

    /**
     * 清理已超过 TTL 的弹幕缓存（含当前 cid 的过期文件，避免缓存无限堆积；
     * 内部缓存目录系统空间不足时本身也会回收，这里只做主动的过期清理）。
     */
    private void cleanExpiredDanmakuCache(File dir) {
        File[] files = dir.listFiles((d, name) -> name.startsWith("danmaku_pb_"));
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File f : files) {
            if (now - f.lastModified() > DANMAKU_CACHE_TTL_MS && f.delete())
                Logu.d("弹幕缓存", "清理过期缓存 " + f.getName());
        }
    }

    /**
     * 读缓存：格式为连续的 [4字节大端长度][protobuf bytes] 块。
     * 任何异常都按未命中处理，走重新下载路径，不影响弹幕装载。
     */
    private List<DmSegMobileReply> readDanmakuCache(File cacheFile) {
        if (!cacheFile.exists()) return null;
        if (System.currentTimeMillis() - cacheFile.lastModified() > DANMAKU_CACHE_TTL_MS) return null;
        DataInputStream in = null;
        try {
            in = new DataInputStream(new FileInputStream(cacheFile));
            List<DmSegMobileReply> segments = new ArrayList<>();
            while (true) {
                int len;
                try {
                    len = in.readInt();
                } catch (EOFException eof) {
                    break;
                }
                if (len <= 0 || len > 50 * 1024 * 1024) throw new IOException("损坏的分段长度 " + len);
                byte[] raw = new byte[len];
                in.readFully(raw);
                segments.add(ProtobufParser.parseDmSegMobileReply(raw));
            }
            return segments;
        } catch (Exception e) {
            Logu.e("弹幕缓存", "读取失败，将重新下载: " + e.getMessage());
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 写缓存：先写临时文件再改名，保证不会留下被半截写入污染的缓存文件。
     */
    private void writeDanmakuCache(File cacheFile, List<byte[]> rawSegments) {
        if (rawSegments == null || rawSegments.isEmpty()) return;
        File tmp = new File(cacheFile.getParent(), cacheFile.getName() + ".tmp");
        DataOutputStream out = null;
        try {
            out = new DataOutputStream(new FileOutputStream(tmp));
            for (byte[] raw : rawSegments) {
                out.writeInt(raw.length);
                out.write(raw);
            }
            out.close();
            out = null;
            if (cacheFile.exists()) cacheFile.delete();
            if (!tmp.renameTo(cacheFile)) Logu.w("弹幕缓存", "缓存改名失败 " + tmp.getName());
        } catch (Exception e) {
            Logu.e("弹幕缓存", "写入失败: " + e.getMessage());
            tmp.delete();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private BaseDanmakuParser createParser(String stream) {
        return createParser(stream, null);
    }

    private BaseDanmakuParser createParser(String stream, java.util.List<DmSegMobileReply> protobufSegments) {
        if (protobufSegments != null && !protobufSegments.isEmpty()) {
            BiliProtobufDanmakuParser parser = new BiliProtobufDanmakuParser();
            parser.sharedPreferences = SharedPreferencesUtil.getSharedPreferences();
            parser.setDanmakuSegments(protobufSegments);
            return parser;
        }

        // 兼容性回退
        if (stream == null) {
            return new BaseDanmakuParser() {
                @Override
                protected Danmakus parse() {
                    return new Danmakus();
                }
            };
        }

        ILoader loader = DanmakuLoaderFactory.create(DanmakuLoaderFactory.TAG_BILI);

        assert loader != null;
        loader.load(stream);
        BaseDanmakuParser parser = new BiliDanmukuParser();
        parser.sharedPreferences = SharedPreferencesUtil.getSharedPreferences();
        IDataSource<?> dataSource = loader.getDataSource();
        parser.load(dataSource);
        return parser;
    }

    private void streamDanmaku(String danmakuFile) {
        streamDanmaku(danmakuFile, null);
    }

    private void streamDanmaku(String danmakuFile, java.util.List<DmSegMobileReply> protobufSegments) {
        Logu.v("danmaku", "stream");

        mContext = DanmakuContext.create();
        HashMap<Integer, Integer> maxLinesPair = new HashMap<>();
        maxLinesPair.put(BaseDanmaku.TYPE_SCROLL_RL, SharedPreferencesUtil.getInt("player_danmaku_maxline", 15));
        HashMap<Integer, Boolean> overlap = new HashMap<>();
        //allowoverlap 的 map 值传给 DFM 是"防重叠"语义（true=会撞的弹幕被拦）；
        //此前只有 LR/BOTTOM 在 map 里，主滚动弹幕（RL）完全不受该设置控制，这里补上
        overlap.put(BaseDanmaku.TYPE_SCROLL_RL, SharedPreferencesUtil.getBoolean("player_danmaku_allowoverlap", true));
        overlap.put(BaseDanmaku.TYPE_SCROLL_LR, SharedPreferencesUtil.getBoolean("player_danmaku_allowoverlap", true));
        overlap.put(BaseDanmaku.TYPE_FIX_BOTTOM, SharedPreferencesUtil.getBoolean("player_danmaku_allowoverlap", true));
        mContext.setDanmakuStyle(IDisplayer.DANMAKU_STYLE_STROKEN, 1)
                .setDuplicateMergingEnabled(SharedPreferencesUtil.getBoolean("player_danmaku_mergeduplicate", false))
                .setScrollSpeedFactor(SharedPreferencesUtil.getFloat("player_danmaku_speed", 1.0f))
                .setScaleTextSize(SharedPreferencesUtil.getFloat("player_danmaku_size", 0.7f))// 缩放值
                .setMaximumLines(maxLinesPair)
                .setDanmakuTransparency(SharedPreferencesUtil.getFloat("player_danmaku_transparency", 0.5f))
                .preventOverlapping(overlap);

        BaseDanmakuParser mParser = createParser(danmakuFile, protobufSegments);

        mDanmakuView.setCallback(new DrawHandler.Callback() {
            @Override
            public void prepared() {
                Logu.v("danmaku", "prepared");
                //弹幕下载通常慢于取流，onPrepared 里的 seek 会被 DanmakuView 直接丢弃（handler 未 prepared），
                //断点续播时弹幕就会从 0 开始播——这里补做一次，这是"跳转后弹幕对不上/像卡住"的另一半原因
                if (pendingDanmakuSeekMs >= 0) {
                    Logu.d("弹幕跳转", "prepared 后补做 seek=" + pendingDanmakuSeekMs);
                    mDanmakuView.seekTo(pendingDanmakuSeekMs);
                    pendingDanmakuSeekMs = -1;
                }
                String msg = protobufSegments != null
                        ? "弹幕君准备完毕～(是新来的哦～)"
                        : "弹幕君准备完毕～(*≧ω≦)";
                addDanmaku(msg, Color.WHITE);
            }

            @Override
            public void updateTimer(DanmakuTimer timer) {
                //这里绝对不要读 ijkPlayer.getCurrentPosition()：回调跑在 DFM 的同步/绘制线程上，
                //而 getCurrentPosition 是会拿播放器原生锁的 JNI 调用，一旦与 seek、release 并发，
                //轻则弹幕时间轴被旧位置拽住不动（跳转后弹幕卡死），重则 DFM 线程彻底卡住，
                //让主线程 release() 里的 join 永远等下去（退出播放后整个应用卡死）。
                //弹幕时钟交给 DFM 自己走，位置对齐由 seekDanmakuTo / syncDanmakuIfDrifted 负责。
            }

            @Override
            public void danmakuShown(BaseDanmaku danmaku) {
            }

            @Override
            public void drawingFinished() {
            }
        });
        mDanmakuView.enableDanmakuDrawingCache(true);
        mDanmakuView.prepare(mParser, mContext);
    }

    public void addDanmaku(String text, int color) {
        addDanmaku(text, color, 25, 1, 0);
    }

    public void addDanmaku(String text, int color, int textSize, int type, int backgroundColor) {
        BaseDanmaku danmaku = mContext.mDanmakuFactory.createDanmaku(type);
        if (text == null || danmaku == null || ijkPlayer == null)
            return;
        danmaku.text = text;
        danmaku.padding = 5;
        danmaku.priority = 1;
        danmaku.textColor = color;
        danmaku.backgroundColor = backgroundColor;
        danmaku.textSize = textSize * (mContext.getDisplayer().getDensity() - 0.6f);
        danmaku.time = mDanmakuView.getCurrentTime() + 100;
        mDanmakuView.addDanmaku(danmaku);
    }

    public static byte[] decompress(byte[] data) {
        byte[] output;
        Inflater decompresser = new Inflater(true);// 这个true是关键
        decompresser.reset();
        decompresser.setInput(data);
        ByteArrayOutputStream o = new ByteArrayOutputStream(data.length);
        try {
            byte[] buf = new byte[2048];
            while (!decompresser.finished()) {
                //截断/损坏的 zlib 流会让 inflate() 恒返回 0 且 finished() 恒为 false：
                //不设这两个退出条件，循环既不退出也不抛异常 → 100% CPU 空转 + OOM
                //（与 NetWorkUtil.uncompress / DownloadService 的解压防护同一套守卫）
                if (decompresser.needsInput() || decompresser.needsDictionary()) break;
                int i = decompresser.inflate(buf);
                if (i == 0) break;
                o.write(buf, 0, i);
            }
            output = o.toByteArray();
        } catch (Exception e) {
            output = data;
            e.printStackTrace();
        } finally {
            try {
                o.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        decompresser.end();
        return output;
    }

    public void controlVideo() {
        //错误态下点播放＝重试。error 状态的实例上 resume 不会有任何反应，必须重新走载入链路
        if (playerError) {
            retryAfterPlayerError();
            autohideReset();
            return;
        }
        if (isPlaying) {
            playerPause();
        } else {
            if (video_now >= video_all - 250) {
                if (interactionData != null && interactionData.edges != null &&
                    interactionData.edges.questions != null && !questionShown) {
                    if (!questionShown) {
                        ijkPlayer.seekTo(0);
                        seekDanmakuTo(0L);
                        Logu.v("播完重播");
                    }
                } else {
                    ijkPlayer.seekTo(0);
                    seekDanmakuTo(0L);
                    Logu.v("播完重播");
                }
            }
            playerResume();
        }
        autohideReset();
    }

    //错误重试复用音频模式切换的同一条重载链路：释放播放器 → 重建 → setDisplay() 由 surface 定时器触发 MPPrepare
    private void retryAfterPlayerError() {
        playerError = false;
        CenterThreadPool.run(() -> {
            try {
                final long resumePosition = video_now;
                runOnUiThread(() -> {
                    isPrepared = false;
                    isPlaying = false;
                    retirePlayer();
                    loading_info.setVisibility(View.VISIBLE);
                    anim_loading.start();
                    loading_text0.setText("重新载入");
                });
                //旧实例 release 完成后再重建（ijkSurface 单实例，见 runAfterPlayerReleased 注释）
                runAfterPlayerReleased(() -> {
                    //页面可能已退出：对已销毁的窗口 setDisplay 会创建无人认领的播放器实例（native 泄漏）
                    if (destroyed || isFinishing()) return;
                    ijkPlayer = new IjkMediaPlayer();
                    progress_history = resumePosition;
                    setDisplay();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    MsgUtil.showMsg("重新载入失败，请重试");
                    loading_info.setVisibility(View.GONE);
                    anim_loading.stop();
                });
            }
        });
    }

    @SuppressLint("SetTextI18n")
    public void changeVolume(Boolean add_or_cut) {
        int volumeNow = audioManager.getStreamVolume(STREAM_MUSIC);
        int volumeMax = audioManager.getStreamMaxVolume(STREAM_MUSIC);
        int volumeNew = volumeNow + (add_or_cut ? 1 : -1);
        if (volumeNew >= 0 && volumeNew <= volumeMax) {
            audioManager.setStreamVolume(STREAM_MUSIC, volumeNew, 0);
            volumeNow = volumeNew;
        }
        int show = (int) ((float) volumeNow / (float) volumeMax * 100);

        text_volume.setVisibility(View.VISIBLE);
        text_volume.setText("音量：" + show + "%");

        text_volume.removeCallbacks(hideVolume);
        text_volume.postDelayed(hideVolume, 3000);
        autohideReset();
    }

    private final Runnable hideVolume = () -> text_volume.setVisibility(View.GONE);

    /**
     * 软件旋屏，给某些特殊设备用的。
     * 终端屎山又增高啦
     */
    private void softwareRotate() {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        screen_width = screen_landscape ? displayMetrics.heightPixels : displayMetrics.widthPixels;
        screen_height = screen_landscape ? displayMetrics.widthPixels : displayMetrics.heightPixels;

        ViewGroup root_layout = findViewById(R.id.root_layout);
        ViewGroup.LayoutParams params = root_layout.getLayoutParams();
        params.width = screen_width;
        params.height = screen_height;

        if (isPrepared && !destroyed)
            runOnUiThread(() -> {
                root_layout.setLayoutParams(params);
                root_layout.setPivotX(0);
                root_layout.setPivotY(0);
                root_layout.setX(screen_landscape ? screen_height : 0);
                root_layout.setRotation(screen_landscape ? 90 : 0);
                if (SharedPreferencesUtil.getBoolean("player_display", Build.VERSION.SDK_INT < 26)) {
                    if (textureView != null) {
                        Matrix matrix = new Matrix();
                        textureView.getTransform(matrix);
                        matrix.postRotate(0);
                        textureView.setTransform(matrix);
                    }
                } else {
                    MsgUtil.showMsg("请切换为TextureView才能支持软件旋屏！");
                }
            });
        changeVideoSize();
    }

    private void videoMoveBy(float dx, float dy) {
        float x = dx + layout_video.getX();
        float y = dy + layout_video.getY();

        float width_delta = 0.5f * video_width * (layout_video.getScaleX() - 1f);
        float height_delta = 0.5f * video_height * (layout_video.getScaleY() - 1f);
        float video_x_min = video_origX - width_delta;
        float video_x_max = video_origX + width_delta;
        float video_y_min = video_origY - height_delta;
        float video_y_max = video_origY + height_delta;

        if (x < video_x_min)
            x = video_x_min;
        if (x > video_x_max)
            x = video_x_max;
        if (y < video_y_min)
            y = video_y_min;
        if (y > video_y_max)
            y = video_y_max;

        if (layout_video.getX() != x || layout_video.getY() != y) {
            // Logu.v("gesture","moveto:" + x + "," + y);
            layout_video.setX(x);
            layout_video.setY(y);
            if (!gesture_moved && (Math.abs(video_origX - x) > 5f || Math.abs(video_origY - y) > 5f)) {
                gesture_moved = true;
            }
        }
    }

    private void playerPause() {
        isPlaying = false;
        if (ijkPlayer != null && isPrepared) {
            ijkPlayer.pause();
            if (hasDanmaku && mDanmakuView != null) {
                mDanmakuView.pause();
            }
        }
        if (btn_control != null)
            btn_control.setImageResource(R.drawable.btn_player_play);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mediaSession != null) {
            updateMediaSessionPlaybackState();
        }
    }

    private void playerResume() {
        isPlaying = true;
        if (ijkPlayer != null && isPrepared) {
            ijkPlayer.start();
            if (hasDanmaku && mDanmakuView != null) {
                mDanmakuView.resume();
            }
        }
        if (btn_control != null)
            btn_control.setImageResource(R.drawable.btn_player_pause);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mediaSession != null) {
            updateMediaSessionPlaybackState();
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Logu.v("开始旋转屏幕");

        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        screen_width = displayMetrics.widthPixels;// 获取屏宽
        screen_height = displayMetrics.heightPixels;// 获取屏高
        changeVideoSize();

        Logu.v("旋转屏幕结束");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Logu.v("onNewIntent");
        finish();
    }

    //======= PlaybackService 状态桥：后台通知读这些状态、动作统一回 UI 线程执行 =======

    public boolean serviceGone() {
        return destroyed;
    }

    public boolean serviceIsPlaying() {
        return isPlaying && isPrepared && !destroyed;
    }

    public String serviceTitle() {
        return videoTitle != null ? videoTitle : "";
    }

    public int servicePositionMs() {
        return video_now;
    }

    public int serviceDurationMs() {
        return video_all;
    }

    public void serviceTogglePlay() {
        runOnUiThread(() -> {
            if (!destroyed && !isFinishing()) controlVideo();
        });
    }

    public void serviceStopPlayback() {
        runOnUiThread(() -> {
            if (!isFinishing()) finish();
        });
    }

    public void serviceReportNow() {
        runOnUiThread(() -> reportProgressNow(true));
    }

    @Override
    protected void onPause() {
        super.onPause();
        Logu.v("onPause");
        if (!SharedPreferencesUtil.getBoolean("player_background", false)) {
            playerPause();
        } else if (!isFinishing() && isPrepared && !finishWatching) {
            //后台/熄屏继续播放：起前台服务保活并挂通知遥控（回前台/销毁时撤掉）
            PlaybackService.start(this, this);
        }
        //兜底上报：进程被系统回收、直接杀后台时 onDestroy 不保证执行，进度不能只依赖退出路径
        reportProgressNow(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Logu.v("onResume");
        //回到前台，后台播放通知没有存在的意义了
        PlaybackService.stop(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        Logu.v("onStop");
        reportProgressNow(false);
    }

    //后台线程赋值、主线程 onDestroy 读取，volatile 保证关闭竞态判断的可见性
    volatile WebSocket liveWebSocket = null;
    //直播弹幕监听器：onDestroy 时显式 destroy 停掉心跳 Timer（close 不保证触发 onClosed）
    private PlayerDanmuClientListener liveDanmuListener;

    //换源竞态守卫：连续切 P/切清晰度时，先发出的请求可能后返回；
    //回调凭 token 判断自己是否仍是"最新一次切换"，过期请求整体放弃，避免旧响应覆盖新界面
    private volatile int switchToken = 0;

    @Override
    protected void onDestroy() {
        //部分设备/ROM 会在页面启动阶段先回调一次 onDestroy（isFinishing=false）：
        //此时播放器与定时器尚未建立，早退是安全的；
        //但若已经初始化过（系统回收内存、"不保留活动"等非 finish 销毁路径），
        //跳过清理会让 native 播放器与 5 个 Timer 全部泄漏，EventBus 也未反注册
        if (!isFinishing() && ijkPlayer == null && progressTimer == null && loadingTimer == null) {
            //早退分支也要完成"零播放器路径"仍必需的收尾：EventBus 反注册、后台服务停掉、
            //destroyed 置位（后台回调据此判断页面已死），否则非 finish 销毁路径全部跳过
            //同时把可能已建立的 surface/online/speed 定时器一并取消：setDisplay 在 postDelayed 后
            //才创建 surfaceTimer，页面在这之前被销毁时主路径的 cancelAllTimers 不会执行，
            //遗留的 200ms/500ms 轮询 Timer 会永久持有 Activity 与 View 树
            cancelAllTimers();
            if (eventBusInit) {
                EventBus.getDefault().unregister(this);
                eventBusInit = false;
            }
            PlaybackService.stop(this);
            destroyed = true;
            super.onDestroy();
            return;
        }

        Logu.v("结束");

        //进度兜底上报要在播放器释放前取位置，这是退出路径最可靠的一道保险
        reportProgressNow(true);
        //无论哪种退出原因，后台播放通知都不能留存
        PlaybackService.stop(this);

        if (eventBusInit) {
            EventBus.getDefault().unregister(this);
            eventBusInit = false;
        }
        destroyed = true;
        isPrepared = false;
        isPlaying = false;
        pendingDanmakuSeekMs = -1;

        cancelAllTimers();

        //先释放弹幕再释放播放器：此时播放器对象仍然有效，DFM 线程不会踩到已释放的对象，
        //release 内部的 join 才可能正常返回（弹幕侧已不再读播放器位置，见 streamDanmaku 回调注释）
        if (mDanmakuView != null) {
            mDanmakuView.release();
            mDanmakuView = null;
        }
        //player 的 native release 移交 player-ops 线程（主线程同步 release 是 ANR 来源）；
        //ijkSurface.release() 只做引用计数递减，主线程原时序保留
        retirePlayer();
        if (ijkSurface != null) {
            ijkSurface.release();
            ijkSurface = null;
        }

        if (isOnlineVideo && danmakuFile != null && danmakuFile.exists())
            danmakuFile.delete();

        if (liveDanmuListener != null) {
            liveDanmuListener.destroy();
            liveDanmuListener = null;
        }
        if (liveWebSocket != null) {
            liveWebSocket.close(1000, "");
            liveWebSocket = null;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }

        setRequestedOrientation(SharedPreferencesUtil.getBoolean("ui_landscape", false)
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        super.onDestroy();
    }

    /**
     * 创建 Timer 时兜住线程耗尽异常。
     * 进程线程/栈地址空间被吃满时 new Timer()（内部 Thread.start → pthread_create）会抛
     * OutOfMemoryError: pthread_create (4112KB stack) failed，直接崩掉正在播放的页面。
     * 定时器只是进度/在线人数/缓冲速度这类辅助刷新，缺了只影响对应 UI 更新，
     * 不该让整段播放陪葬：返回 null 让调用方跳过本次调度即可。
     */
    private static Timer newTimerOrNull(String tag) {
        try {
            return new Timer();
        } catch (Throwable t) {
            Logu.e(tag, "Timer 创建失败（进程线程耗尽），本次跳过刷新：" + t);
            return null;
        }
    }

    private void cancelAllTimers() {
        if (surfaceTimer != null) {
            surfaceTimer.cancel();
            surfaceTimer = null;
        }
        if (progressTimer != null) {
            progressTimer.cancel();
            progressTimer = null;
        }
        if (onlineTimer != null) {
            onlineTimer.cancel();
            onlineTimer = null;
        }
        if (loadingTimer != null) {
            loadingTimer.cancel();
            loadingTimer = null;
        }
        if (speedTimer != null) {
            speedTimer.cancel();
            speedTimer = null;
        }
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
        //视图在"启动阶段即被销毁"的早退分支里可能还没 inflate，判空后再摘回调
        if (layout_control != null) layout_control.removeCallbacks(hidecon);
        if (text_volume != null) text_volume.removeCallbacks(hideVolume);
        if (seekbar_progress != null) seekbar_progress.removeCallbacks(progressbarEnable);
    }

    OkHttpClient okHttpClient;

    private void danmuSocketConnect() {
        CenterThreadPool.run(() -> {
            try {
                //重载/重试会再次进来：先关掉上一条连接并停掉其心跳 Timer，
                //否则旧 WS 的 reader 线程与 32s 心跳线程永久留存，每重连一次泄漏一对
                if (liveWebSocket != null) {
                    liveWebSocket.close(1000, "");
                    liveWebSocket = null;
                }
                if (liveDanmuListener != null) {
                    liveDanmuListener.destroy();
                    liveDanmuListener = null;
                }

                String url = "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?type=0&id=" + aid;
                ArrayList<String> mHeaders = new ArrayList<>() {
                    {
                        add("Cookie");
                        add(CookieGenerator.getCookieString(true));
                        add("Referer");
                        add("https://live.bilibili.com/" + aid);
                        add("Origin");
                        add("https://live.bilibili.com");
                        add("User-Agent");
                        add(USER_AGENT_WEB);
                    }
                };
                Response response = NetWorkUtil.get(ConfInfoApi.signWBI(url), mHeaders);
                JSONObject data = new JSONObject(Objects.requireNonNull(response.body()).string())
                        .getJSONObject("data");
                JSONObject host = data.getJSONArray("host_list").getJSONObject(0);

                //host 来自服务端且即将携带 Cookie 连接：必须过 B 站域名白名单，
                //被劫持的响应把连接指到任意主机时直接放弃，不把 Cookie 交出去
                String wsHost = host.getString("host");
                if (!NetWorkUtil.isBilibiliHost(wsHost))
                    throw new IOException("直播弹幕服务器域名异常：" + wsHost);

                url = "wss://" + wsHost + ":" + host.getInt("wss_port") + "/sub";
                Logu.v("连接WebSocket", url);

                //复用全局客户端：裸 OkHttpClient 没有全局的 TLS 兼容配置（API≤22 默认不启用 TLS1.2，wss 握手会失败）、
                //超时和 IPv4 DNS，且每个直播间新建实例从不释放，线程池随连接次数累积
                okHttpClient = NetWorkUtil.getOkHttpInstance();
                Request request = new Request.Builder()
                        .url(url)
                        .header("Cookie", CookieGenerator.getCookieString(true))
                        .header("Origin", "https://live.bilibili.com")
                        .header("User-Agent", USER_AGENT_WEB)
                        .build();

                PlayerDanmuClientListener listener = new PlayerDanmuClientListener();
                listener.mid = mid;
                listener.roomid = aid;
                listener.key = data.getString("token");
                listener.setPlayerActivity(this);
                //留一份强引用在 Activity 侧：onDestroy 时显式 destroy()（停心跳、清引用），
                //不依赖 ws.close 是否触发 onClosed
                liveDanmuListener = listener;

                //销毁竞态：进直播间立刻退出时，onDestroy 执行时连接往往还没建立（liveWebSocket 仍为 null，
                //无人关闭），连接随后才成功——赋值后必须立刻复核 destroyed，否则心跳线程与整个 Activity
                //连同 View 树一起泄漏，native 播放器已释放但 ws 持续收包
                WebSocket webSocket = okHttpClient.newWebSocket(request, listener);
                liveWebSocket = webSocket;
                if (destroyed) {
                    webSocket.close(1000, "");
                    if (liveWebSocket == webSocket) liveWebSocket = null;
                    return;
                }
                // okHttpClient.dispatcher().executorService().shutdown();
            } catch (Exception e) {
                MsgUtil.showMsg("直播弹幕连接失败");
                e.printStackTrace();
            }
        });
    }

    @SuppressLint("WrongConstant")
    private void initMediaSession() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        mediaSession = new MediaSession(this, "BiliClientPlayer");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                super.onPlay();
                runOnUiThread(() -> {
                    if (!isPlaying) {
                        playerResume();
                        updateMediaSessionPlaybackState();
                    }
                });
            }

            @Override
            public void onPause() {
                super.onPause();
                runOnUiThread(() -> {
                    if (isPlaying) {
                        playerPause();
                        updateMediaSessionPlaybackState();
                    }
                });
            }

            @Override
            public void onSkipToNext() {
                super.onSkipToNext();
                runOnUiThread(() -> {
                    if (hasMultiplePages() && currentPageIndex < pagenames.size() - 1) {
                        switchToPage(currentPageIndex + 1);
                    }
                });
            }

            @Override
            public void onSkipToPrevious() {
                super.onSkipToPrevious();
                runOnUiThread(() -> {
                    if (hasMultiplePages() && currentPageIndex > 0) {
                        switchToPage(currentPageIndex - 1);
                    }
                });
            }

            @Override
            public void onSeekTo(long pos) {
                super.onSeekTo(pos);
                runOnUiThread(() -> {
                    seekToPosition(pos);
                    updateMediaSessionPlaybackState();
                });
            }
        });
        mediaSession.setActive(true);
    }

    private void updateMediaSessionMetadata() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP || mediaSession == null) {
            return;
        }
        MediaMetadata.Builder metadataBuilder = new MediaMetadata.Builder();
        if (videoTitle != null) {
            metadataBuilder.putString(MediaMetadata.METADATA_KEY_TITLE, videoTitle);
        }
        if (video_all > 0) {
            metadataBuilder.putLong(MediaMetadata.METADATA_KEY_DURATION, video_all);
        }
        mediaSession.setMetadata(metadataBuilder.build());
    }

    private void updateMediaSessionPlaybackState() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP || mediaSession == null) {
            return;
        }
        int state = isPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        //这里既会被 progressTimer 线程调用，也会被 finish()→playerPause() 这条主线程退出链调用，
        //因此不能用 ijkPlayer.getCurrentPosition()（原生锁，seek 期间可能不返回），统一用后台维护的 video_now
        long position = video_now;
        long actions = PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_SEEK_TO
                | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        if (!hasMultiplePages() || currentPageIndex >= pagenames.size() - 1) {
            actions &= ~PlaybackState.ACTION_SKIP_TO_NEXT;
        }
        if (!hasMultiplePages() || currentPageIndex <= 0) {
            actions &= ~PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        }
        PlaybackState.Builder stateBuilder = new PlaybackState.Builder()
                .setState(state, position, 1.0f)
                .setActions(actions);
        mediaSession.setPlaybackState(stateBuilder.build());
    }

    private void initUI() {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        screen_width = displayMetrics.widthPixels;// 获取屏宽
        screen_height = displayMetrics.heightPixels;// 获取屏高

        if (SharedPreferencesUtil.getBoolean("player_ui_showRotateBtn", true))
            btn_rotate.setVisibility(View.VISIBLE);
        else
            btn_rotate.setVisibility(View.GONE);

        //弹幕按钮的可见性必须和旋转按钮一样在 initUI 就定下来：原来它拖到 onPrepared 才下发，
        //观感就是"刚进页面按钮还在、开播瞬间凭空少一个"，也让"设置里关掉了"这种正常状态
        //表现得像 bug。默认值与原逻辑一致（true）
        btn_danmaku.setVisibility(SharedPreferencesUtil.getBoolean("player_ui_showDanmakuBtn", true)
                ? View.VISIBLE : View.GONE);

        screen_round = SharedPreferencesUtil.getBoolean("player_ui_round", false);
        if (screen_round) {
            int padding = (int) (screen_width * 0.03);

            LinearLayout.LayoutParams progressParams = (LinearLayout.LayoutParams) seekbar_progress.getLayoutParams();
            progressParams.leftMargin = padding * 4;
            progressParams.rightMargin = padding * 4;
            seekbar_progress.setLayoutParams(progressParams);

            text_online.setPadding(0, 0, padding * 3, 0);
            text_progress.setPadding(padding * 3, 0, 0, 0);

            bottom_buttons.setPadding(padding, 0, padding, padding);

            right_control.setPadding(0, 0, padding, 0);

            //右侧按钮列在圆屏上要保证"整列都落在可视圆内"：
            //原实现用 layout_below/layout_above 让它被上下横条精确夹取，高度吃紧时最后一个按钮会被裁掉
            //（顶栏在圆屏下要变两行、底栏要加下内边距，余量只剩十几像素）。改成自适应高度 + 垂直居中，
            //既不受上下横条挤压，也自然落在圆盘最宽的中段；同时给可横向滚动的菜单行加宽度上限，
            //避免它把整列一起顶出屏幕右缘
            try {
                RelativeLayout.LayoutParams rcParams = (RelativeLayout.LayoutParams) right_control.getLayoutParams();
                rcParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                rcParams.addRule(RelativeLayout.CENTER_VERTICAL);
                rcParams.removeRule(RelativeLayout.BELOW);
                rcParams.removeRule(RelativeLayout.ABOVE);
                right_control.setLayoutParams(rcParams);
                right_control.setClipChildren(false);

                int columnWidth = ToolsUtil.dp2px(28f) + padding;
                int maxRowWidth = Math.max(screen_width - columnWidth - ToolsUtil.dp2px(8f), ToolsUtil.dp2px(56f));
                ViewGroup.LayoutParams rowParams = right_second.getLayoutParams();
                rowParams.width = maxRowWidth;
                right_second.setLayoutParams(rowParams);
            } catch (Throwable e) {
                Logu.e("round", "右侧按钮列圆屏布局调整失败: " + e.getMessage());
            }

            RelativeLayout.LayoutParams danmakuParams = (RelativeLayout.LayoutParams) mDanmakuView.getLayoutParams();
            danmakuParams.setMargins(0, padding * 3, 0, padding * 3);
            mDanmakuView.setLayoutParams(danmakuParams);

            text_subtitle.setMaxWidth((int) (screen_width * 0.65));

            layout_top.setPadding(padding * 7, padding, padding * 7, 0);

            LinearLayout clockLayout = findViewById(R.id.clock_layout);
            clockLayout.setOrientation(LinearLayout.HORIZONTAL);
            RelativeLayout.LayoutParams clockLayoutParams = new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clockLayoutParams.addRule(RelativeLayout.CENTER_HORIZONTAL);
            clockLayout.setLayoutParams(clockLayoutParams);

            RelativeLayout.LayoutParams titleParams = new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            titleParams.addRule(RelativeLayout.BELOW, R.id.clock_layout);
            titleParams.topMargin = padding / 2;
            text_title.setLayoutParams(titleParams);
            text_title.setGravity(Gravity.CENTER);

            TextView textClock = findViewById(R.id.clock);
            LinearLayout.LayoutParams textClockParams = (LinearLayout.LayoutParams) textClock.getLayoutParams();
            textClockParams.leftMargin = padding / 2;
            textClockParams.topMargin = padding / 4;
            textClock.setLayoutParams(textClockParams);
        }

        if ((!SharedPreferencesUtil.getBoolean("player_show_online", false)) || aid == 0 || cid == 0)
            text_online.setVisibility(View.GONE);

        layout_top.setOnClickListener(view -> finish());

        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        if (SharedPreferencesUtil.getBoolean("player_display", Build.VERSION.SDK_INT < 26)) {
            textureView = new TextureView(this);
            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int i, int i1) {
                    Logu.v("surfacetexture", "available");
                    mSurfaceTexture = surfaceTexture;
                    if (isPrepared && ijkPlayer != null) {
                        Surface surface = new Surface(surfaceTexture);
                        Surface oldSurface = ijkSurface;
                        ijkSurface = surface;
                        ijkPlayer.setSurface(surface);
                        if (oldSurface != null) oldSurface.release();
                    }
                }

                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int i, int i1) {
                    Logu.v("surfacetexture", "sizechanged");
                }

                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
                    Logu.v("surfacetexture", "destroyed");
                    mSurfaceTexture = null;
                    if (ijkPlayer != null)
                        ijkPlayer.setSurface(null);
                    if (ijkSurface != null) {
                        ijkSurface.release();
                        ijkSurface = null;
                    }
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {
                }
            });
            layout_video.addView(textureView, params);
        } else {
            surfaceView = new SurfaceView(this);
            layout_video.addView(surfaceView, params);
        }

        btn_rotate.setOnClickListener(view -> {
            Logu.v("点击旋转按钮");
            screen_landscape = !screen_landscape;
            if (SharedPreferencesUtil.getBoolean("dev_player_rotate_software", false))
                softwareRotate();
            else
                setRequestedOrientation(screen_landscape ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        });

        findViewById(R.id.button_sound_add).setOnClickListener(view -> changeVolume(true));
        findViewById(R.id.button_sound_cut).setOnClickListener(view -> changeVolume(false));

        btn_menu.setOnClickListener(view -> {
            if (menu_opened) {
                right_second.setVisibility(View.GONE);
                btn_menu.setImageResource(R.mipmap.morehide);
            } else {
                right_second.setVisibility(View.VISIBLE);
                btn_menu.setImageResource(R.mipmap.moreshow);
            }
            menu_opened = !menu_opened;
        });

        layout_card_bg.setOnClickListener(view -> {
            layout_card_bg.setVisibility(View.GONE);
            card_subtitle.setVisibility(View.GONE);
            card_danmaku_send.setVisibility(View.GONE);
            card_page_selector.setVisibility(View.GONE);
            card_quality_selector.setVisibility(View.GONE);
            card_viewpoint_selector.setVisibility(View.GONE);
        });
        btn_danmaku_send.setOnClickListener(view -> {
            layout_card_bg.setVisibility(View.VISIBLE);
            card_danmaku_send.setVisibility(View.VISIBLE);
        });
        findViewById(R.id.danmaku_send).setOnClickListener(view1 -> {
            EditText editText = findViewById(R.id.danmaku_send_edit);
            if (editText.getText().toString().isEmpty()) {
                MsgUtil.showMsg("不能发送空弹幕喵");
            } else {
                layout_card_bg.setVisibility(View.GONE);
                card_danmaku_send.setVisibility(View.GONE);

                CenterThreadPool.run(() -> {
                    try {
                        MsgUtil.showMsg("正在发送~");

                        int result = DanmakuApi.sendVideoDanmakuByAid(cid, editText.getText().toString(), aid,
                                video_now, ToolsUtil.getRgb888(Color.WHITE), 1);

                        if (result == 0) {
                            MsgUtil.showMsg("发送成功喵~");
                            runOnUiThread(() -> {
                                addDanmaku(editText.getText().toString(), Color.WHITE);
                                editText.setText("");
                            });
                        } else
                            MsgUtil.showMsg("发送失败：" + result);
                    } catch (Exception e) {
                        e.printStackTrace();
                        MsgUtil.err(e);
                    }
                });
            }
        });
    }

    private void initSeekbars() {
        seekbar_progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @SuppressLint("SetTextI18n")
            @Override
            public void onProgressChanged(SeekBar seekBar, int position, boolean fromUser) {
                runOnUiThread(() -> {
                    if (!isLiveMode)
                        text_progress.setText(StringUtil.toTime(position / 1000) + "/" + progress_str);
                });
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                isSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                isSeeking = false;
                if (isPrepared && !destroyed) {
                    int seekPos = seekbar_progress.getProgress();
                    ijkPlayer.seekTo(seekPos);
                    seekDanmakuTo(seekPos);
                    autohideReset();
                }
            }
        });

        seekbar_speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int position, boolean fromUser) {
                if (fromUser) {
                    text_newspeed.setText(speed_strs[position]);
                    text_speed.setText(speed_strs[position]);
                    if (ijkPlayer != null)
                        ijkPlayer.setSpeed(speed_values[position]);
                    if (mDanmakuView != null)
                        mDanmakuView.setSpeed(speed_values[position]);
                    //记住倍速：手动调速才落盘；长按 3x 与 onPrepared 的恢复（fromUser=false）不会走到这里
                    if (SharedPreferencesUtil.getBoolean("player_speed_remember", false))
                        SharedPreferencesUtil.putFloat("player_speed_value", speed_values[position]);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                if (speedTimer != null)
                    speedTimer.cancel();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                speedTimer = newTimerOrNull("倍速提示");
                if (speedTimer == null) {
                    layout_speed.setVisibility(View.GONE);
                    return;
                }
                TimerTask timerTask = new TimerTask() {
                    @Override
                    public void run() {
                        runOnUiThread(() -> layout_speed.setVisibility(View.GONE));
                    }
                };
                speedTimer.schedule(timerTask, 200);
            }
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isPrepared)
            switch (keyCode) {
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                    controlVideo();
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    seekToPosition(video_now - 10000L);   //D-pad 快退：主线程读原生锁有卡死风险
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    seekToPosition(video_now + 10000L);
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    changeVolume(true);
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    changeVolume(false);
                    break;
            }
        return super.onKeyDown(keyCode, event);
    }

    private void seekToPosition(long position) {
        if (ijkPlayer != null && isPrepared) {
            ijkPlayer.seekTo(position);
            seekDanmakuTo(position);
        }
    }

    /**
     * 跳转弹幕时间轴。
     * DanmakuView.seekTo 内部要求 handler 已 prepared，否则静默丢弃——弹幕还在下载时必然如此，
     * 因此这里记住目标位置，由 streamDanmaku 的 prepared 回调补做，保证断点续播弹幕与视频对齐。
     */
    private void seekDanmakuTo(long positionMs) {
        if (!hasDanmaku || mDanmakuView == null) return;
        if (positionMs < 0) positionMs = 0;
        pendingDanmakuSeekMs = positionMs;
        //主动跳转后先进入冷却：播放器 seek 需要时间才真正到位，期间不能反过来把弹幕拉回旧位置
        lastDanmakuSyncMs = System.currentTimeMillis();
        lastObservedDanmakuMs = -1;
        mDanmakuView.seekTo(positionMs);
    }

    /**
     * 弹幕时间轴卡死校正。
     * 判定条件是"弹幕时间轴停止推进，而播放器还在前进"，而不是简单的偏差超阈值——
     * 后者会在 seek 后播放器尚未到位时把弹幕反向拽回旧位置，形成来回抖动。
     * 只允许在后台定时任务里读播放器位置，绝不能在 DFM 的线程里读（见 streamDanmaku 的回调注释）。
     */
    private void syncDanmakuIfDrifted(long playerPositionMs) {
        if (!isPrepared || !isPlaying || isSeeking || !isDanmakuVisible) return;
        //未 prepared 时 getCurrentTime 固定返回 0，会误判；这种情况由 pendingDanmakuSeekMs 兜底
        if (!hasDanmaku || mDanmakuView == null || !mDanmakuView.isPrepared()) return;

        long danmakuTime = mDanmakuView.getCurrentTime();
        long now = System.currentTimeMillis();
        if (danmakuTime != lastObservedDanmakuMs) {   //时间轴在推进 = 正常
            lastObservedDanmakuMs = danmakuTime;
            lastObservedWallMs = now;
            return;
        }
        //确认停住：至少观察 1.5s，且播放器已经领先超过容差
        if (now - lastObservedWallMs < 1500) return;
        if (playerPositionMs - danmakuTime <= DANMAKU_SYNC_TOLERANCE_MS) return;
        if (now - lastDanmakuSyncMs < DANMAKU_SYNC_COOLDOWN_MS) return;

        lastDanmakuSyncMs = now;
        lastObservedWallMs = now;
        Logu.w("弹幕校正", "弹幕时间轴停止推进（danmaku=" + danmakuTime + "，player=" + playerPositionMs + "），重新对齐");
        seekDanmakuTo(playerPositionMs);
    }

    private void toggleAudioOnlyMode() {
        boolean oldMode = isAudioOnlyMode;
        isAudioOnlyMode = !isAudioOnlyMode;
        // 不保存状态，仅在当前播放会话中切换

        if (isPrepared && ijkPlayer != null) {
            final long currentPosition = video_now;

            MsgUtil.showMsg(isAudioOnlyMode ? "正在切换到听视频模式..." : "正在切换到普通模式...");

            CenterThreadPool.run(() -> {
                try {
                    runOnUiThread(() -> {
                        if (hasDanmaku && mDanmakuView != null) {
                            mDanmakuView.pause();
                        }
                        //先摘掉播放状态再释放播放器：250ms 进度定时器一旦在 release 期间读到已释放的实例，
                        //轻则抛异常打死定时器（进度条与进度上报从此静默失效），重则卡在播放器原生锁上
                        isPrepared = false;
                        isPlaying = false;
                        retirePlayer();

                        loading_info.setVisibility(View.VISIBLE);
                        anim_loading.start();
                        loading_text0.setText(isAudioOnlyMode ? "切换到听视频模式" : "切换到普通模式");

                        updateAudioOnlyButton();
                        updateAudioOnlyUI();
                    });

                    //旧实例 release 完成后再重建（ijkSurface 单实例，见 runAfterPlayerReleased 注释）
                    runAfterPlayerReleased(() -> {
                        //页面可能已退出：对已销毁的窗口 setDisplay 会创建无人认领的播放器实例（native 泄漏）
                        if (destroyed || isFinishing()) return;
                        ijkPlayer = new IjkMediaPlayer();
                        progress_history = currentPosition;

                        setDisplay();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        MsgUtil.showMsg("切换失败，请重试");
                        isAudioOnlyMode = oldMode;
                        // 不保存状态
                        updateAudioOnlyButton();
                        updateAudioOnlyUI();
                        loading_info.setVisibility(View.GONE);
                        anim_loading.stop();
                    });
                }
            });
        } else {
            updateAudioOnlyButton();
            updateAudioOnlyUI();
            MsgUtil.showMsg(isAudioOnlyMode ? "已切换到听视频模式" : "已切换到普通模式");
        }
    }

    private void updateAudioOnlyButton() {
        if (btn_audio_only != null) {
            btn_audio_only
                    .setImageResource(isAudioOnlyMode ? R.drawable.icon_audio_only_on : R.drawable.icon_audio_only_off);
        }
    }

    private void updateAudioOnlyUI() {
        runOnUiThread(() -> {
            if (isAudioOnlyMode) {
                // 进入听视频模式
                text_speed.setVisibility(View.GONE);
                btn_debug.setVisibility(View.GONE);
                btn_danmaku.setVisibility(View.GONE);
                layout_video.setVisibility(View.GONE);
                layout_audio_only.setVisibility(View.VISIBLE);
                if (mDanmakuView != null) {
                    mDanmakuView.setVisibility(View.GONE);
                }
                if (text_audio_title != null) {
                    String title = text_title.getText().toString();
                    text_audio_title.setText(title.isEmpty() ? "听视频模式" : title);
                }
            } else {
                // 退出听视频模式
                text_speed.setVisibility(View.VISIBLE);
                updateDebugButtonVisibility();
                //与 initUI/onPrepared 同一口径：用户关掉"显示弹幕按钮"时，退出听视频模式也不能把它放回来
                btn_danmaku.setVisibility(SharedPreferencesUtil.getBoolean("player_ui_showDanmakuBtn", true)
                        ? View.VISIBLE : View.GONE);
                layout_video.setVisibility(View.VISIBLE);
                layout_audio_only.setVisibility(View.GONE);
                if (mDanmakuView != null && isDanmakuVisible) {
                    mDanmakuView.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    private boolean eventBusInit = false;

    @Override
    protected void onStart() {
        super.onStart();
        if (eventBusEnabled() && !eventBusInit) {
            EventBus.getDefault().register(this);
            Logu.v("event", "register");
            eventBusInit = true;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN, sticky = true)
    public void onEvent(SnackEvent snackEvent) {
        if (isFinishing())
            return;
        Logu.v("event", "onEvent");

        long currentTime = System.currentTimeMillis();

        int duration;
        if (snackEvent.getDuration() > 0)
            duration = snackEvent.getDuration();
        else if (snackEvent.getDuration() == Snackbar.LENGTH_SHORT)
            duration = 1950;
        else if (snackEvent.getDuration() == Snackbar.LENGTH_INDEFINITE)
            duration = Integer.MAX_VALUE;
        else
            duration = 2750;

        long endTime = snackEvent.getStartTime() + duration;
        if (currentTime >= endTime) {
            EventBus.getDefault().removeStickyEvent(snackEvent);
        } else {
            MsgUtil.toast(snackEvent.getMessage()); // 由于Theme.Black不支持，只能这样用了
        }
    }

    protected boolean eventBusEnabled() {
        return SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.SNACKBAR_ENABLE, true);
    }

    /**
     * 加载高能进度条数据
     */
    private void loadHighEnergyData() {
        if (!SharedPreferencesUtil.getBoolean("player_high_energy", false)) {
            Logu.d("高能进度条", "功能已禁用");
            return;
        }

        CenterThreadPool.run(() -> {
            try {
                Logu.d("高能进度条", "开始加载数据 aid=" + aid + " cid=" + cid);
                HighEnergyData data = PlayerApi.getHighEnergyData(cid, aid);

                if (data != null && data.hasValidData()) {
                    runOnUiThread(() -> {
                        if (!destroyed && seekbar_progress != null) {
                            seekbar_progress.setHighEnergyData(data.events, data.stepSec);
                            Logu.d("高能进度条", "数据加载成功并设置到进度条");
                        }
                    });
                } else {
                    Logu.w("高能进度条", "未获取到有效数据");
                }
            } catch (Exception e) {
                Logu.e("高能进度条", "加载失败: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private boolean hasMultiplePages() {
        return pagenames != null && pagenames.size() > 1;
    }

    private void showPageSelectorCard() {
        if (!hasMultiplePages())
            return;

        runOnUiThread(() -> {
            RecyclerView pageSelectorRecycler = findViewById(R.id.page_selector_list);
            PageSelectorAdapter adapter = new PageSelectorAdapter();
            adapter.setData(pagenames, currentPageIndex);
            adapter.setOnItemClickListener(index -> {
                layout_card_bg.setVisibility(View.GONE);
                card_page_selector.setVisibility(View.GONE);
                if (index != currentPageIndex) {
                    switchToPage(index);
                }
            });
            pageSelectorRecycler.setLayoutManager(new CustomLinearManager(this));
            pageSelectorRecycler.setAdapter(adapter);
            layout_card_bg.setVisibility(View.VISIBLE);
            card_page_selector.setVisibility(View.VISIBLE);
        });
    }

    private void switchToPage(int pageIndex) {
        if (!hasMultiplePages())
            return;
        if (pageIndex < 0 || pageIndex >= pagenames.size())
            return;
        if (pageIndex == currentPageIndex)
            return;

        //切走前把旧P当前位置立即上报：周期上报粒度15s，不兜底的话旧P尾部进度会丢
        reportProgressNow(true);

        currentPageIndex = pageIndex;
        long newCid = cids.get(pageIndex);
        String newTitle = pagenames.get(pageIndex);

        MsgUtil.showMsg("切换到 P" + (pageIndex + 1));

        final int myToken = ++switchToken;

        CenterThreadPool.run(() -> {
            try {
                PlayerData playerData = new PlayerData();
                playerData.aid = aid;
                playerData.cid = newCid;
                playerData.title = newTitle;
                playerData.mid = mid;
                playerData.qn = SharedPreferencesUtil.getInt("play_qn", 16);
                playerData.pagenames = pagenames;
                playerData.cids = cids;
                playerData.currentPageIndex = currentPageIndex;

                if (isOnlineVideo) {
                    PlayerApi.getVideo(playerData, false);
                } else {
                    runOnUiThread(() -> MsgUtil.showMsg("本地视频暂不支持切换分P"));
                    return;
                }

                runOnUiThread(() -> {
                    if (destroyed || myToken != switchToken)
                        return;

                    //先摘掉播放状态再释放播放器：250ms 进度定时器若在 release 期间读到已释放的实例，
                    //轻则抛异常打死定时器（进度条与上报从此静默失效），重则卡在播放器原生锁上
                    isPrepared = false;
                    isPlaying = false;

                    retirePlayer();
                    if (mDanmakuView != null) {
                        mDanmakuView.release();
                        mDanmakuView = null;
                    }

                    cid = newCid;
                    //节流字段按cid区分语义：切P后必须重置，否则新P前15s的上报会被旧P的进度数值拦掉
                    lastReportedProgressSec = -1;
                    lastReportedProgressMs = -1;
                    video_url = playerData.videoUrl;
                    danmaku_url = playerData.danmakuUrl;
                    text_title.setText(newTitle);
                    videoTitle = newTitle;

                    if (playerData.qnStrList != null && playerData.qnValueList != null) {
                        qnStrList = playerData.qnStrList;
                        qnValueList = playerData.qnValueList;
                        currentQuality = playerData.qn;
                    }

                    loading_info.setVisibility(View.VISIBLE);
                    anim_loading.start();
                    loading_text0.setText("加载P" + (pageIndex + 1));
                    finishWatching = false;
                    progress_history = 0;
                    //换视频/换分P要丢掉上一集的待执行弹幕跳转，否则新弹幕起来会跳到上一集的位置
                    pendingDanmakuSeekMs = -1;
                    subtitles = null;
                    subtitleLinks = null;
                    subtitle_selected = -1;
                    viewPoints = null;
                    viewPointAdapter = null;
                    if (btn_viewpoint != null) {
                        btn_viewpoint.setVisibility(View.GONE);
                    }

                    interactionData = null;
                    currentEdgeId = 0;
                    currentQuestion = null;
                    questionShown = false;
                    if (interactionChoiceLayout != null) {
                        interactionChoiceLayout.setVisibility(View.GONE);
                        interactionChoiceLayout.removeAllViews();
                    }

                    mDanmakuView = findViewById(R.id.sv_danmaku);

                    //旧实例 release 完成后再重建：ijkSurface 单实例，两个 native 播放器不能同时挂同一 Surface
                    runAfterPlayerReleased(() -> {
                        if (destroyed || myToken != switchToken) return;
                        ijkPlayer = new IjkMediaPlayer();

                        setDisplay();
                    });

                    layout_control.postDelayed(() -> CenterThreadPool.run(() -> {
                        if (destroyed || myToken != switchToken)
                            return;

                        runOnUiThread(() -> {
                            loading_text0.setText("装填弹幕中");
                            loading_text1.setText("(≧∇≦)");
                        });

                        if (isOnlineVideo) {
                            danmakuFile = new File(getCacheDir(), "danmaku.xml");
                            if (danmakuFile.exists()) {
                                danmakuFile.delete();
                            }
                            downdanmu();
                        }

                        if (!destroyed && SharedPreferencesUtil.getBoolean("player_subtitle_autoshow", true)) {
                            downSubtitle(false);
                        }

                        if (!destroyed && isOnlineVideo && aid > 0 && cid > 0) {
                            loadHighEnergyData();
                        }

                        if (!destroyed && isOnlineVideo && aid > 0 && cid > 0 && SharedPreferencesUtil.getBoolean("player_show_viewpoints", false)) {
                            loadViewPoints();
                        }

                        if (!destroyed && isOnlineVideo && aid > 0 && cid > 0) {
                            loadInteractionVideo();
                        }
                    }), 60);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    MsgUtil.err(e);
                    MsgUtil.showMsg("切换失败");
                });
            }
        });
    }

    private void toggleAutoNext() {
        auto_next_enabled = !auto_next_enabled;
        updateAutoNextButton();
        MsgUtil.showMsg(auto_next_enabled ? "已开启自动连播" : "已关闭自动连播");
    }

    private void updateAutoNextButton() {
        if (btn_auto_next != null) {
            btn_auto_next
                    .setImageResource(auto_next_enabled ? R.drawable.icon_auto_next_on : R.drawable.icon_auto_next_off);
        }
    }

    private void showQualitySelectorCard() {
        if (qnStrList == null || qnValueList == null || qnStrList.length == 0) {
            MsgUtil.showMsg("清晰度列表未加载");
            return;
        }

        runOnUiThread(() -> {
            RecyclerView qualitySelectorRecycler = findViewById(R.id.quality_selector_list);
            QualitySelectorAdapter adapter = new QualitySelectorAdapter();
            adapter.setData(qnStrList, qnValueList, currentQuality);
            adapter.setOnItemClickListener(index -> {
                layout_card_bg.setVisibility(View.GONE);
                card_quality_selector.setVisibility(View.GONE);
                if (index >= 0 && index < qnValueList.length && qnValueList[index] != currentQuality) {
                    switchQuality(qnValueList[index]);
                }
            });
            qualitySelectorRecycler
                    .setLayoutManager(new CustomLinearManager(this, LinearLayoutManager.HORIZONTAL, false));
            qualitySelectorRecycler.setAdapter(adapter);
            layout_card_bg.setVisibility(View.VISIBLE);
            card_quality_selector.setVisibility(View.VISIBLE);
        });
    }

    private void switchQuality(int newQuality) {
        if (!isOnlineVideo || newQuality == currentQuality) {
            return;
        }

        MsgUtil.showMsg("正在切换清晰度...");

        final int myToken = ++switchToken;

        CenterThreadPool.run(() -> {
            try {
                PlayerData playerData = new PlayerData();
                playerData.aid = aid;
                playerData.cid = cid;
                playerData.title = text_title.getText().toString();
                playerData.mid = mid;
                playerData.qn = newQuality;
                playerData.pagenames = pagenames;
                playerData.cids = cids;
                playerData.currentPageIndex = currentPageIndex;

                PlayerApi.getVideo(playerData, false);

                runOnUiThread(() -> {
                    if (destroyed || myToken != switchToken)
                        return;

                    final long currentPosition = video_now;

                    //先摘掉播放状态再释放，避免进度定时器在 release 期间读到已释放实例
                    isPrepared = false;
                    isPlaying = false;
                    retirePlayer();

                    video_url = playerData.videoUrl;
                    currentQuality = newQuality;

                    if (playerData.qnStrList != null && playerData.qnValueList != null) {
                        qnStrList = playerData.qnStrList;
                        qnValueList = playerData.qnValueList;
                    }

                    loading_info.setVisibility(View.VISIBLE);
                    anim_loading.start();
                    loading_text0.setText("切换清晰度中");

                    //旧实例 release 完成后再重建（ijkSurface 单实例，见 runAfterPlayerReleased 注释）
                    runAfterPlayerReleased(() -> {
                        if (destroyed || myToken != switchToken) return;
                        ijkPlayer = new IjkMediaPlayer();
                        progress_history = currentPosition;

                        setDisplay();
                    });
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    MsgUtil.err(e);
                    MsgUtil.showMsg("清晰度切换失败");
                });
            }
        });
    }

    private List<ViewPoint> viewPoints;
    private ViewPointAdapter viewPointAdapter;

    private void loadViewPoints() {
        CenterThreadPool.run(() -> {
            try {
                Logu.d("视频分段", "开始加载分段数据 aid=" + aid + " cid=" + cid);
                viewPoints = PlayerApi.getViewPoints(aid, cid);

                if (viewPoints != null && !viewPoints.isEmpty()) {
                    runOnUiThread(() -> {
                        if (!destroyed && btn_viewpoint != null) {
                            btn_viewpoint.setVisibility(View.VISIBLE);
                            btn_viewpoint.setOnClickListener(view -> showViewPointSelectorCard());
                            Logu.d("视频分段", "成功加载 " + viewPoints.size() + " 个分段");
                        }
                    });
                } else {
                    Logu.d("视频分段", "未获取到分段数据");
                }
            } catch (Exception e) {
                Logu.e("视频分段", "加载失败: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private void showViewPointSelectorCard() {
        if (viewPoints == null || viewPoints.isEmpty())
            return;

        runOnUiThread(() -> {
            RecyclerView viewPointRecycler = findViewById(R.id.viewpoint_selector_list);
            if (viewPointAdapter == null) {
                viewPointAdapter = new ViewPointAdapter();
                viewPointAdapter.setData(viewPoints);
                viewPointAdapter.setOnItemClickListener(index -> {
                    layout_card_bg.setVisibility(View.GONE);
                    card_viewpoint_selector.setVisibility(View.GONE);
                    if (index >= 0 && index < viewPoints.size()) {
                        ViewPoint vp = viewPoints.get(index);
                        seekToPosition(vp.from * 1000L);
                        MsgUtil.showMsg("跳转到: " + vp.content);
                    }
                });
                viewPointRecycler.setLayoutManager(new CustomLinearManager(this, LinearLayoutManager.HORIZONTAL, false));
                viewPointRecycler.setAdapter(viewPointAdapter);
            }
            if (ijkPlayer != null && isPrepared) {
                int currentPos = video_now / 1000;
                viewPointAdapter.updateCurrentPosition(currentPos);
            }
            layout_card_bg.setVisibility(View.VISIBLE);
            card_viewpoint_selector.setVisibility(View.VISIBLE);
        });
    }





    // 再往下就是互动视频的天下了

    private void loadInteractionVideo() {
        CenterThreadPool.run(() -> {
            try {
                long graphVersion = PlayerApi.getInteractionGraphVersion(aid, cid);
                if (graphVersion > 0) {
                    interactionGraphVersion = graphVersion;
                    Logu.d("互动视频", "检测到互动视频，graph_version: " + interactionGraphVersion + ", cid: " + cid);
                    
                    runOnUiThread(() -> {
                        questionShown = false;
                        currentQuestion = null;
                        if (interactionChoiceLayout != null) {
                            interactionChoiceLayout.setVisibility(View.GONE);
                            interactionChoiceLayout.removeAllViews();
                        }
                    });
                    
                    long edgeId = 0;
                    if (initialEdgeId > 0) {
                        edgeId = initialEdgeId;
                        initialEdgeId = 0;
                    } else if (interactionData != null && currentEdgeId > 0) {
                        edgeId = currentEdgeId;
                    }
                    
                    interactionData = InteractionVideoApi.getEdgeInfo(aid, null, interactionGraphVersion, edgeId);
                    if (interactionData != null) {
                        currentEdgeId = interactionData.edgeId;
                        Logu.d("互动视频", "成功加载互动视频数据，edge_id: " + currentEdgeId);
                        runOnUiThread(() -> updateDebugButtonVisibility());
                    }
                } else {
                    interactionData = null;
                    currentEdgeId = 0;
                    runOnUiThread(() -> {
                        questionShown = false;
                        currentQuestion = null;
                        updateDebugButtonVisibility();
                    });
                }
            } catch (Exception e) {
                Logu.e("互动视频", "加载失败: " + e.getMessage());
                e.printStackTrace();
                interactionData = null;
                currentEdgeId = 0;
                runOnUiThread(() -> {
                    questionShown = false;
                    currentQuestion = null;
                    updateDebugButtonVisibility();
                });
            }
        });
    }

    private void updateDebugButtonVisibility() {
        if (btn_debug == null) return;
        boolean debugEnabled = SharedPreferencesUtil.getBoolean("player_interaction_debug", false);
        if (debugEnabled && interactionData != null && interactionData.hiddenVars != null && !interactionData.hiddenVars.isEmpty() && !isLiveMode && !isAudioOnlyMode) {
            btn_debug.setVisibility(controlsShown ? View.VISIBLE : View.GONE);
        } else {
            btn_debug.setVisibility(View.GONE);
        }
    }

    private void checkEndInteractionQuestions() {
        if (interactionData == null || interactionData.edges == null || 
            interactionData.edges.questions == null || questionShown) {
            return;
        }

        for (InteractionVideoData.InteractionQuestion question : interactionData.edges.questions) {
            if (question.type == 0) {
                if (question.choices != null && !question.choices.isEmpty()) {
                    for (InteractionVideoData.InteractionChoice choice : question.choices) {
                        if (choice.isHidden == 1) continue;
                        
                        if (choice.condition != null && !choice.condition.isEmpty()) {
                            if (!evaluateCondition(choice.condition)) {
                                continue;
                            }
                        }
                        
                        handleChoiceSelection(choice);
                        break;
                    }
                }
                continue;
            }
            showInteractionQuestion(question);
        }
    }

    private void showInteractionQuestion(InteractionVideoData.InteractionQuestion question) {
        if (questionShown || question.choices == null || question.choices.isEmpty()) {
            return;
        }

        runOnUiThread(() -> {
            questionShown = true;
            currentQuestion = question;

            if (question.pauseVideo == 1 && isPlaying) {
                ijkPlayer.pause();
                isPlaying = false;
                btn_control.setImageResource(R.drawable.btn_player_play);
            }

            if (interactionChoiceLayout == null) {
                createInteractionChoiceLayout();
            }

            interactionChoiceLayout.removeAllViews();
            
            for (InteractionVideoData.InteractionChoice choice : question.choices) {
                if (choice.isHidden == 1) continue;
                
                if (choice.condition != null && !choice.condition.isEmpty()) {
                    if (!evaluateCondition(choice.condition)) {
                        continue;
                    }
                }

                TextView choiceView = createChoiceView(choice);
                interactionChoiceLayout.addView(choiceView);
            }

            if (interactionChoiceLayout.getChildCount() > 0) {
                interactionChoiceLayout.setVisibility(View.VISIBLE);
            }
        });
    }

    private TextView createChoiceView(InteractionVideoData.InteractionChoice choice) {
        TextView choiceView = (TextView) LayoutInflater.from(this).inflate(R.layout.cell_interaction_choice, null);
        choiceView.setText(choice.option);
        float fontSize = SharedPreferencesUtil.getFloat("player_interaction_choice_size", 17.0f);
        choiceView.setTextSize(fontSize);
        choiceView.setOnClickListener(v -> handleChoiceSelection(choice));
        return choiceView;
    }

    private void createInteractionChoiceLayout() {
        RelativeLayout rootLayout = findViewById(R.id.root_layout);
        interactionChoiceLayout = new LinearLayout(this);
        interactionChoiceLayout.setOrientation(LinearLayout.VERTICAL);
        interactionChoiceLayout.setGravity(android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
        
        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        );
        params.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        params.setMargins(0, 0, 0, 100);
        
        interactionChoiceLayout.setLayoutParams(params);
        interactionChoiceLayout.setVisibility(View.GONE);
        rootLayout.addView(interactionChoiceLayout);
    }

    private boolean evaluateCondition(String condition) {
        if (interactionData == null || interactionData.hiddenVars == null || condition == null || condition.isEmpty()) {
            return true;
        }
        
        try {
            String result = condition;
            for (InteractionVideoData.InteractionHiddenVar var : interactionData.hiddenVars) {
                if (var.idV2 != null && !var.idV2.isEmpty()) {
                    String pattern = "\\b" + Pattern.quote(var.idV2) + "\\b";
                    result = result.replaceAll(pattern, String.valueOf(var.value));
                }
                if (var.id != null && !var.id.isEmpty() && !var.id.equals(var.idV2)) {
                    String pattern = "\\b" + Pattern.quote(var.id) + "\\b";
                    result = result.replaceAll(pattern, String.valueOf(var.value));
                }
            }
            
            return evaluateExpression(result);
        } catch (Exception e) {
            Logu.e("互动视频", "条件判断失败: " + e.getMessage());
            return true;
        }
    }

    private boolean evaluateExpression(String expr) {
        try {
            expr = expr.trim();
            if (expr.contains(">=")) {
                String[] parts = expr.split(">=");
                long left = Long.parseLong(parts[0].trim());
                long right = Long.parseLong(parts[1].trim());
                return left >= right;
            } else if (expr.contains("<=")) {
                String[] parts = expr.split("<=");
                long left = Long.parseLong(parts[0].trim());
                long right = Long.parseLong(parts[1].trim());
                return left <= right;
            } else if (expr.contains(">")) {
                String[] parts = expr.split(">");
                long left = Long.parseLong(parts[0].trim());
                long right = Long.parseLong(parts[1].trim());
                return left > right;
            } else if (expr.contains("<")) {
                String[] parts = expr.split("<");
                long left = Long.parseLong(parts[0].trim());
                long right = Long.parseLong(parts[1].trim());
                return left < right;
            } else if (expr.contains("==")) {
                String[] parts = expr.split("==");
                long left = Long.parseLong(parts[0].trim());
                long right = Long.parseLong(parts[1].trim());
                return left == right;
            } else if (expr.contains("!=")) {
                String[] parts = expr.split("!=");
                long left = Long.parseLong(parts[0].trim());
                long right = Long.parseLong(parts[1].trim());
                return left != right;
            }
        } catch (Exception e) {
            Logu.e("互动视频", "表达式计算失败: " + expr);
        }
        return true;
    }

    private void handleChoiceSelection(InteractionVideoData.InteractionChoice choice) {
        hideInteractionChoices();

        if (choice.nativeAction != null && !choice.nativeAction.isEmpty()) {
            executeNativeAction(choice.nativeAction);
        }

        CenterThreadPool.run(() -> {
            try {
                long targetEdgeId = choice.id;
                InteractionVideoData newData = InteractionVideoApi.getEdgeInfo(aid, null, interactionGraphVersion, targetEdgeId);
                
                if (newData == null) {
                    runOnUiThread(() -> MsgUtil.showMsg("获取互动视频数据失败"));
                    return;
                }

                interactionData = newData;
                currentEdgeId = newData.edgeId;
                
                long targetCid = choice.cid;
                if (targetCid > 0 && targetCid != cid) {
                    jumpToInteractionPage(targetCid, newData);
                } else {
                    resumePlaybackIfPaused();
                }
            } catch (Exception e) {
                Logu.e("互动视频", "处理选择失败: " + e.getMessage());
                runOnUiThread(() -> MsgUtil.showMsg("处理选择失败: " + e.getMessage()));
            }
        });
    }

    private void hideInteractionChoices() {
        runOnUiThread(() -> {
            if (interactionChoiceLayout != null) {
                interactionChoiceLayout.setVisibility(View.GONE);
            }
            questionShown = false;
            currentQuestion = null;
        });
    }

    private void jumpToInteractionPage(long targetCid, InteractionVideoData newData) {
        CenterThreadPool.run(() -> {
            try {
                PlayerData playerData = new PlayerData();
                playerData.aid = aid;
                playerData.cid = targetCid;
                playerData.title = newData.title;
                playerData.mid = mid;
                playerData.qn = getTargetQuality();
                
                if (pagenames != null && cids != null) {
                    playerData.pagenames = pagenames;
                    playerData.cids = cids;
                    int newPageIndex = cids.indexOf(targetCid);
                    if (newPageIndex >= 0) {
                        playerData.currentPageIndex = newPageIndex;
                        currentPageIndex = newPageIndex;
                    }
                }
                
                PlayerApi.getVideo(playerData, false);
                
                runOnUiThread(() -> {
                    if (destroyed)
                        return;
                    
                    //先摘掉播放状态再释放播放器，避免进度定时器在 release 期间读到已释放实例
                    isPrepared = false;
                    isPlaying = false;

                    retirePlayer();
                    if (mDanmakuView != null) {
                        mDanmakuView.release();
                        mDanmakuView = null;
                    }
                    
                    cid = targetCid;
                    video_url = playerData.videoUrl;
                    danmaku_url = playerData.danmakuUrl;
                    text_title.setText(newData.title);
                    videoTitle = newData.title;
                    currentEdgeId = newData.edgeId;
                    
                    if (playerData.qnStrList != null && playerData.qnValueList != null) {
                        qnStrList = playerData.qnStrList;
                        qnValueList = playerData.qnValueList;
                        currentQuality = playerData.qn;
                    }
                    
                    loading_info.setVisibility(View.VISIBLE);
                    anim_loading.start();
                    loading_text0.setText("加载互动分P");
                    finishWatching = false;
                    progress_history = 0;
                    //换视频/换分P要丢掉上一集的待执行弹幕跳转，否则新弹幕起来会跳到上一集的位置
                    pendingDanmakuSeekMs = -1;
                    subtitles = null;
                    subtitleLinks = null;
                    subtitle_selected = -1;
                    viewPoints = null;
                    viewPointAdapter = null;
                    if (btn_viewpoint != null) {
                        btn_viewpoint.setVisibility(View.GONE);
                    }
                    
                    interactionData = newData;
                    currentQuestion = null;
                    questionShown = false;
                    if (interactionChoiceLayout != null) {
                        interactionChoiceLayout.setVisibility(View.GONE);
                        interactionChoiceLayout.removeAllViews();
                    }
                    
                    mDanmakuView = findViewById(R.id.sv_danmaku);

                    //旧实例 release 完成后再重建：ijkSurface 单实例，两个 native 播放器不能同时挂同一 Surface
                    runAfterPlayerReleased(() -> {
                        if (destroyed) return;
                        ijkPlayer = new IjkMediaPlayer();

                        setDisplay();
                    });
                    
                    layout_control.postDelayed(() -> CenterThreadPool.run(() -> {
                        if (destroyed)
                            return;
                        
                        runOnUiThread(() -> {
                            loading_text0.setText("装填弹幕中");
                            loading_text1.setText("(≧∇≦)");
                        });
                        
                        if (isOnlineVideo) {
                            danmakuFile = new File(getCacheDir(), "danmaku.xml");
                            if (danmakuFile.exists()) {
                                danmakuFile.delete();
                            }
                            downdanmu();
                        }
                        
                        if (!destroyed && SharedPreferencesUtil.getBoolean("player_subtitle_autoshow", true)) {
                            downSubtitle(false);
                        }
                        
                        if (!destroyed && isOnlineVideo && aid > 0 && cid > 0) {
                            loadHighEnergyData();
                        }
                        
                        if (!destroyed && isOnlineVideo && aid > 0 && cid > 0 && SharedPreferencesUtil.getBoolean("player_show_viewpoints", false)) {
                            loadViewPoints();
                        }
                    }), 60);
                });
            } catch (Exception e) {
                Logu.e("互动视频", "跳转失败: " + e.getMessage());
                runOnUiThread(() -> MsgUtil.showMsg("跳转失败: " + e.getMessage()));
            }
        });
    }

    private void resumePlaybackIfPaused() {
        runOnUiThread(() -> {
            if (currentQuestion != null && currentQuestion.pauseVideo == 1 && !isPlaying) {
                ijkPlayer.start();
                isPlaying = true;
                btn_control.setImageResource(R.drawable.btn_player_pause);
            }
        });
    }

    private int getTargetQuality() {
        int defaultQn = SharedPreferencesUtil.getInt("play_qn", 16);
        if (qnValueList == null || qnValueList.length == 0) {
            return currentQuality > 0 ? currentQuality : defaultQn;
        }
        
        for (int qn : qnValueList) {
            if (qn == currentQuality) {
                return currentQuality;
            }
        }
        
        return currentQuality > 0 ? currentQuality : defaultQn;
    }

    private void executeNativeAction(String nativeAction) {
        if (interactionData == null || interactionData.hiddenVars == null || nativeAction == null || nativeAction.isEmpty()) {
            return;
        }

        String[] actions = nativeAction.split(";");
        for (String action : actions) {
            action = action.trim();
            if (action.isEmpty()) continue;

            try {
                if (action.contains("=")) {
                    String[] parts = action.split("=");
                    if (parts.length == 2) {
                        String varId = parts[0].trim();
                        String valueExpr = parts[1].trim();
                        
                        long value = evaluateValueExpression(valueExpr);
                        
                        for (InteractionVideoData.InteractionHiddenVar var : interactionData.hiddenVars) {
                            if ((var.idV2 != null && var.idV2.equals(varId)) || 
                                (var.id != null && var.id.equals(varId))) {
                                var.value = value;
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Logu.e("互动视频", "执行动作失败: " + action);
            }
        }
    }

    private long evaluateValueExpression(String expr) {
        try {
            expr = expr.trim();
            if (expr.contains("+")) {
                String[] parts = expr.split("\\+");
                long sum = 0;
                for (String part : parts) {
                    sum += evaluateValueExpression(part.trim());
                }
                return sum;
            } else if (expr.contains("-")) {
                String[] parts = expr.split("-");
                long result = evaluateValueExpression(parts[0].trim());
                for (int i = 1; i < parts.length; i++) {
                    result -= evaluateValueExpression(parts[i].trim());
                }
                return result;
            } else {
                if (interactionData != null && interactionData.hiddenVars != null) {
                    for (InteractionVideoData.InteractionHiddenVar var : interactionData.hiddenVars) {
                        if ((var.idV2 != null && expr.equals(var.idV2)) || 
                            (var.id != null && expr.equals(var.id))) {
                            return var.value;
                        }
                    }
                }
                if (expr.contains(".")) {
                    return (long) Double.parseDouble(expr);
                } else {
                    return Long.parseLong(expr);
                }
            }
        } catch (Exception e) {
            Logu.e("互动视频", "值表达式计算失败: " + expr + ", 错误: " + e.getMessage());
            return 0;
        }
    }

    private void showInteractionDebugDialog() {
        if (interactionData == null || interactionData.hiddenVars == null || interactionData.hiddenVars.isEmpty()) {
            MsgUtil.showMsg("当前没有互动视频变量");
            return;
        }

        InteractionDebugActivity.setInteractionData(interactionData);
        Intent intent = new Intent(this, InteractionDebugActivity.class);
        startActivity(intent);
    }

    @Override
    public void finish() {
        if (isPlaying)
            playerPause();
        if (ijkPlayer != null) {
            Intent result = new Intent();
            //只回传后台定时器维护的 video_now。finish() 跑在主线程，而退出瞬间播放器往往还在收尾 seek，
            //此时 ijkPlayer.getCurrentPosition() 可能长时间不返回——它会直接卡死主线程（表现为"退出播放后整个应用卡死"），
            //且卡在这里连 setResult 都执行不到，连进度都会一起丢。250ms 的精度对续播足够。
            int progressMs = video_now;
            result.putExtra("progress", progressMs);
            //回传最终观看的 cid：播放中可能切过分P，跳转页(JumpToPlayerActivity)退出上报必须用这个 cid，
            //否则会用进入时的旧P cid + 新P的进度覆盖掉播放器已上报的正确记录
            result.putExtra("cid", cid);
            Logu.d("进度回传", String.valueOf(progressMs));
            setResult(RESULT_OK, result);
        } else
            setResult(RESULT_CANCELED);
        super.finish();
    }
}