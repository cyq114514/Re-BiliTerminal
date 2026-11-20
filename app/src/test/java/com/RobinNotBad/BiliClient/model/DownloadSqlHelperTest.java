package com.RobinNotBad.BiliClient.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import com.RobinNotBad.BiliClient.helper.sql.DownloadSqlHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * download.db 结构与迁移的 JVM 回归测试（Robolectric 真 SQLite，无网络、无真实下载）。
 *
 * <p>覆盖三件最容易造成用户数据丢失的契约：
 * <ol>
 *   <li>onCreate 建出的 v5 表列集合完整，插入后能原样读回；</li>
 *   <li>v1/v3/v4 旧库升级到 v5 时只 ALTER 补列，历史下载记录一条不丢、旧列值不被重置；</li>
 *   <li>已是 v5 再打开（或重复 onUpgrade）是幂等的。</li>
 * </ol>
 *
 * <p>另复现 DownloadService 依赖的两条 SQL 语义：{@code aid+cid} 已存在即不重复插入、
 * 取队列首条时跳过 {@code state='error'}。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class DownloadSqlHelperTest {

    private static final String DB_NAME = "download.db";

    /** 最初的 v1 表：只有 9 列，没有 download_type/audio_url，也没有 epid/season_id/season_type。 */
    private static final String CREATE_V1 =
            "create table download(id INTEGER primary key autoincrement," +
                    "type TEXT,state TEXT,aid BIGINT,cid BIGINT,qn INTEGER," +
                    "title TEXT,child TEXT,cover TEXT)";

    /** v4 表：补了 download_type/audio_url，还没番剧三列。 */
    private static final String CREATE_V4 =
            "create table download(id INTEGER primary key autoincrement," +
                    "type TEXT,state TEXT,aid BIGINT,cid BIGINT,qn INTEGER," +
                    "title TEXT,child TEXT,cover TEXT," +
                    "download_type TEXT DEFAULT 'video',audio_url TEXT)";

    private static final Set<String> V5_COLUMNS = new HashSet<>(Arrays.asList(
            "id", "type", "state", "aid", "cid", "qn", "title", "child", "cover",
            "download_type", "audio_url", "epid", "season_id", "season_type"));

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        // 同一个文件名会被 SQLiteOpenHelper 复用，测试之间必须从干净状态开始
        context.deleteDatabase(DB_NAME);
    }

    @After
    public void tearDown() {
        context.deleteDatabase(DB_NAME);
    }

    // ---------------------------------------------------------------- onCreate

    @Test
    public void onCreateCreatesFullV5SchemaAndInsertRoundTrips() {
        DownloadSqlHelper helper = new DownloadSqlHelper(context);
        SQLiteDatabase db = helper.getWritableDatabase();
        try {
            assertEquals(5, db.getVersion());
            assertEquals(V5_COLUMNS, columns(db));

            db.execSQL("insert into download(type,state,aid,cid,qn,title,child,cover," +
                            "download_type,audio_url,epid,season_id,season_type) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    new Object[]{"video_single", "none", 9000000000001L, 9000000000002L, 80,
                            "新库插入标题", "", "http://i0.hdslb.com/cover.jpg",
                            "video_pgc", "http://upos/audio.m4a",
                            1100000000001L, 2200000000002L, 4});

            Cursor cursor = db.rawQuery("select * from download", null);
            try {
                assertEquals(1, cursor.getCount());
                assertTrue(cursor.moveToFirst());
                assertEquals("video_single", cursor.getString(cursor.getColumnIndexOrThrow("type")));
                assertEquals("none", cursor.getString(cursor.getColumnIndexOrThrow("state")));
                assertEquals(9000000000001L, cursor.getLong(cursor.getColumnIndexOrThrow("aid")));
                assertEquals(9000000000002L, cursor.getLong(cursor.getColumnIndexOrThrow("cid")));
                assertEquals(80, cursor.getInt(cursor.getColumnIndexOrThrow("qn")));
                assertEquals("新库插入标题", cursor.getString(cursor.getColumnIndexOrThrow("title")));
                assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("child")));
                assertEquals("http://i0.hdslb.com/cover.jpg", cursor.getString(cursor.getColumnIndexOrThrow("cover")));
                assertEquals("video_pgc", cursor.getString(cursor.getColumnIndexOrThrow("download_type")));
                assertEquals("http://upos/audio.m4a", cursor.getString(cursor.getColumnIndexOrThrow("audio_url")));
                assertEquals(1100000000001L, cursor.getLong(cursor.getColumnIndexOrThrow("epid")));
                assertEquals(2200000000002L, cursor.getLong(cursor.getColumnIndexOrThrow("season_id")));
                assertEquals(4, cursor.getInt(cursor.getColumnIndexOrThrow("season_type")));
            } finally {
                cursor.close();
            }

            // 省略 download_type 时应落到建表默认值 'video'
            db.execSQL("insert into download(type,state,aid,cid,qn,title,child,cover) values(?,?,?,?,?,?,?,?)",
                    new Object[]{"video_single", "none", 1L, 2L, 16, "默认类型标题", "", ""});
            Cursor def = db.rawQuery("select download_type,audio_url,epid from download where aid=1", null);
            try {
                assertTrue(def.moveToFirst());
                assertEquals("video", def.getString(0)); // 建表默认值
                assertTrue(def.isNull(1));               // audio_url 无默认值 → NULL
                assertEquals(0L, def.getLong(2));        // epid DEFAULT 0
            } finally {
                def.close();
            }
        } finally {
            helper.close();
        }
    }

    // -------------------------------------------------------------- 旧库迁移

    /** 模拟真实的 v4 库（番剧三列尚未加入）：升级后历史记录不丢，旧列值不被重置，新列取默认 0。 */
    @Test
    public void v4DatabaseUpgradesToV5WithoutLosingHistory() {
        createLegacyV4Database(4);
        assertUpgradeKeepsV4History();
    }

    /** 模拟降级/异常写入导致的 v3 版本标记（表仍是 v1 的 9 列）：v3 → v5 两段迁移都要生效。 */
    @Test
    public void v3DatabaseUpgradesToV5WithoutLosingHistory() {
        createLegacyV1Database(3);
        assertUpgradeKeepsV1History();
    }

    /** 最初的 v1 库直接升到 v5：先补 download_type/audio_url，再补番剧三列，历史记录保留。 */
    @Test
    public void v1DatabaseUpgradesToV5WithoutLosingHistory() {
        createLegacyV1Database(1);
        assertUpgradeKeepsV1History();
    }

    private void assertUpgradeKeepsV1History() {
        DownloadSqlHelper helper = new DownloadSqlHelper(context);
        SQLiteDatabase db = helper.getWritableDatabase();
        try {
            assertEquals(5, db.getVersion());
            assertEquals(V5_COLUMNS, columns(db));
            assertEquals(2, count(db, "select * from download"));

            Cursor cursor = db.rawQuery("select id,type,title,aid,cid,download_type,audio_url," +
                    "epid,season_id,season_type from download order by id", null);
            try {
                assertTrue(cursor.moveToFirst());
                // 两条历史记录：id/title/aid/cid 原样保留
                assertEquals(1, cursor.getInt(0));
                assertEquals("video_single", cursor.getString(1));
                assertEquals("历史视频一", cursor.getString(2));
                assertEquals(1001L, cursor.getLong(3));
                assertEquals(2001L, cursor.getLong(4));
                // v1 没有 download_type：ALTER ADD COLUMN DEFAULT 'video' 应回填旧行
                assertEquals("video", cursor.getString(5));
                // v1 没有 audio_url：无默认值 → NULL（读侧 DownloadSection 再兜底为 ""）
                assertTrue(cursor.isNull(6));
                assertEquals(0L, cursor.getLong(7));
                assertEquals(0L, cursor.getLong(8));
                assertEquals(0, cursor.getInt(9));

                assertTrue(cursor.moveToNext());
                assertEquals(2, cursor.getInt(0));
                assertEquals("历史视频二", cursor.getString(2));
                assertEquals(1002L, cursor.getLong(3));
                assertEquals(2002L, cursor.getLong(4));
                assertEquals("video", cursor.getString(5));
                assertTrue(cursor.isNull(6));
                assertEquals(0L, cursor.getLong(7));
                assertEquals(0, cursor.getInt(9));

                assertFalse(cursor.moveToNext());
            } finally {
                cursor.close();
            }
        } finally {
            helper.close();
        }
    }

    private void assertUpgradeKeepsV4History() {
        DownloadSqlHelper helper = new DownloadSqlHelper(context);
        SQLiteDatabase db = helper.getWritableDatabase();
        try {
            assertEquals(5, db.getVersion());
            assertEquals(V5_COLUMNS, columns(db));
            assertEquals(2, count(db, "select * from download"));

            Cursor cursor = db.rawQuery("select id,type,title,aid,cid,download_type,audio_url," +
                    "epid,season_id,season_type from download order by id", null);
            try {
                assertTrue(cursor.moveToFirst());
                assertEquals(1, cursor.getInt(0));
                assertEquals("历史视频一", cursor.getString(2));
                assertEquals(1001L, cursor.getLong(3));
                assertEquals(2001L, cursor.getLong(4));
                // v4 已有的取值必须原样保留，不能被 ALTER 重置成默认值
                assertEquals("audio_only", cursor.getString(5));
                assertEquals("http://legacy/audio-1.m4a", cursor.getString(6));
                // 新列对旧行取默认 0
                assertEquals(0L, cursor.getLong(7));
                assertEquals(0L, cursor.getLong(8));
                assertEquals(0, cursor.getInt(9));

                assertTrue(cursor.moveToNext());
                assertEquals(2, cursor.getInt(0));
                assertEquals("历史视频二", cursor.getString(2));
                assertEquals("video", cursor.getString(5));
                assertEquals("", cursor.getString(6));
                assertEquals(0L, cursor.getLong(7));
                assertEquals(0, cursor.getInt(9));

                assertFalse(cursor.moveToNext());
            } finally {
                cursor.close();
            }
        } finally {
            helper.close();
        }
    }

    /** 已是最新版本（v5）再打开、再 onUpgrade 一次，都不应重建表或动数据。 */
    @Test
    public void reopenLatestVersionIsIdempotent() {
        // 先用 helper 建库并写入一条完整记录
        DownloadSqlHelper first = new DownloadSqlHelper(context);
        SQLiteDatabase db = first.getWritableDatabase();
        try {
            assertEquals(5, db.getVersion());
            db.execSQL("insert into download(type,state,aid,cid,qn,title,child,cover," +
                            "download_type,audio_url,epid,season_id,season_type) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    new Object[]{"video_single", "downloading", 5001L, 6001L, 64,
                            "幂等记录", "", "http://cover/x.jpg", "video", "", 0L, 0L, 0});
        } finally {
            first.close();
        }

        DownloadSqlHelper second = new DownloadSqlHelper(context);
        SQLiteDatabase reopened = second.getWritableDatabase();
        try {
            assertEquals(5, reopened.getVersion());
            assertEquals(1, count(reopened, "select * from download"));
            // 版本相同不会触发 onCreate/onUpgrade；列集合与数据都应保持原样
            assertEquals(V5_COLUMNS, columns(reopened));

            // 直接再调一次 onUpgrade（oldVersion >= newVersion 的守卫）也不应破坏数据
            second.onUpgrade(reopened, 5, 5);
            second.onUpgrade(reopened, 6, 5);
            assertEquals(5, reopened.getVersion());
            assertEquals(1, count(reopened, "select * from download"));

            Cursor cursor = reopened.rawQuery("select title,state,aid,cid from download", null);
            try {
                assertTrue(cursor.moveToFirst());
                assertEquals("幂等记录", cursor.getString(0));
                assertEquals("downloading", cursor.getString(1));
                assertEquals(5001L, cursor.getLong(2));
                assertEquals(6001L, cursor.getLong(3));
            } finally {
                cursor.close();
            }
        } finally {
            second.close();
        }
    }

    // ------------------------------------------- 下载服务依赖的 SQL 语义（复现查询）

    /** 同 aid+cid 已在队列 → 不再插入；不同 aid 或不同 cid 不算重复。 */
    @Test
    public void duplicateAidCidDetectionBlocksSecondInsert() {
        DownloadSqlHelper helper = new DownloadSqlHelper(context);
        SQLiteDatabase db = helper.getWritableDatabase();
        try {
            assertEquals(0, count(db, "select * from download where aid=? and cid=?",
                    new String[]{"7001", "8001"}));

            assertTrue(enqueueIfAbsent(db, 7001L, 8001L, "第一次入队标题"));
            // 服务端同一查重 SQL 第二次必须命中（count>0）→ 跳过插入
            assertEquals(1, count(db, "select * from download where aid=? and cid=?",
                    new String[]{"7001", "8001"}));
            assertFalse(enqueueIfAbsent(db, 7001L, 8001L, "重复入队标题"));

            assertEquals(1, count(db, "select * from download"));
            // 跳过插入时不能覆盖已有条目
            Cursor cursor = db.rawQuery("select title,state from download where aid=7001 and cid=8001", null);
            try {
                assertTrue(cursor.moveToFirst());
                assertEquals("第一次入队标题", cursor.getString(0));
                assertEquals("none", cursor.getString(1));
            } finally {
                cursor.close();
            }

            // aid 相同但 cid 不同、或 cid 相同 aid 不同，都不算重复
            assertTrue(enqueueIfAbsent(db, 7001L, 8002L, "同 aid 不同 cid"));
            assertTrue(enqueueIfAbsent(db, 7002L, 8001L, "同 cid 不同 aid"));
            assertEquals(3, count(db, "select * from download"));
        } finally {
            helper.close();
        }
    }

    /** 队列取首条：{@code state != 'error'} 只跳过失败条目，仍按下标/插入顺序先到先得。 */
    @Test
    public void queueFirstSkipsErrorStateRows() {
        DownloadSqlHelper helper = new DownloadSqlHelper(context);
        SQLiteDatabase db = helper.getWritableDatabase();
        try {
            insertQueueRow(db, "none", "排队一");
            insertQueueRow(db, "error", "失败二");
            insertQueueRow(db, "downloading", "下载中三");

            Cursor cursor = firstQueueRow(db);
            try {
                assertNotNull(cursor);
                assertEquals(1, cursor.getCount());
                assertTrue(cursor.moveToFirst());
                assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("id")));
                assertEquals("none", cursor.getString(cursor.getColumnIndexOrThrow("state")));
                assertEquals("排队一", cursor.getString(cursor.getColumnIndexOrThrow("title")));
            } finally {
                cursor.close();
            }

            // 首条失败后，队列应越过 error 取到下一条可下载任务
            db.execSQL("update download set state='error' where id=1", new Object[]{});
            cursor = firstQueueRow(db);
            try {
                assertNotNull(cursor);
                assertTrue(cursor.moveToFirst());
                assertEquals(3, cursor.getInt(cursor.getColumnIndexOrThrow("id")));
                assertEquals("downloading", cursor.getString(cursor.getColumnIndexOrThrow("state")));
            } finally {
                cursor.close();
            }

            // 全是 error → 查询 0 行（getFirst 据此返回 null，worker 停止且不锁队列）
            db.execSQL("update download set state='error'", new Object[]{});
            cursor = firstQueueRow(db);
            try {
                assertNotNull(cursor);
                assertEquals(0, cursor.getCount());
            } finally {
                cursor.close();
            }
        } finally {
            helper.close();
        }
    }

    // ------------------------------------------------------------------ 工具

    private void createLegacyV1Database(int version) {
        File dbFile = context.getDatabasePath(DB_NAME);
        File dir = dbFile.getParentFile();
        if (dir != null && !dir.exists()) {
            assertTrue(dir.mkdirs());
        }
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(dbFile, null);
        try {
            db.execSQL(CREATE_V1);
            db.execSQL("insert into download(type,state,aid,cid,qn,title,child,cover) values(?,?,?,?,?,?,?,?)",
                    new Object[]{"video_single", "none", 1001L, 2001L, 80, "历史视频一", "", "http://cover/1"});
            db.execSQL("insert into download(type,state,aid,cid,qn,title,child,cover) values(?,?,?,?,?,?,?,?)",
                    new Object[]{"video_multi", "error", 1002L, 2002L, 64, "历史视频二", "P2", "http://cover/2"});
            db.setVersion(version);
            assertEquals(version, db.getVersion());
        } finally {
            db.close();
        }
    }

    private void createLegacyV4Database(int version) {
        File dbFile = context.getDatabasePath(DB_NAME);
        File dir = dbFile.getParentFile();
        if (dir != null && !dir.exists()) {
            assertTrue(dir.mkdirs());
        }
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(dbFile, null);
        try {
            db.execSQL(CREATE_V4);
            db.execSQL("insert into download(type,state,aid,cid,qn,title,child,cover," +
                            "download_type,audio_url) values(?,?,?,?,?,?,?,?,?,?)",
                    new Object[]{"video_single", "none", 1001L, 2001L, 80, "历史视频一", "",
                            "http://cover/1", "audio_only", "http://legacy/audio-1.m4a"});
            db.execSQL("insert into download(type,state,aid,cid,qn,title,child,cover," +
                            "download_type,audio_url) values(?,?,?,?,?,?,?,?,?,?)",
                    new Object[]{"video_multi", "error", 1002L, 2002L, 64, "历史视频二", "P2",
                            "http://cover/2", "video", ""});
            db.setVersion(version);
            assertEquals(version, db.getVersion());
        } finally {
            db.close();
        }
    }

    private static Set<String> columns(SQLiteDatabase db) {
        Set<String> result = new HashSet<>();
        Cursor cursor = db.rawQuery("PRAGMA table_info(download)", null);
        try {
            int nameIndex = cursor.getColumnIndexOrThrow("name");
            while (cursor.moveToNext()) {
                result.add(cursor.getString(nameIndex));
            }
        } finally {
            cursor.close();
        }
        return result;
    }

    private static int count(SQLiteDatabase db, String sql) {
        return count(db, sql, null);
    }

    private static int count(SQLiteDatabase db, String sql, String[] args) {
        Cursor cursor = db.rawQuery(sql, args);
        try {
            return cursor.getCount();
        } finally {
            cursor.close();
        }
    }

    /**
     * 复现 DownloadService.startDownload 的入队判定：先 {@code select * from download where aid=? and cid=?}，
     * 查到即跳过；查不到才 insert。测试用它断言 SQL 层面的去重语义。
     */
    private static boolean enqueueIfAbsent(SQLiteDatabase db, long aid, long cid, String title) {
        Cursor cursor = db.rawQuery("select * from download where aid=? and cid=?",
                new String[]{String.valueOf(aid), String.valueOf(cid)});
        try {
            if (cursor.getCount() > 0) {
                return false;
            }
        } finally {
            cursor.close();
        }
        db.execSQL("insert into download(type,state,aid,cid,qn,title,child,cover,download_type,audio_url)" +
                        " values(?,?,?,?,?,?,?,?,?,?)",
                new Object[]{"video_single", "none", aid, cid, 80, title, "", "http://cover/x", "video", ""});
        return true;
    }

    private static void insertQueueRow(SQLiteDatabase db, String state, String title) {
        db.execSQL("insert into download(type,state,aid,cid,qn,title,child,cover,download_type,audio_url)" +
                        " values(?,?,?,?,?,?,?,?,?,?)",
                new Object[]{"video_single", state, 1L, 2L, 80, title, "", "http://cover/x", "video", ""});
    }

    /** 复现 DownloadService.getFirst 的全表回退查询：跳过 state='error'，取 limit 1。 */
    private static Cursor firstQueueRow(SQLiteDatabase db) {
        return db.rawQuery("select * from download where state!=? limit 1", new String[]{"error"});
    }

    /** 迁移测试的前提自检：旧库文件确实落在 SQLiteOpenHelper 会打开的位置，且升级前没有新列。 */
    @Test
    public void legacyDatabaseStartsAtOldSchemaBeforeUpgrade() {
        createLegacyV1Database(1);
        File dbFile = context.getDatabasePath(DB_NAME);
        assertTrue(dbFile.exists());

        SQLiteDatabase db = SQLiteDatabase.openDatabase(dbFile.getPath(), null, SQLiteDatabase.OPEN_READONLY);
        try {
            assertEquals(1, db.getVersion());
            assertEquals(2, count(db, "select * from download"));
            Set<String> cols = columns(db);
            assertFalse(cols.contains("download_type"));
            assertFalse(cols.contains("audio_url"));
            assertFalse(cols.contains("epid"));
            assertFalse(cols.contains("season_id"));
            assertFalse(cols.contains("season_type"));
        } finally {
            db.close();
        }
    }
}