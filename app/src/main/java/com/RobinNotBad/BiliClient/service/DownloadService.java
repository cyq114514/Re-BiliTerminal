package com.RobinNotBad.BiliClient.service;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity;
import com.RobinNotBad.BiliClient.activity.video.local.DownloadListActivity;
import com.RobinNotBad.BiliClient.activity.video.local.LocalListActivity;
import com.RobinNotBad.BiliClient.api.PlayerApi;
import com.RobinNotBad.BiliClient.helper.sql.DownloadSqlHelper;
import com.RobinNotBad.BiliClient.model.DownloadSection;
import com.RobinNotBad.BiliClient.model.PlayerData;
import com.RobinNotBad.BiliClient.model.SubtitleLink;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.FileUtil;
import com.RobinNotBad.BiliClient.util.GlideUtil;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.MediaMerger;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;

import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Inflater;

import okhttp3.HttpUrl;
import okhttp3.Response;
import okio.BufferedSink;
import okio.Okio;
import okio.Sink;

public class DownloadService extends Service {
    public static volatile boolean started;
    /** 同一时刻只允许一个下载 worker：START_STICKY 重投递/失败重试/重复 start 都会再进 onStartCommand */
    private static final AtomicBoolean WORKER_RUNNING = new AtomicBoolean(false);
    public static volatile int exitCode;
    public static volatile float percent = -1;
    public static volatile String state;
    public static volatile DownloadSection section;
    private static long firstDown;

    final String NOTIFICATION_CHANNEL_ID = "biliterminal_download";
    final int FOREGROUND_ID = 1027;
    NotificationCompat.Builder statusBuilder, completionBuilder;
    NotificationManager notifyManager;

    private String exitMessage = null;

    private Timer notifyTimer;

    private static final int NORMAL = 0;
    private static final int ERR_NETWORK = -1;
    private static final int ERR_JSON = -2;
    private static final int ERR_FILE = -3;
    private static final int ERR_DATABASE = -4;
    private static final int ERR_UNKNOWN = -7;

    public DownloadService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();

        Logu.d("onCreate");

        notifyManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, "Re：哔哩终端下载服务",
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Re：哔哩终端下载服务");
            channel.setSound(null, null);
            channel.enableVibration(false);

