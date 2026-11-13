package com.RobinNotBad.BiliClient.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.player.AudioPlayerActivity;
import com.RobinNotBad.BiliClient.api.AudioQueueApi;
import com.RobinNotBad.BiliClient.api.PlayerApi;
import com.RobinNotBad.BiliClient.helper.MediaSessionHelper;
import com.RobinNotBad.BiliClient.model.AudioQueue;
import com.RobinNotBad.BiliClient.model.DashAudioStream;
import com.RobinNotBad.BiliClient.model.PlayerData;
import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.util.AudioProgressStore;
import com.RobinNotBad.BiliClient.util.AudioTrackPicker;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.LyricProvider;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/**
 * 收藏夹音频播放的独立前台服务。
 *
 * <p>与视频播放完全解耦：本服务自己持有 IjkMediaPlayer（vn=1 纯音频解码）、
 * 自己的 MediaSession、自己的通知与通知渠道，不依赖任何 Activity 存活；
 * 因此可以边放音频边做别的事，也可以与 PlayerActivity 的视频同时出声。
 *
 * <p>按产品要求（真混音）：本服务**不请求音频焦点**，避免抢占视频播放的焦点；
 * 只在耳机拔出（ACTION_AUDIO_BECOMING_NOISY）时暂停，防止突然外放。
 *
 * <p>稳定性与加载速度：
 * <ul>
 *     <li>一首歌的 playurl 会下发多个候选 CDN 地址，逐个重试，单个节点 403 不再直接失败；</li>
 *     <li>解析结果按 cid 缓存 10 分钟，重播/来回切歌不再重复请求；</li>
 *     <li>当前首开始播放后预取下一首地址，切歌时直接命中；</li>
 *     <li>候选全部失败才退回「OkHttp 缓冲到本地再播」兜底。</li>
 * </ul>
 */
public class AudioPlayerService extends Service {

    public static final String ACTION_START = "com.RobinNotBad.BiliClient.action.AUDIO_START";
    public static final String ACTION_TOGGLE = "com.RobinNotBad.BiliClient.action.AUDIO_TOGGLE";
    public static final String ACTION_NEXT = "com.RobinNotBad.BiliClient.action.AUDIO_NEXT";
    public static final String ACTION_PREV = "com.RobinNotBad.BiliClient.action.AUDIO_PREV";
    public static final String ACTION_STOP = "com.RobinNotBad.BiliClient.action.AUDIO_STOP";
    public static final String ACTION_SEEK = "com.RobinNotBad.BiliClient.action.AUDIO_SEEK";
    public static final String EXTRA_POSITION = "position";

    private static final String TAG = "audio-diag";
    private static final String CHANNEL_ID = "audio_playback_channel";
    private static final int NOTIFICATION_ID = 1029;    //1027 下载、1028 视频后台播放
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    private static final long URL_CACHE_TTL_MS = 10 * 60 * 1000L;
    private static final long PREFETCH_TTL_MS = 5 * 60 * 1000L;
    private static final long NOTIFY_INTERVAL_MS = 2000;
    /**
     * 起播看门狗：prepareAsync 之后这么久还没 onPrepared 就主动判这条地址失败、换下一条。
     *
     * <p>真机日志里正常 prepare 中位 284ms、最慢 1453ms，3 秒有 2 倍余量；
     * 而坏节点上 ijk 自己的 onError 不可靠（实测最坏 15.2 秒），不能只依赖它。
     * 6 秒会把"第一条候选卡死"的体感放大成 6 秒以上（实测 7.5s / 9.7s），故收紧到 3 秒。
     */
    private static final long STARTUP_WATCHDOG_MS = 3000;

    /** 音频状态变化回调（UI 线程） */
    public interface Listener {
        void onAudioStateChanged();
    }

    /** 单首的候选地址缓存 */
    private static class CachedUrls {
        final List<String> urls;
        final long time;

        CachedUrls(List<String> urls) {
            this.urls = urls;
            this.time = System.currentTimeMillis();
        }

        boolean valid() {
            return !urls.isEmpty() && System.currentTimeMillis() - time < URL_CACHE_TTL_MS;
        }
    }

    private static AudioPlayerService sInstance;
    /**
     * 当前播放实例：无极调音面板要实时改音量，原实现里播放器只是 prepareAndPlay 的局部变量，
     * 外部拿不到（用户反馈 2026-11-07：听歌页也要能长按调出无极调音）
     */
    private IjkMediaPlayer currentPlayer;
    //支持多个 UI 同时监听（全屏页 + 队列页）
    private static final List<Listener> sListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    /**
     * 续播起点（毫秒）：由 {@link #startQueue} 在服务真正启动前写入，onPrepared 时消费一次。
     * 只能是静态字段——组装队列发生在 startService 之前，那时服务实例还不存在。
     */
    private static volatile int pendingStartMs = 0;

    public static void addListener(Listener listener) {
        if (listener != null && !sListeners.contains(listener)) sListeners.add(listener);
    }

    public static void removeListener(Listener listener) {
        sListeners.remove(listener);
    }

    private IjkMediaPlayer player;
    private MediaSessionHelper mediaSessionHelper;
    private NotificationManager notifyManager;
    private Handler mainHandler;
    private Timer progressTimer;

    //volatile：三个标志都被后台线程读（全量拉取循环、取流回调、静态访问器），主线程写——
    //非 volatile 时后台可能长时间读到旧值（服务销毁了拉取循环还在跑）
    private volatile boolean prepared, playing, destroyed;
    private volatile int positionMs, durationMs;
    private int resolvingGeneration = 0;
    private int consecutiveFailures = 0;
    /** 队列本页播完、正在等懒加载补页时置位，补到后自动续播 */
    private boolean waitingForMore = false;
    /** 当前这首是否已经尝试过「缓冲到本地再播」兜底 */
    private boolean triedDownload = false;
    /** 起播看门狗（主线程投递，换歌/换线/成功起播时都要撤销） */
    private Runnable startupWatchdog;

    //候选地址与缓存
    private final List<String> currentUrls = new ArrayList<>();
    /**
     * cid → 候选地址（10 分钟有效）。
     * get 在主线程（起播）与后台（预取）都有，put 全在解析线程：并发容器是必须的，
     * 否则并发写会丢条目。过期条目由读取路径顺手 remove（见 playCurrent / prefetchNext），
     * 不然服务存活期内按 cid 只增不减
     */
    private final Map<Long, CachedUrls> urlCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 通知里的 4 个按钮意图 + 点正文回播放页意图：组件、action、requestCode 在服务存活期内都不变，
     * 建一次复用。原先每次 buildNotification() 都新建 5 个 PendingIntent，而进度 tick 每 2 秒跑一次
     */
    private PendingIntent prevPending, togglePending, nextPending, stopPending, contentPending;
    /**
     * 上一次真正 notify 出去的内容快照：暂停时 title/进度/按钮全不变，
     * 没必要每 2 秒重建 Builder 并重发（主线程读写，全部调用点都在主线程）
     */
    private String lastNotificationSnapshot;
    private long prefetchCid = 0;
    private List<String> prefetchUrls = null;
    private long prefetchTime = 0;

    private BroadcastReceiver noisyReceiver;
    private boolean noisyRegistered = false;

    // ==================== 对外静态入口 ====================

    public static boolean isRunning() {
        AudioPlayerService s = sInstance;
        return s != null && !s.destroyed;
    }

