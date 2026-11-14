package com.RobinNotBad.BiliClient.helper.sql;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.Nullable;

import com.RobinNotBad.BiliClient.util.MsgUtil;

public class DownloadSqlHelper extends SQLiteOpenHelper {
    public DownloadSqlHelper(@Nullable Context context) {
        super(context, "download.db", null, 5);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("create table download(id INTEGER primary key autoincrement," +
                "type TEXT," +
                "state TEXT," +
                "aid BIGINT," +
                "cid BIGINT," +
                "qn INTEGER," +
                "title TEXT," +
                "child TEXT," +
                "cover TEXT," +
                "download_type TEXT DEFAULT 'video'," + // 下载类型：video / audio_only / video_dash / video_pgc
                "audio_url TEXT," + // 音频流URL
                "epid BIGINT DEFAULT 0," + // 番剧剧集 id（pgc/player/web/v2/playurl 按集定位）
                "season_id BIGINT DEFAULT 0," +
                "season_type INTEGER DEFAULT 0)"); // 国创/电视剧等分区取流必须带真实值
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion >= newVersion) return;
        try {
            //逐级迁移：任何旧版本升上来都先尝试 ALTER 补列，保住历史下载记录；
            //只有表结构异常（ALTER 失败，如列已存在/表损坏）才降级重建
            if (oldVersion < 4) {
                try {
                    db.execSQL("ALTER TABLE download ADD COLUMN download_type TEXT DEFAULT 'video'");
                    db.execSQL("ALTER TABLE download ADD COLUMN audio_url TEXT");
                } catch (Throwable e) {
                    db.execSQL("drop table if exists download");
                    onCreate(db);
                }
            }
            //v4 → v5（番剧缓存）：三列逐条独立 ALTER，某列已存在只跳过这一列，
            //绝不 drop table —— 用户几十条历史下载记录比一次表结构修正重要得多。
            if (oldVersion < 5) {
                try {
                    db.execSQL("ALTER TABLE download ADD COLUMN epid BIGINT DEFAULT 0");
                } catch (Throwable ignored) {
                }
                try {
                    db.execSQL("ALTER TABLE download ADD COLUMN season_id BIGINT DEFAULT 0");
                } catch (Throwable ignored) {
                }
                try {
                    db.execSQL("ALTER TABLE download ADD COLUMN season_type INTEGER DEFAULT 0");
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable e) {
            MsgUtil.err(e);
        }
    }
}