            notifyManager.createNotificationChannel(channel);
        }

        Intent intent = new Intent(this, DownloadListActivity.class);
        @SuppressLint("UnspecifiedImmutableFlag")
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, 0);
        statusBuilder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.mipmap.icon)
                .setContentTitle("下载视频中")
                .setProgress(100, 0, false)
                .setContentIntent(pendingIntent)
                .setSound(null)
                .setVibrate(null)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        completionBuilder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.mipmap.icon)
                .setContentTitle("下载完成")
                .setContentIntent(pendingIntent)
                .setOngoing(false)
                .setSound(null)
                .setVibrate(null)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @SuppressLint("MutatingSharedPrefs")
    @Override
    public int onStartCommand(Intent serviceIntent, int flags, int startId) {
        Logu.d("onStartCommand");
        startForeground(FOREGROUND_ID, statusBuilder.build());

        //START_STICKY 进程级重启时 intent 为 null：static 标记已复位，若不恢复 started，
        //下面的 worker 会立即空转退出，队列永久卡在 downloading 且服务空占前台
        if (serviceIntent == null) {
            started = true;
        }

        exitCode = ERR_UNKNOWN;
        startNotifyProgress();

        //重复进入 onStartCommand（START_STICKY 进程级重启、失败重试、重复 start）时不再起第二个 worker：
        //两个 worker 会同时扫同一张 download 表，重复下载同一条目、互相删对方正在写的目录
        if (!WORKER_RUNNING.compareAndSet(false, true)) {
            Logu.w("download", "已有下载 worker 在运行，忽略重复启动");
            return Service.START_STICKY;
        }

        CenterThreadPool.run(() -> {
            try {
            boolean failed = false;
            while (!failed && started) {
                DownloadSection section_tmp = getFirst();
                if (section_tmp == null)
                    break;

                section = section_tmp;

                // 获取视频链接（候选列表：官方 CDN 优先、PCDN 垫底，见 PlayerApi.buildMediaCandidates）
                List<String> urls_video, urls_audio;
                String url_danmaku;
                try {
                    PlayerData data = section.toPlayerData();

                    // 如果是仅音频下载，使用DASH格式获取音频流
                    if (section.isAudioOnly()) {
                        PlayerApi.getVideoDash(data);
                        //优先用刚拉取的新链接：数据库里持久化的 URL 数小时就过期，
                        //重试/延迟下载的音频必然 403
                        urls_audio = new ArrayList<>(data.audioUrls);
                        if (urls_audio.isEmpty() && data.audioUrl != null && !data.audioUrl.isEmpty())
                            urls_audio.add(data.audioUrl);
                        if (urls_audio.isEmpty() && section.audioUrl != null && !section.audioUrl.isEmpty())
                            urls_audio.add(section.audioUrl);
                        urls_video = null; // 仅音频模式不需要视频
                    } else if ("video_dash".equals(section.downloadType)) {
                        // DASH 双流下载（1080P 高码率/4K 等 durl 拿不到的清晰度）：
                        // 视频、音频各下一份后在本地 remux 成单个 MP4
                        PlayerApi.getVideoDash(data);
                        urls_video = new ArrayList<>(data.videoUrls);
                        if (urls_video.isEmpty() && data.videoUrl != null && !data.videoUrl.isEmpty())
                            urls_video.add(data.videoUrl);
                        urls_audio = new ArrayList<>(data.audioUrls);
                        if (urls_audio.isEmpty() && data.audioUrl != null && !data.audioUrl.isEmpty())
                            urls_audio.add(data.audioUrl);
                        if (urls_video.isEmpty()) {
                            // DASH 没给视频流（不该发生）：退回普通单文件取流兜底
                            PlayerApi.getVideo(data, true);
                            urls_video = new ArrayList<>(data.videoUrls);
                            if (urls_video.isEmpty() && data.videoUrl != null && !data.videoUrl.isEmpty())
                                urls_video.add(data.videoUrl);
                            urls_audio = null;
                        }
                    } else {
                        PlayerApi.getVideo(data, true);
                        urls_video = new ArrayList<>(data.videoUrls);
                        if (urls_video.isEmpty() && data.videoUrl != null && !data.videoUrl.isEmpty())
                            urls_video.add(data.videoUrl);
                        urls_audio = null;
                    }
                    url_danmaku = data.danmakuUrl;
                } catch (JSONException e) {
                    setState(section.id, "error");
                    notifyCompletion("下载链接获取失败：\n" + section.name_short, (int) section.id);
                    section = null;
                    refreshDownloadList();
                    continue;
                } catch (IOException e) {
                    failed = true;
                    exitCode = ERR_NETWORK;
                    setState(section.id, "none");
                    continue;
                }

                try {
                    setState(section.id, "downloading");
                    percent = 0;
                    refreshDownloadList();

                    File file_sign = null;

                    int result;

                    switch (section.type) {
                        case "video_single": // 单集视频
                            File path_single = section.getPath();

                            file_sign = new File(path_single, ".DOWNLOADING");
                            if (!file_sign.exists() && !file_sign.createNewFile()) {
                                failed = true;
                                exitCode = ERR_FILE;
                                setState(section.id, "error");   //失败落库，否则该条目永久停留在 downloading 被误锁
                                continue;
                            }

                            toastState("下载封面");
                            result = downFile(section.url_cover, new File(path_single, "cover.png"));
                            if (result != NORMAL) {
                                failed = true;
                                exitCode = result;
                                setState(section.id, "error");   //失败落库，否则该条目永久停留在 downloading 被误锁
                                continue;
                            }

                            // 仅音频模式不下载字幕和弹幕
                            if (!section.isAudioOnly()) {
                                toastState("下载字幕");
                                downSubtitles(section.aid, section.cid, path_single);

                                toastState("下载弹幕");
                                result = downDanmaku(url_danmaku, new File(path_single, "danmaku.xml"));
                                if (result != NORMAL) {
                                    failed = true;
                                    exitCode = result;
                                    setState(section.id, "error");   //失败落库，否则该条目永久停留在 downloading 被误锁
                                    continue;
                                }
                            }

                            // 根据下载类型选择下载内容
                            if (section.isAudioOnly()) {
                                toastState("下载音频");
                                result = downFile(urls_audio, new File(path_single, "audio.m4a"), true);
                                if (result != NORMAL) {
                                    failed = true;
                                    exitCode = result;
                                    setState(section.id, "error");   //失败落库，否则该条目永久停留在 downloading 被误锁
                                    continue;
                                }
                            } else if ("video_dash".equals(section.downloadType)) {
                                result = downloadDashPair(urls_video, urls_audio, path_single);
                                if (result != NORMAL) {
                                    failed = true;
                                    exitCode = result;
                                    setState(section.id, "error");   //失败落库，否则该条目永久停留在 downloading 被误锁
                                    continue;
                                }
                            } else {
                                toastState("下载视频");
                                result = downFile(urls_video, new File(path_single, "video.mp4"), true);
                                if (result != NORMAL) {
                                    failed = true;
                                    exitCode = result;
                                    setState(section.id, "error");   //失败落库，否则该条目永久停留在 downloading 被误锁
                                    continue;
                                }
                            }

                            break;
                        case "video_multi": // 多集视频
                            File path_page = section.getPath();
                            File path_parent = path_page.getParentFile();

                            if (!path_page.exists() && !path_page.mkdirs()) {
                                failed = true;
                                exitCode = ERR_FILE;
                                setState(section.id, "error");   //失败落库，否则该条目永久停留在 downloading 被误锁
                                continue;
                            }

                            file_sign = new File(path_page, ".DOWNLOADING");
                            if (!file_sign.exists() && !file_sign.createNewFile()) {
                                failed = true;
                                exitCode = ERR_FILE;
                                setState(section.id, "error");   //失败落库，否则该条目永久停留在 downloading 被误锁
                                continue;
                            }

                            toastState("下载封面");
                            File cover_multi = new File(path_parent, "cover.png");
                            if (!cover_multi.exists()) {
                                result = downFile(section.url_cover, cover_multi);
                                if (result != NORMAL) {
                                    failed = true;
                                    exitCode = result;
                                    setState(section.id, "error");   //失败落库，否则该条目永久停留在 downloading 被误锁
                                    continue;
                                }
                            }

                            // 仅音频模式不下载字幕和弹幕
                            if (!section.isAudioOnly()) {
                                toastState("下载字幕");
                                downSubtitles(section.aid, section.cid, path_page);

                                toastState("下载弹幕");
                                result = downDanmaku(url_danmaku, new File(path_page, "danmaku.xml"));
                                if (result != NORMAL) {
                                    failed = true;
                                    exitCode = result;
                                    setState(section.id, "error");   //失败落库，否则该条目永久停留在 downloading 被误锁
                                    continue;
                                }
                            }

                            // 根据下载类型选择下载内容
                            if (section.isAudioOnly()) {
                                toastState("下载音频");
                                result = downFile(urls_audio, new File(path_page, "audio.m4a"), true);
                                if (result != NORMAL) {
                                    failed = true;
                                    exitCode = result;
                                    setState(section.id, "error");   //失败落库，否则该条目永久停留在 downloading 被误锁
                                    continue;
                                }
                            } else if ("video_dash".equals(section.downloadType)) {
                                result = downloadDashPair(urls_video, urls_audio, path_page);
                                if (result != NORMAL) {
                                    failed = true;
                                    exitCode = result;
                                    setState(section.id, "error");   //失败落库，否则该条目永久停留在 downloading 被误锁
                                    continue;
                                }
                            } else {
                                toastState("下载视频");
                                result = downFile(urls_video, new File(path_page, "video.mp4"), true);
                                if (result != NORMAL) {
                                    failed = true;
                                    exitCode = result;
                                    setState(section.id, "error");   //失败落库，否则该条目永久停留在 downloading 被误锁
                                    continue;
                                }
                            }

                            break;
                    }

                    notifyCompletion("下载成功：\n" + section.name_short, (int) section.id);

                    if (file_sign != null && file_sign.exists())
                        file_sign.delete();

                    deleteSection(section.id);

                    refreshLocalList();
                    section = null;
                } catch (IOException e) {
                    failed = true;
                    exitCode = ERR_FILE;
                    setState(section.id, "error");
                }
            }

            section = null;
            refreshDownloadList();

            if (!failed) {
                exitCode = NORMAL;
                exitMessage = "全部下载完成";
            } else
                switch (exitCode) {
                    case ERR_NETWORK:
                        exitMessage = "下载失败，网络错误";
                        break;
                    case ERR_JSON:
                        exitMessage = "下载失败，视频链接获取错误";
                        break;
                    case ERR_FILE:
                        exitMessage = "下载失败，文件错误";
                        break;
                    case ERR_DATABASE:
                        exitMessage = "下载失败，数据库错误";
                        break;
                    default:
                        exitMessage = "下载失败，未知错误";
                }
            } catch (Throwable t) {
                //worker 内任何未预料的异常都必须在这里收口：交给线程池只会弹个提示，
                //而 started 会一直为 true，之后所有下载入口都提示"队列已在进行中"，只能杀进程恢复
                exitCode = ERR_UNKNOWN;
                exitMessage = "下载失败：" + t;
                Logu.e("download", "下载 worker 异常退出：" + t);
                t.printStackTrace();
                section = null;
                refreshDownloadList();
            } finally {
                //无论正常/异常/被取消，都必须复位状态并让服务退出，否则前台通知常驻
                started = false;
                WORKER_RUNNING.set(false);
                stopSelf();
            }
        });

        return Service.START_STICKY;
    }

    private void toastState(String newState) {
        state = newState;
        percent = 0;
    }

    private void startNotifyProgress() {
        //多次 start（START_STICKY 重投递、失败重试）会重复走到这里：新建前必须取消旧实例，
        //否则旧 Timer 线程泄漏并重复发通知
        if (notifyTimer != null)
            notifyTimer.cancel();
        notifyTimer = new Timer();
        notifyTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    DownloadSection currentSection = section;
                    String currentState = state;
                    if (currentSection == null || notifyTimer == null)
                        return;

                    statusBuilder.setContentText(currentState + "：" + currentSection.name_short);
                    statusBuilder.setProgress(100, (int) Math.max(0, Math.min(percent, 1f) * 100), false);
                    notifyManager.notify(FOREGROUND_ID, statusBuilder.build());
                } catch (Exception e) {
                    //TimerTask 抛未捕获异常会永久终止整个 Timer，进度通知从此静默失效
                    e.printStackTrace();
                }
            }
        }, 500, 500);
    }

    private void notifyExit(String content) {
        MsgUtil.showMsg(content);
        notifyManager.cancel(FOREGROUND_ID);
        completionBuilder.setContentTitle("下载结束");
        completionBuilder.setContentText(content);
        completionBuilder.setProgress(0, 0, false);
        notifyManager.notify(2, completionBuilder.build());
    }

    private void notifyCompletion(String content, int id) {
        MsgUtil.showMsg(content);
        completionBuilder.setContentText(content);
        notifyManager.notify(id % 100 + 100, completionBuilder.build());
    }

    private void refreshDownloadList() {
        if (DownloadListActivity.weakRef != null && DownloadListActivity.weakRef.get() != null) {
            DownloadListActivity.weakRef.get().refreshList(true);
        }
    }

    private void refreshLocalList() {
        InstanceActivity instance = BiliTerminal.getInstanceActivityOnTop();
        if (instance instanceof LocalListActivity && !instance.isDestroyed())
            ((LocalListActivity) (instance)).refresh();
    }

    private int downSubtitles(long aid, long cid, File folder) {
        try {
            SubtitleLink[] subtitleLinks = PlayerApi.getSubtitleLinks(aid, cid);
            if (subtitleLinks.length <= 1)
                return NORMAL;

            File subtitleFolder = new File(folder, "subtitles");
            //mkdirs 在目录已存在时返回 false，不能当失败；重下/残目录场景会误杀整个下载单
            if (!subtitleFolder.isDirectory() && !subtitleFolder.mkdirs())
                return ERR_FILE;
            for (SubtitleLink subtitleLink : subtitleLinks) {
                if (subtitleLink.id != -1) {
                    File subtitleFile = new File(subtitleFolder, subtitleLink.lang + ".json");
                    if (!subtitleFile.exists() && !subtitleFile.createNewFile())
                        return ERR_FILE;
                    int result = downFile(subtitleLink.url, subtitleFile);
                    if (result != NORMAL)
                        return result;
                }
            }
        } catch (IOException e) {
            return ERR_NETWORK;
        } catch (JSONException e) {
            return ERR_JSON;
        }
        return NORMAL;
    }

    /** 一条可下载的媒体地址 + 它是否属于 B 站自有域名（决定能否携带登录 Cookie）。 */
    private static final class MediaSource {
        final String url;
        final boolean trusted;

        MediaSource(String url, boolean trusted) {
            this.url = url;
            this.trusted = trusted;
        }
    }

    /**
     * 把服务端下发的地址解析成可下载的 {@link MediaSource}（两档策略）。
     *
     * <p><b>为什么改</b>：B 站现在对 1080P 的 mp4（format=mp4720）与 DASH 流下发的首地址
     * 大量是 PCDN —— {@code *.mcdn.bilivideo.cn:8082}（B 站自有）与
     * {@code *.edge.mountaintoys.cn:4483}（第三方 PCDN）。改造前这里只放行 bilivideo.com，
     * 于是下载第一步就返回 ERR_NETWORK，用户看到的就是「下载失败，网络错误」；
     * 而 backup_url 里一直带着官方 {@code upos-sz-*.bilivideo.com}。
     *
     * <p><b>策略</b>：
     * <ul>
     *   <li>B 站自有域：与改造前一致，带完整请求头（含 Cookie），http 升级为 https；</li>
     *   <li>非 B 站域：只有"确实来自 playurl 响应"（fromPlayUrl）才放行，且<b>一律不带 Cookie</b>，
     *       只保留 UA/Referer/Origin —— 第三方 PCDN 是合法播放地址，但不是 B 站资产；</li>
     *   <li>其余（用户输入、未知来源）仍然拒绝。</li>
     * </ul>
     */
    private static MediaSource resolveMediaSource(String url, boolean fromPlayUrl) {
        if (url == null || url.isEmpty()) return null;
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null) return null;
        boolean trusted = NetWorkUtil.isBilibiliHost(parsed.host());
        if (trusted) {
            if (!parsed.isHttps()) parsed = parsed.newBuilder().scheme("https").build();
            return new MediaSource(parsed.toString(), true);
        }
        if (!fromPlayUrl) return null;
        //第三方 PCDN 节点：保持原 scheme（部分节点只在特定端口上监听 http），且永远拿不到 Cookie
        return new MediaSource(parsed.toString(), false);
    }

    private static String hostOf(String url) {
        try {
            HttpUrl parsed = HttpUrl.parse(url);
            return parsed != null ? parsed.host() : "?";
        } catch (Exception e) {
            return "?";
        }
    }

    /**
     * DASH 双流下载：视频、音频各下一份，再用 MediaMerger remux 成单个 video.mp4。
     *
     * <p>临时文件统一在 finally 里清理——合并失败保留分轨没有消费方（本地列表只认 video.mp4），
     * 反而会占空间；任务已按 error 落库，重新下载即可。
     *
     * @return NORMAL 成功；否则返回错误码（由调用方落 error 状态）
     */
    private int downloadDashPair(List<String> urls_video, List<String> urls_audio, File targetDir) throws IOException {
        File videoTmp = new File(targetDir, "video_dash_tmp.mp4");
        File audioTmp = new File(targetDir, "audio_dash_tmp.m4a");
        File merged = new File(targetDir, "video.mp4");
        try {
            toastState("下载视频");
            int result = downFile(urls_video, videoTmp, true);
            if (result != NORMAL) return result;

            if (urls_audio == null || urls_audio.isEmpty()) {
                //无音轨（极少见）：视频临时文件直接转正
                if (!videoTmp.renameTo(merged)) return ERR_FILE;
                return NORMAL;
            }

            toastState("下载音频");
            int audioResult = downFile(urls_audio, audioTmp, true);
            if (audioResult != NORMAL) return audioResult;

            toastState("合并音视频");
            int mergeResult = MediaMerger.mergeAv(videoTmp, audioTmp, merged, null, () -> !started);
            if (mergeResult != MediaMerger.RESULT_OK) {
                Logu.e("download", "DASH 合并失败（" + mergeResult + "）");
                MsgUtil.showMsg("音视频合并失败（" + mergeResult + "），请重新下载");
                return ERR_UNKNOWN;
            }
            return NORMAL;
        } finally {
            videoTmp.delete();
            audioTmp.delete();
        }
    }

    /** 下载单一可信地址（封面、字幕等）。 */
    private int downFile(String url, File file) throws IOException {
        ArrayList<String> one = new ArrayList<>(1);
        one.add(url);
        return downFile(one, file, false);
    }

    /**
     * 下载播放媒体（视频/音频）：逐个候选地址尝试，带 Range 断点续传。
     *
     * <p>候选列表由 {@link PlayerApi#buildMediaCandidates} 整理：官方 CDN 在前、PCDN 在后。
     * 任何一个候选成功即返回，全部失败才报 ERR_NETWORK —— 这样既不会被自家域名校验拦死，
     * 又能优先走快的官方线路（实测 PCDN 拉 200KB 要 5 秒，官方 CDN 约 1 秒）。
     *
     * @param urls        候选地址
     * @param fromPlayUrl 是否直接来自 playurl 响应（决定非 B 站域能否放行）
     */
    private int downFile(List<String> urls, File file, boolean fromPlayUrl) throws IOException {
        if (urls == null || urls.isEmpty()) return ERR_NETWORK;
        int lastResult = ERR_NETWORK;
        for (int i = 0; i < urls.size(); i++) {
            if (!started) return ERR_UNKNOWN;
            MediaSource source = resolveMediaSource(urls.get(i), fromPlayUrl);
            if (source == null) {
                Logu.w("download", "候选 " + (i + 1) + "/" + urls.size() + " 被域名策略拒绝");
                continue;
            }
            int result = downloadFrom(source, file);
            if (result == NORMAL) return NORMAL;
            lastResult = result;
            Logu.e("download", "候选 " + (i + 1) + "/" + urls.size() + " 失败(" + result + ") host=" + hostOf(source.url));
        }
        return lastResult;
    }

    /** 单个候选地址的实际下载：支持 Range 续传 + 长度校验。 */
    private int downloadFrom(MediaSource source, File file) throws IOException {
        ArrayList<String> headers = source.trusted
                ? new ArrayList<>(NetWorkUtil.webHeaders)
                : NetWorkUtil.webHeadersWithoutCookie();

        long existing = file.exists() ? file.length() : 0;
        boolean resume = existing > 0;
        if (resume) {
            headers.add("Range");
            headers.add("bytes=" + existing + "-");
        }

        Response response;
        try {
            response = NetWorkUtil.get(source.url, headers);
        } catch (IOException e) {
            return ERR_NETWORK;
        }

        InputStream inputStream = null;
        FileOutputStream fileOutputStream = null;
        try {
            int code = response.code();
            boolean append;
            if (resume && code == 206) {
                append = true;
            } else if (resume && code == 416) {
                //Range 超出文件末尾：本地那份其实已经完整
                response.close();
                return NORMAL;
            } else if (response.isSuccessful()) {
                //200：服务端忽略了 Range（或不支持续传）→ 从头覆盖写
                append = false;
                existing = 0;
            } else {
                //B 站错误响应多为 JSON：不校验状态码会把错误页写进 video.mp4，本地列表显示可播放
                Logu.e("download", "HTTP " + code + " host=" + hostOf(source.url));
                response.close();
                //403/412 多为地址过期，换下一个候选比原地重试更有意义
                return ERR_NETWORK;
            }

            if (!append) {
                if (file.exists() && !file.delete()) return ERR_FILE;
                if (!file.createNewFile()) return ERR_FILE;
            } else if (!file.exists() && !file.createNewFile()) {
                return ERR_FILE;
            }

            if (response.body() == null) {
                //没有响应体（服务端异常/被拦截）：当网络错误处理，交给下一个候选
                return ERR_NETWORK;
            }
            long bodyLength = response.body().contentLength();
            long total = bodyLength > 0 ? existing + bodyLength : -1;
            percent = (total > 0 && existing > 0) ? 1f * existing / total : 0;

            inputStream = Objects.requireNonNull(response.body()).byteStream();
            fileOutputStream = new FileOutputStream(file, append);
            int len;
            byte[] bytes = new byte[1024 * 10];
            long written = existing;
            while ((len = inputStream.read(bytes)) != -1 && started) {
                fileOutputStream.write(bytes, 0, len);
                written += len;
                //长度未知（chunked/-1）时不算百分比，避免负数/NaN
                if (total > 0) percent = 1f * written / total;
            }
            fileOutputStream.flush();
            if (!started) return ERR_UNKNOWN;

            //完整性校验：PCDN 节点偶尔提前断流，长度对不上就判本次候选失败，
            //交给下一个候选（或下次下载从断点续传）
            if (total > 0 && file.length() < total) {
                Logu.e("download", "长度不足 " + file.length() + "/" + total + " host=" + hostOf(source.url));
                return ERR_FILE;
            }
            return NORMAL;
        } catch (IOException e) {
            return ERR_FILE;
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException ignored) {
                }
            }
            if (fileOutputStream != null) {
                try {
                    fileOutputStream.close();
                } catch (IOException ignored) {
                }
            }
            if (response.body() != null) response.body().close();
            response.close();
        }
    }

    private int downDanmaku(String danmaku, File danmakuFile) throws IOException {
        //弹幕地址固定是 comment.bilibili.com，属于 B 站自有域，不允许走"第三方 PCDN"那档
        MediaSource source = resolveMediaSource(danmaku, false);
        if (source == null) return ERR_NETWORK;
        String safeUrl = source.url;
        Response response;
        try {
            response = NetWorkUtil.get(safeUrl);
        } catch (IOException e) {
            return ERR_NETWORK;
        }
        BufferedSink bufferedSink = null;
        try {
            if (!danmakuFile.exists() && !danmakuFile.createNewFile())
                return ERR_FILE;
            else if (!danmakuFile.delete() || !danmakuFile.createNewFile())
                return ERR_FILE;

            Sink sink = Okio.sink(danmakuFile);
            byte[] decompressBytes = decompress(Objects.requireNonNull(response.body()).bytes());// 调用解压函数进行解压，返回包含解压后数据的byte数组
            bufferedSink = Okio.buffer(sink);
            bufferedSink.write(decompressBytes);// 将解压后数据写入文件（sink）中
            bufferedSink.close();
        } catch (IOException e) {
            return ERR_FILE;
        } finally {
            if (bufferedSink != null)
                bufferedSink.close();
            if (response.body() != null)
                response.body().close();
            response.close();
        }
        return NORMAL;
    }

    @Override
    public void onDestroy() {
        Logu.d("结束");

        //只有"用户主动取消"（stopService）时 started 才仍为 true：worker 正常/异常收尾都会先把它
        //置 false 再 stopSelf。原实现无条件清理目录，会把"下载失败待重试"的半成品也一并删掉，
        //在双 worker 场景下更会删掉另一个 worker 正在写的目录（表现为"下载到一半自己没了"）
        final boolean canceledByUser = started;
        started = false;
        percent = -1;
        state = null;

        if (notifyTimer != null)
            notifyTimer.cancel();
        notifyTimer = null;

        if (exitMessage == null)
            exitMessage = "下载服务已退出";

        Logu.d("退出下载服务");
        //无论是否还有未完成条目都要发总结通知：worker 正常收尾时会把 section 置 null，
        //此前只有 section != null 才通知，成功/失败汇总全部丢失
        final DownloadSection pendingSection = section;
        section = null;

        CenterThreadPool.run(() -> {
            if (pendingSection != null && canceledByUser) {
                //用户取消：残留条目恢复为可重试状态并清理半成品
                //（异常退出不删：失败的条目已由 worker 置 error，留着文件供下次续传）
                setState(pendingSection.id, "none");
                FileUtil.deleteFolder(pendingSection.getPath());
            }
            notifyExit(exitMessage);
            refreshDownloadList();
        });

        super.onDestroy();
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
                //数据截断/损坏时 inflate 恒返回 0 且 finished() 恒 false，不 break 会 100% CPU 死循环卡死整个下载服务
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
                decompresser.end();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return output;
    }

    public static DownloadSection getFirst() {
        Cursor cursor = null;
        SQLiteDatabase database = null;
        try {
            DownloadSqlHelper helper = new DownloadSqlHelper(BiliTerminal.context);
            database = helper.getReadableDatabase();

            if (firstDown >= 0) {
                cursor = database.rawQuery("select * from download where id=? limit 1",
                        new String[]{String.valueOf(firstDown)});
                if (cursor != null && cursor.getCount() == 0) {
                    //指定的行已被删除（如刚被用户移除）：必须回退全表查询继续队列，
                    //否则直接 return null 让整个下载静默停止
                    cursor.close();
                    cursor = null;
                }
            }
            if (cursor == null)
                cursor = database.rawQuery("select * from download where state!=? limit 1", new String[]{"error"});

            firstDown = -1;

            if (cursor == null || cursor.getCount() == 0)
                return null;

            cursor.moveToFirst();
            return new DownloadSection(cursor);
        } catch (Exception e) {
            MsgUtil.err(e);
            return null;
        } finally {
            if (cursor != null)
                cursor.close();
            if (database != null)
                database.close();
        }
    }

    public static ArrayList<DownloadSection> getAll() {
        Cursor cursor = null;
        SQLiteDatabase database = null;
        try {
            DownloadSqlHelper helper = new DownloadSqlHelper(BiliTerminal.context);
            database = helper.getReadableDatabase();
            cursor = database.rawQuery("select * from download", null);
            if (cursor == null || cursor.getCount() == 0)
                return null;

            ArrayList<DownloadSection> list = new ArrayList<>();
            while (cursor.moveToNext()) {
                list.add(new DownloadSection(cursor));
            }
            return list;
        } catch (Exception e) {
            MsgUtil.err(e);
            return new ArrayList<>();
        } finally {
            if (cursor != null)
                cursor.close();
            if (database != null)
                database.close();
        }
    }

    public static void deleteSection(long id) {
        SQLiteDatabase database = null;
        try {
            DownloadSqlHelper helper = new DownloadSqlHelper(BiliTerminal.context);
            database = helper.getWritableDatabase();
            database.execSQL("delete from download where id=?", new Object[]{id});
        } catch (Exception e) {
            MsgUtil.err(e);
        } finally {
            if (database != null)
                database.close();
        }
    }

    public static void clear() {
        SQLiteDatabase database = null;
        try {
            DownloadSqlHelper helper = new DownloadSqlHelper(BiliTerminal.context);
            database = helper.getWritableDatabase();
            database.execSQL("delete from download", new Object[]{});
            database.close();
        } catch (Exception e) {
            MsgUtil.err(e);
        } finally {
            if (database != null)
                database.close();
        }
    }

    public static void setState(long id, String state) {
        SQLiteDatabase database = null;
        try {
            DownloadSqlHelper helper = new DownloadSqlHelper(BiliTerminal.context);
            database = helper.getWritableDatabase();
            database.execSQL("update download set state=? where id=?", new Object[]{state, id});
            database.close();
        } catch (Exception e) {
            MsgUtil.err(e);
        } finally {
            if (database != null)
                database.close();
        }
    }

    public static void startDownload(String title, long aid, long cid, String cover, int qn, String downloadType,
                                     String audioUrl) {
        CenterThreadPool.run(() -> {
            SQLiteDatabase database = null;
            Cursor cursor = null;
            try {
                DownloadSqlHelper helper = new DownloadSqlHelper(BiliTerminal.context);
                database = helper.getWritableDatabase();

                cursor = database.rawQuery("select * from download where aid=? and cid=?",
                        new String[]{String.valueOf(aid), String.valueOf(cid)});
                if (cursor != null && cursor.getCount() > 0) {
                    MsgUtil.showMsg("该视频已在下载队列中");
                    return;
                }
                if (cursor != null)
                    cursor.close();

                database.execSQL(
                        "insert into download(type,state,aid,cid,qn,title,child,cover,download_type,audio_url) values(?,?,?,?,?,?,?,?,?,?)",
                        new Object[]{"video_single", "none", aid, cid, qn, title, "", GlideUtil.url(cover),
                                downloadType, audioUrl});

                File path_single = FileUtil.getVideoDownloadPath(title, null);
                path_single.mkdirs();

                File file_sign = new File(path_single, ".DOWNLOADING");
                if (!file_sign.exists())
                    file_sign.createNewFile();

                String msg = "audio_only".equals(downloadType) ? "已添加音频下载" : "已添加下载";
                MsgUtil.showMsg(msg);

                start(-1);
            } catch (Exception e) {
                MsgUtil.err(e);
            } finally {
                if (cursor != null)
                    cursor.close();
                if (database != null)
                    database.close();
            }
        });
    }

    public static void startDownload(String parent, String child, long aid, long cid, String cover, int qn,
                                     String downloadType, String audioUrl) {
        CenterThreadPool.run(() -> {
            SQLiteDatabase database = null;
            Cursor cursor = null;
            try {
                DownloadSqlHelper helper = new DownloadSqlHelper(BiliTerminal.context);
                database = helper.getWritableDatabase();

                cursor = database.rawQuery("select * from download where aid=? and cid=?",
                        new String[]{String.valueOf(aid), String.valueOf(cid)});
                if (cursor != null && cursor.getCount() > 0) {
                    MsgUtil.showMsg("该视频已在下载队列中");
                    return;
                }
                if (cursor != null)
                    cursor.close();

                database.execSQL(
                        "insert into download(type,state,aid,cid,qn,title,child,cover,download_type,audio_url) values(?,?,?,?,?,?,?,?,?,?)",
                        new Object[]{"video_multi", "none", aid, cid, qn, parent, child, GlideUtil.url(cover),
                                downloadType, audioUrl});

                File path_page = FileUtil.getVideoDownloadPath(parent, child);
                path_page.mkdirs();

                File file_sign = new File(path_page, ".DOWNLOADING");
                if (!file_sign.exists())
                    file_sign.createNewFile();

                String msg = "audio_only".equals(downloadType) ? "已添加音频下载" : "已添加下载";
                MsgUtil.showMsg(msg);

                start(-1);
            } catch (Exception e) {
                MsgUtil.err(e);
            } finally {
                if (cursor != null)
                    cursor.close();
                if (database != null)
                    database.close();
            }
        });
    }

    public static synchronized void start(long first) {
        if (started) {
            //onDestroy 不保证执行（进程级回收），此时再 start 会永远卡在 started=true：
            //不重复启动第二条下载循环，但给出可感知的提示而不是静默失效
            MsgUtil.showMsg("下载队列已在进行中");
            return;
        }
        started = true;
        Logu.d("start");
        firstDown = first;

        Context context = BiliTerminal.context;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            context.startForegroundService(new Intent(context, DownloadService.class));
        else
            context.startService(new Intent(context, DownloadService.class));
    }

}