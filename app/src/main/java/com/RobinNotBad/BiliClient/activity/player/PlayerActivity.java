package com.RobinNotBad.BiliClient.activity.player;

import static android.media.AudioManager.STREAM_MUSIC;
import static com.RobinNotBad.BiliClient.util.NetWorkUtil.USER_AGENT_WEB;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.AnimationDrawable;
import android.media.AudioManager;
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
import android.widget.ScrollView;
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
import com.RobinNotBad.BiliClient.helper.MediaSessionHelper;
import com.RobinNotBad.BiliClient.util.AdaptiveDialog;
import com.RobinNotBad.BiliClient.util.NetworkPlaybackQuality;
import com.RobinNotBad.BiliClient.util.PlayerSeekPolicies;
import com.RobinNotBad.BiliClient.util.VideoMetaManager;
import com.RobinNotBad.BiliClient.api.BangumiApi;
import com.RobinNotBad.BiliClient.api.DanmakuApi;
import com.RobinNotBad.BiliClient.api.HistoryApi;
import com.RobinNotBad.BiliClient.api.InteractionVideoApi;
import com.RobinNotBad.BiliClient.api.PlayerApi;
import com.RobinNotBad.BiliClient.api.VideoInfoApi;
import com.RobinNotBad.BiliClient.event.SnackEvent;
import com.RobinNotBad.BiliClient.model.DanmakuElem;
import com.RobinNotBad.BiliClient.model.DmSegMobileReply;
import com.RobinNotBad.BiliClient.model.HighEnergyData;
import com.RobinNotBad.BiliClient.model.InteractionVideoData;
import com.RobinNotBad.BiliClient.model.PlayerData;
import com.RobinNotBad.BiliClient.model.Subtitle;
import com.RobinNotBad.BiliClient.model.SubtitleLink;
import com.RobinNotBad.BiliClient.model.ViewPoint;
import com.RobinNotBad.BiliClient.service.PlaybackService;
import com.RobinNotBad.BiliClient.ui.widget.HighEnergyProgressBar;
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.CookieGenerator;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.PlayerSentinel;
import com.RobinNotBad.BiliClient.util.ProgressDiag;
import com.RobinNotBad.BiliClient.util.ProtobufParser;
import com.RobinNotBad.BiliClient.service.AudioPlayerService;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.RobinNotBad.BiliClient.util.StringUtil;
import com.RobinNotBad.BiliClient.util.ToolsUtil;
import com.RobinNotBad.BiliClient.util.VolumeKeyHelper;
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
import master.flame.danmaku.danmaku.model.Duration;
import master.flame.danmaku.danmaku.model.DanmakuTimer;
import master.flame.danmaku.danmaku.model.IDanmakuIterator;
import master.flame.danmaku.danmaku.model.IDanmakus;
import master.flame.danmaku.danmaku.model.IDisplayer;
import master.flame.danmaku.danmaku.model.android.DanmakuContext;
import master.flame.danmaku.danmaku.model.android.Danmakus;
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser;
import master.flame.danmaku.danmaku.util.DanmakuUtils;
import master.flame.danmaku.danmaku.parser.IDataSource;
import master.flame.danmaku.danmaku.parser.android.BiliDanmukuParser;
import master.flame.danmaku.danmaku.parser.android.BiliProtobufDanmakuParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okio.BufferedSink;
import com.RobinNotBad.BiliClient.model.ClipInfo;
import com.RobinNotBad.BiliClient.util.DanmakuTimestampUtil;
import com.RobinNotBad.BiliClient.util.SegmentSkipController;
import com.RobinNotBad.BiliClient.util.SegmentSkipMode;
import master.flame.danmaku.ui.widget.DanmakuTouchHelper;
import okio.Okio;
import okio.Sink;
import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

public class PlayerActivity extends Activity implements IjkMediaPlayer.OnPreparedListener {
    //主线程写、DFM 绘制线程与缓存线程读：不加 volatile 时回调线程可能读到过期值
    //（旧代码里表现出来就是 prepared 回调读到 null 的 mDanmakuView，线上 2 例 NPE）
    private volatile boolean destroyed = false;

    private IjkMediaPlayer ijkPlayer;
    private volatile IDanmakuView mDanmakuView;
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
    private LinearLayout layout_speed, layout_volume, right_control, loading_info, left_control;
    private RelativeLayout bottom_buttons;
    private HorizontalScrollView right_second;
    private LinearLayout card_subtitle, card_danmaku_send, card_page_selector, card_quality_selector, card_viewpoint_selector;

    private ImageView img_loading;
    private AnimationDrawable anim_loading;
    private ImageButton btn_control, btn_danmaku, btn_loop, btn_rotate, btn_menu, btn_subtitle, btn_danmaku_send,
            btn_audio_only, btn_page_selector, btn_auto_next, btn_quality, btn_viewpoint, btn_debug;
    private HighEnergyProgressBar seekbar_progress;
    private SeekBar seekbar_speed, seekbar_volume;
    private TextView text_progress, text_online, text_volume, loading_text0, loading_text1, text_speed, text_newspeed,
            text_newvolume, button_seek_back, button_seek_forward;
    public TextView text_title, text_subtitle, text_audio_title, text_audio_subtitle;

    private Timer progressTimer, speedTimer, loadingTimer, onlineTimer, surfaceTimer;
    private Handler mainHandler;
    private String video_url, danmaku_url;
    //MediaSession 的类型被隔离在 MediaSessionHelper 里（API21+ 才加载）：
    //PlayerActivity 自身不能再出现 android.media.session.* 签名，否则 EventBus 反射会在 4.4 上崩
    private MediaSessionHelper mediaSessionHelper;

    private boolean isPlaying, isPrepared, hasDanmaku,
            isOnlineVideo, isLiveMode, isSeeking, isDanmakuVisible;
    private boolean menu_opened = false;
    private boolean isAudioOnlyMode = false;
    private boolean isLocalAudioFile = false; // 标记是否为本地音频文件

    private int video_all, video_now_last;
    //video_now 由 progressTimer 在后台线程写，退出路径（onPause/onStop/onDestroy，主线程）要读它做兜底上报，
    //必须 volatile，否则主线程可能读到 0 或旧值，上报的位置就是错的
    private volatile int video_now;
    //无极调音：0~1 的软件增益，和系统音量是乘算关系（系统音量 100% 时，50% 增益就是半音量）
    //只在 ijk 侧出手，不碰系统音量——手表上系统音量往往只有 3~15 档，靠它调不出"35%"这种值
    private float volumeGain = 1f;
    /** 音量键：短按系统音量 ±1 档 + 居中提示，按住 600ms 弹无极调音 */
    private VolumeKeyHelper volumeKeyHelper;
    //切后台回来黑屏：surface 在后台被销毁后播放器停在暂停态，读线程不会自己吐帧，
    //必须在 surface 重建后重新挂载并强解一帧（见 repaintPausedFrame）
    private volatile boolean surfaceLostWhileBackground;
    /**
     * 用户是否主动处于暂停态。
     *
     * <p>不能用 isPlaying 代替：onPrepared 里 isPlaying 要到最后才置 true，起播那一刻它也是 false。
     * DFM 的 start()/seekTo() 在 DrawHandler 里都会落到 RESUME 分支、把绘制线程重新拉起来，
     * 所以暂停态下每次动它的时间轴之后，都要按这个标记把它再压回去——否则就是"暂停了弹幕还在滚"。
     */
    private boolean userPaused = false;
    /** 页面是否在后台：onPrepared 迟到时用它兜住"弱网下退后台/熄屏后自动起播出声"（B-01） */
    private volatile boolean inBackground = false;
    /** 暂停态跳转的目标位置：部分 ijk 版本对"暂停中 seek"只记住不落地，恢复播放前再补一次 */
    private long pendingPausedSeekMs = -1;
    private long progress_history;
    private String progress_str;

    //番剧维度随 Intent 进入，播放中周期上报需要（epid=0 即普通视频）
    private long epid = 0;
    private long seasonId = 0;
    private int seasonType = 0;
    private String bvid = "";

    /**
     * 第三方番剧源播放：请求头与 B 站链路完全隔离。
     *
     * <p>为空表示走原来的 {@code PlayerApi.mediaHeadersFor}（B 站 Referer/UA/Cookie），字节不变。
     * 非空时只用这份白名单头，绝不附加 B 站 Cookie。
     */
    private java.util.Map<String, String> thirdPartyHeaders = java.util.Collections.emptyMap();
    /** 是否第三方播放（失败文案与重试提示要与 B 站区分） */
    private boolean isThirdPartyPlayback = false;
    /** 第三方播放失败提示只弹一次，避免错误码刷屏 */
    private boolean thirdPartyErrorHintShown = false;
    /** 本次播放对应的线路 ID：播放器报错时据此自动屏蔽线路 */
    private String thirdChannelKey = "";
    /** 第三方弹幕匹配参数（番剧名 + 集号）；为空表示不尝试 */
    private String thirdDanmakuTitle = "";
    private int thirdDanmakuEpisode = 0;
    private boolean thirdDanmakuTried = false;
    /** 第三方弹幕首次拉取失败后的补拉延迟；只补拉一次，不是定时重试 */
    private static final long THIRD_DANMAKU_RETRY_MS = 10_000L;

    //番剧片头/片尾跳过：片段随取流结果经 Intent 进入，档位来自设置项。
    //判定挂在既有的 250ms 进度 tick 上（见 progressChange），不新增定时器、不新增网络请求
    private SegmentSkipController clipSkipController = null;
    private View clipSkipCard;
    private TextView clipSkipText;
    //「仅显示」档位下已经提示过的片段结束位置，避免每个 tick 都弹一次
    //（volatile：写它的进度 tick 与换清晰度/换P后新建的 Timer 不是同一个线程）
    private volatile long clipHintShownForMs = -1;
    //跳过按钮当前是否已显示：只有状态变化时才更新 UI，避免每 250ms 都往主线程投任务
    private volatile boolean clipCardShown = false;

    //空降弹幕：最近一次弹幕命中回调解析出的目标位置（-1 表示这次没有可跳转的时间点）
    private long danmakuHitTargetMs = -1;

    //「新版弹幕获取失败」提示只弹一次：downdanmu() 会在每次 onPrepared 上重跑
    //（换P/换清晰度/重试都会触发），每次都弹就变成"一直弹窗"
    private boolean danmakuFallbackTipShown = false;

    /**
     * 空降弹幕的样式：琥珀金 + 「[跳]」前缀 + 字号放大 15%。
     * 目的是让含时间点的弹幕一眼能从普通弹幕里分出来——否则用户根本不知道该点哪一条。
     * 颜色选琥珀金而不是应用主题粉：弹幕要压在视频画面上，需要与常见画面色都拉开对比。
     */
    private static final BiliProtobufDanmakuParser.TimestampHighlighter AIRBORNE_HIGHLIGHTER =
            new BiliProtobufDanmakuParser.TimestampHighlighter() {
                @Override
                public boolean isTimestamp(String text) {
                    return DanmakuTimestampUtil.hasTimestamp(text);
                }

                @Override
                public int color() {
                    return 0xFFFFD24D;
                }

                @Override
                public String prefix() {
                    //用纯文本前缀而不是 emoji/箭头符号：minSdk 19 的老设备上 emoji 有渲染成方块的风险
                    return "[跳]";
                }

                @Override
                public float sizeScale() {
                    return 1.15f;
                }
            };
    //周期上报节流：记录上次上报的视频位置，推进超过阈值才再报，避免 250ms tick 打爆接口
    private long lastReportedProgressMs = -1;
    //5 秒与 PiliPlus 一致（它按播放位置每 +5s 发一次心跳），服务端记录更接近"退出那一刻"
    private static final long PROGRESS_REPORT_INTERVAL_MS = 5000;
    /**
     * 番剧进度的最小上报位置（秒）。低于它的位置没有续播价值，写上去只会把服务端已有记录覆盖掉。
     *
     * <p>这条守卫来自真机诊断日志：攻壳 SAC（整季共享 aid）续播失败 → 从头播 → 用户很快退出 →
     * 上报 progress=1 把观看记录里原本 691s 的那条覆盖成了 1s，之后连"回落到观看记录"这条
     * 兜底路径也一起失效，形成不可自愈的循环。不拦的话，任何一次"打开即退出"都会破坏记录。
     */
    private static final long MIN_PGC_REPORT_SECONDS = 5;
    //兜底上报（onPause/onStop/onDestroy）去重：记录已上报的秒数，避免三连发写同一个位置
    private long lastReportedProgressSec = -1;
    //未登录只提示一次，否则每隔一个上报周期刷一条日志
    private boolean notLoggedInWarned = false;
    //进度端到端自检：播放到 25 秒时回读一次服务端，确认上报是否真的落库（诊断用，只做一次）
    private boolean diagReadbackDone = false;

    //弹幕跳转请求：弹幕尚未 prepare 时 DanmakuView.seekTo 会被丢弃，先记住位置等 prepared 回调补做
    private long pendingDanmakuSeekMs = -1;
    //弹幕校正冷却：seekDanmakuTo 会刷新它，避免 seek 期间反被拉回旧位置
    private long lastDanmakuSyncMs = 0;
    //兜底纠偏：只有"极端偏差"（>2s）且持续 3 秒（12 个 250ms tick）才动一次手，20 秒内不再纠。
    //阈值这么宽是因为每纠一次都会被用户看成"弹幕整体跳一下"——2s 以内的小偏差宁可不纠
    private static final long DANMAKU_EXTREME_DRIFT_MS = 2000;
    private static final int DANMAKU_EXTREME_CONFIRM = 12;
    private static final long DANMAKU_EXTREME_COOLDOWN_MS = 20000;
    private int danmakuExtremeTickCount = 0;
    //停顿检测：DFM 的时钟是墙钟驱动的（mTimeBase + SystemClock），画面卡住（缓冲）时它照走，
    //恢复后整场就多走一段、且不会自己回来。靠"播放器位置有没有前进"识别停顿，恢复时重 base 一次。
    private long lastDanmakuTickPos = -1;
    private long lastDanmakuTickWall = 0;
    private boolean danmakuStalled = false;
    //同步诊断日志节流：每 5 秒一条，真机上直接看两个时间差多少
    private long lastDanmakuDiagMs = 0;
    //诊断（同上日志）：上一次采样的两个位置，用来算"这段时间里各自走了多少"，
    //只有增量才看得出"弹幕时钟速度是否等于 播放器速度×倍速"
    private long lastDanmakuDiagPlayer = -1;
    private long lastDanmakuDiagDanmaku = -1;
    //seek 落点校正：ijk 的 seek 会落在目标位置前最近的关键帧上并从关键帧起播（本工程预编译
    //native 无 accurate-seek），而 seekDanmakuTo 把弹幕对齐到"请求位置"——真机实测续播 28000ms
    //时播放器实际从 24872ms 起播，弹幕因此整体超前 3.4s，且因 20s 冷却一直无人纠偏。
    //窗口期内检测到真实偏差就立即按实际落点重新对齐，不等冷却
    private static final long DANMAKU_LANDING_WINDOW_MS = 8000;
    //窗口开头的宽限期：ijkPlayer.seekTo 返回后旧解码流水线还会推进几百毫秒，这期间
    //getCurrentPosition 报的是旧流位置而非落点，不能拿去对齐。
    //提到 1200ms：DFM 的 seek 要等一次 RESUME 把 mTimeBase 重新定好，600ms 时读到的有时还是旧值
    private static final long DANMAKU_LANDING_GRACE_MS = 1200;
    //落点校正与卡顿纠偏共用的最小有效偏差：实测两个时钟差低于它时弹幕其实没偏，
    //此时 seek 只会白白清一次屏（真机观测到启动期 431ms"卡顿"的误触发）
    private static final long DANMAKU_REALIGN_MIN_DRIFT_MS = 800;
    //单次 seek 会话里落点校正的最多次数：连纠这么多次仍不收敛就判定"seek 修不掉这条偏移"
    private static final int DANMAKU_LANDING_MAX_CORRECTIONS = 2;
    //落点校正窗口截止时刻：seekDanmakuTo 里刷新，progressTimer 的 tick 里消费
    private long danmakuLandingCheckUntil = 0;
    //落点校正止损状态（真机 PJZ110 实测：弹幕时钟恒定超前 41s，每纠一次 1 秒内又回到 41s，
    //0.5~1.5 秒清一次屏 —— 说明"读到的弹幕时钟"和"seekTo 修的时钟"不是同一个源，再纠也没用）
    private int landingCorrectionCount = 0;
    private long landingLastCorrectionDiff = 0;
    private boolean landingCorrectionDisabled = false;
    //缓冲期弹幕暂停标记：只有 BUFFERING_START 真的把弹幕暂停了才置位，
    //BUFFERING_END 按这个标记恢复，而不是再看 isPlaying——两个事件的时序与
    //isPlaying 的改写交叉时，会出现"暂停了没人恢复"，表现为弹幕突然不再滚动
    private boolean danmakuPausedByBuffering = false;

    // ---------- 弹幕消失取证与自愈（2026-11-05）----------
    //现场：播放中整屏弹幕消失、不会自行恢复、手动开关一次弹幕按钮就回来。
    //开关按钮做的是 show()（清掉 DFM 的隐藏态 mIsHidden）+ seekTo()（重置 mTimeBase、重启更新线程），
    //所以"能靠开关恢复"说明数据仍在 danmakuList 里，问题出在引擎状态（暂停/隐藏/时钟停摆）。
    //心跳每 5 秒一条 W 级日志；看门狗在"播放中但弹幕时钟连续 3 秒不前进"时自动做一次同样的事。
    private static final long DM_HEARTBEAT_INTERVAL_MS = 5000;
    private static final long DM_CLOCK_STALL_MS = 3000;
    private static final long DM_WATCHDOG_COOLDOWN_MS = 60000;
    private static final int DM_WATCHDOG_MAX_TIMES = 5;
    private long dmHeartbeatAt = 0;
    private long dmClockLastMs = -1;
    private long dmClockStallSince = 0;
    private long dmWatchdogLastAt = 0;
    private int dmWatchdogCount = 0;
    //弹幕装载/渲染失败提示只弹一次，避免换P/换清晰度时连续弹窗
    private boolean danmakuRenderFailedTipShown = false;
    //当前倍速缓存：DFM 的 prepared 回调跑在弹幕线程上，那里不能去读 SeekBar（跨线程碰 View）
    private volatile float currentSpeed = 1.0f;

    // ---------- 位置驱动的弹幕投放（1.3.6 根治"弹幕整屏消失又出现"）----------
    //原先由 DFM 自己按墙钟走时间轴，再用 seek 纠偏；因为 seek 必然清屏，一旦时钟分叉就表现为
    //"消失→出现"。这里改成：出现时机由播放位置决定，DFM 只负责飘动。开关置 false 可回退旧链路。
    private static final String KEY_DANMAKU_FEED_MODE = "player_danmaku_feed_mode";
    private DanmakuFeedController danmakuFeeder;
    /** 本次弹幕装载是否走位置驱动（和 danmakuFeeder 同时置位，供不持有台账的路径做分支） */
    private volatile boolean danmakuFeedMode = false;
    /** seek/起播后需要补投一屏历史弹幕：交给下一个 tick 做，避开"清屏消息还在队列里"的竞态 */
    private volatile boolean danmakuFeedWarmup = false;
    /** 「强制为滚动弹幕」开关快照：旧链路在解析器里做，位置驱动只能在这里补同样的语义 */
    private boolean danmakuForceR2L = false;
    /** 当前生效的滚动时长因子（基础速度 × 播放倍速）：变速时要拿它算新旧比例 */
    private volatile float danmakuFactor = 1.0f;
    /** 屏蔽词原始串缓存：位置驱动是逐条判定，每次读 SharedPreferences 会拖慢 tick */
    private String blockedWordsCache;
    /** 屏蔽词切分结果，随 blockedWordsCache 同生命周期 */
    private java.util.List<String> blockedWordsParsed;

    private int screen_width, screen_height;
    /** 取流响应的真实时长（毫秒）：换P/换清晰度取流成功后回填（F4），0=未取得 */
    private long videoDurationMs = 0;
    /** 本次弹幕拉取采用的视频时长（秒）：缓存键按它分桶（F4），downdanmuNew 里赋值 */
    private int danmakuDurationSec = 0;
    private int video_width, video_height;

    private AudioManager audioManager;

