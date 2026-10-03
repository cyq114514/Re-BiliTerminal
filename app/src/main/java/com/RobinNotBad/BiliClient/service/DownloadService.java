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
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;

import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.zip.Inflater;

import okhttp3.Response;
import okio.BufferedSink;
import okio.Okio;
import okio.Sink;

public class DownloadService extends Service {
    public static volatile boolean started;
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

    private Timer toastTimer, notifyTimer;

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

        CenterThreadPool.run(() -> {
            boolean failed = false;
            while (!failed && started) {
                DownloadSection section_tmp = getFirst();
                if (section_tmp == null)
                    break;

                section = section_tmp;

                // 获取视频链接
                String url_video, url_danmaku, url_audio;
                try {
                    PlayerData data = section.toPlayerData();

                    // 如果是仅音频下载，使用DASH格式获取音频流
                    if (section.isAudioOnly()) {
                        PlayerApi.getVideoDash(data);
                        //优先用刚拉取的新链接：数据库里持久化的 URL 数小时就过期，
                        //重试/延迟下载的音频必然 403
                        url_audio = data.audioUrl != null && !data.audioUrl.isEmpty()
                                ? data.audioUrl
                                : section.audioUrl;
                        url_video = null; // 仅音频模式不需要视频
                    } else {
                        PlayerApi.getVideo(data, true);
                        url_video = data.videoUrl;
                        url_audio = null;
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
                                result = downFile(url_audio, new File(path_single, "audio.m4a"));
                                if (result != NORMAL) {
                                    failed = true;
                                    exitCode = result;
                                    setState(section.id, "error");   //失败落库，否则该条目永久停留在 downloading 被误锁
                                    continue;
                                }
                            } else {
                                toastState("下载视频");
                                result = downFile(url_video, new File(path_single, "video.mp4"));
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
                                result = downFile(url_audio, new File(path_page, "audio.m4a"));
                                if (result != NORMAL) {
                                    failed = true;
                                    exitCode = result;
                                    setState(section.id, "error");   //失败落库，否则该条目永久停留在 downloading 被误锁
                                    continue;
                                }
                            } else {
                                toastState("下载视频");
                                result = downFile(url_video, new File(path_page, "video.mp4"));
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

            stopSelf();
        });

        return Service.START_STICKY;
    }

    private void fakeDownload() {
        setState(section.id, "downloading");
        percent = 0;
        refreshDownloadList();
        try {
            Thread.sleep(3000);
        } catch (Exception ignored) {
        }

        deleteSection(section.id);
        refreshLocalList();
    }

    private void toastState(String newState) {
        state = newState;
        percent = 0;
        if (toastTimer != null)
            toastTimer.cancel();
    }

    private void startNotifyProgress() {
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

    private int downFile(String url, File file) throws IOException {
        Response response;
        try {
            response = NetWorkUtil.get(url);
        } catch (IOException e) {
            return ERR_NETWORK;
        }
        InputStream inputStream = null;
        FileOutputStream fileOutputStream = null;
        try {
            //B 站错误响应多为 JSON：不校验状态码会把错误页写进 video.mp4，本地列表显示可播放
            if (!response.isSuccessful()) {
                response.close();
                return ERR_NETWORK;
            }
            if (!file.exists() && !file.createNewFile())
                return ERR_FILE;
            else if (!file.delete() || !file.createNewFile())
                return ERR_FILE;

            inputStream = Objects.requireNonNull(response.body()).byteStream();
            fileOutputStream = new FileOutputStream(file);
            int len;
            byte[] bytes = new byte[1024 * 10];
            long TotalFileSize = Objects.requireNonNull(response.body()).contentLength();
            long completeFileSize = 0;
            while ((len = inputStream.read(bytes)) != -1 && started) {
                fileOutputStream.write(bytes, 0, len);
                completeFileSize += len;
                //长度未知（chunked/-1）时不算百分比，避免负数/NaN；也不再每 10KB 一次 file.length() 系统调用
                if (TotalFileSize > 0)
                    percent = 1.0f * completeFileSize / TotalFileSize;
            }
            if (!started)
                return ERR_UNKNOWN;
        } catch (IOException e) {
            return ERR_FILE;
        } finally {
            if (inputStream != null)
                inputStream.close();
            if (fileOutputStream != null)
                fileOutputStream.close();
            if (response.body() != null)
                response.body().close();
            response.close();
        }
        return NORMAL;
    }

    private int downDanmaku(String danmaku, File danmakuFile) throws IOException {
        Response response;
        try {
            response = NetWorkUtil.get(danmaku);
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

        started = false;
        percent = -1;
        state = null;

        if (toastTimer != null)
            toastTimer.cancel();
        toastTimer = null;

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
            if (pendingSection != null && exitCode != NORMAL) {
                //中途退出：残留条目恢复为可重试状态并清理半成品
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

    public static void start(long first) {
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