    public static boolean isActive() {
        AudioPlayerService s = sInstance;
        return s != null && !s.destroyed && s.prepared;
    }

    public static boolean isResolving() {
        AudioPlayerService s = sInstance;
        return s != null && !s.destroyed && !s.prepared;
    }

    public static boolean isPlaying() {
        AudioPlayerService s = sInstance;
        return s != null && !s.destroyed && s.playing;
    }

    public static int getPositionMs() {
        AudioPlayerService s = sInstance;
        return s != null ? s.positionMs : 0;
    }

    public static int getDurationMs() {
        AudioPlayerService s = sInstance;
        return s != null ? s.durationMs : 0;
    }

    public static VideoCard getCurrentItem() {
        return AudioQueue.get().current();
    }

    public static void startQueue(Context context, List<VideoCard> items, long mediaId,
                                  String order, String folderName) {
        startQueue(context, items, mediaId, order, folderName, 0, 0);
    }

    /**
     * 带续播位置的启动：{@code startIndex} 指向当前列表里的哪一首，{@code startPositionMs} 是
     * 上次听到的毫秒位置（0 表示从头）。定位不到时调用方传 0，不会错 seek 到别的歌。
     */
    public static void startQueue(Context context, List<VideoCard> items, long mediaId,
                                  String order, String folderName,
                                  int startIndex, int startPositionMs) {
        AudioQueue.get().setQueue(items, mediaId, order, folderName);
        AudioQueue.get().setIndex(startIndex);
        pendingStartMs = Math.max(0, startPositionMs);
        sendAction(context, ACTION_START);
    }

    public static void playIndex(Context context, int index) {
        AudioQueue.get().setIndex(index);
        //手动点队列里的另一首：不能把上次「听全部」留下的续播位置用到这首上
        pendingStartMs = 0;
        sendAction(context, ACTION_START);
    }

    public static void toggle(Context context) {
        sendAction(context, ACTION_TOGGLE);
    }

    public static void next(Context context) {
        sendAction(context, ACTION_NEXT);
    }

    public static void previous(Context context) {
        sendAction(context, ACTION_PREV);
    }

    /**
     * 无极调音：把软件增益立刻套到当前播放实例（面板拖动时调用）。
     * 下一次 onPrepared 仍会按偏好重新套一遍，所以换歌不会丢。
     */
    public static void setGain(Context context, float gain) {
        AudioPlayerService s = sInstance;
        if (s == null) return;
        float g = Math.max(0f, Math.min(1f, gain));
        IjkMediaPlayer p = s.currentPlayer;
        if (p == null) return;
        try {
            p.setVolume(g, g);
        } catch (Throwable t) {
            Logu.e(TAG, "套用音量增益失败: " + t);
        }
    }

    public static void seekTo(Context context, int positionMs) {
        Intent intent = new Intent(context, AudioPlayerService.class)
                .setAction(ACTION_SEEK)
                .putExtra(EXTRA_POSITION, positionMs);
        startCompat(context, intent);
    }

    public static void stopService(Context context) {
        sendAction(context, ACTION_STOP);
    }

    private static void sendAction(Context context, String action) {
        startCompat(context, new Intent(context, AudioPlayerService.class).setAction(action));
    }

    private static void startCompat(Context context, Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (Throwable t) {
            Logu.e("audio-service", "启动音频服务失败: " + t);
        }
    }