    //收藏夹音频队列模式专用：音频焦点与耳机拔出处理（普通视频播放不注册，避免改变既有行为）
    private boolean audioFocusHeld = false;
    private boolean noisyReceiverRegistered = false;
    private final AudioManager.OnAudioFocusChangeListener audioFocusListener = focusChange -> runOnUiThread(() -> {
        if (destroyed || isFinishing()) return;
        //来电/其他应用抢占时暂停；AUDIOFOCUS_GAIN 不自动恢复，避免用户已手动暂停后被抢播
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS
                || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            if (isPlaying) playerPause();
            if (hasMediaSession()) updateMediaSessionPlaybackState();
        }
    });
    private final BroadcastReceiver becomingNoisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction()) && isPlaying) {
                runOnUiThread(() -> {
                    if (!destroyed) playerPause();
                });
            }
        }
    };

    private ScaleGestureDetector scaleGestureDetector;
    private ViewScaleGestureListener scaleGestureListener;
    private GestureDetector doubleTapGestureDetector;
    private float previousX, previousY;
    private boolean gesture_moved, gesture_scaled, gesture_click_disabled;
    //横滑调进度：单指水平拖动超过阈值才接管；画面被放大时让位给"拖动画布"（videoMoveBy）
    private boolean seekDragActive = false;
    private float seekDragStartX, seekDragStartY;
    private long seekDragStartPos, seekDragTargetPos, seekDragSpanMs;
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

    private TextView batteryText;
    //电量广播只在 onStart~onStop 之间注册/注销：不常驻、不轮询，也不改系统状态栏
    private boolean batteryReceiverRegistered = false;
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) return;
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level < 0 || scale <= 0) return;
            int percent = Math.round(level * 100f / scale);
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
            updateBatteryUi(percent, charging);
        }
    };

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
    //选项外层套的 ScrollView：选项一多就超出屏高，裸 LinearLayout 会把超出部分直接裁掉
    private ScrollView interactionChoiceScroll;
    //「互动选项自动竖屏」是否正由本功能临时转着竖屏（选完整要还原回横屏）
    private boolean interactionPortraitTemp = false;

    @Override
    public void onBackPressed() {
        if (SharedPreferencesUtil.getBoolean("back_disable", false)) return;
        //弹层（字幕/弹幕发送/选P/画质/查看点/倍速面板）打开时，返回键只收起弹层——
        //此前直接 super.onBackPressed() 销毁整个播放页，弹层唯一关闭途径是点遮罩（B-02）
        if (layout_card_bg != null && layout_card_bg.getVisibility() == View.VISIBLE) {
            layout_card_bg.setVisibility(View.GONE);
            card_subtitle.setVisibility(View.GONE);
            card_danmaku_send.setVisibility(View.GONE);
            card_page_selector.setVisibility(View.GONE);
            card_quality_selector.setVisibility(View.GONE);
            card_viewpoint_selector.setVisibility(View.GONE);
            return;
        }
        if (layout_speed != null && layout_speed.getVisibility() == View.VISIBLE) {
            layout_speed.setVisibility(View.GONE);
            return;
        }
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
        //取流响应的真实时长（jumpToPlayer 补传，F4）：首播路径没有 playerData 可回查，
        //缺了它 downdanmuNew 会退回 1 小时兜底 → 66 分钟后弹幕消失
        videoDurationMs = intent.getLongExtra("durationMs", 0);
        bvid = intent.getStringExtra("bvid");
        if (bvid == null) bvid = "";

        //第三方源：白名单请求头 + 来源标记。为空时下面的分支一律不生效，B 站行为不变
        thirdPartyHeaders = com.RobinNotBad.BiliClient.source.model.SourceMedia
                .headersFromIntent(intent.getStringExtra("headers"));
        isThirdPartyPlayback = intent.getBooleanExtra("third_party", false)
                || !thirdPartyHeaders.isEmpty();
        thirdDanmakuTitle = intent.getStringExtra("third_danmaku_title");
        if (thirdDanmakuTitle == null) thirdDanmakuTitle = "";
        thirdChannelKey = intent.getStringExtra("third_channel_key");
        if (thirdChannelKey == null) thirdChannelKey = "";
        thirdDanmakuEpisode = intent.getIntExtra("third_danmaku_ep", 0);

        //片头/片尾片段（取不到就是 null，跳过能力整体不生效）。档位只读一次，
        //播放页中途改设置不生效，避免一次播放内行为前后不一致
        java.util.ArrayList<ClipInfo> clipList = intent.getParcelableArrayListExtra("clipInfoList");
        //按「下标」读，与设置页写入的下标同源；默认档位统一取自 SegmentSkipMode.DEFAULT
        SegmentSkipMode clipMode = SegmentSkipMode.fromIndex(
                SharedPreferencesUtil.getInt(SegmentSkipController.KEY_MODE, SegmentSkipMode.defaultIndex()));
        clipSkipController = new SegmentSkipController(clipList, clipMode);
        Logu.d("clip-skip", "片段数=" + (clipList != null ? clipList.size() : 0) + " 档位=" + clipMode.label);

        //在线/直播判定必须早于本地续播读取：isLocalVideoForResume() 依赖这两个字段，
        //原先在读取之后赋值，导致在线视频与直播也会拿播放 URL 去拼本地目录（读不到数据，但守卫形同失效）
        isLiveMode = intent.getBooleanExtra("live_mode", false);
        isOnlineVideo = video_url.contains("http");

        progress_history = intent.getIntExtra("progress", 0);
        Logu.d("history", String.valueOf(progress_history));

        //本地缓存视频断点续播：在线视频走服务端历史，本地视频从视频目录的 .video_meta.json 读。
        //隐私模式不读不写，保持“不记录观看”语义；开关默认开。
        if (progress_history == 0 && isLocalVideoForResume()) {
            if (!SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.PRIVACY_MODE, false)
                    && SharedPreferencesUtil.getBoolean("player_local_resume", true)) {
                File folder = localVideoFolder();
                if (folder != null) {
                    progress_history = VideoMetaManager.readResumeMs(folder);
                    Logu.d("本地续播", "读到的续播位置=" + progress_history + "ms");
                }
            }
        }

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
            currentQuality = intent.getIntExtra("currentQuality", NetworkPlaybackQuality.defaultQuality(this));
        }

        return true;
    }

    @SuppressLint("SimpleDateFormat")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        //哨兵最早打点：崩溃若发生在布局加载阶段，这里是唯一还能留下线索的地方
        PlayerSentinel.beginOrContinue("进入播放页");
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
        //哨兵打点收敛到「能区分崩溃阶段」的最小集合：每调用一次 stage 就是一次 commit（同步落盘），
        //原来 8 次打点在低端机上光 fsync 就要几十毫秒，这里把相邻的快速步骤合并
        PlayerSentinel.stage("布局完成");
        findview();
        if (!getExtras()) {
            finish();
            return;
        }

        initUI();
        PlayerSentinel.stage("UI初始化完成");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.PLAYER_MEDIA_SESSION_ENABLE, false)) {
            initMediaSession();
        }

        //最经典的原生崩溃点：so 加载失败直接 SIGSEGV/SIGILL，进不了任何 Java handler
        PlayerSentinel.stage("加载ijk库");
        IjkMediaPlayer.loadLibrariesOnce(null);

        //so 加载与 new 是连续两行，中间再打一次点区分不出阶段，白多一次同步落盘
        ijkPlayer = new IjkMediaPlayer();

        //电量改由 ACTION_BATTERY_CHANGED 粘性广播更新（API 1+）：
        //原先的 BatteryManager.getIntProperty 只有 API21+ 可用，minSdk19 设备会直接隐藏电量

        loop_enabled = SharedPreferencesUtil.getBoolean("player_loop", false);
        // 从设置读取听视频模式的默认值
        isAudioOnlyMode = SharedPreferencesUtil.getBoolean("player_audio_only", false);
        // 从Intent读取是否为仅音频模式（用于播放本地音频文件）
        isLocalAudioFile = getIntent().getBooleanExtra("audio_only", false);
        if (isLocalAudioFile) {
            isAudioOnlyMode = true;
        }
        //initUI 在读取这几个默认值之前就跑过了，监听/听视频模式下 ±N 秒按钮要按最终状态再刷一次
        updateSeekButtons();
        img_loading.setImageResource(R.drawable.loading_tv_shaking);
        anim_loading = (AnimationDrawable) img_loading.getDrawable();
        anim_loading.start();

        File cachepath = getCacheDir();
        if (!cachepath.exists())
            cachepath.mkdirs();

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        //视频播放参与音频焦点管理：来电/其他应用播放时自动暂停；耳机拔出自动暂停。
        //收藏夹音频服务按产品要求不请求焦点，因此两者可以同时出声（真混音）
        requestAudioFocusForPlayback();
        registerNoisyReceiver();
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
                    //「更多」在直播下必须留着：直播发弹幕的入口（danmaku_send_btn）就在它展开的
                    //右侧菜单里。此前这里把 btn_menu 一起隐藏，等于把直播发弹幕功能藏死
                    //（用户反馈 2026-11-07：能不能加直播发弹幕——其实早就写了，只是点不到）
                    btn_menu.setVisibility(View.VISIBLE);
                    //直播没有进度/字幕，这两个入口一并收起来，右侧菜单只剩发弹幕
                    View jumpBtn = findViewById(R.id.jump_time_btn);
                    if (jumpBtn != null) jumpBtn.setVisibility(View.GONE);
                    View subBtn = findViewById(R.id.subtitle_btn);
                    if (subBtn != null) subBtn.setVisibility(View.GONE);
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
                    //隐藏完再按剩下的按钮重算一次宽度：否则行宽还按 7 个按钮算，
                    //只剩一个发弹幕按钮时会被拉成横贯屏幕的长条
                    clampQuickMenuWidth();
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
                if (isThirdPartyPlayback && (danmaku_url == null || danmaku_url.isEmpty())) {
                    //第三方源没有 cid：按番剧名+集号去外部弹幕源匹配，拿到 XML 再走同一条渲染链路
                    loadThirdPartyDanmaku();
                } else {
                    downdanmu();
                }
            } else {
                runOnUiThread(() -> btn_danmaku_send.setVisibility(View.GONE));
                //本地缓存的弹幕必须在主线程渲染：streamDanmaku 会直接操作 DanmakuView，
                //在 CenterThreadPool 的协程线程（Dispatchers.IO，线程名 DefaultDispatcher-worker-N）
                //上调用会抛 CalledFromWrongThreadException。此前只修了在线路径
                //（downdanmuOld / streamDanmakuOnUi），这条本地分支被漏掉，表现为
                //「播放缓存视频弹错误框、停在装填弹幕中且进度 00:00/00:00」——
                //因为异常把同一个 lambda 后面的 setDisplay() 一起中断了。
                File localDanmaku = (danmaku_url == null || danmaku_url.isEmpty()) ? null : new File(danmaku_url);
                danmakuFile = localDanmaku;
                if (localDanmaku != null && localDanmaku.exists()) {
                    //再包一层 try/catch：弹幕文件损坏只该"没有弹幕"，不该让整段加载失败
                    runOnUiThread(() -> {
                        try {
                            streamDanmaku(localDanmaku.toString());
                        } catch (Throwable t) {
                            hasDanmaku = false;
                            Logu.e("本地弹幕", "渲染失败: " + t);
                        }
                    });
                } else {
                    hasDanmaku = false;
                }
            }

            if (!destroyed && SharedPreferencesUtil.getBoolean("player_subtitle_autoshow", true))
                downSubtitle(false);

            //高能进度条不在这里算：它依赖已解析的弹幕，由 streamDanmaku 在解析完成时调用
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
        //第三方源没有 B 站 cid，发弹幕必然失败：直接把入口藏掉，避免用户白打一段字
        if (isThirdPartyPlayback && btn_danmaku_send != null)
            btn_danmaku_send.setVisibility(View.GONE);
        clipSkipCard = findViewById(R.id.clip_skip_card);
        clipSkipText = findViewById(R.id.clip_skip_text);
        //手动跳过档位用：点了才跳，不自动干预播放
        if (clipSkipCard != null) clipSkipCard.setOnClickListener(v -> {
            if (clipSkipController == null) return;
            long target = clipSkipController.skipNow(video_now);
            if (target > 0) {
                seekToPosition(target);
                Logu.w("clip-skip", "手动跳过至 " + target + "ms");
            }
        });
        seekbar_progress = findViewById(R.id.videoprogress);
        loading_text0 = findViewById(R.id.loading_text0);
        loading_text1 = findViewById(R.id.loading_text1);
        text_title = findViewById(R.id.text_title);
        text_volume = findViewById(R.id.showsound);
        layout_video = findViewById(R.id.videoArea);
        mDanmakuView = findViewById(R.id.sv_danmaku);
        batteryText = findViewById(R.id.battery_text);

        text_speed = findViewById(R.id.text_speed);
        layout_speed = findViewById(R.id.layout_speed);
        seekbar_speed = findViewById(R.id.seekbar_speed);
        text_newspeed = findViewById(R.id.text_newspeed);
        layout_volume = findViewById(R.id.layout_volume);
        seekbar_volume = findViewById(R.id.seekbar_volume);
        text_newvolume = findViewById(R.id.text_newvolume);
        bottom_buttons = findViewById(R.id.bottom_buttons);
        button_seek_back = findViewById(R.id.button_seek_back);
        button_seek_forward = findViewById(R.id.button_seek_forward);
        left_control = findViewById(R.id.left_control);
        btn_debug = findViewById(R.id.btn_debug);

        //无极调音：恢复上次的增益，并把 0~100 的滑条初始位置对上
        volumeGain = Math.max(0f, Math.min(1f, SharedPreferencesUtil.getFloat("player_volume_gain", 100f) / 100f));
        if (seekbar_volume != null) seekbar_volume.setProgress(Math.round(volumeGain * 100));
        if (text_newvolume != null) text_newvolume.setText(Math.round(volumeGain * 100) + "%");

        //±N 秒按钮：文案跟随「快进快退秒数」设置，显隐跟随「显示快进快退按钮」；直播没有可 seek 的进度条
        updateSeekButtons();

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
                //横滑调进度优先：被它接管时后续的缩放/移动/单击都不再处理
                if (handleSeekDrag(event)) return false;
                int action = event.getActionMasked();
                int pointerCount = event.getPointerCount();
                boolean singleTouch = pointerCount == 1;
                boolean doubleTouch = pointerCount == 2;

                // Logu.v("gesture", event.getEventTime() + "");
                scaleGestureDetector.onTouchEvent(event);

                //空降弹幕：干净的单击命中含时间点的弹幕时跳转，并吞掉这次单击（不切控制层）
                if (action == MotionEvent.ACTION_UP && !gesture_moved && !gesture_scaled
                        && tryAirborneDanmaku(event)) {
                    gesture_click_disabled = true;
                    return false;
                }
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
                            applySpeed(normalSpeed, "长按加速结束");
                            text_speed.setText(speed_strs[seekbar_speed.getProgress()]);
                            //松手也要给反馈：3x 期间控制栏是隐藏的，用户看不到倍速文字变化（1.3.4 反馈）
                            flashCenterText("已恢复 " + speed_strs[seekbar_speed.getProgress()], 1200);
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
                //横滑调进度（不允许缩放的分支）：同样优先接管
                if (handleSeekDrag(motionEvent)) return false;
                //空降弹幕：同上，这里是不允许缩放时的分支
                if (motionEvent.getAction() == MotionEvent.ACTION_UP && !gesture_moved && !gesture_scaled
                        && tryAirborneDanmaku(motionEvent)) {
                    gesture_click_disabled = true;
                    return false;
                }
                if (motionEvent.getAction() == MotionEvent.ACTION_UP && onLongClick) {
                    onLongClick = false;
                    float normalSpeed = speed_values[seekbar_speed.getProgress()];
                    applySpeed(normalSpeed, "手势加速结束");
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
                    applySpeed(3.0f, "长按加速开始");
                    text_speed.setText("x 3.0");
                    //控制栏已被 hidecon 收起，倍速文字跟着一起看不见，必须在屏幕中间补一条提示
                    //（用户反馈 2026-11-08：长按倍速时没有任何反馈，不知道生没生效）
                    flashCenterText("3x 加速中，松手恢复", 1200);
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

    // ---------------------------------------------------------------- 横滑调进度

    /**
     * 单指左右滑动调进度。
     *
     * <p>返回 true 表示这次事件已被进度拖动接管，调用方不要再走缩放/移动/单击那一套。
     *
     * <p>接管条件：单指、未放大画面、不是长按 3x、不是双指缩放；水平位移先超过 8dp，
     * 且横向位移要达到纵向的 1.5 倍（防止斜着滑时误触）。放大画面后单指拖动仍然是拖动画布，
     * 否则"缩放后想挪画面"就没法用了。
     *
     * <p>按下时记住当前播放位置，拖动中只更新提示文字，**松手才真正 seek**——
     * 边拖边 seek 会让播放器不停重建缓冲，手表上直接卡成幻灯片。
     */
    private boolean handleSeekDrag(MotionEvent event) {
        if (!SharedPreferencesUtil.getBoolean("player_swipe_seek", true)) return false;
        if (isLiveMode || !isPrepared || ijkPlayer == null) return false;
        final int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            seekDragActive = false;
            seekDragStartX = event.getX(0);
            seekDragStartY = event.getY(0);
            seekDragStartPos = video_now;
            return false;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            if (event.getPointerCount() != 1) {    //中途多指：退出拖动状态，交给缩放分支
                if (seekDragActive) {
                    seekDragActive = false;
                    hideVolume.run();
                }
                return false;
            }
            if (onLongClick || gesture_scaled) return false;
            float dx = event.getX(0) - seekDragStartX;
            float dy = event.getY(0) - seekDragStartY;
            if (!seekDragActive) {
                float slop = ToolsUtil.dp2px(8f);
                if (Math.abs(dx) < slop || Math.abs(dx) < Math.abs(dy) * 1.5f) return false;
                if (isVideoZoomed()) return false;
                seekDragActive = true;
                gesture_click_disabled = true;   //别让松手后的那次单击把控制栏切走
                layout_control.removeCallbacks(hidecon);
                seekDragSpanMs = computeSeekSpanMs();
                Logu.d("gesture", "横滑调进度开始 pos=" + seekDragStartPos + " span=" + seekDragSpanMs);
            }
            long duration = currentDurationMs();
            long target = seekDragStartPos
                    + (long) (dx / Math.max(1f, layout_control.getWidth()) * seekDragSpanMs);
            if (target < 0) target = 0;
            if (duration > 0 && target > duration) target = duration;
            seekDragTargetPos = target;
            showSeekDragHint(duration);
            return true;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (!seekDragActive) return false;
            seekDragActive = false;
            if (action == MotionEvent.ACTION_UP) {
                seekToPosition(seekDragTargetPos);
                showCenterText("已跳转到 " + StringUtil.toTime((int) (seekDragTargetPos / 1000)));
                Logu.d("gesture", "横滑调进度结束 target=" + seekDragTargetPos);
            } else {
                hideVolume.run();
            }
            gesture_click_disabled = true;
            autohideReset();
            return true;
        }
        return seekDragActive;
    }

    /** 满屏宽度对应的可拖动时长：短视频整屏就是全片，长视频按 1/6 且夹在 30s~300s，再乘灵敏度档位 */
    private long computeSeekSpanMs() {
        long duration = currentDurationMs();
        long base;
        if (duration <= 0) {
            base = 60000L;
        } else {
            base = Math.max(30000L, Math.min(300000L, duration / 6));
        }
        //灵敏度在设置页是 input_float（putFloat 写入），必须用 getFloat 读：
        //getString 读 float 存档会抛 ClassCastException，横滑一触发就崩（1.2.9 实测）
        float scale = PlayerSeekPolicies.swipeScale(
                SharedPreferencesUtil.getFloat(PlayerSeekPolicies.KEY_SWIPE_SEEK_SCALE, PlayerSeekPolicies.DEFAULT_SCALE));
        return (long) (base * scale);
    }

    private long currentDurationMs() {
        long duration = video_all;
        if (duration <= 0 && ijkPlayer != null) {
            try {
                duration = ijkPlayer.getDuration();
            } catch (Throwable ignored) {
            }
        }
        return Math.max(duration, 0);
    }

    /** 是否是本地缓存视频（非在线、非直播、非本地音频文件），只有这类才走本地续播。 */
    private boolean isLocalVideoForResume() {
        //isLocalAudioFile 在 onCreate 里才赋值，而本方法在 getExtras（更早）就会被调用，
        //所以这里直接读 intent 里的 audio_only，避免本地音频文件被当成视频写进度
        return !isOnlineVideo && !isLiveMode && !isLocalAudioFile
                && !getIntent().getBooleanExtra("audio_only", false)
                && video_url != null && !video_url.isEmpty();
    }

    /** 本地视频所在目录，用于读写 .video_meta.json；取不到返回 null。 */
    private File localVideoFolder() {
        if (video_url == null || video_url.isEmpty()) return null;
        File file = new File(video_url);
        File parent = file.getParentFile();
        return parent != null ? parent : null;
    }

    /** 退出/切后台时保存本地视频进度；隐私模式或开关关闭时不写。 */
    private void saveLocalProgressNow() {
        if (!isLocalVideoForResume()) return;
        if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.PRIVACY_MODE, false)) return;
        if (!SharedPreferencesUtil.getBoolean("player_local_resume", true)) return;
        if (ijkPlayer == null || !isPrepared) return;
        File folder = localVideoFolder();
        if (folder == null) return;
        long position = video_now;
        long duration = currentDurationMs();
        CenterThreadPool.run(() -> VideoMetaManager.saveProgress(folder, position, duration));
    }

    /** 画面是否被放大过（放大后单指拖动要留给"拖动画布"） */
    private boolean isVideoZoomed() {
        return layout_video != null
                && (layout_video.getScaleX() > 1.02f || layout_video.getScaleY() > 1.02f);
    }

    /** 拖动中的提示：目标时间 / 总时长（+/- 秒数） */
    private void showSeekDragHint(long duration) {
        if (text_volume == null) return;
        long delta = seekDragTargetPos - seekDragStartPos;
        String arrow = delta >= 0 ? "快进 " : "快退 ";
        String text = arrow + StringUtil.toTime((int) (seekDragTargetPos / 1000));
        if (duration > 0) text += " / " + StringUtil.toTime((int) (duration / 1000));
        text += "（" + (delta >= 0 ? "+" : "-") + Math.abs(delta) / 1000 + "s）";
        text_volume.setText(text);
        text_volume.setVisibility(View.VISIBLE);
        text_volume.removeCallbacks(hideVolume);
    }

    /** 中间闪一条提示（1.2 秒后自动消失），用于松手后的"已跳转到 xx" */
    private void showCenterText(String text) {
        if (text_volume == null) return;
        text_volume.setText(text);
        text_volume.setVisibility(View.VISIBLE);
        text_volume.removeCallbacks(hideVolume);
        text_volume.postDelayed(hideVolume, 1200);
    }

    /**
     * “跳到指定时间”：弹出输入框，输入秒数或 mm:ss / hh:mm:ss 后直接 seek。
     *
     * <p>直播没有时间点，不显示入口；时长未知时也不让跳，避免跳到非法位置。
     */
    private void showJumpTimeDialog() {
        if (isLiveMode || !isPrepared || ijkPlayer == null) {
            MsgUtil.showMsg("当前视频不支持跳转到指定时间");
            return;
        }
        long duration = currentDurationMs();
        if (duration <= 0) {
            MsgUtil.showMsg("还没获取到视频时长，稍后再试");
            return;
        }
        EditText input = new EditText(this);
        input.setHint(PlayerSeekPolicies.jumpHint(duration));
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        AdaptiveDialog.Builder builder = new AdaptiveDialog.Builder(this)
                .setTitle("跳到指定时间")
                .setMessage("输入秒数或 mm:ss / hh:mm:ss")
                .setView(input)
                .setPositiveButton("跳转", (dialog, which) -> {
                    Long seconds = PlayerSeekPolicies.parseJumpSeconds(input.getText().toString());
                    if (seconds == null) {
                        MsgUtil.showMsg("时间格式不正确");
                        return;
                    }
                    Long targetMs = PlayerSeekPolicies.clampJumpMs(seconds * 1000L, duration);
                    if (targetMs == null) {
                        MsgUtil.showMsg("当前视频不支持跳转");
                        return;
                    }
                    seekToPosition(targetMs);
                    showCenterText("已跳转到 " + StringUtil.toTime((int) (targetMs / 1000)));
                })
                .setNegativeButton("取消", null);
        builder.show();
        autohideReset();
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
        //左侧纵列（±N 秒 + 倍速）跟控制栏一起显隐：点一下画面出来、再点收回，
        //不再常驻挡在画面上（原来是独立于控制栏的常驻元素）
        View[] controls = {right_control, layout_top, bottom_buttons, seekbar_progress, left_control};
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
        if (screen_round) {
            text_progress.setGravity(Gravity.NO_GRAVITY);
            text_progress.setPadding(ToolsUtil.dp2px(24f), 0, 0, 0);
            if (onlineTimer != null)
                text_online.setVisibility(View.VISIBLE);
        }

        autohideReset();
    }

    /** 电量 UI：只显示百分比（充电时追加 +；不引入新权限，也不做后台轮询）。
     *  电池图形已删（用户反馈 2026-11-07：时间/图形/数字叠在一起） */
    @SuppressLint("SetTextI18n")
    private void updateBatteryUi(int percent, boolean charging) {
        if (batteryText != null) {
            batteryText.setText(percent + "%" + (charging ? "+" : ""));
        }
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

    // ---------------------------------------------------------------- 回前台画面恢复

    /** 把某个 SurfaceHolder 重新挂到播放器上；实例可能已被释放，所有入口都做空值/异常保护。 */
    private void attachSurface(SurfaceHolder holder) {
        if (destroyed || ijkPlayer == null || holder == null) return;
        try {
            ijkPlayer.setDisplay(holder);
        } catch (Throwable t) {
            Logu.e("surface", "重新挂载 surface 失败：" + t);
        }
    }

    /**
     * 暂停态下把当前帧重画一遍。
     *
     * <p>切后台回来时 surface 被销毁重建、播放器还停在暂停态：读线程不跑，谁都不会去画新 surface，
     * 于是整块画面黑着（声音和进度都正常）。这里用 seek 到「当前位置 ±1ms」逼解码器从关键帧重解一次，
     * 帧就会重新吐到新 surface 上。偏移 1ms 是为了绕开部分 ijk 版本对"位置没变就不做"的 seek 优化。
     */
    private void repaintPausedFrame() {
        if (destroyed || ijkPlayer == null || !isPrepared || isPlaying || isLiveMode) return;
        long duration = video_all > 0 ? video_all : ijkPlayer.getDuration();
        long target = video_now;
        if (duration > 0 && target < duration - 1) target += 1;
        else if (target > 1) target -= 1;
        try {
            ijkPlayer.seekTo(target);
            Logu.v("surface", "暂停态强制重画：" + target);
        } catch (Throwable t) {
            Logu.e("surface", "强制重画失败：" + t);
        }
    }

    /**
     * 回到前台后的画面自愈：surface 若在后台被销毁，重新挂载 + 强解一帧。
     * 由 onResume 延迟 250ms 调起，等窗口重新布局完再挂，避免挂到尺寸还是 0 的窗口上。
     */
    private void recoverSurfaceAfterResume() {
        if (destroyed || ijkPlayer == null || !isPrepared) return;
        if (isAudioOnlyMode) return;
        try {
            if (textureView != null) {
                if (mSurfaceTexture != null) {
                    Surface surface = new Surface(mSurfaceTexture);
                    Surface oldSurface = ijkSurface;
                    ijkSurface = surface;
                    ijkPlayer.setSurface(surface);
                    if (oldSurface != null) oldSurface.release();
                }
            } else if (surfaceView != null) {
                SurfaceHolder holder = surfaceView.getHolder();
                if (holder != null && holder.getSurface() != null && holder.getSurface().isValid()) {
                    attachSurface(holder);
                }
            }
        } catch (Throwable t) {
            Logu.e("surface", "回前台重挂 surface 失败：" + t);
        }
        repaintPausedFrame();
    }

    private void setDisplay() {
        PlayerSentinel.stage("配置显示");
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
                                        attachSurface(surfaceHolder);
                                        //暂停态下 surface 重建后画面是黑的：读线程停着不会吐帧，
                                        //这里让解码器按当前进度重解一帧，把暂停帧画回来
                                        repaintPausedFrame();
                                    }
                                }

                                @Override
                                public void surfaceChanged(@NonNull SurfaceHolder surfaceHolder, int format, int width, int height) {
                                    //真正可用的窗口尺寸要等这里才有。只在 surfaceCreated 里挂载时，
                                    //解码器可能绑在一个 0×0 的窗口上，表现就是有声音没画面
                                    if (!destroyed) attachSurface(surfaceHolder);
                                }

                                @Override
                                public void surfaceDestroyed(@NonNull SurfaceHolder surfaceHolder) {
                                    Logu.v("surface", "Holder没了");
                                    if (destroyed) return;
                                    //记下"surface 是在后台丢的"，回前台时据此决定要不要补一次重画
                                    surfaceLostWhileBackground = true;
                                    if (isPrepared && ijkPlayer != null) ijkPlayer.setDisplay(null);
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

        //重试路径恢复加载占位：错误态曾把转圈藏掉、文案换成错误提示，每次重新载入统一复位（X-08）
        runOnUiThread(() -> {
            loading_text0.setText(isLiveMode ? "载入直播中" : "载入视频中");
            View circle = findViewById(R.id.circle);
            if (circle != null) circle.setVisibility(View.VISIBLE);
        });
        if (isLiveMode) {
            danmuSocketConnect();
        }
        PlayerSentinel.stage("设置数据源");
        try {
            if (isOnlineVideo) {
                String playUrl = upgradeMediaUrlToHttps(nowurl);
                //请求头统一走 PlayerApi.mediaHeadersFor：登录 Cookie 只发给 B 站自有媒体域，
                //第三方 PCDN 地址不再携带 SESSDATA/bili_jct（下载链路一直这么做，播放器此前遗漏）
                //第三方番剧源则用解析阶段得到的白名单头（Referer/UA/Origin/规则 Cookie），
                //绝不把 B 站登录态发给外站
                java.util.Map<String, String> headers = thirdPartyHeaders.isEmpty()
                        ? PlayerApi.mediaHeadersFor(playUrl)
                        : thirdPartyHeaders;
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
                    userPaused = true;
                    if (hasDanmaku && mDanmakuView != null) {
                        mDanmakuView.pause();
                    }
                    btn_control.setImageResource(R.drawable.btn_player_play);
                    return;
                }
            }
            
            if (loop_enabled) {
                ijkPlayer.seekTo(0);
                seekDanmakuTo(0L, "LOOP");
                ijkPlayer.start();
            } else if (auto_next_enabled && hasMultiplePages() && currentPageIndex < pagenames.size() - 1) {
                switchToPage(currentPageIndex + 1);
            } else {
                isPlaying = false;
                userPaused = true;   //播完之后再横滑：弹幕不能跟着跑起来
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
                if (hasMediaSession()) {
                    updateMediaSessionPlaybackState();
                }
            }
        });

        ijkPlayer.setOnErrorListener((iMediaPlayer, what, extra) -> {
            if (iMediaPlayer != thisPlayer) return true;
            String EReport = "播放器可能遇到错误！\n错误码：" + what + "\n附加：" + extra;
            //日志里补上地址摘要（不带 query/token）与来源标记，toast 文案保持原样不变
            Logu.e("ijk-err", EReport
                    + " | 第三方=" + isThirdPartyPlayback
                    + " | url=" + com.RobinNotBad.BiliClient.source.resolve.MediaUrls.summarize(video_url)
                    + " | 头=" + thirdPartyHeaders.keySet());
            //第三方线路实测播不了：自动屏蔽，返回选集页后这条不会再出现，也不再为它等超时。
            //屏蔽有 12h 期限且可在选集页「恢复」，一次 CDN 抖动不会永久丢线路。
            if (isThirdPartyPlayback && !thirdChannelKey.isEmpty()) {
                com.RobinNotBad.BiliClient.source.SourceChannelHealth.get()
                        .notePlaybackFailure(thirdChannelKey);
            }
            //原来返回 false，错误会继续走到 onCompletion 被当成“播放完毕”，用户会误以为视频播完了；
            //改为标记错误态并提示，点播放按钮重新载入重试（controlVideo 里分发）
            playerError = true;
            if (!destroyed) {
                MsgUtil.showMsgLong(EReport);
                runOnUiThread(() -> {
                    isPlaying = false;
                    userPaused = true;
                    //错误态可视化：收起转圈、换成常驻错误文案——
                    //此前只有一闪而过的 toast，首轮 prepare 失败时「载入视频中」转圈永挂、
                    //控制栏 4s 自动隐藏后连重试入口都看不见（X-08）
                    loading_info.setVisibility(View.VISIBLE);
                    anim_loading.stop();
                    View circle = findViewById(R.id.circle);
                    if (circle != null) circle.setVisibility(View.GONE);
                    //第三方源没有「切清晰度重取流」这条退路，失败就是这条线路不通：
                    //文案要直接指到「返回换线路」，否则用户只能反复点重试
                    loading_text0.setText(isThirdPartyPlayback
                            ? "这条线路播不了\n返回上一页可换线路，或点播放键重试"
                            : "播放出错\n点屏幕中央的播放键重试");
                    if (isThirdPartyPlayback && !thirdPartyErrorHintShown) {
                        thirdPartyErrorHintShown = true;
                        MsgUtil.showMsgLong("该线路播放失败，返回上一页可以换线路");
                    }
                    if (hasDanmaku && mDanmakuView != null) {
                        mDanmakuView.pause();
                    }
                    btn_control.setImageResource(R.drawable.btn_player_play);
                    showcon();
                    if (hasMediaSession()) {
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
                            Logu.w("弹幕控制", "pause(缓冲开始) 弹幕时钟=" + safeDanmakuTime());
                            mDanmakuView.pause();
                            danmakuPausedByBuffering = true;
                        }
                    });
                } else if (what == IMediaPlayer.MEDIA_INFO_BUFFERING_END) {
                    runOnUiThread(() -> {
                        if (loadingTimer != null)
                            loadingTimer.cancel();
                        loading_info.setVisibility(View.GONE);
                        anim_loading.stop();
                        //按 START 时记下的标记恢复，不能只看 isPlaying：
                        //START 在播、END 时 isPlaying 已被其它回调改写时，弹幕会永久停住
                        if (hasDanmaku && mDanmakuView != null && danmakuPausedByBuffering) {
                            danmakuPausedByBuffering = false;
                            Logu.w("弹幕控制", "resume(缓冲结束) 弹幕时钟=" + safeDanmakuTime());
                            mDanmakuView.resume();
                            //resume 会把绘制线程拉起来，用户已暂停时要再压回去
                            pauseDanmakuIfUserPaused();
                        } else if (hasDanmaku && mDanmakuView != null) {
                            //END 到了却没有暂停标记：说明 START 时没暂停，或标记被 playerPause/playerResume 清掉了。
                            //后者会让弹幕永久停在暂停态，是"弹幕消失"的重点嫌疑，必须留证
                            Logu.w("弹幕控制", "缓冲结束但无暂停标记，不 resume；缓冲暂停=" + danmakuPausedByBuffering
                                    + " 弹幕时钟=" + safeDanmakuTime());
                        }
                    });
                }

                return false;
            });

        ijkPlayer.setScreenOnWhilePlaying(true);
        ijkPlayer.prepareAsync();
        PlayerSentinel.stage("已提交准备");
        Logu.v("开始准备");
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onPrepared(IMediaPlayer mediaPlayer) {
        //播放视频时暂停后台音乐（1.3.2 反馈：视频一起放音乐是刻意设计，按约定加开关、默认开）。
        //放在 onPrepared：真的要出声了才动音乐；isPlaying 兜住"本来就没在放"的情况，toggle 不会误开
        if (SharedPreferencesUtil.getBoolean("player_pause_bg_music", true)
                && AudioPlayerService.isPlaying()) {
            AudioPlayerService.toggle(this);
        }
        //旧实例的滞后回调：它的 release 已在 player-ops 排队，直接忽略，防止旧事件作用到新实例
        if (mediaPlayer != ijkPlayer) return;
        if (destroyed) {
            retirePlayer();
            return;
        }

        isPrepared = true;
        //新实例准备完就自动开播（本方法末尾会 start()），暂停态已不复存在：
        //不重置的话，上一段播放里按下的暂停会让新实例刚 prepared 就把弹幕压住（声音在走、弹幕不动）
        userPaused = false;
        pendingPausedSeekMs = -1;
        //新实例不继承上一段播放的缓冲暂停标记，否则首帧后会被误 resume/pause
        danmakuPausedByBuffering = false;
        PlayerSentinel.stage("首帧就绪");
        //换清晰度/切听视频会复用同一个 video_now 字段：重建期间它还是旧实例的位置，
        //不清掉的话新实例刚 prepared 的头 250ms 里，updateTimer 会拿旧位置把弹幕拽过去、
        //再被新位置拽回来（两次可见跳变）。这里连同"变化检测"一起复位
        video_now = 0;
        video_now_last = -1;
        video_all = (int) ijkPlayer.getDuration();

        changeVideoSize();

        //新实例必须重新套一遍软件增益：切清晰度/换P/听视频模式都会走这里重建播放器
        applyVolumeGain();

        //记住倍速：上个会话手动调过速的话，新实例（含换P/换清晰度重建）自动应用；
        //长按 3x 属临时加速不走这里，不会污染记忆值；直播流不应用倍速（倍速按钮在直播本来就隐藏）
        if (!isLiveMode && SharedPreferencesUtil.getBoolean("player_speed_remember", false)) {
            float savedSpeed = SharedPreferencesUtil.getFloat("player_speed_value", 1.0f);
            for (int i = 0; i < speed_values.length; i++) {
                if (speed_values[i] == savedSpeed && i != 2) {
                    ijkPlayer.setSpeed(speed_values[i]);
                    currentSpeed = speed_values[i];
                    //位置驱动模式下弹幕时钟恒 1.0；此时 streamDanmaku 可能还没跑（mContext 为 null），
                    //滚动速度因子由 prepared 回调统一补设，所以这里只管旧的时钟驱动链路
                    if (mDanmakuView != null && !isDanmakuFeedMode())
                        mDanmakuView.setSpeed(speed_values[i]);
                    text_speed.setText(speed_strs[i]);
                    seekbar_speed.setProgress(i);   //fromUser=false，监听器不会重复应用
                    break;
                }
            }
        }

        if ((isLiveMode || hasDanmaku) && mDanmakuView != null) {
            startDanmakuEngine(-1);
        }
        if (SharedPreferencesUtil.getBoolean("player_ui_showDanmakuBtn", true)) {
            isDanmakuVisible = !SharedPreferencesUtil.getBoolean("pref_switch_danmaku", true);
            btn_danmaku.setOnClickListener(view -> {
                if (mDanmakuView == null)
                    return;
                if (isDanmakuVisible) {
                    Logu.w("弹幕控制", "hide(用户点关闭弹幕) 弹幕时钟=" + safeDanmakuTime());
                    mDanmakuView.hide();
                } else {
                    Logu.w("弹幕控制", "show(用户点开启弹幕) 弹幕时钟=" + safeDanmakuTime());
                    mDanmakuView.show();
                    //DFM 在隐藏期间时钟停走，重新显示时必须按当前播放位置重新对齐，否则弹幕会整体错位
                    if (isPrepared && ijkPlayer != null) {
                        seekDanmakuTo(video_now, "SHOW");   //同上：主线程读原生锁有卡死风险
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
                seekDanmakuTo(progress_history, "START");
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

        //弱网下 onPrepared 可能迟到：此时页面可能已经 onPause（用户离开/熄屏）。
        //无条件 start 会变成"后台/熄屏后开始出声"，与 playerPause 的暂停策略相悖（B-01）。
        //仅在前台（或用户主动开启后台播放）且没有主动暂停时才自动起播；
        //被拦下的，回前台由用户点播放。isPlaying/按钮态在这里回正，别等下一次 tick。
        if ((!inBackground || SharedPreferencesUtil.getBoolean("player_background", false)) && !userPaused) {
            ijkPlayer.start();
        } else {
            isPlaying = false;
            btn_control.setImageResource(R.drawable.btn_player_play);
        }

        if (hasMediaSession()) {
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
                        maybeSkipClipSegment(video_now);
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

                        if (hasMediaSession()) {
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
     * 番剧片头/片尾跳过判定，挂在既有的 250ms 进度 tick 上（<b>不新增定时器</b>）。
     *
     * <p>片段数据来自取流响应里的 {@code clip_info_list}（B 站官方下发，随响应一次返回），
     * 因此<b>零新增网络请求</b>，也不依赖任何第三方服务。
     *
     * <p>所有 seek 与 UI 更新都 post 到主线程执行，与其他 seek 调用点保持一致。
     * 跳过目标位置由 {@link SegmentSkipController} 按档位决定，控制器自带冷却
     * （seek 需要时间才到位，期间 tick 仍会读到区间内旧位置，不冷却会连续 seek 造成抖动）。
     */
    private void maybeSkipClipSegment(int positionMs) {
        final SegmentSkipController controller = clipSkipController;
        if (controller == null || !controller.isEnabled() || isLiveMode) return;
        //总时长要等 onPrepared 之后才知道，这里每次 tick 同步一次（幂等、无额外开销）；
        //控制器用它剔除"结束位置已经超出片长"的脏片段，避免跳过去直接把播放推到结尾
        if (video_all > 0) controller.setDurationMs(video_all);

        final long target = controller.onTick(positionMs);
        final ClipInfo manual = controller.manualButtonSegment(positionMs);
        final ClipInfo hint = controller.showOnlyHint(positionMs);
        final boolean hintNow = hint != null && hint.endMs() != clipHintShownForMs;
        if (hintNow) clipHintShownForMs = hint.endMs();

        final boolean wantCard = manual != null;
        //状态没变化、也没有跳转与提示时直接返回：不给主线程投无意义的任务
        if (wantCard == clipCardShown && target <= 0 && !hintNow) return;
        clipCardShown = wantCard;

        runOnUiThread(() -> {
            if (clipSkipCard != null) clipSkipCard.setVisibility(wantCard ? View.VISIBLE : View.GONE);
            if (wantCard && manual != null && clipSkipText != null)
                clipSkipText.setText("跳过" + manual.displayName());
            if (hintNow && hint != null) MsgUtil.showMsg("即将进入" + hint.displayName() + "（仅显示，不自动跳过）");
            if (target > 0) {
                seekToPosition(target);
                Logu.w("clip-skip", "自动跳过至 " + target + "ms");
            }
        });
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
        //默认关：与设置页开关的默认值保持一致（原来这里是 true，导致设置页显示"关闭"
        //但回读自检实际在跑，白付 4 个额外请求）
        if (!SharedPreferencesUtil.getBoolean("diag_readback", false)) return;
        if (video_now < 25000) return;
        diagReadbackDone = true;
        final long fAid = aid, fCid = cid, fEpid = epid, fSeasonId = seasonId;
        final int fSeasonType = seasonType;   //回读必须与取流用同一 season_type，否则非番剧分区会假阴性
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
                        ? PlayerApi.queryPgcWatchProgress(fAid, fCid, fEpid, fSeasonId, fSeasonType) : null;
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
        if (cid == 0) {
            Logu.e("进度上报", "跳过：cid 缺失 cid=0");
            return false;
        }
        //aid 缺失不再一票否决：番剧走的是带 epid/sid 的心跳接口，aid 只是附带字段，
        //只有"既没有 aid 也没有 epid"时才真的无从定位这条稿件
        if (aid == 0 && epid == 0) {
            Logu.e("进度上报", "跳过：aid/epid 均缺失（aid=0 epid=0 cid=" + cid + "）");
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
        //番剧：过小的位置不上报，避免把服务端已有的较大进度覆盖成"刚开头"。
        //只拦"有值但过小"的情况：PROGRESS_FINISHED(-1) 与 0 仍走各自原有的分支
        if (epid != 0 && progressSec > 0 && progressSec < MIN_PGC_REPORT_SECONDS) {
            Logu.w("进度上报", "跳过：番剧位置过小 progress=" + progressSec
                    + "s（避免覆盖服务端已有记录）");
            return;
        }
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
                //播放页不该因为一次后台上报失败就弹「网络错误」：播放流走 ijk，上报走 OkHttp，
                //两者互不影响，用户看到的是"视频明明在放却说网络错误"（1.3.5 反馈 R4）
                Logu.w("进度上报", "上报失败（不打扰用户）：" + e);
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
                        //在看人数只是装饰信息，拉不到就隐藏，不弹「网络错误」（R4）
                        Logu.w("在看人数", "拉取失败（不打扰用户）：" + e);
                        runOnUiThread(() -> text_online.setVisibility(View.GONE));
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
            //措辞准确：这里失败只影响字幕，不代表网络不可用（R4）
            Logu.w("字幕", "加载字幕内容失败：" + e);
            MsgUtil.showMsg("字幕加载失败");
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
            //字幕列表拉不到：只提示字幕相关，不误导成"网络错误"（R4）
            Logu.w("字幕", "获取字幕列表失败：" + e);
            MsgUtil.showMsg("字幕获取失败");
        }
    }

    private void downdanmu() {
        if (danmaku_url == null || danmaku_url.isEmpty()) {
            //这条分支以前完全静默：换P时若取流没带回弹幕地址，用户看到的就是"这个视频没有弹幕"
            Logu.w("弹幕装载", "danmaku_url 为空，跳过装载；cid=" + cid + " hasDanmaku=" + hasDanmaku);
            return;
        }

        boolean useNewApi = SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.NEW_DANMAKU_API, true);

        if (useNewApi) {
            downdanmuNew();
        } else {
            downdanmuOld();
        }
    }

    /**
     * 第三方源弹幕：按「番剧名 + 集号」去弹弹play 匹配 → 转 B 站 XML → 走同一条渲染链路。
     *
     * <p>失败对用户静默：没凭据/没匹配到/网络失败都只是「没有弹幕」，播放与其它功能不受影响。
     * 但日志一律走 W 级，否则真机上「为什么这集没弹幕」无迹可查（正式包 D 级不输出）。
     */
    private void loadThirdPartyDanmaku() {
        if (thirdDanmakuTried) return;
        thirdDanmakuTried = true;
        fetchThirdPartyDanmaku(false);
    }

    /**
     * 真正去拉第三方弹幕，拉到了就装载并启动引擎。
     *
     * @param retry 是否为首次失败后的补拉；补拉只做一次
     */
    private void fetchThirdPartyDanmaku(boolean retry) {
        if (thirdDanmakuTitle.isEmpty()
                || !com.RobinNotBad.BiliClient.source.danmaku.ThirdPartyDanmaku.available()) {
            Logu.w("third-danmaku", "跳过：标题为空或未配置弹弹play凭据 title=" + thirdDanmakuTitle);
            return;
        }
        final String title = thirdDanmakuTitle;
        final int episode = thirdDanmakuEpisode;
        CenterThreadPool.run(() -> {
            final java.io.File file = com.RobinNotBad.BiliClient.source.danmaku.ThirdPartyDanmaku
                    .fetch(getApplicationContext(), title, episode);
            if (file == null) {
                //这条请求与取流是并发发起的，起播前后正是网络最紧张的时候，首次失败更可能是
                //超时而不是"这集真没有弹幕"；不补拉的话用户看到的就是"这场没弹幕"
                if (!retry && !destroyed) {
                    Logu.w("third-danmaku", "首次拉取失败，" + (THIRD_DANMAKU_RETRY_MS / 1000)
                            + "s 后补拉一次");
                    layout_control.postDelayed(() -> {
                        if (!destroyed) fetchThirdPartyDanmaku(true);
                    }, THIRD_DANMAKU_RETRY_MS);
                }
                return;
            }
            runOnUiThread(() -> {
                if (destroyed || mDanmakuView == null) return;
                try {
                    streamDanmaku(file.toString());
                    hasDanmaku = true;
                    //装载后必须显式启动：DFM 的 prepare 只装解析器、不启动渲染循环，
                    //而 onPrepared 里那次 start 在本路径上早被 hasDanmaku=false 挡掉了
                    startDanmakuEngine(video_now > 0 ? video_now : 0);
                } catch (Throwable t) {
                    Logu.e("third-danmaku", "渲染失败：" + t);
                }
            });
        });
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
            //同 downdanmuNew：这个方法也在后台线程跑，streamDanmaku 必须回主线程
            runOnUiThread(() -> {
                try {
                    streamDanmaku(danmakuFile.toString(), null);
                } catch (Exception t) {
                    Logu.e("旧版弹幕", "渲染弹幕失败: " + t);
                }
            });
        } catch (Exception e) {
            //弹幕拉取失败与播放无关，结果只是"这场没有弹幕"（R4）
            Logu.w("旧版弹幕", "下载失败（不打扰用户）：" + e);
        }
    }

    //弹幕磁盘缓存时效：30 分钟内重进/换P不再重新下载（1小时视频逐段串行要 11 个请求）
    private static final long DANMAKU_CACHE_TTL_MS = 30 * 60 * 1000L;

    private void downdanmuNew() {
        try {
            //时长取值链（用户反馈 2026-11-07：长视频 66 分钟后弹幕消失）：此前兜底 3600 秒，
            //prepared 未就绪时 getDuration()=0 直接走兜底 → 只拉 11 段 = 66 分钟截断。
            //改为优先用取流响应的真实时长（PlayerData.durationMs），播放器时长次之，兜底最后
            int estimatedDuration = 0;

            if (videoDurationMs > 0) {
                estimatedDuration = (int) (videoDurationMs / 1000);
            } else if (ijkPlayer != null) {
                long duration = ijkPlayer.getDuration();
                if (duration > 0) {
                    estimatedDuration = (int) (duration / 1000);
                }
            }
            if (estimatedDuration <= 0) estimatedDuration = 3600;
            danmakuDurationSec = estimatedDuration;

            //磁盘缓存命中：直接读本地，省掉逐段网络请求
            File cacheFile = getDanmakuCacheFile();
            if (cacheFile != null) {
                List<DmSegMobileReply> cached = readDanmakuCache(cacheFile);
                if (cached != null && !cached.isEmpty()) {
                    int cachedElems = 0;
                    for (DmSegMobileReply seg : cached) {
                        if (seg != null) cachedElems += seg.elems.size();
                    }
                    Logu.w("弹幕缓存", "命中磁盘缓存：" + cached.size() + " 段 / " + cachedElems + " 条");
                    streamDanmakuOnUi(cached);
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

            streamDanmakuOnUi(downloaded.parsed);
        } catch (Exception e) {
            Logu.e("新版弹幕", "获取失败: " + e.getMessage() + "，回退到旧版接口");
            //只提示一次（见字段注释）：否则换P/换清晰度/重试都会再弹一遍，看起来像"一直弹窗"
            if (!danmakuFallbackTipShown) {
                danmakuFallbackTipShown = true;
                runOnUiThread(() -> MsgUtil.toast("新版弹幕获取失败，已改用旧版接口"));
            }
            CenterThreadPool.run(() -> downdanmuOld());
        }
    }

    /**
     * 回主线程渲染弹幕。
     *
     * <p>downdanmuNew/readDanmakuCache 都在后台线程跑（网络与磁盘 IO），而 streamDanmaku 会直接
     * 操作 DanmakuView。原来直接调用会抛
     * {@code Only the original thread that created a view hierarchy can touch its views}，
     * 被上层的 catch 当成"弹幕获取失败"→ 弹提示 + 白回退一次 XML 下载——而弹幕其实已经拿到了。
     * 这是"一直弹窗显示切回旧版弹幕"的真正原因，与接口本身无关。
     *
     * <p>这里再包一层 try/catch：切到主线程后抛出的异常不再能被 downdanmuNew 的 catch 捕获，
     * 必须就地兜住，否则会冒到主线程导致崩溃。
     */
    private void streamDanmakuOnUi(final List<DmSegMobileReply> segments) {
        runOnUiThread(() -> {
            try {
                streamDanmaku(null, segments);
            } catch (Exception t) {
                //只吞 Exception：Error 上抛，避免把 OOM/StackOverflow 伪装成"弹幕渲染失败"
                Logu.e("新版弹幕", "渲染弹幕失败: " + t);
                //之前只写日志，用户侧完全无感；只提示一次，避免换P/换清晰度连续弹窗
                if (!danmakuRenderFailedTipShown) {
                    danmakuRenderFailedTipShown = true;
                    MsgUtil.showMsg("弹幕渲染失败，本视频可能无弹幕");
                }
            }
        });
    }

    /**
     * 当前视频的弹幕缓存文件（含 cid 隔离）。取用时顺带清理其他视频的过期缓存。
     */
    private File getDanmakuCacheFile() {
        if (!isOnlineVideo || aid <= 0 || cid <= 0) return null;
        File dir = getCacheDir();
        cleanExpiredDanmakuCache(dir);
        //缓存键纳入时长（按分钟取整防抖）：修复前被 66 分钟截断的旧缓存文件
        //键与新时长对不上，自然失效重拉；旧文件由 TTL 清理回收
        int durationBucket = ((danmakuDurationSec + 59) / 60) * 60;
        return new File(dir, "danmaku_pb_" + cid + "_" + durationBucket);
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
            //空降弹幕醒目标识：关掉「空降弹幕」开关时置 null，弹幕按服务端原样渲染
            parser.timestampHighlighter = SharedPreferencesUtil.getBoolean("player_danmaku_airborne", true)
                    ? AIRBORNE_HIGHLIGHTER : null;
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

    /** 位置驱动开关（实验室项）：关掉即回退"整包装载 + 时钟纠偏"的旧链路 */
    private static boolean isDanmakuFeedMode() {
        return SharedPreferencesUtil.getBoolean(KEY_DANMAKU_FEED_MODE, true);
    }

    /** 空解析器：位置驱动模式下弹幕由 {@link #feedDanmaku} 逐条投放，引擎里不预装任何条目 */
    private BaseDanmakuParser createEmptyParser() {
        return new BaseDanmakuParser() {
            @Override
            protected Danmakus parse() {
                return new Danmakus();
            }
        };
    }

    /**
     * 按播放位置投放弹幕（位置驱动模式的核心，挂在既有的 250ms 进度 tick 上）。
     *
     * <p>为什么这样就不会"整屏消失"：弹幕的可见性不再由引擎时钟决定，而是每次 tick 把
     * {@code (上次位置, 当前位置]} 区间内的弹幕投进去，{@code time} 一律设成"引擎时钟+1"。
     * 引擎时钟跑快跑慢都只影响飘动速度，不会出现"该出现的没出现"。
     *
     * <p>暂停/缓冲时进度 tick 本身不跑（见 startProgressTimer 的 isPlaying 判断），
     * 所以恢复后游标仍停在暂停前的位置，不会漏投也不会补投一大段。
     */
    private void feedDanmaku(long positionMs) {
        DanmakuFeedController feeder = danmakuFeeder;
        IDanmakuView view = mDanmakuView;
        if (!danmakuFeedMode || feeder == null || view == null || destroyed) return;
        if (!view.isPrepared()) return;
        //用户关掉弹幕时不投放，只把游标跟上：重新打开时从当前位置开始，
        //而不是把关闭期间攒下的几百条一次性倒出来
        if (!isDanmakuVisible) {
            feeder.reset(positionMs);
            danmakuFeedWarmup = true;
            return;
        }
        boolean warmup = danmakuFeedWarmup;
        danmakuFeedWarmup = false;
        java.util.List<DanmakuElem> batch = feeder.take(positionMs, warmup);
        if (batch.isEmpty()) return;
        long clock = safeDanmakuTime();
        if (clock < 0) clock = positionMs;
        for (DanmakuElem e : batch) addDanmakuElem(view, e, clock);
        Logu.v("弹幕投放", (warmup ? "补投 " : "投放 ") + batch.size()
                + " 条 pos=" + positionMs + " 游标=" + feeder.cursor());
    }

    /**
     * 把一条台账弹幕投进引擎。
     *
     * <p>{@code time} 用"引擎当前时钟 + 1ms"而不是原始的 {@code progress}：位置驱动的语义就是
     * "现在该出现"，引擎只按 time 判断可见性，给一个 1ms 之后的时刻既不会跳帧也不会被判过期。
     */
    private void addDanmakuElem(IDanmakuView view, DanmakuElem e, long clockMs) {
        if (e == null || e.content == null || e.content.isEmpty()) return;
        int mode = e.mode;
        //高级弹幕(7)/代码弹幕(8)：DFM 没有对应的渲染实现，与旧链路一样直接丢弃
        if (mode == 7 || mode == 8) return;
        //「强制为滚动弹幕」：把顶部/底部弹幕也改成从右往左，语义与旧链路一致
        if (danmakuForceR2L) mode = 1;
        if (isBlockedDanmaku(e.content)) return;
        try {
            BaseDanmaku item = mContext.mDanmakuFactory.createDanmaku(mode, mContext);
            if (item == null) return;
            //倍速下的时长快照：DFM 的滚动 Duration 是**所有弹幕共享的一个对象**，
            //直接改 factor 会让在屏弹幕的位置当场跳变（加速瞬间整屏消失、减速时倒着退回去）。
            //每条弹幕投出时把当前时长拷一份，之后切倍速只影响新投的弹幕，新旧自然过渡。
            if (item.getDuration() > 0) item.setDuration(new Duration(item.getDuration()));
            item.time = clockMs + 1;

            String text = e.content;
            int color = e.color | 0xFF000000;
            int shadow = color <= Color.BLACK ? Color.WHITE : Color.BLACK;
            float sizeScale = 1f;
            //空降弹幕高亮：与整包装载时同一套钩子，开关关掉则按服务端原样渲染
            if (SharedPreferencesUtil.getBoolean("player_danmaku_airborne", true)
                    && AIRBORNE_HIGHLIGHTER.isTimestamp(text)) {
                color = AIRBORNE_HIGHLIGHTER.color();
                shadow = Color.BLACK;
                sizeScale = AIRBORNE_HIGHLIGHTER.sizeScale();
                String prefix = AIRBORNE_HIGHLIGHTER.prefix();
                if (prefix != null && !prefix.isEmpty()) text = prefix + text;
            }

            DanmakuUtils.fillText(item, text);
            item.textSize = e.fontsize * (mContext.getDisplayer().getDensity() - 0.6f) * sizeScale;
            item.textColor = color;
            item.textShadowColor = shadow;
            view.addDanmaku(item);
        } catch (Throwable t) {
            //单条失败只丢这一条：整批中断会让后面几十条一起消失
            Logu.e("弹幕投放", "单条投放失败: " + t);
        }
    }

    /** 弹幕屏蔽词命中判定；每次调用读一次存档（与旧链路"每次装载读一次"的开销同量级）*/
    private boolean isBlockedDanmaku(String content) {
        if (content == null || content.isEmpty()) return false;
        String raw = blockedWordsCache;
        if (raw == null) {
            raw = SharedPreferencesUtil.getString("danmaku_blocked_words", "");
            blockedWordsCache = raw == null ? "" : raw;
            blockedWordsParsed = null;
        }
        if (raw.trim().isEmpty()) return false;
        java.util.List<String> words = blockedWordsParsed;
        if (words == null) {
            words = new ArrayList<>();
            for (String w : raw.split("[,，、;；\\s]+")) {
                if (!w.trim().isEmpty()) words.add(w.trim());
            }
            blockedWordsParsed = words;
        }
        for (int i = 0; i < words.size(); i++) {
            if (content.contains(words.get(i))) return true;
        }
        return false;
    }

    private void streamDanmaku(String danmakuFile) {
        streamDanmaku(danmakuFile, null);
    }

    private void streamDanmaku(String danmakuFile, java.util.List<DmSegMobileReply> protobufSegments) {
        Logu.v("danmaku", "stream");
        int segCount = protobufSegments == null ? 0 : protobufSegments.size();
        int elemCount = 0;
        if (protobufSegments != null) {
            for (DmSegMobileReply seg : protobufSegments) {
                if (seg != null) elemCount += seg.elems.size();
            }
        }
        Logu.w("弹幕装载", "来源=" + (protobufSegments != null ? "新版protobuf" : "旧版XML")
                + " 段数=" + segCount + " 条数=" + elemCount
                + " cid=" + cid + " 引擎就绪=" + (mDanmakuView != null && mDanmakuView.isPrepared()));
        //切P/换清晰度会先 release 并置空，再延迟重下弹幕；这里兜一次，
        //否则下面的 setCallback 直接 NPE，异常被上层 catch 吞掉后
        //用户看到的就是"弹幕静默消失"，既无提示也无重试入口
        if (mDanmakuView == null) {
            mDanmakuView = findViewById(R.id.sv_danmaku);
            if (mDanmakuView == null) {
                Logu.e("danmaku", "DanmakuView 不存在，跳过本次弹幕装载");
                if (!danmakuRenderFailedTipShown) {
                    danmakuRenderFailedTipShown = true;
                    MsgUtil.showMsg("弹幕加载失败，可退出重进重试");
                }
                return;
            }
        }
        //新一场弹幕：落点校正的止损状态归零（换视频/换P/换清晰度都走这里）
        landingCorrectionCount = 0;
        landingLastCorrectionDiff = 0;
        landingCorrectionDisabled = false;

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
        //记下当前因子：建context时已经按设置值设过一次，变速时要用它算新旧比例
        float initFactor = SharedPreferencesUtil.getFloat("player_danmaku_speed", 1.0f);
        danmakuFactor = initFactor > 0f ? initFactor : 1f;

        //位置驱动：protobuf 通道建立台账，DFM 只拿一个空解析器（不再把上万条灌进引擎）。
        //旧版 XML 通道没有 progress 维度上的现成结构，仍走原来的整包装载。
        danmakuFeedMode = protobufSegments != null && isDanmakuFeedMode();
        //强制滚动弹幕：旧链路在 BiliProtobufDanmakuParser 里改 mode，位置驱动在这里读同一份存档
        danmakuForceR2L = SharedPreferencesUtil.getBoolean("player_danmaku_forceR2L", false);
        if (danmakuFeedMode) {
            danmakuFeeder = new DanmakuFeedController(DanmakuFeedController.flatten(protobufSegments));
            danmakuFeedWarmup = true;
            Logu.w("弹幕装载", "位置驱动：台账 " + danmakuFeeder.size() + " 条，引擎只做渲染");
        } else {
            danmakuFeeder = null;
        }

        BaseDanmakuParser mParser = danmakuFeedMode
                ? createEmptyParser()
                : createParser(danmakuFile, protobufSegments);

        mDanmakuView.setCallback(new DrawHandler.Callback() {
            @Override
            public void prepared() {
                Logu.v("danmaku", "prepared");
                //屏蔽词过滤必须在 seek/start 之前：prepared 回调里引擎还没开始渲染，
                //此时从 mParser 的集合里摘掉命中关键词的条目，高能条统计也随之排除
                filterBlockedDanmaku(mParser);
                //本回调跑在 DFM 线程，而主线程在换 P / 换清晰度 / 退出时已经把 mDanmakuView
                //release 并置空；必须先取一次局部引用判空，否则直接 NPE（线上 2 例 setSpeed on null）。
                //后续一律用这个局部引用，避免中途被主线程改掉。
                IDanmakuView danmakuView = mDanmakuView;
                if (danmakuView == null || destroyed) return;
                //弹幕下载通常慢于取流，onPrepared 里的 seek/start 会被 DanmakuView 直接丢弃（handler 未 prepared），
                //弹幕就会从 0 开始播。这里**无条件**按当前播放位置对齐一次：
                //只有 pendingDanmakuSeekMs 时补做是不够的——普通起播时它一直是 -1，
                //而 DFM 的零点此时才被 RESUME 定成"现在"，视频却已经播了 N 秒（整场固定偏后）
                //换清晰度/切听视频会重建播放器：此刻 video_now 还是旧实例的位置，不能再拿来当锚点
                long anchor = pendingDanmakuSeekMs >= 0 ? pendingDanmakuSeekMs
                        : (isPrepared ? video_now : 0);
                //引擎实际装载了多少条：0 条=数据层为空；>0 条而屏幕空白=渲染层停摆（看门狗负责捞）
                Logu.w("弹幕同步", "prepared 引擎条数=" + danmakuCount(mParser)
                        + " anchor=" + anchor + "ms 播放位置=" + video_now);
                if (danmakuFeedMode) {
                    //位置驱动：引擎时钟只负责"飘多快"，不再承担"何时出现"，所以这里不 seek。
                    //也不在这里定游标——此刻 video_now 可能是旧实例的位置（换清晰度/切听视频）甚至是 0，
                    //交给下一个 250ms tick 用真实播放位置初始化（见 DanmakuFeedController#take 的首调分支）
                    danmakuFeedWarmup = true;
                    Logu.w("弹幕投放", "prepared anchor=" + anchor + "ms 台账="
                            + (danmakuFeeder == null ? -1 : danmakuFeeder.size()) + " 等首个 tick 定游标");
                } else if (anchor > 0) {
                    Logu.w("弹幕跳转", "source=PREPARED pos=" + anchor + " 引擎条数=" + danmakuCount(mParser));
                    danmakuView.seekTo(anchor);
                    lastDanmakuSyncMs = System.currentTimeMillis();
                }
                pendingDanmakuSeekMs = -1;
                //高能进度条的密度统计放在这里：只有走到 prepared，弹幕集合才按正确视口解析完
                //（parse 依赖 DanmakuFactory 的 viewport，早于此时调用要么 NPE 要么算出错误速度）。
                //本回调就跑在 DFM 线程上，遍历弹幕集合不会与绘制线程互踩共享迭代器。
                if (danmakuFeedMode) loadHighEnergyData();
                else loadHighEnergyData(mParser);
                //prepare() 之后 handler 才 isPrepared，此前 onPrepared 里的 setSpeed 会被静默丢弃，
                //倍速播放时弹幕时钟就停在 1x——这里补一次。
                //位置驱动模式下时钟恒定 1.0，倍速由滚动时长因子体现（见 applyDanmakuScrollSpeed）
                if (danmakuFeedMode) {
                    danmakuView.setSpeed(1.0f);
                    applyDanmakuScrollSpeed(currentPlayerSpeed());
                } else {
                    danmakuView.setSpeed(currentPlayerSpeed());
                }
                Logu.d("倍速", "prepared 后同步弹幕倍速 " + currentPlayerSpeed() + "x");
                //弹幕通常晚于取流加载完：如果用户在这之前就按了暂停，上面的 seekTo 会顺带把
                //绘制线程拉起来（DFM 内部 SEEK_POS → RESUME），这里再压一次
                pauseDanmakuIfUserPaused();
                String msg = protobufSegments != null
                        ? "弹幕君准备完毕～(是新来的哦～)"
                        : "弹幕君准备完毕～(*≧ω≦)";
                addDanmaku(msg, Color.WHITE);
            }

            @Override
            public void updateTimer(DanmakuTimer timer) {
                //这里**只读、绝不写**。两条红线：
                //1) 不要读 ijkPlayer.getCurrentPosition()：回调跑在 DFM 的同步/绘制线程上，
                //   而这个 JNI 调用会拿播放器原生锁，与 seek/release 并发时轻则时间轴被旧位置拽住，
                //   重则 DFM 线程彻底卡住，让主线程 release() 里的 join 永远等下去。
                //2) 不要在这里 timer.update(播放器位置) 强行改时间轴。DFM 的时钟是**墙钟驱动**的
                //   （DrawHandler.syncTimer 每帧按 SystemClock 推进 timer，基准是 mTimeBase），
                //   而 timer.update() 只改"当前值"、不改 base：下一帧 syncTimer 发现 timer 落后于墙钟，
                //   又按 mCordonTime 一帧一帧把时钟推回去。于是形成死循环——
                //   被拽回去（弹幕整体往右跳一下）→ 又被推回来（继续往左飘）→ 再拽……
                //   这正是"每隔几秒弹幕整体右跳一次"的根因，写时钟这条路必须整条删掉。
                //
                //真正的对齐只在**事件**上做：起播/prepared、用户跳转、隐藏后重新显示、卡顿恢复，
                //这些都由 seekDanmakuTo() 完成（它会顺手 rebase DFM 的 mTimeBase）。
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

        //空降弹幕：登记点击回调。DFM 早就暴露了它，本应用此前从未注册过。
        //这里只记录命中弹幕里的时间点，真正的跳转由 tryAirborneDanmaku 在 control_layout 的
        //触摸链路里触发（原因见该方法注释：弹幕层被控制层盖住，收不到触摸事件）
        mDanmakuView.setOnDanmakuClickListener(new IDanmakuView.OnDanmakuClickListener() {
            @Override
            public void onDanmakuClick(BaseDanmaku latest) {
                danmakuHitTargetMs = (latest == null || latest.text == null)
                        ? -1 : DanmakuTimestampUtil.parseTimestampMs(latest.text.toString());
            }

            @Override
            public void onDanmakuClick(master.flame.danmaku.danmaku.model.IDanmakus danmakus) {
                //DFM 的触摸分发走的是单条重载（见 DanmakuTouchHelper.onTouchEvent），这里无需处理
            }
        });
    }

    /**
     * 空降弹幕：把触摸事件旁路喂给 DFM 的命中检测，点到含时间点的弹幕就跳过去。
     *
     * <p>为什么不用 DFM 官方推荐的 {@code setOnDanmakuClickListener} 直接收点击：
     * 本布局里 sv_danmaku 位于 control_layout 之下，触摸永远先落在 control_layout 上，
     * 弹幕层根本收不到点击事件。{@link DanmakuTouchHelper#onTouchEvent} 是 public 且
     * <b>恒返回 false（不消费事件）</b>，所以可以从现有的 control_layout 触摸链路里旁路调用它：
     * 既不影响原有手势，又能拿到弹幕自己的命中结果与现成的矩形命中算法。
     *
     * @return 是否命中了一条带时间点的弹幕（调用方应据此吞掉这次单击）
     */
    private boolean tryAirborneDanmaku(MotionEvent event) {
        if (event == null || mDanmakuView == null || isLiveMode) return false;
        if (!SharedPreferencesUtil.getBoolean("player_danmaku_airborne", true)) return false;

        danmakuHitTargetMs = -1;
        try {
            //坐标必须换算到弹幕层自己的坐标系：两者原点会因父容器/边距不同而错位
            int[] danmakuPos = new int[2];
            int[] controlPos = new int[2];
            //IDanmakuView 只是接口，屏幕坐标要经 View 取（实现类 DanmakuView 本身就是 View）
            ((View) mDanmakuView).getLocationOnScreen(danmakuPos);
            layout_control.getLocationOnScreen(controlPos);
            MotionEvent copy = MotionEvent.obtain(event);
            try {
                copy.offsetLocation(controlPos[0] - danmakuPos[0], controlPos[1] - danmakuPos[1]);
                DanmakuTouchHelper.instance(mDanmakuView).onTouchEvent(copy);
            } finally {
                //必须放在 finally：命中检测内部抛异常时这条分支也要回收，
                //否则每次异常都漏一个 MotionEvent（obtain 池空时要重新分配）
                copy.recycle();
            }
        } catch (Exception e) {
            //命中检测纯属增强能力，任何异常都不该影响播放。
            //这里只吞 Exception：Error（OOM/StackOverflow）应当继续上抛，吞掉只会掩盖真实故障
            Logu.e("danmaku-airborne", "弹幕命中检测失败：" + e);
            return false;
        }

        long target = danmakuHitTargetMs;
        danmakuHitTargetMs = -1;
        //点在空白处或弹幕里没有时间点时静默返回：随机点一下就弹提示会很吵
        if (target <= 0) return false;
        seekToPosition(target);
        MsgUtil.showMsg("已跳转到 " + StringUtil.toTime((int) (target / 1000)));
        return true;
    }

    /**
     * 弹幕屏蔽词（1.3.3 反馈：能不能加入弹幕屏蔽词）：设置里逗号/空白分隔的关键词，
     * 弹幕文本含任一关键词即整条摘除。只作用于批量装载的弹幕（prepared 回调里调用，
     * 此时引擎未开始渲染，从集合移除是安全的）；自己发送的弹幕不走这里。
     * 每次装载读一次存档，设置改完下个视频生效；过滤异常一律吞掉，不影响装载。
     */
    private void filterBlockedDanmaku(BaseDanmakuParser parser) {
        try {
            String raw = SharedPreferencesUtil.getString("danmaku_blocked_words", "");
            if (raw == null || raw.trim().isEmpty()) return;
            java.util.List<String> words = new ArrayList<>();
            for (String w : raw.split("[,，、;；\\s]+")) {
                if (!w.trim().isEmpty()) words.add(w.trim());
            }
            if (words.isEmpty()) return;
            IDanmakus danmakus = parser.getDanmakus();
            java.util.List<BaseDanmaku> blocked = new ArrayList<>();
            IDanmakuIterator it = danmakus.iterator();
            while (it.hasNext()) {
                BaseDanmaku d = it.next();
                if (d == null || d.text == null) continue;
                for (String w : words) {
                    if (d.text.toString().contains(w)) {
                        blocked.add(d);
                        break;
                    }
                }
            }
            for (BaseDanmaku d : blocked) danmakus.removeItem(d);
            if (!blocked.isEmpty()) Logu.d("danmaku", "屏蔽词过滤 " + blocked.size() + " 条");
        } catch (Throwable t) {
            Logu.e("danmaku", "屏蔽词过滤失败: " + t);
        }
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
                        seekDanmakuTo(0L, "REPLAY");
                        Logu.v("播完重播");
                    }
                } else {
                    ijkPlayer.seekTo(0);
                    seekDanmakuTo(0L, "REPLAY");
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

        flashCenterText("音量：" + show + "%");
        autohideReset();
    }

    private final Runnable hideVolume = () -> text_volume.setVisibility(View.GONE);

    /** 屏幕中间闪一条提示（复用音量提示那个 TextView，3 秒后自动消失） */
    private void flashCenterText(String text) {
        flashCenterText(text, 3000);
    }

    /** 同上，可指定停留时长：长按加速这类"按住期间一直有用"的提示不需要挂 3 秒（用户反馈 2026-11-08） */
    private void flashCenterText(String text, long durationMs) {
        if (text_volume == null) return;
        text_volume.setVisibility(View.VISIBLE);
        text_volume.setText(text);
        text_volume.removeCallbacks(hideVolume);
        text_volume.postDelayed(hideVolume, durationMs);
    }

    // ---------------------------------------------------------------- 无极调音（软件增益）

    /**
     * 把 0~1 的软件增益应用到当前播放器。
     *
     * <p>ijk 的 {@code setVolume} 是采样级线性乘算（原生方法在 libijkplayer.so 里），
     * 所以能做到 1% 一档、和系统音量档位无关——手表上系统音量常常只有 3~15 档，靠它调不出 35% 这种值。
     * 上限卡在 1.0：超过 1.0 会削波失真，不做"增益放大"。
     *
     * <p>换清晰度/换P/切听视频模式都会重建播放器实例，所以 onPrepared 里要重新调一次。
     */
    private void applyVolumeGain() {
        if (ijkPlayer != null) {
            try {
                ijkPlayer.setVolume(volumeGain, volumeGain);
            } catch (Throwable t) {
                Logu.e("volume", "应用软件音量失败：" + t);
            }
        }
        if (text_newvolume != null) text_newvolume.setText(Math.round(volumeGain * 100) + "%");
    }

    private void persistVolumeGain() {
        SharedPreferencesUtil.putFloat("player_volume_gain", volumeGain * 100f);
    }

    /** 长按底部音量按钮打开无极调音；4 秒无操作自动收起并落盘 */
    private void showVolumePanel() {
        if (layout_volume == null) return;
        if (layout_speed != null) layout_speed.setVisibility(View.GONE);
        if (seekbar_volume != null) seekbar_volume.setProgress(Math.round(volumeGain * 100));
        if (text_newvolume != null) text_newvolume.setText(Math.round(volumeGain * 100) + "%");
        layout_volume.setVisibility(View.VISIBLE);
        layout_volume.removeCallbacks(hideVolumePanel);
        layout_volume.postDelayed(hideVolumePanel, 4000);
        autohideReset();
    }

    private final Runnable hideVolumePanel = () -> {
        if (layout_volume != null) layout_volume.setVisibility(View.GONE);
        persistVolumeGain();
    };

    // ---------------------------------------------------------------- ±N 秒快进/快退

    /** 快进快退秒数：与「双击快进快退」共用同一个设置项，配成 0/负数时回落到 10 秒 */
    private int seekStepSeconds() {
        int step = SharedPreferencesUtil.getInt("player_doubletap_seek_seconds", 10);
        return step > 0 ? step : 10;
    }

    /**
     * ±N 秒按钮的文案与显隐。
     *
     * <p>按钮已从底部那一行挪到左侧纵列（和倍速同一列）：底部一排按钮在小屏上放不下，
     * ±N 秒这种文字按钮会被压成竖排、只露半个字。显隐由「显示快进快退按钮」控制，
     * 直播（没有可 seek 的时间轴）与听视频模式一律不显示。
     */
    private void updateSeekButtons() {
        boolean show = !isLiveMode && !isAudioOnlyMode
                && SharedPreferencesUtil.getBoolean("player_seek_buttons", true);
        int seekStep = seekStepSeconds();
        if (button_seek_back != null) {
            button_seek_back.setText("-" + seekStep + "s");
            button_seek_back.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (button_seek_forward != null) {
            button_seek_forward.setText("+" + seekStep + "s");
            button_seek_forward.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        //显隐一变列高就变，重新夹一次（post 到下一帧，等布局稳定）
        if (left_control != null) left_control.post(this::clampSeekColumnHeight);
    }

    // ---------------------------------------------------------------- 左侧纵列的小屏保护

    /**
     * 左侧纵列（±N 秒 + 倍速）在小屏上的高度夹取。
     *
     * <p>布局里已经用 below="top" + above="text_progress" 把整列夹在顶栏和进度文字之间；
     * 但控制区再矮一档时（点读笔/手表 240×180 这一级），三行内容照样会顶出可用区间，
     * 顶出去的那截就是压住进度条、拖进度时误触的元凶。这里按实际可用高度把两个秒数
     * 按钮一起压矮（下限 22dp），保证整列始终落在进度文字上方。
     *
     * <p>每次都从 34dp 的默认值重新算，反复调用（旋转 / 显隐切换 / 重新布局）不会越压越小。
     */
    private void clampSeekColumnHeight() {
        if (left_control == null || layout_top == null || text_progress == null) return;
        if (left_control.getVisibility() != View.VISIBLE) return;

        int available = text_progress.getTop() - layout_top.getBottom();
        if (available <= 0) {   //还没完成布局
            //顶栏被异常撑高时这里恒为负、左右控件列会被压成 0 高（1.3.0 就是这么整列消失的）。
            //布局已完成（进度文字已有 top）却仍不够高度时留一条日志，下次一眼能定位
            if (layout_top.getHeight() > ToolsUtil.dp2px(80f) && text_progress.getTop() > 0)
                Logu.w("player_ui", "顶栏高度异常 " + layout_top.getHeight() + "px，控件列可用高度 " + available + "px");
            return;
        }
        int budget = available - ToolsUtil.dp2px(4f);

        int defaultHeight = ToolsUtil.dp2px(34f);
        boolean speedVisible = text_speed != null && text_speed.getVisibility() == View.VISIBLE;
        //倍速文字是 wrap_content：还没量到高度时按「一行 14sp + 上下 5dp」估
        int speedHeight = speedVisible ? Math.max(text_speed.getHeight(), ToolsUtil.dp2px(24f)) : 0;

        int buttonHeight = defaultHeight;
        if (defaultHeight * 2 + speedHeight > budget) {
            buttonHeight = Math.max(ToolsUtil.dp2px(22f), (budget - speedHeight) / 2);
        }
        applySeekButtonHeight(button_seek_back, buttonHeight, defaultHeight);
        applySeekButtonHeight(button_seek_forward, buttonHeight, defaultHeight);
    }

    private void applySeekButtonHeight(TextView button, int height, int defaultHeight) {
        if (button == null) return;
        ViewGroup.LayoutParams params = button.getLayoutParams();
        if (params == null || params.height == height) return;
        params.height = height;
        button.setLayoutParams(params);
        button.setTextSize(height < defaultHeight ? 11f : 12f);
    }

    /** direction：1 快进、-1 快退；走 seekToPosition，弹幕零点会跟着对齐 */
    private void seekBySeconds(int direction) {
        if (ijkPlayer == null || !isPrepared || isLiveMode) return;
        int step = seekStepSeconds();
        long target = video_now + (long) direction * step * 1000L;
        if (target < 0) target = 0;
        long duration = video_all > 0 ? video_all : ijkPlayer.getDuration();
        if (duration > 0 && target > duration) target = duration;
        seekToPosition(target);
        flashCenterText((direction > 0 ? "快进 " : "快退 ") + step + " 秒");
        autohideReset();
    }

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
        userPaused = true;
        //用户主动暂停后，缓冲期的"待恢复"标记作废，避免缓冲结束时把弹幕又拉起来
        danmakuPausedByBuffering = false;
        if (ijkPlayer != null && isPrepared) {
            ijkPlayer.pause();
            if (hasDanmaku && mDanmakuView != null) {
                Logu.w("弹幕控制", "pause(用户暂停) 弹幕时钟=" + safeDanmakuTime());
                mDanmakuView.pause();
            }
        }
        if (btn_control != null)
            btn_control.setImageResource(R.drawable.btn_player_play);
        if (hasMediaSession()) {
            updateMediaSessionPlaybackState();
        }
    }

    private void playerResume() {
        isPlaying = true;
        userPaused = false;
        //用户恢复播放：缓冲标记由新的 BUFFERING_START 重新置位
        danmakuPausedByBuffering = false;
        if (ijkPlayer != null && isPrepared) {
            //暂停期间的跳转可能只在暂停态"记了个位置"：start() 之前补发一次，
            //保证恢复后画面从用户拖动的位置继续，而不是回到暂停前的位置
            if (pendingPausedSeekMs >= 0) {
                try {
                    ijkPlayer.seekTo(pendingPausedSeekMs);
                } catch (Throwable ignored) {
                }
                pendingPausedSeekMs = -1;
            }
            ijkPlayer.start();
            if (hasDanmaku && mDanmakuView != null) {
                Logu.w("弹幕控制", "resume(用户继续) 弹幕时钟=" + safeDanmakuTime());
                mDanmakuView.resume();
            }
        }
        if (btn_control != null)
            btn_control.setImageResource(R.drawable.btn_player_pause);
        if (hasMediaSession()) {
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

        //横竖切换后控制区高度完全变了，左侧纵列要重新夹一次
        if (left_control != null) left_control.post(this::clampSeekColumnHeight);

        //「互动选项自动竖屏」会在这里才真正完成转向：选项面板的高度上限要按新方向重新算一遍，
        //否则竖屏下仍按横屏的 55% 屏高夹取，面板会偏矮、白白多出滚动
        if (questionShown && interactionChoiceScroll != null
                && interactionChoiceScroll.getVisibility() == View.VISIBLE) {
            clampInteractionChoiceHeight();
        }

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

    //======= 收藏夹音频队列：音频焦点 / 耳机拔出 / 通知栏切歌 =======

    private void requestAudioFocusForPlayback() {
        try {
            audioFocusHeld = audioManager.requestAudioFocus(audioFocusListener,
                    STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } catch (Throwable t) {
            Logu.e("audio-focus", "requestAudioFocus 失败: " + t);
        }
    }

    private void abandonAudioFocusIfNeeded() {
        if (!audioFocusHeld) return;
        try {
            audioManager.abandonAudioFocus(audioFocusListener);
        } catch (Throwable ignored) {
        }
        audioFocusHeld = false;
    }

    private void registerNoisyReceiver() {
        if (noisyReceiverRegistered) return;
        try {
            registerReceiver(becomingNoisyReceiver,
                    new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
            noisyReceiverRegistered = true;
        } catch (Throwable t) {
            Logu.e("audio-focus", "注册耳机拔出监听失败: " + t);
        }
    }

    private void unregisterNoisyReceiver() {
        if (!noisyReceiverRegistered) return;
        try {
            unregisterReceiver(becomingNoisyReceiver);
        } catch (Throwable ignored) {
        }
        noisyReceiverRegistered = false;
    }

    /** 通知栏/MediaSession 下一集（仅分P，普通视频行为不变） */
    public void serviceSkipNext() {
        runOnUiThread(() -> {
            if (destroyed || isFinishing()) return;
            if (hasMultiplePages() && currentPageIndex < pagenames.size() - 1) {
                switchToPage(currentPageIndex + 1);
            }
        });
    }

    /** 通知栏/MediaSession 上一集（仅分P） */
    public void serviceSkipPrevious() {
        runOnUiThread(() -> {
            if (destroyed || isFinishing()) return;
            if (hasMultiplePages() && currentPageIndex > 0) {
                switchToPage(currentPageIndex - 1);
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        Logu.v("onPause");
        inBackground = true;
        if (!SharedPreferencesUtil.getBoolean("player_background", false)) {
            playerPause();
        } else if (!isFinishing() && isPrepared && !finishWatching) {
            //后台/熄屏继续播放：起前台服务保活并挂通知遥控（回前台/销毁时撤掉）
            PlaybackService.start(this, this);
        }
        //兜底上报：进程被系统回收、直接杀后台时 onDestroy 不保证执行，进度不能只依赖退出路径
        reportProgressNow(false);
        saveLocalProgressNow();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Logu.v("onResume");
        inBackground = false;
        //回到前台，后台播放通知没有存在的意义了
        PlaybackService.stop(this);
        //切后台再回来黑屏：surface 在后台被销毁，播放器又停在暂停态，谁都不会去画新 surface。
        //等窗口重新布局完再挂回去并强解一帧（详见 repaintPausedFrame）
        if (!destroyed && !isAudioOnlyMode && surfaceLostWhileBackground) {
            View root = findViewById(R.id.root_layout);
            if (root != null) {
                root.removeCallbacks(recoverSurfaceRunnable);
                root.postDelayed(recoverSurfaceRunnable, 250);
            }
        }
    }

    private final Runnable recoverSurfaceRunnable = () -> {
        surfaceLostWhileBackground = false;
        recoverSurfaceAfterResume();
    };

    @Override
    protected void onStop() {
        super.onStop();
        Logu.v("onStop");
        //离开页面立即注销，避免广播持有 Activity
        if (batteryReceiverRegistered) {
            try {
                unregisterReceiver(batteryReceiver);
            } catch (IllegalArgumentException ignored) {
                //重复注销/未注册：忽略，不影响退出
            }
            batteryReceiverRegistered = false;
        }
        //标记「已退到后台」：此后进程若被系统回收，不应算作播放异常退出
        PlayerSentinel.markBackgrounded(true);
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
        //走到这里说明是正常销毁（原生崩溃根本执行不到 onDestroy），哨兵可以收工了
        PlayerSentinel.finish();
        //音量键的长按计时器挂在主线程 Handler 上，页面销毁必须撤掉
        if (volumeKeyHelper != null) volumeKeyHelper.cancel();
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
            abandonAudioFocusIfNeeded();
            unregisterNoisyReceiver();
            destroyed = true;
            super.onDestroy();
            return;
        }

        Logu.v("结束");

        //进度兜底上报要在播放器释放前取位置，这是退出路径最可靠的一道保险
        reportProgressNow(true);
        saveLocalProgressNow();
        //无论哪种退出原因，后台播放通知都不能留存
        PlaybackService.stop(this);
        abandonAudioFocusIfNeeded();
        unregisterNoisyReceiver();

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
            Logu.w("弹幕控制", "release(退出播放页) 弹幕时钟=" + safeDanmakuTime());
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

        if (hasMediaSession()) {
            mediaSessionHelper.release();
            mediaSessionHelper = null;
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
        //无极调音面板的自动收起（showVolumePanel / 拖动续期都挂在 layout_volume 上）：
        //它走 View 自己的 handler 链，mainHandler.removeCallbacksAndMessages 摘不到，
        //漏了这一条，退出后最多 4 秒还有一条消息持有 Activity 与整棵 View 树
        if (layout_volume != null) layout_volume.removeCallbacks(hideVolumePanel);
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

    /**
     * 是否真的存在 MediaSession。
     *
     * <p>SDK 判断必须放在前面做短路：API&lt;21 的设备上连 helper 字段都不要去读，
     * 免得虚拟机在解析字段类型时碰到 android.media.session.*（那正是老系统崩溃的来源）。
     */
    private boolean hasMediaSession() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mediaSessionHelper != null;
    }

    /** 实例化 MediaSession 隔离层；调用方已判过 SDK≥21（API21 以下本类不加载）。 */
    private void initMediaSession() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        mediaSessionHelper = new MediaSessionHelper(this, "BiliClientPlayer", new MediaSessionHelper.Callback() {
            @Override
            public void onPlay() {
                runOnUiThread(() -> {
                    if (!isPlaying) {
                        playerResume();
                        updateMediaSessionPlaybackState();
                    }
                });
            }

            @Override
            public void onPause() {
                runOnUiThread(() -> {
                    if (isPlaying) {
                        playerPause();
                        updateMediaSessionPlaybackState();
                    }
                });
            }

            @Override
            public void onSkipToNext() {
                serviceSkipNext();
            }

            @Override
            public void onSkipToPrevious() {
                serviceSkipPrevious();
            }

            @Override
            public void onSeekTo(long pos) {
                runOnUiThread(() -> {
                    seekToPosition(pos);
                    updateMediaSessionPlaybackState();
                });
            }
        });
    }

    private void updateMediaSessionMetadata() {
        if (mediaSessionHelper == null) return;
        mediaSessionHelper.setMetadata(videoTitle, video_all);
    }

    private void updateMediaSessionPlaybackState() {
        if (mediaSessionHelper == null) return;
        //这里既会被 progressTimer 线程调用，也会被 finish()→playerPause() 这条主线程退出链调用，
        //因此不能用 ijkPlayer.getCurrentPosition()（原生锁，seek 期间可能不返回），统一用后台维护的 video_now
        boolean hasNext = hasMultiplePages() && currentPageIndex < pagenames.size() - 1;
        boolean hasPrevious = hasMultiplePages() && currentPageIndex > 0;
        mediaSessionHelper.setPlaybackState(isPlaying, video_now, hasNext, hasPrevious);
    }

    /**
     * 「更多」展开出来的快速菜单行按可用宽度钳制（所有屏幕，不只圆屏）。
     *
     * <p>{@code right_second} 是 wrap_content 的 HorizontalScrollView，里面 7 个 34dp 按钮约 250dp；
     * 它和右侧主列在同一个居右的横向 LinearLayout 里，展开时会把整行顶出屏幕左缘——
     * 用户反馈 2026-11-07：视频内功能一多，按钮就溢出窗口点不到。
     * 钳死宽度后超出部分改成横向滚动。
     */
    private void clampQuickMenuWidth() {
        if (right_second == null || screen_width <= 0) return;
        int columnWidth = ToolsUtil.dp2px(28f) + ToolsUtil.dp2px(8f);
        int maxRowWidth = Math.max(screen_width - columnWidth - ToolsUtil.dp2px(8f), ToolsUtil.dp2px(56f));

        //只钳"上限"：内容比屏宽窄时保持 wrap_content（直播下只剩一个发弹幕按钮，
        //写死 maxRowWidth 会把它拉成一条横贯屏幕、顶到对侧边缘的长条——用户反馈 2026-11-07）
        int target = maxRowWidth;
        View container = findViewById(R.id.right_second_container);
        if (container != null) {
            container.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int measured = container.getMeasuredWidth();
            if (measured > 0) target = Math.min(measured, maxRowWidth);
        }

        ViewGroup.LayoutParams lp = right_second.getLayoutParams();
        if (lp.width == target) return;
        lp.width = target;
        right_second.setLayoutParams(lp);
        Logu.d("player", "快速菜单行宽度=" + target + "px（上限 " + maxRowWidth + "，屏宽 " + screen_width + "）");
    }

    /**
     * 把底部按钮行收缩进可用宽度（只在放不下时生效，见 initUI 调用处注释）。
     *
     * <p><b>1.3.4 的实现在这里翻过车</b>：{@code bottom_buttons} 是 RelativeLayout，直接子视图
     * 只有一个宽度为 {@code wrap_content}(-2) 的居中 LinearLayout，真正的 5 个按钮在下一层。
     * 原代码遍历的是这一层，于是把 -2 乘上 scale 再取 max：
     * {@code (int)(-2 * scale)} 恒为 -1（MATCH_PARENT），最后被 max 抬到 {@code 24dp}——
     * 整排按钮的容器被压成 24dp 宽，5 个 38dp 的按钮全挤在里面被裁，
     * 界面上就是「底部按钮只剩中间一点残片、两头的都不见了」。
     * 而且只要触发一次就永久生效，不会随字体/密度回退恢复。
     *
     * <p>现在的口径：遍历真正的按钮层、按实测参数累加所需宽度（不再写死 38dp/6dp，
     * XML 改了这里自动跟），下限按「可用宽度均分」兜底而不是裸 dp，
     * 保证任何倍率下这一行都恰好塞得进屏幕，宽屏保持原尺寸不动。
     */
    private void fitBottomButtons() {
        if (bottom_buttons == null || screen_width <= 0) return;
        if (bottom_buttons.getChildCount() == 0) return;
        //按钮行是 bottom_buttons 里那个居中的 LinearLayout（真按钮在它下面一层）
        View rowView = bottom_buttons.getChildAt(0);
        if (!(rowView instanceof ViewGroup)) return;
        ViewGroup row = (ViewGroup) rowView;

        int availPx = screen_width - bottom_buttons.getPaddingLeft() - bottom_buttons.getPaddingRight();
        if (availPx <= 0) return;

        int needPx = 0, count = 0;
        for (int i = 0; i < row.getChildCount(); i++) {
            ViewGroup.LayoutParams lp = row.getChildAt(i).getLayoutParams();
            if (!(lp instanceof ViewGroup.MarginLayoutParams)) continue;
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
            //wrap_content/match_parent 的按钮算不出确定宽度，跳过（不参与收缩，避免乘出负数）
            if (mlp.width <= 0) continue;
            needPx += mlp.width + mlp.leftMargin + mlp.rightMargin;
            count++;
        }
        if (count == 0 || needPx <= availPx) return;   //放得下：保持原尺寸

        float scale = (float) availPx / needPx;
        float density = getResources().getDisplayMetrics().density;
        //下限取「24dp」与「均分可用宽度」中更小的那个：放大到 24dp 本身就超宽时，
        //宁可按钮变小也要让 5 个都露出来（用户诉求是"能看见能点"）
        int minW = Math.min((int) (24 * density + 0.5f), availPx / count);
        int minH = Math.max(1, (int) (minW * 42f / 38f));

        for (int i = 0; i < row.getChildCount(); i++) {
            ViewGroup.LayoutParams lp = row.getChildAt(i).getLayoutParams();
            if (!(lp instanceof ViewGroup.MarginLayoutParams)) continue;
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
            if (mlp.width <= 0) continue;
            mlp.width = Math.max((int) (mlp.width * scale), minW);
            mlp.height = Math.max((int) (mlp.height * scale), minH);
            mlp.leftMargin = (int) (mlp.leftMargin * scale);
            mlp.rightMargin = (int) (mlp.rightMargin * scale);
            row.getChildAt(i).setLayoutParams(mlp);
        }
        Logu.d("player_ui", "底部按钮行收缩：所需 " + needPx + "px / 可用 " + availPx
                + "px，缩放 " + String.format("%.2f", scale) + "，按钮宽 " + minW + "px");
    }

    /**
     * 右侧按钮列的高度兜底（弹幕/旋转/菜单 + 「更多」展开行）。
     *
     * <p>XML 里它用 {@code below=top + above=bottom_control} 被上下两条横条夹取，
     * 高度就是两者之间的剩余空间。「界面大小」放大后顶栏与底部控制区都按 density 成倍长高，
     * 中间剩下的高度可能小于整列内容所需（34dp 展开行 + 3×28dp 按钮），
     * 超出部分被父容器裁掉——表现为右侧按钮只露一部分、底下几个点不到（1.3.4 反馈）。
     *
     * <p>这里在真正布局完之后量一次：装不下就改成 wrap_content + 垂直居中，
     * 让整列按自身高度落在两条横条中间，而不是被夹成一条缝。圆屏分支此前已经这么处理，
     * 这里只补非圆屏（方屏/手机放大场景）。
     */
    private void fitRightControl() {
        if (right_control == null || screen_round) return;
        right_control.post(() -> {
            if (right_control == null || isFinishing() || isDestroyed()) return;
            int avail = right_control.getHeight();
            if (avail <= 0) return;
            right_control.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int needed = right_control.getMeasuredHeight();
            if (needed <= avail) return;
            ViewGroup.LayoutParams lp = right_control.getLayoutParams();
            if (!(lp instanceof RelativeLayout.LayoutParams)) return;
            RelativeLayout.LayoutParams rlp = (RelativeLayout.LayoutParams) lp;
            rlp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            rlp.addRule(RelativeLayout.CENTER_VERTICAL);
            rlp.removeRule(RelativeLayout.BELOW);
            rlp.removeRule(RelativeLayout.ABOVE);
            right_control.setLayoutParams(rlp);
            Logu.d("player_ui", "右侧按钮列高度不足（需 " + needed + "px / 可用 " + avail + "px），改为自适应居中");
        });
    }

    private void initUI() {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        screen_width = displayMetrics.widthPixels;// 获取屏宽
        screen_height = displayMetrics.heightPixels;// 获取屏高

        volumeKeyHelper = new VolumeKeyHelper(this, new VolumeKeyHelper.Host() {
            @Override
            public void showVolumePercent(int percent) {
                flashCenterText("音量：" + percent + "%");
            }

            @Override
            public void showVolumePanel() {
                showVolumePanel();
            }
        });

        if (SharedPreferencesUtil.getBoolean("player_ui_showRotateBtn", true))
            btn_rotate.setVisibility(View.VISIBLE);
        else
            btn_rotate.setVisibility(View.GONE);

        //顶栏时间显隐（1.3.3 反馈：右上角只有电量，加时间显示开关）
        View playerClock = findViewById(R.id.player_clock);
        if (playerClock != null)
            playerClock.setVisibility(SharedPreferencesUtil.getBoolean("player_show_clock", true)
                    ? View.VISIBLE : View.GONE);

        //弹幕按钮的可见性必须和旋转按钮一样在 initUI 就定下来：原来它拖到 onPrepared 才下发，
        //观感就是"刚进页面按钮还在、开播瞬间凭空少一个"，也让"设置里关掉了"这种正常状态
        //表现得像 bug。默认值与原逻辑一致（true）
        btn_danmaku.setVisibility(SharedPreferencesUtil.getBoolean("player_ui_showDanmakuBtn", true)
                ? View.VISIBLE : View.GONE);

        screen_round = SharedPreferencesUtil.getBoolean("player_ui_round", false);

        //弹层卡片底边距矮屏钳制（适配审计 20261107 P1 附属）：画质/选P等浮层 XML 贴底 56dp，
        //在 ~220dp 高的手表上起点就吃掉 1/4 屏。钳到屏高 10% 上限——手机屏高下 56dp 远小于该值，不受影响。
        //放在圆屏分支之前：圆屏内缩循环会沿用这里钳过的 bottomMargin
        int maxOverlayBottom = (int) (screen_height * 0.10);
        int[] overlayBottomIds = {R.id.subtitle_card, R.id.danmaku_send_card,
                R.id.page_selector_card, R.id.quality_selector_card,
                R.id.viewpoint_selector_card, R.id.layout_speed};
        for (int overlayId : overlayBottomIds) {
            View overlay = findViewById(overlayId);
            if (overlay == null) continue;
            ViewGroup.LayoutParams lp = overlay.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                if (mlp.bottomMargin > maxOverlayBottom) {
                    mlp.bottomMargin = maxOverlayBottom;
                    overlay.setLayoutParams(mlp);
                }
            }
        }

        //「更多」展开的快速菜单行按可用宽度钳制（所有屏幕，不只圆屏）：功能一多就会把整行顶出屏幕
        clampQuickMenuWidth();

        if (screen_round) {
            int padding = (int) (screen_width * 0.03);

            LinearLayout.LayoutParams progressParams = (LinearLayout.LayoutParams) seekbar_progress.getLayoutParams();
            progressParams.leftMargin = padding * 4;
            progressParams.rightMargin = padding * 4;
            seekbar_progress.setLayoutParams(progressParams);

            text_online.setPadding(0, 0, padding * 3, 0);
            text_progress.setPadding(padding * 3, 0, 0, 0);

            //「手动跳过」按钮同样要落在可视圆内：它是该档位下唯一的跳过入口，
            //漏掉它就会掉进圆角外的触控死角（其余控件都在这里做过内缩）
            View clipCard = findViewById(R.id.clip_skip_card);
            if (clipCard != null && clipCard.getLayoutParams() instanceof RelativeLayout.LayoutParams) {
                RelativeLayout.LayoutParams clipParams = (RelativeLayout.LayoutParams) clipCard.getLayoutParams();
                clipParams.rightMargin = padding * 3;
                clipParams.bottomMargin = padding * 2;
                clipCard.setLayoutParams(clipParams);
            }

            //弹层卡片（字幕/弹幕发送/选P/画质/查看点/倍速面板）在圆屏下同样内缩：
            //它们此前只有 6dp 水平边距 + 贴底，左右两角会伸到可视圆之外（X-07）
            int[] overlayCardIds = {R.id.subtitle_card, R.id.danmaku_send_card,
                    R.id.page_selector_card, R.id.quality_selector_card,
                    R.id.viewpoint_selector_card, R.id.layout_speed};
            int sideInset = (int) (screen_width * 0.08);
            for (int overlayCardId : overlayCardIds) {
                View overlayCard = findViewById(overlayCardId);
                if (overlayCard == null) continue;
                ViewGroup.LayoutParams cardLp = overlayCard.getLayoutParams();
                if (cardLp instanceof ViewGroup.MarginLayoutParams) {
                    ((ViewGroup.MarginLayoutParams) cardLp).setMargins(
                            sideInset, 0, sideInset, ((ViewGroup.MarginLayoutParams) cardLp).bottomMargin);
                    overlayCard.setLayoutParams(cardLp);
                }
            }

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
            //顶栏的时钟已随 TextClock 一并删除（定位见上一段），圆屏这里不再需要给它补间距
        }

        //底部按钮行窄屏收缩（1.3.3 反馈截图：小屏/「界面大小」放大后一排图标被屏幕左右
        //边缘裁掉）。该行是居中 wrap_content，放不下时两侧对称外溢、被物理边缘裁切；
        //按满编 5 键（菜单/音量-/播放/音量+/选P）算所需宽度，放不下就整行等比收缩，
        //宽度足够的屏幕保持原尺寸。必须放在圆屏 padding 之后：padding 会吃掉可用宽度
        fitBottomButtons();
        fitRightControl();

        if ((!SharedPreferencesUtil.getBoolean("player_show_online", false)) || aid == 0 || cid == 0)
            text_online.setVisibility(View.GONE);

        //退出热区收窄到标题+返回箭头：整条顶栏可退时，点电量/时钟区域（圆屏下在正中）
        //也会直接退出播放页，弹幕编辑框打开时误触即丢（X-41）
        text_title.setOnClickListener(view -> finish());
        text_title.setClickable(true);
        //固定宽度返回热区：标题宽度=屏宽-clock_layout，1.2.9 加电量百分比后右侧块变宽，
        //标题可点范围跟着缩水；热区钉在左侧 72dp 不随右侧宽度变化。
        //圆屏模式下标题换行居中、左缘没有视觉锚点，隐藏该热区避免出现"看不见的退出区"
        View backHot = findViewById(R.id.back_hot);
        if (backHot != null) {
            backHot.setOnClickListener(view -> finish());
            backHot.setVisibility(screen_round ? View.GONE : View.VISIBLE);
            //热区高度贴回顶栏实际高度。XML 里只能写 0dp：顶栏 top 是 wrap_content 的 RelativeLayout，
            //MATCH_PARENT 子 View 在测量阶段拿到的是「给满可用空间」= AT_MOST(屏高) 再按 EXACTLY 用，
            //顶栏会被量成整屏，随后 below=top 的左右控件列上下两边全被钉死、量出 0 高度整列消失
            //（1.3.0 控制面板消失事故）。0dp 不参与测量，这里在每次顶栏重新布局后把高度同步过来，
            //字号缩放、竖屏/横屏切换都能跟上
            if (layout_top != null)
                layout_top.addOnLayoutChangeListener((v, left, top, right, bottom,
                                                        oldLeft, oldTop, oldRight, oldBottom) -> {
                    int hotHeight = bottom - top;
                    if (hotHeight <= 0 || backHot.getHeight() == hotHeight) return;
                    ViewGroup.LayoutParams hotParams = backHot.getLayoutParams();
                    hotParams.height = hotHeight;
                    backHot.setLayoutParams(hotParams);
                });
        }

        //电量/时间区域恢复点击退出（1.3.2 反馈：旧版本点击此区域也可以返回）。
        //仅非圆屏开放：X-41 记录的误触（弹幕编辑框打开时一点就丢）只存在于圆屏——
        //圆屏的电量区在顶部正中，保持不可点
        if (!screen_round) {
            View clockBack = findViewById(R.id.clock_layout);
            if (clockBack != null) clockBack.setOnClickListener(view -> finish());
        }

        //左侧纵列的小屏保护：进度文字每次重新布局（首次布局/旋转/字号或按钮显隐变化）都重算一次
        if (text_progress != null)
            text_progress.addOnLayoutChangeListener((v, left, top, right, bottom,
                                                     oldLeft, oldTop, oldRight, oldBottom)
                    -> clampSeekColumnHeight());

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
                        //同 SurfaceView：暂停态下 textured 重建后也要强解一帧，否则画面停在黑屏
                        repaintPausedFrame();
                    }
                }

                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int i, int i1) {
                    Logu.v("surfacetexture", "sizechanged");
                }

                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
                    Logu.v("surfacetexture", "destroyed");
                    if (!destroyed) surfaceLostWhileBackground = true;
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
        //单击 = 系统音量 ±1 档（老习惯不变）；长按 = 打开无极调音面板（0~100% 软件增益）
        findViewById(R.id.button_sound_add).setOnLongClickListener(view -> {
            showVolumePanel();
            return true;
        });
        findViewById(R.id.button_sound_cut).setOnLongClickListener(view -> {
            showVolumePanel();
            return true;
        });

        //±N 秒按钮
        if (button_seek_back != null)
            button_seek_back.setOnClickListener(view -> seekBySeconds(-1));
        if (button_seek_forward != null)
            button_seek_forward.setOnClickListener(view -> seekBySeconds(1));

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

        findViewById(R.id.jump_time_btn).setOnClickListener(view -> showJumpTimeDialog());

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

                        //直播模式走 live msg/send（aid 存的是 roomid，见 LiveInfoActivity），
                        //无 progress/color 语义；普通视频仍走 dm/post
                        int result = isLiveMode
                                ? DanmakuApi.sendLiveDanmaku(aid, editText.getText().toString())
                                : DanmakuApi.sendVideoDanmakuByAid(cid, editText.getText().toString(), aid,
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
                    //统一走 seekToPosition：暂停态下它会同步进度条/时间文字并记下待恢复位置
                    seekToPosition(seekbar_progress.getProgress());
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
                    currentSpeed = speed_values[position];
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

        seekbar_volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                volumeGain = progress / 100f;
                applyVolumeGain();
                //拖动期间持续续期，松手后再留 4 秒方便看数值
                if (layout_volume != null) {
                    layout_volume.removeCallbacks(hideVolumePanel);
                    layout_volume.postDelayed(hideVolumePanel, 4000);
                }
                //拖动期间别让控制栏自动隐藏，否则面板会跟着一起消失
                autohideReset();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                if (layout_volume != null) layout_volume.removeCallbacks(hideVolumePanel);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                persistVolumeGain();
            }
        });

        //点面板空白处收起（与倍速面板一致）
        layout_volume.setOnClickListener(view -> {
            layout_volume.removeCallbacks(hideVolumePanel);
            hideVolumePanel.run();
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        //音量键（含 D-pad 上下）：短按系统音量 ±1 档 + 居中提示，按住 600ms 弹无极调音
        if (isPrepared && volumeKeyHelper != null && volumeKeyHelper.onKeyDown(keyCode, event))
            return true;
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
            }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (volumeKeyHelper != null && volumeKeyHelper.onKeyUp(keyCode, event)) return true;
        return super.onKeyUp(keyCode, event);
    }

    /**
     * 旋转表冠缩放画面（用户反馈 2026-11-07）。开关见 设置 → 实验室 →「表冠缩放视频」。
     *
     * <p>走的是与双指捏合同一个 scale 值（{@link ViewScaleGestureListener} 缩放的
     * {@code layout_video}），所以双击复位、1x~5x 的边界与现有手势完全一致。
     */
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_SCROLL
                && event.getSource() == android.view.InputDevice.SOURCE_ROTARY_ENCODER
                && SharedPreferencesUtil.getBoolean("ui_rotatory_video_zoom", false)
                && layout_video != null) {
            float axis = event.getAxisValue(MotionEvent.AXIS_SCROLL);
            if (axis != 0f) {
                //灵敏度可调（用户反馈 2026-11-08）：每格缩放倍数读实验室设置，0 表示没设过 → 沿用原来的 1.08
                float step = SharedPreferencesUtil.getFloat("ui_rotatory_video_zoom_step", 0f);
                if (step <= 0f) step = 1.08f;
                step = Math.max(1.01f, Math.min(3f, step));
                float next = layout_video.getScaleX() * (axis > 0 ? step : 1f / step);
                next = Math.max(1f, Math.min(5f, next));
                layout_video.setScaleX(next);
                layout_video.setScaleY(next);
                if (scaleGestureListener != null) scaleGestureListener.can_reset = next != 1.0f;
                autohideReset();
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    private void seekToPosition(long position) {
        if (ijkPlayer != null && isPrepared) {
            if (position < 0) position = 0;
            int duration = video_all > 0 ? video_all : (int) Math.max(0, currentDurationMs());
            if (duration > 0 && position > duration) position = duration;

            //seek 期间先停掉纠偏：250ms tick 会拿旧播放位置与新弹幕时钟比较，
            //可能误判成"卡顿"或"落点偏差"。此前只有进度条拖动会置 isSeeking，
            //双击快进、±N 秒、键盘方向键都漏了。
            isSeeking = true;
            //用户主动跳转=新的一次 seek 会话：恢复落点校正额度，
            //否则一次误判会让本视频剩余时间全部不再纠偏
            landingCorrectionCount = 0;
            landingLastCorrectionDiff = 0;
            landingCorrectionDisabled = false;

            ijkPlayer.seekTo(position);
            seekDanmakuTo(position, "USER");
            //宽限期（1200ms）过后再把 tick 交还纠偏；不依赖 seek 完成回调，
            //ijk 预编译版没有 accurate-seek 回调
            if (mainHandler != null) {
                mainHandler.postDelayed(() -> isSeeking = false, DANMAKU_LANDING_GRACE_MS);
            } else {
                isSeeking = false;
            }

            //播放中由 progressTimer 每 250ms 刷新进度条；它遇到 !isPlaying 会直接 early-return，
            //所以暂停态跳转后进度条、时间文字和画面都不会动——用户看到的就是"提示跳转了、进度没变"，
            //而且下一次横滑仍按旧位置算起点，越滑越偏。这里手动同步一次 UI 并强解一帧
            if (!isPlaying) {
                pendingPausedSeekMs = position;
                video_now = (int) position;
                video_now_last = video_now;
                final int uiPos = (int) position;
                runOnUiThread(() -> {
                    if (destroyed) return;
                    if (!isLiveMode) seekbar_progress.setProgress(uiPos);   //进度条的监听器会同步时间文字
                    else text_progress.setText(StringUtil.toTime(uiPos / 1000));
                });
                repaintPausedFrame();
            } else {
                pendingPausedSeekMs = -1;
            }
        }
    }

    /**
     * 跳转弹幕时间轴。
     * DanmakuView.seekTo 内部要求 handler 已 prepared，否则静默丢弃——弹幕还在下载时必然如此，
     * 因此这里记住目标位置，由 streamDanmaku 的 prepared 回调补做，保证断点续播弹幕与视频对齐。
     */
    private void seekDanmakuTo(long positionMs) {
        seekDanmakuTo(positionMs, "OTHER");
    }

    /**
     * 跳转弹幕时间轴，并记录**来源**。
     *
     * <p>来源是排查"弹幕消失"的关键：每一次 seek 都会让 DFM 清一次屏，
     * 只有知道是谁在跳、跳了几次，才能判断用户看到的是"纠偏抖动"还是"引擎停摆"。
     */
    private void seekDanmakuTo(long positionMs, String source) {
        if (!hasDanmaku || mDanmakuView == null) {
            Logu.w("弹幕跳转", "跳过 source=" + source + " pos=" + positionMs
                    + " hasDanmaku=" + hasDanmaku + " view=" + (mDanmakuView != null));
            return;
        }
        if (positionMs < 0) positionMs = 0;
        if (danmakuFeedMode) {
            //位置驱动：只清屏 + 重整游标，绝不碰引擎时钟（动它就会累积 mSpeedOffset 且无法回退）。
            //补投交给下一个 250ms tick：clearDanmakusOnScreen 是投递到 DFM 线程的异步消息，
            //这里立刻 addDanmaku 会被随后到达的清屏消息一起抹掉。
            resetDanmakuTick();
            if (danmakuFeeder != null) danmakuFeeder.reset(positionMs);
            danmakuFeedWarmup = true;
            if (mDanmakuView.isPrepared()) {
                pendingDanmakuSeekMs = -1;
                //两步都要做：removeAllDanmakus 清台账侧（danmakuList），否则旧弹幕会在下一帧
                //被 drawDanmakus 的窗口重新捞回屏幕；clearDanmakusOnScreen 清渲染器与当前屏集合
                mDanmakuView.removeAllDanmakus(true);
                mDanmakuView.clearDanmakusOnScreen();
                Logu.w("弹幕跳转", "source=" + source + " pos=" + positionMs + " 位置驱动：清屏并重整游标");
            } else {
                pendingDanmakuSeekMs = positionMs;
                Logu.w("弹幕跳转", "source=" + source + " pos=" + positionMs + " 引擎未就绪，记录待补投");
            }
            pauseDanmakuIfUserPaused();
            return;
        }
        //主动跳转后先进入冷却：播放器 seek 需要时间才真正到位，期间不能反过来把弹幕拉回旧位置
        lastDanmakuSyncMs = System.currentTimeMillis();
        //同时打开落点校正窗口：ijk 关键帧落点与请求位置的偏差由窗口期内的 tick 尽快修正
        danmakuLandingCheckUntil = lastDanmakuSyncMs + DANMAKU_LANDING_WINDOW_MS;
        resetDanmakuTick();
        if (mDanmakuView.isPrepared()) {
            //已经 prepared 就当场生效；pending 必须清掉，否则下次 reload 的 prepared 回调
            //会拿这个过期位置再跳一次（切P/换清晰度后弹幕莫名跳回旧位置就是这个原因）
            pendingDanmakuSeekMs = -1;
            Logu.w("弹幕跳转", "source=" + source + " pos=" + positionMs
                    + " 弹幕时钟=" + safeDanmakuTime() + " 播放位置=" + video_now
                    + " userPaused=" + userPaused);
            mDanmakuView.seekTo(positionMs);
        } else {
            Logu.w("弹幕跳转", "source=" + source + " pos=" + positionMs + " 引擎未就绪，挂起到 prepared");
            //handler 还没 prepared，seekTo 会被 DanmakuView 直接丢弃，等 prepared 回调补做
            pendingDanmakuSeekMs = positionMs;
        }
        //DFM 的 seekTo 在 DrawHandler 里会落到 RESUME 分支（SEEK_POS → RESUME），
        //暂停态下等于把弹幕又跑起来了，必须立刻压回去
        pauseDanmakuIfUserPaused();
    }

    /**
     * 启动弹幕引擎（并把零点对齐到指定位置）。
     *
     * <p><b>为什么必须显式调用</b>：DFM 的 {@code prepare(parser, config)} 只装载解析器，
     * 不会启动渲染循环——{@code DrawHandler} 的 PREPARE 分支只做 prepare + 回调，
     * 真正跑起来要 START（{@code DanmakuView.start}）打进 RESUME 分支才行。所以"装载了弹幕文件"
     * 与"弹幕在画"之间差一次 start。
     *
     * <p><b>为什么第三方源必须补这一次</b>：第三方源没有 {@code danmaku_url}，
     * 进入播放页时 {@code hasDanmaku} 是 false，{@code onPrepared} 里那次启动会被条件挡掉；
     * 而弹幕要等播放器 prepared 之后才异步拉回来，两者顺序天然错开。
     *
     * @param anchorMs 弹幕零点（毫秒）；<b>-1 表示按 B 站续播记忆自动决定</b>，
     *                 第三方源应传当前播放位置
     */
    private void startDanmakuEngine(long anchorMs) {
        if (mDanmakuView == null) return;
        long danmakuAnchor = anchorMs;
        if (danmakuAnchor < 0) {
            //零点必须跟播放器对齐：无参 start() 内部等价于 start(0)，会把弹幕零点定在
            //"DFM 准备好的那一刻"；弹幕下载/解析慢于取流时，整场就固定偏后（见 DanmakuView.start）
            danmakuAnchor = 0;
            if (!isLiveMode && progress_history > 5
                    && SharedPreferencesUtil.getBoolean("player_from_last", true)) {
                danmakuAnchor = progress_history;
            }
        }
        Logu.w("弹幕控制", "start 锚点=" + danmakuAnchor + "ms（progress_history="
                + progress_history + "）引擎就绪=" + mDanmakuView.isPrepared());
        mDanmakuView.start(danmakuAnchor);
        //起播时 isPlaying 还是 false（onPrepared 末尾才置 true），只有用户主动暂停过才需要压回去
        pauseDanmakuIfUserPaused();
    }

    /**
     * 暂停态下把弹幕绘制线程压回暂停。
     *
     * <p>DFM 的 {@code start()} 与 {@code seekTo()} 在 DrawHandler.handleMessage 里都会顺带执行
     * RESUME 分支（quitFlag=false、重建 mTimeBase、启动绘制线程），所以任何"对齐时间轴"的操作
     * 都会在用户暂停时把弹幕重新跑起来——这就是"暂停之后弹幕继续滚动"的根因。
     */
    private void pauseDanmakuIfUserPaused() {
        if (userPaused && hasDanmaku && mDanmakuView != null) {
            mDanmakuView.pause();
            Logu.w("弹幕控制", "pause(用户暂停后压回) 弹幕时钟=" + safeDanmakuTime());
        }
    }

    /** 解析器实际拿到的弹幕条数；-1=拿不到集合，-2=抛异常。 */
    private static int danmakuCount(BaseDanmakuParser parser) {
        try {
            IDanmakus danmakus = parser == null ? null : parser.getDanmakus();
            return danmakus == null ? -1 : danmakus.size();
        } catch (Throwable t) {
            return -2;
        }
    }

    /** 读 DFM 时钟；view 为空或抛异常时返回 -1，供日志与看门狗判空。 */
    private long safeDanmakuTime() {
        IDanmakuView view = mDanmakuView;
        if (view == null) return -1;
        try {
            return view.getCurrentTime();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 弹幕 5 秒心跳：只打日志，不改状态。
     *
     * <p>一条日志就能区分两类现场：**弹幕时钟不动**（引擎暂停/停摆，E1）与
     * **弹幕时钟在走但屏幕是空的**（隐藏态或渲染窗口错位，E2）。
     */
    private void maybeDanmakuHeartbeat(long playerPositionMs, boolean engineNotPrepared) {
        long now = System.currentTimeMillis();
        if (dmHeartbeatAt != 0 && now - dmHeartbeatAt < DM_HEARTBEAT_INTERVAL_MS) return;
        dmHeartbeatAt = now;
        long clock = engineNotPrepared ? -1 : safeDanmakuTime();
        Logu.w("弹幕心跳", "播放位置=" + playerPositionMs + " 弹幕时钟=" + clock
                + " 差值=" + (clock < 0 ? "n/a" : String.valueOf(playerPositionMs - clock))
                + " 引擎就绪=" + !engineNotPrepared
                + " 播放中=" + isPlaying + " 用户暂停=" + userPaused
                + " 缓冲暂停=" + danmakuPausedByBuffering
                + " 可见=" + isDanmakuVisible + " 已自愈=" + dmWatchdogCount);
    }

    /**
     * 弹幕停摆看门狗：播放中、弹幕可见、引擎已就绪，但**弹幕时钟连续 3 秒不前进**时，
     * 自动执行一次"开关弹幕按钮"做的同一套动作（show + seek）。
     *
     * <p>为什么这样能救：{@code show()} 会走 {@code SHOW_DANMAKUS} → {@code drawTask.requestClear()}，
     * 把 DFM 的隐藏态 {@code mIsHidden} 清掉；{@code seekTo()} 走 {@code SEEK_POS → RESUME}，
     * 重建 {@code mTimeBase} 并重启更新线程。二者合起来覆盖"暂停卡死"与"隐藏卡死"两种状态。
     *
     * <p>限流：60 秒冷却、每视频最多 5 次——看门狗是兜底，不是每秒钟硬 seek。
     */
    private void checkDanmakuWatchdog(long playerPositionMs) {
        long clock = safeDanmakuTime();
        if (clock < 0) return;
        long now = System.currentTimeMillis();
        if (dmClockLastMs != clock) {
            dmClockLastMs = clock;
            dmClockStallSince = 0;
            return;
        }
        if (dmClockStallSince == 0) {
            dmClockStallSince = now;
            return;
        }
        if (now - dmClockStallSince < DM_CLOCK_STALL_MS) return;
        if (dmWatchdogCount >= DM_WATCHDOG_MAX_TIMES) return;
        if (dmWatchdogLastAt != 0 && now - dmWatchdogLastAt < DM_WATCHDOG_COOLDOWN_MS) return;

        dmWatchdogCount++;
        dmWatchdogLastAt = now;
        long stalledMs = now - dmClockStallSince;
        dmClockStallSince = 0;
        Logu.w("弹幕看门狗", "弹幕时钟停摆 " + stalledMs + "ms（播放位置=" + playerPositionMs
                + " 弹幕时钟=" + clock + "），执行 show+seek 自愈，第 " + dmWatchdogCount + " 次");
        try {
            mDanmakuView.show();
        } catch (Throwable t) {
            Logu.e("弹幕看门狗", "show 失败: " + t);
        }
        seekDanmakuTo(playerPositionMs, "WATCHDOG");
    }

    /**
     * 弹幕时间轴的纠偏（250ms tick，后台线程）。
     *
     * <p>只在两类情况下动时间轴，其余一律不碰——每动一次用户都会看到"弹幕整体跳一下"：
     * <ol>
     *   <li><b>卡顿恢复</b>：DFM 的时钟是墙钟驱动的，画面缓冲时它照走；恢复播放后整场就多走
     *       一段，而且不会自己回来。这个 tick 只在播放位置变化时才跑，所以"墙钟过了很久、
     *       播放位置却几乎没变"就等于中间卡过，按当前播放位置重 base 一次即可。</li>
     *   <li><b>极端偏差兜底</b>：>2s 且连续 3 秒都成立，才纠一次，20 秒冷却。</li>
     * </ol>
     *
     * <p>以前的实现是"绝对偏差 >800ms 就 seek"，结果时间轴只要有一点点系统性偏移就被每 2 秒
     * 拽一次，表现为"每隔几秒弹幕整体右跳一下"，已废弃。
     *
     * <p>只允许在后台定时任务里读播放器位置，绝不能在 DFM 的线程里读（见 updateTimer 的注释）。
     */
    private void syncDanmakuIfDrifted(long playerPositionMs) {
        if (danmakuFeedMode) {
            //位置驱动：没有第二套时钟，就没有可纠的偏差。这里只按位置投放，并保留心跳用于诊断。
            //卡顿/缓冲/倍速都走这条路径，全部不需要 seek。
            if (isPrepared && isPlaying && !isSeeking) {
                IDanmakuView view = mDanmakuView;
                if (view != null && view.isPrepared()) {
                    feedDanmaku(playerPositionMs);
                    maybeDanmakuHeartbeat(playerPositionMs, false);
                }
            }
            return;
        }
        if (!isPrepared || !isPlaying || isSeeking || !isDanmakuVisible) {
            resetDanmakuTick();
            return;
        }
        //未 prepared 时 getCurrentTime 固定返回 0，会误判；这种情况由 pendingDanmakuSeekMs 兜底
        if (!hasDanmaku || mDanmakuView == null) return;
        if (!mDanmakuView.isPrepared()) {
            //引擎没就绪：心跳照打（这正是"弹幕装载失败"的现场），但不做纠偏与自愈
            maybeDanmakuHeartbeat(playerPositionMs, true);
            return;
        }
        maybeDanmakuHeartbeat(playerPositionMs, false);
        //看门狗只在真正看画面时介入：听视频/后台时弹幕层不可见，时钟本来就不该走
        if (!isAudioOnlyMode && !inBackground) checkDanmakuWatchdog(playerPositionMs);

        long now = System.currentTimeMillis();

        //---------- 卡顿恢复 ----------
        if (lastDanmakuTickPos < 0) {
            lastDanmakuTickPos = playerPositionMs;
            lastDanmakuTickWall = now;
            return;
        }
        long wallGap = now - lastDanmakuTickWall;
        long posGap = playerPositionMs - lastDanmakuTickPos;
        lastDanmakuTickPos = playerPositionMs;
        lastDanmakuTickWall = now;
        //tick 长时间没跑（暂停、缓冲、切页、隐藏弹幕）说明这期间时间轴由 pause/resume 那条链路负责，
        //这里只重新建立基线，不要再补一次 seek——否则每次暂停恢复、缓冲结束都会看到弹幕抖一下
        if (wallGap > 2000) {
            return;
        }
        //正常情况下 250ms 一个 tick，墙钟与播放位置同步前进；缓冲时位置停住、墙钟照走，
        //差值就是这段卡顿被弹幕时钟"白走"掉的时间（阈值 400ms 只为吃掉 tick 抖动）。
        //posGap < 0（用户往回跳）不算卡顿，否则每次后退都会紧跟一次多余的 seek
        if (posGap >= 0 && wallGap - posGap > 400) {
            //卡顿期间 DFM 大多已被 BUFFERING 事件正确暂停、时钟没偏（真机观测到启动期 431ms
            //"卡顿"的误触发，白清一次屏）。先实测两个时钟的差，没偏就不纠
            long danmakuTimeStall = mDanmakuView.getCurrentTime();
            if (Math.abs(playerPositionMs - danmakuTimeStall) > DANMAKU_REALIGN_MIN_DRIFT_MS) {
                Logu.w("弹幕同步", "检测到卡顿 " + (wallGap - posGap) + "ms，按播放位置重对齐到 "
                        + playerPositionMs + "ms");
                seekDanmakuTo(playerPositionMs, "STALL");
            }
            return;
        }

        //---------- seek 落点校正 ----------
        //只在 seek 后的短窗口内做：偏差来源明确（ijk 关键帧落点 vs 弹幕对齐的请求位置，
        //真机实测可达 3s 以上），不必等 20s 冷却的极端偏差兜底。
        //能走到这里说明播放位置正在真实推进（tick 门控了位置变化），宽限期内除外——
        //seekTo 返回后旧解码流水线还会推进几百毫秒，那不是落点，拿去对齐会把弹幕拽回过去
        long sinceSync = now - lastDanmakuSyncMs;
        if (!landingCorrectionDisabled && sinceSync >= DANMAKU_LANDING_GRACE_MS
                && now < danmakuLandingCheckUntil) {
            long danmakuTimeLanding = mDanmakuView.getCurrentTime();
            long landingDiff = playerPositionMs - danmakuTimeLanding;
            if (Math.abs(landingDiff) > DANMAKU_REALIGN_MIN_DRIFT_MS) {
                if (landingCorrectionCount >= DANMAKU_LANDING_MAX_CORRECTIONS) {
                    //连纠仍未收敛，且每次纠完都在 1 秒内回到同一量级：这条偏移 seek 修不掉
                    disableLandingCorrection(landingDiff, "已连纠 " + landingCorrectionCount + " 次仍不收敛");
                    return;
                }
                //第一次纠偏之后又出现"同号、量级相近"的偏差 = 修好了立刻复原，不必再试第二次
                if (landingCorrectionCount > 0 && isSameDrift(landingDiff, landingLastCorrectionDiff)) {
                    disableLandingCorrection(landingDiff, "纠偏后立刻复现同一量级偏差（上次 "
                            + landingLastCorrectionDiff + "ms）");
                    return;
                }
                Logu.w("弹幕校正", "seek 落点偏差 " + landingDiff + "ms（弹幕=" + danmakuTimeLanding
                        + "，player=" + playerPositionMs + "），按实际落点重新对齐（第 "
                        + (landingCorrectionCount + 1) + " 次）");
                realignToLanding(playerPositionMs, landingDiff);
                return;
            }
        }

        //---------- 极端偏差兜底 ----------
        long danmakuTime = mDanmakuView.getCurrentTime();
        long diff = playerPositionMs - danmakuTime;
        if (now - lastDanmakuDiagMs >= 5000) {
            long playerDelta = lastDanmakuDiagPlayer < 0 ? -1 : playerPositionMs - lastDanmakuDiagPlayer;
            long danmakuDelta = lastDanmakuDiagDanmaku < 0 ? -1 : danmakuTime - lastDanmakuDiagDanmaku;
            long wallDelta = lastDanmakuDiagMs == 0 ? -1 : now - lastDanmakuDiagMs;
            lastDanmakuDiagMs = now;
            lastDanmakuDiagPlayer = playerPositionMs;
            lastDanmakuDiagDanmaku = danmakuTime;
            Logu.d("弹幕同步", "player=" + playerPositionMs + " danmaku=" + danmakuTime
                    + " diff=" + diff + "ms | 增量 player=" + playerDelta
                    + " danmaku=" + danmakuDelta + " wall=" + wallDelta
                    + " speed=" + currentSpeed);
        }
        if (Math.abs(diff) <= DANMAKU_EXTREME_DRIFT_MS) {
            danmakuExtremeTickCount = 0;
            return;
        }
        //这条偏移已判定为"seek 修不掉"（见 disableLandingCorrection）：极端兜底也别去碰它，
        //否则会退化成"每 20 秒清一次屏"
        if (landingCorrectionDisabled && isSameDrift(diff, landingLastCorrectionDiff)) {
            danmakuExtremeTickCount = 0;
            return;
        }
        if (++danmakuExtremeTickCount < DANMAKU_EXTREME_CONFIRM) return;
        danmakuExtremeTickCount = 0;
        if (now - lastDanmakuSyncMs < DANMAKU_EXTREME_COOLDOWN_MS) return;
        Logu.w("弹幕校正", "极端偏差 " + diff + "ms（danmaku=" + danmakuTime
                + "，player=" + playerPositionMs + "），重新对齐");
        seekDanmakuTo(playerPositionMs, "EXTREME");
    }

    /** 落点校正专用入口：除了对齐，还要记住"这是第几次纠"与"纠前偏差多大"（供止损判断） */
    private void realignToLanding(long positionMs, long diffMs) {
        int previous = landingCorrectionCount;
        landingLastCorrectionDiff = diffMs;
        seekDanmakuTo(positionMs, "LANDING");
        landingCorrectionCount = previous + 1;
    }

    /**
     * 两次偏差是否属于"同一条修不掉的偏移"：同号、量级相近（差 ≤ 25%，至少 1 秒容差）。
     *
     * <p>真机实测那种"恒定超前 41 秒"每次纠完 1 秒内就回到同一量级，靠这个判定停手。
     */
    private static boolean isSameDrift(long current, long previous) {
        if (previous == 0 || current == 0) return false;
        if ((current > 0) != (previous > 0)) return false;
        long tolerance = Math.max(1000L, Math.abs(previous) / 4);
        return Math.abs(Math.abs(current) - Math.abs(previous)) <= tolerance;
    }

    /**
     * 本视频内停用落点校正。
     *
     * <p>触发条件是"纠完立刻复现同一量级偏差"或"连纠到上限仍不收敛"：说明这台设备上
     * {@code DanmakuView.getCurrentTime()} 读到的时钟与 {@code seekTo()} 修正的不是同一个源，
     * 继续纠的唯一效果就是每 0.5~1.5 秒把整屏弹幕清一次（用户看到的是"弹幕一闪一闪"）。
     * 换视频 / 换 P / 换清晰度都会重新走 {@code streamDanmaku}，届时自动恢复。
     */
    private void disableLandingCorrection(long diffMs, String reason) {
        landingCorrectionDisabled = true;
        Logu.w("弹幕校正", "停用落点校正：偏差 " + diffMs + "ms，" + reason
                + "；本视频内不再用 seek 修弹幕时钟（换P/换清晰度后恢复）");
    }

    /** 清掉卡顿检测的基线（暂停/跳转/隐藏弹幕后重新开始采样） */
    private void resetDanmakuTick() {
        lastDanmakuTickPos = -1;
        lastDanmakuTickWall = 0;
        danmakuExtremeTickCount = 0;
        //跨过一次 seek 的增量没有诊断意义（播放位置是跳变的），下一次采样只重建基线
        lastDanmakuDiagPlayer = -1;
        lastDanmakuDiagDanmaku = -1;
    }

    /**
     * 当前倍速。读的是缓存字段而不是 SeekBar：{@code prepared()} 回调跑在弹幕线程上，
     * 跨线程读 View 属于未定义行为（个别 ROM 会直接抛异常）。
     */
    private float currentPlayerSpeed() {
        float speed = currentSpeed;
        return speed > 0 ? speed : 1.0f;
    }

    /**
     * 统一改倍速：播放器与弹幕必须同步改，并在日志里留一条。
     *
     * <p>诊断用（定位"弹幕时钟比画面跑得快"时，靠这条日志判断倍速有没有同步上），可整段删。
     */
    private void applySpeed(float speed, String source) {
        currentSpeed = speed;
        if (ijkPlayer != null) ijkPlayer.setSpeed(speed);
        if (danmakuFeedMode) {
            //位置驱动：引擎时钟恒 1.0——把倍速喂给时钟会累积 mSpeedOffset 且无法回退
            //（见 DrawHandler.syncTimer），这正是之前"弹幕整屏消失又出现"的根源。
            //"弹幕跟随视频倍速"改用滚动时长因子实现，只改飞行时长、不碰时间轴。
            applyDanmakuScrollSpeed(speed);
        } else if (mDanmakuView != null) {
            mDanmakuView.setSpeed(speed);
        }
        Logu.d("倍速", source + " -> " + speed + "x（弹幕"
                + (danmakuFeedMode ? "时钟 1.0x + 滚动时长 ×" + speed + "）" : "时钟同步）"));
    }

    /**
     * 弹幕跟随视频倍速（位置驱动模式）。
     *
     * <p>等价于 PiliPlus 的 {@code duration / speed}：只改每条弹幕的飞行时长，
     * 不改变引擎时钟，所以既跟得上倍速，又不会产生任何累积偏移。
     *
     * <p>切换前先调 {@link #retimeOnScreenDanmaku} 把在屏弹幕按新旧时长重定基准，
     * 保证位置连续——加速时不会整屏消失重来，减速时也不会往回跳。
     */
    private void applyDanmakuScrollSpeed(float speed) {
        DanmakuContext ctx = mContext;
        if (ctx == null) return;
        float base = SharedPreferencesUtil.getFloat("player_danmaku_speed", 1.0f);
        if (base <= 0f) base = 1f;
        if (speed <= 0f) speed = 1f;
        float target = base * speed;
        float old = danmakuFactor > 0f ? danmakuFactor : base;
        if (Math.abs(target - old) < 0.001f) return;
        //顺序不能反：必须先用**旧时长**算出重定基准后的 time，再切因子；
        //切因子会触发全局重测量，那时新时长已经生效，位置才对得上
        retimeOnScreenDanmaku(old, target);
        ctx.setScrollSpeedFactor(target);
        danmakuFactor = target;
    }

    /**
     * 倍速切换时让**已经在屏的弹幕**也换速度，同时保持位置连续。
     *
     * <p>滚动弹幕的屏幕位置只取决于 {@code elapsed / duration} 这个比值
     * （{@code R2LDanmaku#getAccurateLeft}）。时长从 D 变成 D' 时，只要把基准时间
     * 反向挪成 {@code time' = now - elapsed × D'/D}，比值不变、位置不动，
     * 之后它就按新时长继续移动——这就是"原地变速"。
     *
     * <p>顶部/底部弹幕位置固定，只影响剩余停留时间，不动它们。
     */
    private void retimeOnScreenDanmaku(float oldFactor, float newFactor) {
        IDanmakuView view = mDanmakuView;
        if (view == null || newFactor <= 0f) return;
        IDanmakus visible;
        try {
            visible = view.getCurrentVisibleDanmakus();
        } catch (Throwable t) {
            return;
        }
        if (visible == null || visible.isEmpty()) return;
        long now = safeDanmakuTime();
        if (now < 0) return;
        float ratio = oldFactor / newFactor;
        if (ratio <= 0f) return;
        int changed = 0;
        try {
            IDanmakuIterator it = visible.iterator();
            while (it.hasNext()) {
                BaseDanmaku d = it.next();
                if (d == null) continue;
                int type = d.getType();
                if (type != BaseDanmaku.TYPE_SCROLL_RL && type != BaseDanmaku.TYPE_SCROLL_LR) continue;
                long dur = d.getDuration();
                if (dur <= 0) continue;
                //基准必须用弹幕自己的计时器（layout() 用的就是它）；getCurrentTime() 会扣掉
                //mRemainingTime，和 layout 的基准差几十毫秒，重定之后仍会有细小的横向跳变
                DanmakuTimer itemTimer = d.getTimer();
                long ref = itemTimer != null ? itemTimer.currMillisecond : now;
                long elapsed = ref - d.time;
                //刚投放（elapsed<=0）或已经过期的不动：没有位置需要守恒
                if (elapsed <= 0 || elapsed >= dur) continue;
                long newDur = (long) (dur * (double) ratio);
                if (newDur <= 0) continue;
                d.time = ref - (long) (elapsed * (double) newDur / dur);
                d.setDuration(new Duration(newDur));
                changed++;
            }
        } catch (Throwable t) {
            Logu.e("倍速", "在屏弹幕重定基准失败: " + t);
            return;
        }
        if (changed > 0) {
            Logu.d("倍速", "在屏弹幕变速 " + changed + " 条（系数 " + oldFactor + " -> " + newFactor + "）");
        }
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
                //听视频模式没有画面，快进快退按钮也没必要占着左侧一列
                updateSeekButtons();
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
                updateSeekButtons();
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
        //回到前台：撤销 onStop 打的「后台」标记
        PlayerSentinel.markBackgrounded(false);
        //粘性广播：注册即回调一次当前电量，之后随系统变化更新；离开页面即注销
        if (!batteryReceiverRegistered) {
            registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            batteryReceiverRegistered = true;
        }
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
     * 高能进度条数据：从本地已解析的弹幕算密度，<b>不再走网络</b>。
     *
     * <p>为什么换数据源：旧源 {@code bvc…/pbp/data} 已 404；后来改用的
     * {@code x/player/wbi/v2} 的 {@code view_points} 是"名场面标记"（content/from/to/imgUrl，
     * 见 PlayerApi.ViewPointData），元素里<b>没有 score 字段</b>，按 score 取值恒为 0，
     * 归一化时 {@code maxValue<=0} 让波形一行都不画——这正是"高能进度条无法正常显示"的根因。
     *
     * <p>弹幕本来就要下载解析（见 {@link #streamDanmaku}），用它按 10s 分桶统计条数，
     * 语义正好就是"高能"（弹幕越密的一段越热），且不需要登录、不额外发请求、断网也能画。
     *
     * <p>只读两遍弹幕集合、不产生临时大数组：10 万条弹幕的 {@code long[]} 在 64MB heap 的手表上
     * 是不可接受的峰值。
     *
     * <p><b>只能在 {@code DrawHandler.Callback#prepared()} 里调用</b>（即 DFM 线程、且弹幕已按
     * 正确视口解析完）。更早调用时 {@code parser.getDanmakus()} 会因 {@code mContext} 尚未由
     * DrawHandler 设置而 NPE；从别的线程调用则会与绘制线程互踩 {@code Danmakus} 的共享迭代器。
     */
    private void loadHighEnergyData(BaseDanmakuParser parser) {
        if (seekbar_progress == null || parser == null || destroyed) return;
        if (!SharedPreferencesUtil.getBoolean("player_high_energy", false)) {
            //本方法在 DFM 线程上被调用，碰 View 必须回主线程
            runOnUiThread(() -> {
                if (seekbar_progress != null) seekbar_progress.clearHighEnergyData();
            });
            Logu.d("高能进度条", "功能已禁用");
            return;
        }
        final int stepSec = 10;
        float[] events;
        try {
            IDanmakus danmakus = parser.getDanmakus();
            if (danmakus == null || danmakus.size() == 0) {
                clearHighEnergyOnUi("没有弹幕，清掉波形");
                return;
            }
            //第一遍：找最大时间（决定桶数）
            long maxTime = 0;
            IDanmakuIterator it = danmakus.iterator();
            while (it.hasNext()) {
                BaseDanmaku d = it.next();
                if (d == null || d.time <= 0) continue;
                if (d.time > maxTime) maxTime = d.time;
            }
            if (maxTime <= 0) {
                clearHighEnergyOnUi("弹幕时间全为 0，清掉波形");
                return;
            }
            //桶数按"视频总时长"和"最后一条弹幕时间"的较大者算：只按弹幕时间算时，
            //片尾几分钟没人发弹幕，波形会在那里戛然而止（用户反馈：高能条要跟弹幕一起改）
            long spanMs = videoDurationMs > maxTime ? videoDurationMs : maxTime;
            int buckets = (int) (spanMs / (stepSec * 1000L)) + 1;
            events = new float[buckets];
            //第二遍：按时间落桶。iterator() 内部会 reset，可以安全地重新取一次
            it = danmakus.iterator();
            int total = 0;
            while (it.hasNext()) {
                BaseDanmaku d = it.next();
                if (d == null || d.time <= 0) continue;
                int b = (int) (d.time / (stepSec * 1000L));
                if (b >= 0 && b < buckets) {
                    events[b] += 1f;
                    total++;
                }
            }
            Logu.d("高能进度条", "本地弹幕密度：条数=" + total + " 桶数=" + buckets + " 步长=" + stepSec + "s");
        } catch (Throwable t) {
            //统计失败只该"没有波形"，不能让播放页崩
            Logu.e("高能进度条", "弹幕密度统计失败: " + t);
            return;
        }
        final float[] result = events;
        runOnUiThread(() -> {
            if (destroyed || seekbar_progress == null) return;
            seekbar_progress.setHighEnergyData(result, stepSec);
        });
    }

    /**
     * 高能进度条数据（位置驱动模式）：直接对台账按 10s 分桶统计。
     *
     * <p>语义与整包装载时完全一致（弹幕越密的一段越"热"），只是不再依赖解析器——
     * 位置驱动模式下解析器是空的，走原来那条路会得到一条空波形。
     */
    private void loadHighEnergyData() {
        if (seekbar_progress == null || destroyed) return;
        if (!SharedPreferencesUtil.getBoolean("player_high_energy", false)) {
            clearHighEnergyOnUi("功能已禁用");
            return;
        }
        DanmakuFeedController feeder = danmakuFeeder;
        if (feeder == null || feeder.isEmpty()) {
            clearHighEnergyOnUi("没有弹幕，清掉波形");
            return;
        }
        final int stepSec = 10;
        float[] events;
        try {
            java.util.List<DanmakuElem> ledger = feeder.ledger();
            //第一遍：最大时间（决定桶数）
            long maxTime = 0;
            for (int i = 0; i < ledger.size(); i++) {
                DanmakuElem e = ledger.get(i);
                if (e == null || e.progress <= 0) continue;
                if (e.progress > maxTime) maxTime = e.progress;
            }
            if (maxTime <= 0) {
                clearHighEnergyOnUi("弹幕时间全为 0，清掉波形");
                return;
            }
            //桶数与整包装载口径一致：取"视频总时长"和"最后一条弹幕时间"的较大者
            long spanMs = videoDurationMs > maxTime ? videoDurationMs : maxTime;
            int buckets = (int) (spanMs / (stepSec * 1000L)) + 1;
            events = new float[buckets];
            //第二遍：按时间落桶
            int total = 0;
            for (int i = 0; i < ledger.size(); i++) {
                DanmakuElem e = ledger.get(i);
                if (e == null || e.progress <= 0) continue;
                int b = e.progress / (stepSec * 1000);
                if (b >= 0 && b < buckets) {
                    events[b] += 1f;
                    total++;
                }
            }
            Logu.d("高能进度条", "位置驱动密度：条数=" + total + " 桶数=" + buckets + " 步长=" + stepSec + "s");
        } catch (Throwable t) {
            Logu.e("高能进度条", "位置驱动密度统计失败: " + t);
            return;
        }
        final float[] result = events;
        runOnUiThread(() -> {
            if (destroyed || seekbar_progress == null) return;
            seekbar_progress.setHighEnergyData(result, stepSec);
        });
    }

    private void clearHighEnergyOnUi(String reason) {
        Logu.d("高能进度条", reason);
        runOnUiThread(() -> {
            if (seekbar_progress != null) seekbar_progress.clearHighEnergyData();
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
                playerData.qn = NetworkPlaybackQuality.defaultQuality(this);
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
                        Logu.w("弹幕控制", "release(切P) 弹幕时钟=" + safeDanmakuTime() + " 新cid=" + newCid);
                        mDanmakuView.release();
                        mDanmakuView = null;
                    }

                    cid = newCid;
                    //节流字段按cid区分语义：切P后必须重置，否则新P前15s的上报会被旧P的进度数值拦掉
                    lastReportedProgressSec = -1;
                    lastReportedProgressMs = -1;
                    video_url = playerData.videoUrl;
                    videoDurationMs = playerData.durationMs;
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
                        setInteractionChoicesVisible(false);
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

                        if (!destroyed && isOnlineVideo && aid > 0 && cid > 0 && SharedPreferencesUtil.getBoolean("player_show_viewpoints", false)) {
                            loadViewPoints();
                        }

                        if (!destroyed && isOnlineVideo && aid > 0 && cid > 0) {
                            loadInteractionVideo();
                        }
                    }), 60);
                });
            } catch (Exception e) {
                //提示一次就够：再叠一条「网络错误」会让人以为整段播放都断了（R4）
                Logu.e("切P", "切换失败：" + e);
                runOnUiThread(() -> MsgUtil.showMsg("切换失败"));
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
                    videoDurationMs = playerData.durationMs;
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
                //同上：只留一句准确的失败提示（R4）
                Logu.e("清晰度", "切换失败：" + e);
                runOnUiThread(() -> MsgUtil.showMsg("清晰度切换失败"));
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
                            setInteractionChoicesVisible(false);
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

            //低分辨率设备横屏时选项会被截断，先按设置把屏幕临时转成竖屏
            enterPortraitForInteractionIfNeeded();

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
                clampInteractionChoiceHeight();
                setInteractionChoicesVisible(true);
            }
        });
    }

    private void setInteractionChoicesVisible(boolean visible) {
        int visibility = visible ? View.VISIBLE : View.GONE;
        if (interactionChoiceScroll != null) interactionChoiceScroll.setVisibility(visibility);
        else if (interactionChoiceLayout != null) interactionChoiceLayout.setVisibility(visibility);
    }

    /**
     * 选项总高超过屏幕一半时给外层 ScrollView 定高，让它内部滚动；没超就按内容自然高度。
     *
     * <p>互动视频一道题最多能有五六个选项，小屏横屏时这一列会顶到屏幕上沿并继续被裁掉，
     * 排在最前面的选项连点都点不到。定高 + 滚动至少保证每一个都能选。
     */
    private void clampInteractionChoiceHeight() {
        if (interactionChoiceLayout == null || interactionChoiceScroll == null) return;
        View root = findViewById(R.id.root_layout);
        int width = root != null ? root.getWidth() : 0;
        if (width <= 0) width = getResources().getDisplayMetrics().widthPixels;
        int maxHeight = Math.round(getResources().getDisplayMetrics().heightPixels * 0.55f);
        try {
            interactionChoiceLayout.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int contentHeight = interactionChoiceLayout.getMeasuredHeight();
            ViewGroup.LayoutParams lp = interactionChoiceScroll.getLayoutParams();
            lp.height = contentHeight > maxHeight ? maxHeight : ViewGroup.LayoutParams.WRAP_CONTENT;
            interactionChoiceScroll.setLayoutParams(lp);
        } catch (Throwable t) {
            Logu.e("互动视频", "计算选项高度失败：" + t);
        }
    }

    /**
     * 「互动选项自动竖屏」：显示选项前把横屏切成竖屏，让选项有足够的竖向空间。
     * 只在开关打开、当前确实横屏、且没用软件旋屏（自己转 View，不跟系统方向走）时才动。
     */
    private void enterPortraitForInteractionIfNeeded() {
        if (interactionPortraitTemp || destroyed) return;
        if (!SharedPreferencesUtil.getBoolean("player_interaction_portrait", false)) return;
        if (!screen_landscape) return;
        if (SharedPreferencesUtil.getBoolean("dev_player_rotate_software", false)) return;
        interactionPortraitTemp = true;
        screen_landscape = false;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        Logu.v("互动视频", "显示选项，临时切竖屏");
    }

    /** 选项收起后把方向还原回横屏；只有临时切过才还原，用户自己的方向不动 */
    private void restoreLandscapeAfterInteraction() {
        if (!interactionPortraitTemp || destroyed) return;
        interactionPortraitTemp = false;
        screen_landscape = true;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        Logu.v("互动视频", "选项收起，还原横屏");
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

        //外层 ScrollView：选项一多就往外滚，容器本身仍然贴着底部、按内容或 55% 屏高定高
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(false);
        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
        );
        params.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        params.setMargins(0, 0, 0, 100);
        scroll.setLayoutParams(params);
        scroll.setVisibility(View.GONE);

        interactionChoiceLayout = new LinearLayout(this);
        interactionChoiceLayout.setOrientation(LinearLayout.VERTICAL);
        interactionChoiceLayout.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        scroll.addView(interactionChoiceLayout, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));

        interactionChoiceScroll = scroll;
        rootLayout.addView(scroll);
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
            setInteractionChoicesVisible(false);
            questionShown = false;
            currentQuestion = null;
            //选完就把方向还原回横屏；没临时转过的话这里什么都不做
            restoreLandscapeAfterInteraction();
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
                        Logu.w("弹幕控制", "release(互动跳转) 弹幕时钟=" + safeDanmakuTime() + " 新cid=" + targetCid);
                        mDanmakuView.release();
                        mDanmakuView = null;
                    }

                    cid = targetCid;
                    video_url = playerData.videoUrl;
                    videoDurationMs = playerData.durationMs;
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
                        setInteractionChoicesVisible(false);
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
                //统一走 playerResume：它会把「用户暂停」标记清掉并把弹幕一起恢复；
                //直接 ijkPlayer.start() 会漏掉弹幕恢复，选完选项后视频在走、弹幕却停着
                playerResume();
            }
        });
    }

    private int getTargetQuality() {
        int defaultQn = NetworkPlaybackQuality.defaultQuality(this);
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