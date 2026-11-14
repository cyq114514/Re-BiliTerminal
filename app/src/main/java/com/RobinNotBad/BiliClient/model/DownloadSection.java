package com.RobinNotBad.BiliClient.model;

import android.database.Cursor;

import com.RobinNotBad.BiliClient.util.FileUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import java.io.File;

public class DownloadSection {
    public long id;
    public String type;
    public long aid;
    public long cid;
    public int qn;
    public String url_cover;
    public String title;
    public String child;
    public String name_short;
    public String state;
    public String downloadType; // 下载类型：video / audio_only / video_dash / video_pgc
    public String audioUrl; // 音频流URL
    //番剧（PGC）身份：pgc/player/web/v2/playurl 按 ep_id 定位剧集，
    //season_type 用错分区会让服务端按错误的剧集类型取流，所以三者都要落库。
    public long epid;
    public long seasonId;
    public int seasonType;

    public DownloadSection() {
    }

    public DownloadSection(Cursor cursor) {
        id = cursor.getInt(0);
        type = cursor.getString(1);
        state = cursor.getString(2);
        aid = cursor.getLong(3);
        cid = cursor.getLong(4);
        qn = cursor.getInt(5);
        title = cursor.getString(6);
        child = cursor.getString(7);
        url_cover = cursor.getString(8);

        int downloadTypeIndex = cursor.getColumnIndex("download_type");
        if (downloadTypeIndex != -1) {
            downloadType = cursor.getString(downloadTypeIndex);
        }
        if (downloadType == null || downloadType.isEmpty()) {
            downloadType = "video";
        }

        int audioUrlIndex = cursor.getColumnIndex("audio_url");
        if (audioUrlIndex != -1) {
            audioUrl = cursor.getString(audioUrlIndex);
        }
        if (audioUrl == null) {
            audioUrl = "";
        }

        //按列名取而不是按下标：老库是逐级 ALTER 上来的，列顺序不保证；
        //列不存在（老版本进程还没重启）就留 0，UGC 下载完全不受影响。
        int epidIndex = cursor.getColumnIndex("epid");
        if (epidIndex != -1) epid = cursor.getLong(epidIndex);
        int seasonIdIndex = cursor.getColumnIndex("season_id");
        if (seasonIdIndex != -1) seasonId = cursor.getLong(seasonIdIndex);
        int seasonTypeIndex = cursor.getColumnIndex("season_type");
        if (seasonTypeIndex != -1) seasonType = cursor.getInt(seasonTypeIndex);

        StringBuilder sBuilder = new StringBuilder();
        sBuilder.append(title.substring(0, Math.min(8, title.length())));
        sBuilder.append(title.length() > 7 ? "..." : "");

        if (type.equals("video_multi")) {
            sBuilder.append("-");
            sBuilder.append(child.substring(0, Math.min(8, child.length())));
            sBuilder.append(child.length() > 7 ? "..." : "");
        }

        // 如果是仅音频下载，在名称后添加标识
        if ("audio_only".equals(downloadType)) {
            sBuilder.append("[音频]");
        }

        name_short = sBuilder.toString();

    }

    public File getPath() {
        if (type.contains("video")) {
            File path = FileUtil.getVideoDownloadPath(title, child);
            if (!path.exists())
                path.mkdirs();
            return path;
        } else
            return FileUtil.getPicturePath();
    }

    /**
     * 是否是仅音频下载
     */
    public boolean isAudioOnly() {
        return "audio_only".equals(downloadType);
    }

    public PlayerData toPlayerData() {
        PlayerData data = new PlayerData();
        //番剧下载条目必须带番剧身份，否则 DownloadService 会走 UGC 的 x/player/playurl
        if ("video_pgc".equals(downloadType)) {
            data.type = PlayerData.TYPE_BANGUMI;
            data.epid = epid;
            data.seasonId = seasonId;
            data.seasonType = seasonType;
        }
        data.aid = aid;
        data.cid = cid;
        data.title = title;
        data.qn = qn;
        data.mid = SharedPreferencesUtil.getLong("mid", 0);
        return data;
    }
}