    // ==================== 生命周期 ====================

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        mainHandler = new Handler(Looper.getMainLooper());
        notifyManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "音频播放",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            notifyManager.createNotificationChannel(channel);
        }
        initNotificationIntents();
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
            //刚发出去的就是最新内容：进度 tick 第一次比较时才不会白重建一次
            lastNotificationSnapshot = notificationSnapshot();
        } catch (Throwable t) {
            Logu.e("audio-service", "startForeground 失败: " + t);
        }
        registerNoisyReceiver();
        initMediaSession();
        //关闭「快速加载」后不再保留磁盘缓存：开关的语义就是"不占本地空间"。
        //直接删目录而不启动代理，避免为了清理白白起一个 ServerSocket。
        if (!fastLoadEnabled()) {
            try {
                File dir = new File(getCacheDir(), "audio_cache_v2");
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        //noinspection ResultOfMethodCallIgnored
                        file.delete();
                    }
                }
            } catch (Throwable t) {
                Logu.e(TAG, "清理音频缓存失败: " + t);
            }
        }
        diag("service onCreate 快速加载=" + fastLoadEnabled());
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
            //与 onCreate 同口径：刚发出去的内容视为最新，避免下一次 tick 白重建
            lastNotificationSnapshot = notificationSnapshot();
        } catch (Throwable ignored) {
        }

        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopPlayback();
            return START_NOT_STICKY;
        }
        if (action == null) {
            startProgressTimer();
            return START_NOT_STICKY;
        }

        //除 START 外，没有播放器时：解析中要忽略控制动作（不能 stopSelf，否则会打断正在解析的播放）；
        //只有队列也为空（真正没有播放任务）时才收摊，避免挂空通知
        if (!ACTION_START.equals(action) && player == null) {
            if (AudioQueue.get().current() != null) {
                MsgUtil.showMsg("正在加载音源，请稍候");
                startProgressTimer();
                return START_NOT_STICKY;
            }
            stopSelf();
            return START_NOT_STICKY;
        }

        switch (action) {
            case ACTION_START:
                playCurrent(0);
                break;
            case ACTION_TOGGLE:
                toggleInternal();
                break;
            case ACTION_NEXT:
                nextInternal(true);
                break;
            case ACTION_PREV:
                previousInternal();
                break;
            case ACTION_SEEK:
                seekInternal(intent.getIntExtra(EXTRA_POSITION, 0));
                break;
            default:
                break;
        }

        startProgressTimer();
        return START_NOT_STICKY;
    }

    /**
     * 用户在最近任务里划掉应用 = 明确的「退出」意图。之前前台服务继续存活，
     * 重开应用后顶部悬浮胶囊按 {@code isRunning()} 又出现，且应用内没有任何关闭入口。
     * 这里选择停止播放并收摊（与「清理后台」的预期一致）；想继续后台听歌请用通知栏控制，别划卡片。
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Logu.i(TAG, "onTaskRemoved: 用户清理后台，停止播放");
        try {
            stopPlayback();
        } catch (Throwable t) {
            Logu.e(TAG, "onTaskRemoved 停止失败: " + t);
        }
    }

    @Override
    public void onDestroy() {
        //先落盘再销毁：此刻 positionMs 还是最后一次轮询到的有效值
        persistProgressNow();
        destroyed = true;
        stopProgressTimer();
        releasePlayer();
        unregisterNoisyReceiver();
        if (mediaSessionHelper != null) {
            mediaSessionHelper.release();
            mediaSessionHelper = null;
        }
        if (sInstance == this) sInstance = null;
        sListeners.clear();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ==================== 播放主流程 ====================

    /**
     * 多P 稿件：把除当前分P 之外的其它分P 插到当前项之后。
     *
     * <p>队列条目来自收藏夹接口，只带**第一P**的 cid，所以此前听歌模式对多P 视频
     * 永远只播 P1、其余分P 根本没有进过队列。这里按 aid 拉一次分P 列表补全；
     * 每个稿件只拉一次（{@link AudioQueue#markExpanded}），重播不会重复插入。
     *
     * <p>异步执行且失败只记日志：分P 列表拉不到时退化成"只播 P1"，不影响当前播放。
     */
    private void expandMultiPartIfNeeded(final VideoCard item) {
        if (item == null || item.aid <= 0 || item.cid <= 0) return;
        final AudioQueue queue = AudioQueue.get();
        if (!queue.markExpanded(item.aid)) return;
        new Thread(() -> {
            try {
                java.util.List<PlayerApi.PageInfo> pages = PlayerApi.getPageList(item.aid);
                if (pages == null || pages.size() <= 1) {
                    //留一条日志：分P 列表只拿到 0/1 条时（合集不是分P、接口受限）也能从日志看出来
                    diag("多P 展开 aid=" + item.aid + " 分P=" + (pages == null ? -1 : pages.size()) + "（无需展开）");
                    return;
                }
                java.util.List<VideoCard> parts = new java.util.ArrayList<>();
                for (PlayerApi.PageInfo page : pages) {
                    if (page.cid == item.cid) continue;
                    VideoCard card = new VideoCard();
                    card.aid = item.aid;
                    card.bvid = item.bvid;
                    card.cid = page.cid;
                    card.upName = item.upName;
                    card.cover = item.cover;
                    card.title = item.title + " · P" + page.page
                            + (page.part == null || page.part.isEmpty() ? "" : " " + page.part);
                    parts.add(card);
                }
                int added = queue.insertAfterCurrent(parts);
                diag("多P 展开 aid=" + item.aid + " 分P=" + pages.size() + " 插入=" + added);
            } catch (Exception e) {
                //失败就撤销标记：否则这个稿件在本次队列里永远不再尝试展开
                queue.unmarkExpanded(item.aid);
                diag("多P 展开失败 aid=" + item.aid + " : " + e);
            }
        }).start();
    }

    private void playCurrent(int skipped) {
        final AudioQueue queue = AudioQueue.get();
        //播放模式在每次换歌时从存档同步一次：设置页/播放页改完立即生效，无需重启服务
        queue.setPlayMode(SharedPreferencesUtil.getInt(AudioQueue.KEY_AUDIO_PLAY_MODE, AudioQueue.MODE_LIST_LOOP));
        final VideoCard item = queue.current();
        if (item == null) {
            diag("playCurrent: 队列为空，停止");
            stopPlayback();
            return;
        }
        diag("playCurrent: " + item.title + " aid=" + item.aid + " cid=" + item.cid
                + " index=" + queue.getIndex() + "/" + queue.size());

        //换歌必须清掉 seek 恢复状态：否则上一首拖动的目标位置会被用到新歌上
        resumeAfterSeekMs = -1;
        seekRecoverCount = 0;
        seekGraceUntil = 0L;

        //多P 展开挪到 onPrepared 之后（见 prepareAndPlay）：它要发一次 x/player/pagelist，
        //和起播首包是同一个网络，弱网下并发会把起播拖慢几十到几百毫秒。

        triedDownload = false;
        currentUrls.clear();
        final int generation = ++resolvingGeneration;

        //0) 本地文件条目（1.3.3 反馈：缓存的音频用听歌模式听，不利用可惜）：
        //直读下载目录里的 media 文件，不走取流/URL缓存/缓存代理（cid=0 时它们天然旁路），
        //local=true 跳过网络超时与请求头选项。多P展开/预取对 aid=0 的条目本来就有守卫
        if (item.localPath != null && !item.localPath.isEmpty()
                && new java.io.File(item.localPath).exists()) {
            diag("本地直读 " + item.localPath);
            currentUrls.add(item.localPath);
            prepareAndPlay(currentUrls, item, true, 0);
            return;
        }

        //1) 命中预取（下一首已经解析好，切歌秒开）
        if (prefetchUrls != null && prefetchCid == item.cid
                && System.currentTimeMillis() - prefetchTime < PREFETCH_TTL_MS) {
            diag("使用预取地址 cid=" + item.cid + " 候选=" + prefetchUrls.size());
            currentUrls.addAll(prefetchUrls);
            prefetchUrls = null;
            prefetchCid = 0;
            prepareAndPlay(currentUrls, item, false, 0);
            return;
        }

        //2) 命中 URL 缓存（10 分钟内重播不再请求 playurl）
        CachedUrls cached = urlCache.get(item.cid);
        if (cached != null && cached.valid()) {
            diag("命中URL缓存 cid=" + item.cid + " 候选=" + cached.urls.size());
            currentUrls.addAll(cached.urls);
            prepareAndPlay(currentUrls, item, false, 0);
            return;
        }
        //过期条目读时绕过就顺手摘掉：否则服务存活期内按 cid 单调累积
        if (cached != null) urlCache.remove(item.cid);

        //3) 重新解析
        new Thread(() -> {
            try {
                if (item.cid <= 0) throw new IllegalStateException("缺少 cid");
                PlayerData data = new PlayerData(PlayerData.TYPE_VIDEO);
                data.aid = item.aid;
                data.cid = item.cid;
                data.title = item.title;
                data.bvid = item.bvid != null ? item.bvid : "";
                data.qn = 16;
                long startAt = System.currentTimeMillis();
                PlayerApi.getVideoDash(data, 16);
                DashAudioStream track = AudioTrackPicker.pick(data.dashData);
                if (track == null) throw new IllegalStateException("没有可用的音频流");

                List<String> urls = new ArrayList<>(data.audioUrls);
                if (urls.isEmpty() && track.baseUrl != null) urls.add(track.baseUrl);
                if (urls.isEmpty()) throw new IllegalStateException("没有可用的音频地址");

                long cost = System.currentTimeMillis() - startAt;
                //拿到地址后先按"上次成功的 CDN 优先 + 快速探活"重排，避免 ijk 在坏节点上等满超时
                List<String> ordered = healthOrder(urls);
                diag("解析成功 cid=" + item.cid + " id=" + track.id
                        + " 候选=" + urls.size() + " 首选=" + urlHost(ordered.get(0))
                        + " 解析耗时=" + cost + "ms");
                urlCache.put(item.cid, new CachedUrls(ordered));

                mainHandler.post(() -> {
                    if (destroyed || generation != resolvingGeneration) return;
                    currentUrls.clear();
                    currentUrls.addAll(ordered);
                    prepareAndPlay(currentUrls, item, false, 0);
                });
            } catch (Exception e) {
                diag("解析失败 " + item.title + " aid=" + item.aid + " cid=" + item.cid + " : " + e);
                //提示里带上 cid：多P 展开的条目若解析失败，cid 是 0 还是真实 cid 一眼就能分辨
                //（0=插入时没拿到 cid，真实值=接口拒绝了这个分P）
                final String reason = (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
                        + "（cid=" + item.cid + "）";
                mainHandler.post(() -> {
                    if (destroyed || generation != resolvingGeneration) return;
                    MsgUtil.showMsg("《" + item.title + "》" + reason + "，已跳过");
                    skipAfterFailure(skipped);
                });
            }
        }).start();
    }

    /**
     * seek 之后的读超时宽限期。拖动到未缓冲位置时 ffmpeg 必然重建连接、重读索引，
     * 这段时间的 onError 属于正常现象；以前它会被当成"这首播不了"直接跳下一首，
     * 分P 视频上的表现就是"拖一下进度条就到下一个P"。
     */
    private volatile long seekGraceUntil = 0L;
    /** seek 宽限期内的重试次数（同一地址最多 2 次，防死循环） */
    private int seekRecoverCount = 0;
    /** 重试起播后要回到的位置；-1 表示不需要 */
    private int resumeAfterSeekMs = -1;

    /**
     * 本进程内被判定过的坏地址（URL → 失败时间），10 分钟内排到最后。
     *
     * <p>真机日志里同一首歌来回切会反复踩同一条卡死地址（同 host、不同 URL 参数，
     * 一条卡 6 秒一条 300ms），只按 host 记忆不够，必须按完整 URL 记。
     */
    private final Map<String, Long> failedUrls = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long FAILED_URL_TTL_MS = 10 * 60 * 1000L;

    private void markUrlFailed(String url) {
        if (url != null && !url.isEmpty()) failedUrls.put(url, System.currentTimeMillis());
    }

    private boolean isUrlFailed(String url) {
        Long time = url == null ? null : failedUrls.get(url);
        if (time == null) return false;
        if (System.currentTimeMillis() - time > FAILED_URL_TTL_MS) {
            failedUrls.remove(url);
            return false;
        }
        return true;
    }

    /**
     * 候选地址健康排序：并发探活 + 第一个能出数据的排第一。
     *
     * <p>这是切歌快慢的关键。此前只探前两个、只等 350ms，而 CDN 首字节经常 400-800ms，
     * 探不出结论就按接口给的顺序盲试——而接口顺序的第一条经常是坏的，于是要等
     * 3-10 秒才换线（真机日志实测 3.7s / 7.5s / 9.7s）。
     *
     * <p>现在：所有候选（最多 4 条）同时探活，谁先吐出首字节谁排第一，最多等 1.5 秒。
     * 已知坏地址不再探活、直接沉底。
     */
    private List<String> healthOrder(List<String> urls) {
        if (urls == null || urls.size() < 2) return urls;

        //① 已知坏地址沉底（保持其余地址的相对顺序）
        List<String> ordered = new ArrayList<>();
        List<String> dead = new ArrayList<>();
        for (String url : urls) {
            if (isUrlFailed(url)) dead.add(url);
            else ordered.add(url);
        }
        if (ordered.isEmpty()) {
            diag("候选排序：全部为已知坏地址，按原顺序重试");
            return urls;
        }
        if (!dead.isEmpty()) {
            diag("候选排序：已知坏地址 " + dead.size() + " 条沉底");
            ordered.addAll(dead);
        }
        if (ordered.size() < 2) return ordered;

        //② 并发探活，第一个成功者胜出
        final int probeCount = Math.min(4, ordered.size());
        final java.util.concurrent.CountDownLatch firstOk =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger winner =
                new java.util.concurrent.atomic.AtomicInteger(-1);
        for (int i = 0; i < probeCount; i++) {
            final int idx = i;
            final String candidate = ordered.get(i);
            if (isUrlFailed(candidate)) continue;
            new Thread(() -> {
                try {
                    if (probeReachable(candidate) && winner.compareAndSet(-1, idx)) {
                        firstOk.countDown();
                    }
                } catch (Throwable ignored) {
                }
            }).start();
        }
        try {
            firstOk.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        final int best = winner.get();
        if (best < 0) {
            //1.5 秒内一条都没吐数据：保持原顺序交给 ijk，由读超时 + 起播看门狗兜底
            diag("候选探活 1.5s 内无结论，按原顺序开播");
            return ordered;
        }
        diag("候选探活 首选 host=" + urlHost(ordered.get(best))
                + "（原第 " + (best + 1) + " 个）");
        return moveToFront(ordered, best);
    }

    private static List<String> moveToFront(List<String> urls, int index) {
        if (index <= 0 || index >= urls.size()) return urls;
        List<String> reordered = new ArrayList<>(urls);
        String pick = reordered.remove(index);
        reordered.add(0, pick);
        return reordered;
    }

    /** 探活专用客户端：整体超时 2.5 秒，死节点不会占着线程拖住排序 */
    private static volatile okhttp3.OkHttpClient probeClient;

    private static okhttp3.OkHttpClient probeClient() {
        okhttp3.OkHttpClient client = probeClient;
        if (client == null) {
            client = NetWorkUtil.getOkHttpInstance().newBuilder()
                    .connectTimeout(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .readTimeout(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .callTimeout(2500, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build();
            probeClient = client;
        }
        return client;
    }

    /**
     * 探活：拿响应头 + 真读 1 字节。
     *
     * <p>只看状态码不够——部分 CDN 节点会先回 200/206，正文迟迟不出数据，
     * 到了 ijk 那边就变成"连接建好了但起播卡住"。这里必须把首字节读出来才算可用。
     * 只读 1 个字节（不用 {@code body.bytes()}：有的节点忽略 Range 会回整个文件，
     * 那样会把整首 3-5MB 都读进内存）。
     */
    private static boolean probeReachable(String url) {
        try {
            okhttp3.Request.Builder builder = new okhttp3.Request.Builder()
                    .url(url)
                    .header("Range", "bytes=0-1");
            for (Map.Entry<String, String> entry : PlayerApi.mediaHeadersFor(url).entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
            }
            okhttp3.Call call = probeClient().newCall(builder.build());
            try (okhttp3.Response response = call.execute()) {
                if (!response.isSuccessful() && response.code() != 206) return false;
                okhttp3.ResponseBody body = response.body();
                if (body == null) return false;
                return body.byteStream().read() >= 0;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    private void prepareAndPlay(List<String> urls, VideoCard item, boolean local, int index) {
        if (urls == null || urls.isEmpty()) {
            skipAfterFailure(0);
            return;
        }
        final String source = urls.get(Math.min(index, urls.size() - 1));
        cancelStartupWatchdog();
        releasePlayer();
        diag("prepareAndPlay local=" + local + " index=" + index + "/" + urls.size()
                + " host=" + urlHost(source) + " src=" + urlTail(source));
        try {
            IjkMediaPlayer mp = new IjkMediaPlayer();
            currentPlayer = mp;
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "vn", 1);
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_frame", 48);
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 48);
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1);
            //不做协议层自动重连：ffmpeg 的 reconnect 会在坏节点上「超时→重连→再超时」，
            //真机日志里最坏一次 15.2 秒才报错（8 秒超时 × 2 轮），用户看到的就是"点了没反应"。
            //坏节点应该由下面的 4 秒读超时 + 起播看门狗快速判死、立刻换候选地址。
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 0);
            //起播不等缓冲：ijk 默认 packet-buffering=1，要等缓冲够一段才出声，
            //体感就是"点了要等一两秒"。音频流码率低，关掉后 prepared 即出声。
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 0);
            //拖动进度条时按索引直接定位（DASH 分片 MP4 尤其需要），否则要顺序读到大跨度位置
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags", "fastseek");
            //缩小探测窗口：DASH 音频结构简单，64KB/200ms 足够，省掉起播前的探测等待
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 65536);
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 200000);
            if (!local) {
                //网络超时收紧：音频正常 prepare 的中位数 284ms、观测最慢 1453ms（真机日志 66 个样本），
                //4 秒还拿不到数据一定是坏节点。配合关闭 reconnect，最坏等待从 15.2 秒降到 4 秒，
                //然后立刻换下一个候选地址（不再让 ffmpeg 自己重连）。
                mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 4000000);
                mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rw_timeout", 4000000);
                //ffmpeg 的 http 专用选项：部分 CDN（bcache/移动节点）只认这两个；
                //setDataSource 的 headers 字典在 ijkplayer 里不一定覆盖到协议层
                mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user_agent", NetWorkUtil.USER_AGENT_WEB);
                mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "referer", "https://www.bilibili.com/");
            }

            //实例守卫：换候选/换歌时旧播放器是先 release 再新建，但 release 触发的
            //onError/onCompletion 仍会投递到主线程。没有这层判断，旧播放器的迟到回调
            //会把队列往后推一格（日志里出现过两次 playCurrent 相隔 133ms、却没有对应 onError 记录）
            final IjkMediaPlayer thisPlayer = mp;
            final long prepareStartAt = System.currentTimeMillis();
            mp.setOnPreparedListener(mediaPlayer -> {
                if (mediaPlayer != thisPlayer) return;
                mainHandler.post(() -> {
                //旧实例的 prepared 迟到（已换线/换歌）不能覆盖当前状态
                if (destroyed || player != thisPlayer) return;
                cancelStartupWatchdog();
                prepared = true;
                playing = true;
                consecutiveFailures = 0;
                durationMs = (int) Math.max(0, mediaPlayer.getDuration());
                positionMs = 0;
                diag("onPrepared duration=" + durationMs + " local=" + local
                        + " host=" + urlHost(source)
                        + " 准备耗时=" + (System.currentTimeMillis() - prepareStartAt) + "ms");
                //seek 触发的重试：起播后立刻回到用户拖到的位置，否则会从 0 开始放
                if (resumeAfterSeekMs >= 0) {
                    int target = resumeAfterSeekMs;
                    resumeAfterSeekMs = -1;
                    try {
                        mediaPlayer.seekTo(target);
                        positionMs = target;
                        diag("seek 重试后回到位置 " + target + "ms");
                    } catch (Throwable ignored) {
                    }
                }
                //续播：点「听全部」时带了上次的进度，首次起播后跳回去（只消费一次）
                if (pendingStartMs > 0) {
                    int target = pendingStartMs;
                    pendingStartMs = 0;
                    try {
                        mediaPlayer.seekTo(target);
                        positionMs = target;
                        diag("续播 seek=" + target + "ms");
                    } catch (Throwable ignored) {
                    }
                }
                //无极调音：与播放页共用 player_volume_gain（0~100，换算成 0~1 软件增益）。
                //ijk 的 setVolume 是实例级采样乘算，听歌每次换歌都重建实例，必须每次重套
                try {
                    float gain = Math.max(0f, Math.min(1f,
                            SharedPreferencesUtil.getFloat("player_volume_gain", 100f) / 100f));
                    if (gain < 0.999f) mediaPlayer.setVolume(gain, gain);
                } catch (Throwable t) {
                    Logu.e(TAG, "套用音量增益失败: " + t);
                }
                try {
                    mediaPlayer.start();
                } catch (Throwable t) {
                    Logu.e(TAG, "start 失败: " + t);
                }
                refreshNotification();
                updateMediaSession();
                notifyState();
                ensureMoreItems();
                prefetchNext();
                //起播成功后再展开多P：这时候首包已经拿到，pagelist 请求不会再和起播抢带宽
                expandMultiPartIfNeeded(item);
                });
            });

            mp.setOnCompletionListener(mediaPlayer -> {
                if (mediaPlayer != thisPlayer) return;
                mainHandler.post(() -> {
                //双重校验：旧实例被 release 时也会投递一次 completion，若此时已经换歌，
                //player 指向新实例——不拦住就会把新歌当成"播完了"，连切好几首。
                if (destroyed || player != thisPlayer) return;
                diag("onCompletion");
                //播完的判据必须是"真的到结尾"：拖动进度条后 ijk 偶尔会误报 completion，
                //此时位置离结尾还很远，若照常切歌就成了"拖一下就到下一个分P"
                long posNow = 0;
                try {
                    posNow = mediaPlayer.getCurrentPosition();
                } catch (Throwable ignored) {
                }
                if (durationMs > 0 && posNow + 1500 < durationMs) {
                    diag("onCompletion 位置=" + posNow + "/" + durationMs + "，忽略（误报）");
                    return;
                }
                AudioQueue queue = AudioQueue.get();
                if (queue.getPlayMode() == AudioQueue.MODE_SINGLE_LOOP) {
                    //单曲循环：原地重播（播到结尾按"听完"口径存 0，下次从头）
                    persistProgressNow();
                    playCurrent(0);
                    return;
                }
                //懒加载还有下一页且本页已见底：先等页拉完再切，否则列表循环会把
                //没加载出来的歌永远绕过去（绕着已加载区间打转，其余页再也不播）
                if (queue.hasMore() && !queue.hasNext()) {
                    diag("本页播完，等待懒加载下一页");
                    waitingForMore = true;
                    ensureMoreItems();
                    return;
                }
                if (queue.next() != null) {
                    //播完自动切歌：先把这首落盘（next() 内部处理随机/列表循环回卷）
                    persistProgressNow();
                    playCurrent(0);
                } else {
                    //队列播完就收摊：以前只置 playing=false，服务带着"暂停"通知永远挂着，
                    //顶部悬浮胶囊按 isRunning() 判定也就永远不消失
                    diag("队列播完，停止服务");
                    stopPlayback();
                }
                });
            });

            mp.setOnErrorListener((mediaPlayer, what, extra) -> {
                if (mediaPlayer != thisPlayer) return true;
                mainHandler.post(() -> {
                    //旧实例的迟到 onError 不能作用到新歌上：否则会把正在正常起播的新歌
                    //当成失败去换线/跳过（真机日志里出现过 prepareAndPlay 后 55ms 就 onError）
                    if (destroyed || player != thisPlayer) return;
                    diag("onError what=" + what + " extra=" + extra
                            + " local=" + local + " index=" + index);
                    //拖动进度条后的读超时是正常重连，不能当成整首失败：
                    //留在当前这首歌、用同一条地址重来，并在起播后回到用户拖到的位置
                    if (!local && System.currentTimeMillis() < seekGraceUntil && seekRecoverCount < 2) {
                        seekRecoverCount++;
                        diag("seek 后读超时，重试当前地址（第 " + seekRecoverCount + " 次）");
                        prepareAndPlay(urls, item, false, index);
                        return;
                    }
                    //候选地址逐个重试：换一个 CDN 往往就能播
                    if (!local && index + 1 < urls.size()) {
                        markUrlFailed(source);   //记进本进程黑名单，10 分钟内不再优先
                        MsgUtil.showMsg("当前线路失败，切换下一个");
                        prepareAndPlay(urls, item, false, index + 1);
                    } else if (!local && !triedDownload) {
                        triedDownload = true;
                        MsgUtil.showMsg("直连失败，改为缓冲后播放");
                        downloadAndPlay(source, item);
                    } else {
                        MsgUtil.showMsg("播放出错，已跳过");
                        skipAfterFailure(0);
                    }
                });
                return true;
            });

            if (local) {
                mp.setDataSource(source);
            } else {
                //「快速加载」开启时走本地缓存代理（读穿缓存，拖回已播位置不再回源）；
                //关闭或代理不可用时回退直连，行为与改造前完全一致
                String proxyUrl = fastLoadProxyUrl(item.cid, source);
                if (proxyUrl != null) {
                    mp.setDataSource(proxyUrl);
                } else {
                    mp.setDataSource(source, PlayerApi.mediaHeadersFor(source));
                }
            }
            mp.prepareAsync();
            player = mp;
            positionMs = 0;
            durationMs = 0;
            notifyState();

            //起播看门狗：ijk 在部分坏节点上迟迟不回调 onPrepared/onError，只靠 4 秒读超时不够保险。
            //超时后主动换下一条候选，保证用户最多等 STARTUP_WATCHDOG_MS 而不是十几秒。
            final IjkMediaPlayer watchedPlayer = thisPlayer;
            final Runnable watchdog = () -> {
                if (destroyed || prepared || player != watchedPlayer) return;
                startupWatchdog = null;
                diag("起播看门狗超时 " + STARTUP_WATCHDOG_MS + "ms 未 prepared local=" + local
                        + " index=" + index + "/" + urls.size() + " host=" + urlHost(source));
                if (!local && index + 1 < urls.size()) {
                    markUrlFailed(source);   //确认这条地址在限定时间内出不来数据，下次不再优先
                    MsgUtil.showMsg("当前线路加载超时，切换下一个");
                    prepareAndPlay(urls, item, false, index + 1);
                } else if (!local && !triedDownload) {
                    triedDownload = true;
                    MsgUtil.showMsg("直连超时，改为缓冲后播放");
                    downloadAndPlay(source, item);
                } else {
                    MsgUtil.showMsg("播放出错，已跳过");
                    skipAfterFailure(0);
                }
            };
            startupWatchdog = watchdog;
            mainHandler.postDelayed(watchdog, STARTUP_WATCHDOG_MS);
        } catch (Throwable t) {
            diag("创建播放器失败: " + t);
            if (!local && index + 1 < urls.size()) {
                prepareAndPlay(urls, item, false, index + 1);
            } else if (!local && !triedDownload) {
                triedDownload = true;
                downloadAndPlay(source, item);
            } else {
                skipAfterFailure(0);
            }
        }
    }

    /** 兜底：用项目成熟的 OkHttp 链路（带完整请求头）把音频流缓冲到本地，再用 ijk 播本地文件 */
    private void downloadAndPlay(String url, VideoCard item) {
        MsgUtil.showMsg("正在缓冲音频…");
        new Thread(() -> {
            File file = new File(getCacheDir(), "audio_cache/" + item.cid + ".m4s");
            try {
                File dir = file.getParentFile();
                if (dir != null && !dir.exists()) dir.mkdirs();
                File[] old = dir != null ? dir.listFiles() : null;
                if (old != null) {
                    for (File f : old) if (!f.equals(file)) f.delete();
                }

                ArrayList<String> headers = new ArrayList<>();
                for (Map.Entry<String, String> entry : PlayerApi.mediaHeadersFor(url).entrySet()) {
                    headers.add(entry.getKey());
                    headers.add(entry.getValue());
                }
                long startAt = System.currentTimeMillis();
                okhttp3.Response response = NetWorkUtil.get(url, headers);
                if (!response.isSuccessful()) {
                    int code = response.code();
                    response.close();
                    throw new IOException("HTTP " + code);
                }
                long total = response.body() != null ? response.body().contentLength() : -1;
                try (InputStream in = response.body().byteStream();
                     FileOutputStream out = new FileOutputStream(file)) {
                    byte[] buffer = new byte[64 * 1024];
                    int len;
                    while ((len = in.read(buffer)) > 0) out.write(buffer, 0, len);
                } finally {
                    response.close();
                }
                diag("缓冲完成 " + file.length() + "/" + total
                        + " 耗时=" + (System.currentTimeMillis() - startAt) + "ms");
                mainHandler.post(() -> {
                    if (!destroyed) {
                        List<String> localUrls = new ArrayList<>();
                        localUrls.add(file.getAbsolutePath());
                        prepareAndPlay(localUrls, item, true, 0);
                    }
                });
            } catch (Exception e) {
                diag("缓冲音频失败: " + e);
                mainHandler.post(() -> {
                    if (destroyed) return;
                    MsgUtil.showMsg("缓冲失败，已跳过");
                    skipAfterFailure(0);
                });
            }
        }).start();
    }

    /** 预取下一首地址：当前首开始播放后调用，切歌时直接命中 */
    private void prefetchNext() {
        final AudioQueue queue = AudioQueue.get();
        if (!queue.hasNext()) return;
        List<VideoCard> items = queue.getItems();
        int nextIndex = queue.getIndex() + 1;
        if (nextIndex >= items.size()) return;
        final VideoCard next = items.get(nextIndex);
        if (next == null || next.cid <= 0) return;
        if (prefetchUrls != null && prefetchCid == next.cid) return;
        CachedUrls cached = urlCache.get(next.cid);
        if (cached != null && cached.valid()) {
            prefetchCid = next.cid;
            prefetchUrls = cached.urls;
            prefetchTime = cached.time;
            return;
        }
        //与 playCurrent 同口径：过期条目顺手摘掉
        if (cached != null) urlCache.remove(next.cid);

        new Thread(() -> {
            try {
                PlayerData data = new PlayerData(PlayerData.TYPE_VIDEO);
                data.aid = next.aid;
                data.cid = next.cid;
                data.qn = 16;
                long startAt = System.currentTimeMillis();
                PlayerApi.getVideoDash(data, 16);
                DashAudioStream track = AudioTrackPicker.pick(data.dashData);
                if (track == null) return;
                List<String> urls = new ArrayList<>(data.audioUrls);
                if (urls.isEmpty() && track.baseUrl != null) urls.add(track.baseUrl);
                if (urls.isEmpty()) return;
                //预取时就把候选排好，切歌时可以直接开播，不必再等探活
                urls = healthOrder(urls);
                prefetchCid = next.cid;
                prefetchUrls = urls;
                prefetchTime = System.currentTimeMillis();
                urlCache.put(next.cid, new CachedUrls(urls));
                diag("预取下一首完成 cid=" + next.cid + " 候选=" + urls.size()
                        + " 耗时=" + (System.currentTimeMillis() - startAt) + "ms");
                //快速加载：把下一首开头 1.5MB 落盘，切歌时第一段直接从磁盘出（等价 NeriPlayer 的字节预取）
                if (fastLoadEnabled()) {
                    try {
                        AudioCacheProxy.get(AudioPlayerService.this).prefetch(next.cid, urls.get(0));
                    } catch (Throwable t) {
                        diag("预取落盘失败 cid=" + next.cid + " : " + t);
                    }
                }
                //歌词也提前取好：切歌时直接命中本地缓存，界面不用等
                try {
                    LyricProvider.prefetch(AudioPlayerService.this, next, durationMs);
                } catch (Throwable ignored) {
                }
            } catch (Exception e) {
                diag("预取下一首失败 cid=" + next.cid + " : " + e);
            }
        }).start();
    }

    /**
     * 全量拉取：从已加载页的下一页起，串行翻页直到收藏夹到底，把整夹内容一次性装进队列。
     *
     * <p>起播不等它：第一页（列表页已加载的内容）够立即开播，翻页在后台跑，
     * 每页到达即追加并通知——队列页实时变长，进度文案（3/57）随之变准。
     * 串行单页请求，与原懒加载同样的限速口径，避免触发 412 风控。
     *
     * <p>失败重试：单页最多 3 次（间隔 1.5s），仍失败则停住——已有内容照常播放，
     * 下一首歌的 onPrepared 会再次进入本方法，从断点页续拉。
     */
    private void ensureMoreItems() {
        final AudioQueue queue = AudioQueue.get();
        if (!queue.isActive() || !queue.hasMore() || queue.isLoadingMore()) return;
        queue.setLoadingMore(true);
        final long mediaId = queue.getMediaId();
        final String order = queue.getOrder();
        new Thread(() -> {
            int attempts = 0;
            int pagesFetched = 0;
            try {
                //安全上限：100 页 = 2000 条，正常收藏夹远达不到；仅防接口异常时死循环
                while (!destroyed && pagesFetched < 100) {
                    AudioQueue q = AudioQueue.get();
                    if (!q.isActive() || !q.hasMore()) break;
                    final int nextPage = q.getLoadedPage() + 1;
                    AudioQueueApi.Page page;
                    try {
                        page = AudioQueueApi.loadPage(mediaId, nextPage, order);
                        attempts = 0;
                    } catch (Exception e) {
                        attempts++;
                        diag("全量拉取失败 page=" + nextPage + " 第" + attempts + "次 : " + e);
                        if (attempts >= 3) break;
                        try {
                            Thread.sleep(1500);
                        } catch (InterruptedException ie) {
                            break;
                        }
                        continue;
                    }
                    int added = q.append(page.items);
                    q.setLoadedPage(nextPage);
                    q.setHasMore(page.hasMore);
                    pagesFetched++;
                    diag("全量拉取 page=" + nextPage + " 追加=" + added
                            + " 总数=" + q.size() + " hasMore=" + page.hasMore);
                    mainHandler.post(() -> {
                        notifyState();
                        //播完等待补页的场景：每页到达都尝试续播，不必等整个收藏夹拉完
                        if (waitingForMore) {
                            waitingForMore = false;
                            if (AudioQueue.get().hasNext()) {
                                AudioQueue.get().next();
                                playCurrent(0);
                            }
                        }
                    });
                    if (!page.hasMore) break;
                }
            } finally {
                AudioQueue.get().setLoadingMore(false);
                diag("全量拉取结束 本轮=" + pagesFetched + " 页 总数=" + AudioQueue.get().size());
            }
        }).start();
    }

    private void skipAfterFailure(int skipped) {
        consecutiveFailures++;
        if (consecutiveFailures > MAX_CONSECUTIVE_FAILURES) {
            MsgUtil.showMsgLong("连续多首播放失败，已停止");
            stopPlayback();
            return;
        }
        if (AudioQueue.get().hasNext()) {
            AudioQueue.get().next();
            playCurrent(skipped + 1);
        } else {
            MsgUtil.showMsgLong("已经是最后一首");
            //最后一首也失败了：没有可播的内容，收摊而不是挂着一个"暂停"服务永远留着
            stopPlayback();
        }
    }

    private void toggleInternal() {
        IjkMediaPlayer mp = player;
        if (mp == null || !prepared) {
            MsgUtil.showMsg("正在加载音源，请稍候");
            return;
        }
        try {
            if (playing) {
                mp.pause();
                playing = false;
                //暂停是最容易"退出应用"的时刻：立刻落盘，不等 5 秒节流
                persistProgressNow();
            } else {
                mp.start();
                playing = true;
            }
        } catch (Throwable t) {
            Logu.e("audio-service", "toggle 失败: " + t);
        }
        refreshNotification();
        updateMediaSession();
        notifyState();
    }

    private void nextInternal(boolean userAction) {
        if (!AudioQueue.get().hasNext()) {
            if (userAction) MsgUtil.showMsg("已经是最后一首");
            return;
        }
        //切歌前把当前这首的位置落盘（切完 current 就变成下一首了）
        persistProgressNow();
        AudioQueue.get().next();
        playCurrent(0);
    }

    private void previousInternal() {
        if (!AudioQueue.get().hasPrevious()) {
            MsgUtil.showMsg("已经是第一首");
            return;
        }
        persistProgressNow();
        AudioQueue.get().previous();
        playCurrent(0);
    }

    private void seekInternal(int ms) {
        IjkMediaPlayer mp = player;
        if (mp == null || !prepared) return;
        try {
            mp.seekTo(Math.max(0, ms));
            positionMs = Math.max(0, ms);
            //开一个宽限期：这期间若因重连报 onError，走"同曲重试"而不是跳下一首
            seekGraceUntil = System.currentTimeMillis() + 6000L;
            seekRecoverCount = 0;
            resumeAfterSeekMs = Math.max(0, ms);
            diag("seek 到 " + positionMs + "ms，宽限期 6s");
        } catch (Throwable ignored) {
        }
        notifyState();
    }

    private void stopPlayback() {
        releasePlayer();
        AudioQueue.get().clear();
        stopProgressTimer();
        if (mediaSessionHelper != null) {
            mediaSessionHelper.release();
            mediaSessionHelper = null;
        }
        notifyState();
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
        stopSelf();
    }

    /** 「听歌快速加载」开关：预取 + 本地缓存代理；关闭后完全走直连、不占本地空间 */
    private boolean fastLoadEnabled() {
        return SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.AUDIO_FAST_LOAD, true);
    }

    /**
     * 快速加载开启时把远程地址换成 127.0.0.1 的缓存代理；关闭或代理启动失败返回 null（回退直连）。
     *
     * <p>换线时 URL 会变，但缓存键是 cid——同一分P 的不同 CDN 内容一致，已缓存的前缀可以直接复用。
     */
    private String fastLoadProxyUrl(long cid, String source) {
        if (!fastLoadEnabled() || cid <= 0 || source == null || source.isEmpty()) return null;
        try {
            AudioCacheProxy proxy = AudioCacheProxy.get(this);
            proxy.setPlayingCid(cid);
            return proxy.localUrl(cid, source);
        } catch (Throwable t) {
            diag("缓存代理不可用，回退直连: " + t);
            return null;
        }
    }

    /** 撤销起播看门狗：换歌、换线、成功起播、释放播放器时都必须调，避免旧定时器误伤新播放器 */
    private void cancelStartupWatchdog() {
        if (startupWatchdog != null) {
            mainHandler.removeCallbacks(startupWatchdog);
            startupWatchdog = null;
        }
    }

    private void releasePlayer() {
        cancelStartupWatchdog();
        IjkMediaPlayer mp = player;
        player = null;
        prepared = false;
        playing = false;
        if (mp == null) return;
        try {
            mp.stop();
        } catch (Throwable ignored) {
        }
        try {
            mp.release();
        } catch (Throwable ignored) {
        }
    }

    // ==================== 通知 / MediaSession ====================

    @SuppressLint("UnspecifiedImmutableFlag")
    private Notification buildNotification() {
        VideoCard item = AudioQueue.get().current();
        String title = item != null && item.title != null ? item.title : "音频播放";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.icon)
                .setContentTitle(title)
                .setContentText(notificationText(item))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        builder.addAction(0, "上一首", prevPending);
        builder.addAction(playing ? R.drawable.btn_player_pause : R.drawable.btn_player_play,
                playing ? "暂停" : "播放", togglePending);
        builder.addAction(0, "下一首", nextPending);
        builder.addAction(0, "关闭", stopPending);

        builder.setContentIntent(contentPending);
        return builder.build();
    }

    /** 通知正文（标题与进度）：buildNotification 与内容快照共用，保证两处永远同源 */
    private String notificationText(VideoCard item) {
        if (item == null) return "";
        return AudioQueue.get().positionText()
                + (item.upName != null && !item.upName.isEmpty() ? " · " + item.upName : "")
                + "  " + formatTime(positionMs) + " / " + formatTime(durationMs);
    }

    /** 通知内容快照：内容没变就不重建、不重发（暂停挂后台时每 2 秒一次纯属浪费） */
    private String notificationSnapshot() {
        VideoCard item = AudioQueue.get().current();
        return (playing ? 1 : 0) + "|"
                + (item != null && item.title != null ? item.title : "音频播放") + "|"
                + notificationText(item);
    }

    /**
     * 5 个 PendingIntent 只在服务创建时建一次：系统内部按 (组件, action, requestCode) 复用，
     * 每次 getXxx 仍是一次 binder 往返，而进度 tick 每 2 秒就会走一遍 buildNotification
     */
    @SuppressLint("UnspecifiedImmutableFlag")
    private void initNotificationIntents() {
        prevPending = actionPending(ACTION_PREV, 1);
        togglePending = actionPending(ACTION_TOGGLE, 2);
        nextPending = actionPending(ACTION_NEXT, 3);
        stopPending = actionPending(ACTION_STOP, 4);
        Intent content = new Intent(this, AudioPlayerActivity.class);
        content.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        contentPending = PendingIntent.getActivity(this, 5, content,
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent actionPending(String action, int requestCode) {
        Intent intent = new Intent(this, AudioPlayerService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void refreshNotification() {
        try {
            String snapshot = notificationSnapshot();
            if (snapshot.equals(lastNotificationSnapshot)) return;
            lastNotificationSnapshot = snapshot;
            notifyManager.notify(NOTIFICATION_ID, buildNotification());
        } catch (Throwable ignored) {
        }
    }

    private void initMediaSession() {
        if (Build.VERSION.SDK_INT < 21 || mediaSessionHelper != null) return;
        try {
            mediaSessionHelper = new MediaSessionHelper(this, "BiliClientAudio", new MediaSessionHelper.Callback() {
                @Override
                public void onPlay() {
                    mainHandler.post(() -> {
                        if (!playing && prepared) toggleInternal();
                    });
                }

                @Override
                public void onPause() {
                    mainHandler.post(() -> {
                        if (playing) toggleInternal();
                    });
                }

                @Override
                public void onSeekTo(long positionMs) {
                    mainHandler.post(() -> seekInternal((int) positionMs));
                }

                @Override
                public void onSkipToNext() {
                    mainHandler.post(() -> nextInternal(true));
                }

                @Override
                public void onSkipToPrevious() {
                    mainHandler.post(AudioPlayerService.this::previousInternal);
                }
            });
        } catch (Throwable t) {
            Logu.e("audio-service", "MediaSession 初始化失败: " + t);
        }
    }

    private void updateMediaSession() {
        if (mediaSessionHelper == null) return;
        VideoCard item = AudioQueue.get().current();
        mediaSessionHelper.setMetadata(item != null ? item.title : "", durationMs);
        mediaSessionHelper.setPlaybackState(playing, positionMs,
                AudioQueue.get().hasNext(), AudioQueue.get().hasPrevious());
    }

    // ==================== 进度轮询 / 耳机拔出 / 诊断 ====================

    private void startProgressTimer() {
        if (progressTimer != null) return;
        progressTimer = new Timer();
        progressTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    IjkMediaPlayer mp = player;
                    if (mp != null && prepared) {
                        positionMs = (int) Math.max(0, mp.getCurrentPosition());
                        int d = (int) mp.getDuration();
                        if (d > 0) durationMs = d;
                    }
                } catch (Throwable ignored) {
                }
                //通知降频到 2 秒，避免每秒重建通知导致通知栏/锁屏卡顿
                mainHandler.post(() -> {
                    if (destroyed) return;
                    refreshNotification();
                    updateMediaSession();
                    notifyState();
                    persistProgressIfDue();
                });
            }
        }, 1000, NOTIFY_INTERVAL_MS);
    }

    private void stopProgressTimer() {
        if (progressTimer != null) {
            progressTimer.cancel();
            progressTimer = null;
        }
    }

    /** 进度落盘节流：5 秒一次，避免每秒写 SharedPreferences */
    private long lastPersistAt = 0L;

    private void persistProgressIfDue() {
        long now = System.currentTimeMillis();
        if (now - lastPersistAt < 5000) return;
        lastPersistAt = now;
        persistProgressNow();
    }

    /**
     * 写本地续播记录：下次在同一收藏夹点「听全部」时用来定位到这首、这个位置。
     *
     * <p>只存 aid/cid/位置这类标识，不存播放地址（会过期）；隐私模式、位置过小、
     * 已经播到结尾的判断都在 {@link AudioProgressStore#save} 里。
     */
    private void persistProgressNow() {
        try {
            AudioQueue queue = AudioQueue.get();
            VideoCard item = queue.current();
            if (item == null || !queue.isActive() || item.cid <= 0) return;
            AudioProgressStore.Progress progress = new AudioProgressStore.Progress();
            progress.mediaId = queue.getMediaId();
            progress.order = queue.getOrder();
            progress.folderName = queue.getFolderName();
            progress.index = queue.getIndex();
            progress.aid = item.aid;
            progress.cid = item.cid;
            progress.positionMs = positionMs;
            progress.durationMs = durationMs;
            progress.title = item.title;
            progress.upName = item.upName;
            progress.cover = item.cover;
            AudioProgressStore.save(progress);
        } catch (Throwable t) {
            diag("保存听歌进度失败: " + t);
        }
    }

    private void registerNoisyReceiver() {
        if (noisyRegistered) return;
        noisyReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())
                        && playing && prepared) {
                    mainHandler.post(AudioPlayerService.this::toggleInternal);
                }
            }
        };
        try {
            registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
            noisyRegistered = true;
        } catch (Throwable t) {
            Logu.e("audio-service", "注册耳机拔出监听失败: " + t);
        }
    }

    private void unregisterNoisyReceiver() {
        if (!noisyRegistered || noisyReceiver == null) return;
        try {
            unregisterReceiver(noisyReceiver);
        } catch (Throwable ignored) {
        }
        noisyRegistered = false;
        noisyReceiver = null;
    }

    private void notifyState() {
        for (Listener listener : sListeners) {
            try {
                listener.onAudioStateChanged();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 诊断日志：同时写 logcat 和文件（logcat 会被新进程冲掉，文件可 adb pull） */
    private void diag(String message) {
        Log.e(TAG, message);
        try {
            File file = new File(getExternalFilesDir(null), "audio-debug.log");
            if (file.length() > 512 * 1024) file.delete();
            FileWriter writer = new FileWriter(file, true);
            writer.write(new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())
                    + " " + message + "\n");
            writer.close();
        } catch (Throwable ignored) {
        }
    }

    private static String urlTail(String url) {
        if (url == null) return "null";
        int start = Math.max(0, url.length() - 60);
        return url.substring(start);
    }

    /** 只取主机名：候选地址失败时能一眼看出是哪个 CDN（PCDN 节点是失败常客） */
    private static String urlHost(String url) {
        try {
            okhttp3.HttpUrl parsed = okhttp3.HttpUrl.parse(url);
            return parsed != null && parsed.host() != null ? parsed.host() : "?";
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String formatTime(int ms) {
        int totalSec = Math.max(ms, 0) / 1000;
        return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60);
    }
}
