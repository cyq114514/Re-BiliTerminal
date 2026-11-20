package com.RobinNotBad.BiliClient.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;

/**
 * DownloadSection 的 Cursor → 模型映射测试（Robolectric 真 SQLite/Cursor，不联网、不落真实下载目录）。
 *
 * <p>重点覆盖旧库兼容与番剧身份：
 * <ul>
 *   <li>{@code select * from download} 的列顺序（前 9 列按位置读）；</li>
 *   <li>{@code download_type}/{@code audio_url} 缺列、NULL、空串的回退（video / ""）；</li>
 *   <li>{@code epid/season_id/season_type} 必须按列名读取，列顺序变化不能错位；</li>
 *   <li>{@code name_short} 的截断、video_multi 追加 child、audio_only 追加 [音频]；</li>
 *   <li>{@code toPlayerData} 只有 video_pgc 才带番剧身份。</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class DownloadSectionTest {

    private static final String DB_NAME = "download_section_test.db";

    /** 与 DownloadSqlHelper.onCreate 完全一致的 v5 表结构（列顺序也一致）。 */
    private static final String CREATE_V5 =
            "create table download(id INTEGER primary key autoincrement," +
                    "type TEXT,state TEXT,aid BIGINT,cid BIGINT,qn INTEGER," +
                    "title TEXT,child TEXT,cover TEXT," +
                    "download_type TEXT DEFAULT 'video',audio_url TEXT," +
                    "epid BIGINT DEFAULT 0,season_id BIGINT DEFAULT 0,season_type INTEGER DEFAULT 0)";

    private static final String INSERT_V5 =
            "insert into download(id,type,state,aid,cid,qn,title,child,cover," +
                    "download_type,audio_url,epid,season_id,season_type) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private Context context;
    private SQLiteDatabase db;
    private SharedPreferences previousSharedPreferences;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase(DB_NAME);
        File dbFile = context.getDatabasePath(DB_NAME);
        File dir = dbFile.getParentFile();
        if (dir != null && !dir.exists()) {
            assertTrue(dir.mkdirs());
        }
        db = SQLiteDatabase.openOrCreateDatabase(dbFile, null);
        db.execSQL(CREATE_V5);

        // toPlayerData 会读 SharedPreferencesUtil 的静态引用；测试里先置空，
        // 需要验证 mid 的用例再单独设置，避免被别的用例/进程状态影响。
        previousSharedPreferences = SharedPreferencesUtil.sharedPreferences;
        SharedPreferencesUtil.sharedPreferences = null;

        // id1：番剧 PGC 条目（完整列 + 超 7 字标题）
        insertRow(1, "video_single", "none",
                9000000000001L, 9000000000002L, 80, "番剧第一集标题超长", "",
                "http://i0.hdslb.com/cover1.jpg", "video_pgc", "http://upos/audio1.m4a",
                1100000000001L, 2200000000002L, 4);
        // id2：普通短视频（title <= 7）
        insertRow(2, "video_single", "downloading",
                1002L, 2002L, 16, "短视频", "", "http://cover/2", "video", "",
                0L, 0L, 0);
        // id3：分 P（video_multi + video_dash）
        insertRow(3, "video_multi", "none",
                1003L, 2003L, 64, "合集名字很长很长", "第一话",
                "http://cover/3", "video_dash", "", 0L, 0L, 0);
        // id4：仅音频（title <= 7）
        insertRow(4, "video_single", "none",
                1004L, 2004L, 16, "三字", "", "http://cover/4", "audio_only",
                "http://upos/a-4.m4a", 0L, 0L, 0);
        // id5：仅音频 + 超长标题（验证 [音频] 追加在省略号之后）
        insertRow(5, "video_single", "none",
                1005L, 2005L, 16, "音频标题很长啊哈", "", "http://cover/5", "audio_only",
                "http://upos/a-5.m4a", 0L, 0L, 0);
        // id6：普通视频，但列里塞了番剧身份 → toPlayerData 不得带 PGC 身份
        insertRow(6, "video_single", "none",
                1006L, 2006L, 32, "普通视频", "", "http://cover/6", "video", "",
                777L, 888L, 2);
        // id7：老库行——download_type/audio_url 为 NULL（老版本插入或 ALTER 后未回填）
        insertRow(7, "video_single", "none",
                1007L, 2007L, 16, "老库标题", "", "http://cover/7", null, null, 0L, 0L, 0);
        // id8：download_type/audio_url 为空串
        insertRow(8, "video_single", "none",
                1008L, 2008L, 16, "空串标题", "", "http://cover/8", "", "", 0L, 0L, 0);
        // id9：视频标题恰好 7 字（边界，不加省略号）
        insertRow(9, "video_single", "none",
                1009L, 2009L, 16, "刚好七个字边界", "", "http://cover/9", "video", "",
                0L, 0L, 0);
        // id10：child 超 7 字（video_multi 的 child 也走省略号）
        insertRow(10, "video_multi", "none",
                1010L, 2010L, 64, "合集", "第一话标题超长啊", "", "video", "",
                0L, 0L, 0);
    }

    @After
    public void tearDown() {
        SharedPreferencesUtil.sharedPreferences = previousSharedPreferences;
        if (db != null) {
            db.close();
        }
        context.deleteDatabase(DB_NAME);
    }

    // ------------------------------------------------------------ 字段映射

    @Test
    public void mapsAllColumnsIncludingPgcIdentity() {
        DownloadSection section = load(1);

        assertEquals(1, section.id);
        assertEquals("video_single", section.type);
        assertEquals("none", section.state);
        assertEquals(9000000000001L, section.aid);
        assertEquals(9000000000002L, section.cid);
        assertEquals(80, section.qn);
        assertEquals("番剧第一集标题超长", section.title);
        assertEquals("", section.child);
        assertEquals("http://i0.hdslb.com/cover1.jpg", section.url_cover);
        assertEquals("video_pgc", section.downloadType);
        assertEquals("http://upos/audio1.m4a", section.audioUrl);
        assertEquals(1100000000001L, section.epid);
        assertEquals(2200000000002L, section.seasonId);
        assertEquals(4, section.seasonType);
        assertFalse(section.isAudioOnly());
        assertEquals("番剧第一集标题超...", section.name_short);

        PlayerData data = section.toPlayerData();
        assertEquals(PlayerData.TYPE_BANGUMI, data.type);
        assertEquals(1100000000001L, data.epid);
        assertEquals(2200000000002L, data.seasonId);
        assertEquals(4, data.seasonType);
        assertEquals(9000000000001L, data.aid);
        assertEquals(9000000000002L, data.cid);
        assertEquals(80, data.qn);
        assertEquals("番剧第一集标题超长", data.title);
        assertTrue(data.isBangumi());
    }

    // ------------------------------------------------------- name_short 规则

    @Test
    public void nameShortRulesForTruncationChildAndAudioTag() {
        // title 3 字：原样，不追加省略号
        assertEquals("短视频", load(2).name_short);

        // title 恰好 7 字：边界上不加省略号
        assertEquals("刚好七个字边界", load(9).name_short);

        // video_multi 的 child 超 7 字：同样截 8 字 + "..."
        assertEquals("合集-第一话标题超长啊...", load(10).name_short);

        // video_multi：父标题 8 字加 "..." 后再追加 "-" + child（3 字不截断）
        assertEquals("合集名字很长很长...-第一话", load(3).name_short);

        // audio_only：短标题也追加 [音频]
        assertEquals("三字[音频]", load(4).name_short);

        // audio_only + 超长标题：[音频] 必须排在省略号之后
        assertEquals("音频标题很长啊哈...[音频]", load(5).name_short);
    }

    // ------------------------------------------------ 老库缺列 / 空值回退语义

    @Test
    public void legacyColumnsFallBackToVideoAndEmptyAudioUrl() {
        // 列存在但值为 NULL → 回退
        DownloadSection nullType = load(7);
        assertEquals("video", nullType.downloadType);
        assertEquals("", nullType.audioUrl);
        assertFalse(nullType.isAudioOnly());
        assertEquals("老库标题", nullType.name_short);

        // 列存在但值为空串 → 回退
        DownloadSection emptyType = load(8);
        assertEquals("video", emptyType.downloadType);
        assertEquals("", emptyType.audioUrl);
        assertEquals("空串标题", emptyType.name_short);

        // 列根本不存在（老版本进程刚升级、Cursor 还是旧列集合）→ 同样回退，且不影响前 9 列
        Cursor legacyCursor = db.rawQuery(
                "select id,type,state,aid,cid,qn,title,child,cover from download where id=2", null);
        try {
            assertTrue(legacyCursor.moveToFirst());
            assertEquals(-1, legacyCursor.getColumnIndex("download_type"));
            assertEquals(-1, legacyCursor.getColumnIndex("audio_url"));
            assertEquals(-1, legacyCursor.getColumnIndex("epid"));
            DownloadSection old = new DownloadSection(legacyCursor);
            assertEquals("video", old.downloadType);
            assertEquals("", old.audioUrl);
            assertEquals(0L, old.epid);
            assertEquals(0L, old.seasonId);
            assertEquals(0, old.seasonType);
            assertEquals(1002L, old.aid);
            assertEquals(2002L, old.cid);
            assertEquals("短视频", old.name_short);
        } finally {
            legacyCursor.close();
        }
    }

    // -------------------------------------------- 番剧三列必须按列名读（抗列序）

    @Test
    public void readsPgcColumnsByNameRegardlessOfColumnOrder() {
        // 故意把 epid/season_id/season_type 挪到 download_type/audio_url 前面
        Cursor reordered = db.rawQuery(
                "select id,type,state,aid,cid,qn,title,child,cover," +
                        "epid,season_id,season_type,download_type,audio_url from download where id=1", null);
        try {
            assertTrue(reordered.moveToFirst());
            DownloadSection section = new DownloadSection(reordered);
            // 前 9 列仍按位置读
            assertEquals("video_single", section.type);
            assertEquals(9000000000001L, section.aid);
            assertEquals(9000000000002L, section.cid);
            // 后 5 列按名字读，不能因为顺序变了就错位
            assertEquals(1100000000001L, section.epid);
            assertEquals(2200000000002L, section.seasonId);
            assertEquals(4, section.seasonType);
            assertEquals("video_pgc", section.downloadType);
            assertEquals("http://upos/audio1.m4a", section.audioUrl);
        } finally {
            reordered.close();
        }

        // 番剧三列缺失、download_type/audio_url 存在：三列各自独立回退 0，其余列照常读
        Cursor partial = db.rawQuery(
                "select id,type,state,aid,cid,qn,title,child,cover,download_type,audio_url " +
                        "from download where id=1", null);
        try {
            assertTrue(partial.moveToFirst());
            DownloadSection section = new DownloadSection(partial);
            assertEquals(0L, section.epid);
            assertEquals(0L, section.seasonId);
            assertEquals(0, section.seasonType);
            assertEquals("video_pgc", section.downloadType);
            assertEquals("http://upos/audio1.m4a", section.audioUrl);
        } finally {
            partial.close();
        }
    }

    // ------------------------------------------------- toPlayerData 身份分流

    @Test
    public void ugcPlayerDataCarriesNoPgcIdentity() {
        // download_type=video，即使列里有 epid/season 值也不能带进播放器
        DownloadSection video = load(6);
        assertFalse(video.isAudioOnly());
        PlayerData videoData = video.toPlayerData();
        assertEquals(PlayerData.TYPE_VIDEO, videoData.type);
        assertEquals(777L, video.epid); // 模型本身仍保留列值
        assertEquals(0L, videoData.epid);
        assertEquals(0L, videoData.seasonId);
        assertEquals(0, videoData.seasonType);
        assertFalse(videoData.isBangumi());
        assertEquals(1006L, videoData.aid);
        assertEquals(2006L, videoData.cid);
        assertEquals(32, videoData.qn);
        assertEquals("普通视频", videoData.title);

        // video_dash 同样是 UGC 取流路径
        DownloadSection dash = load(3);
        assertFalse(dash.isAudioOnly());
        assertEquals(PlayerData.TYPE_VIDEO, dash.toPlayerData().type);

        // audio_only 不是番剧：isAudioOnly=true 但播放器身份仍是普通视频
        DownloadSection audio = load(4);
        assertTrue(audio.isAudioOnly());
        assertEquals("audio_only", audio.downloadType);
        assertEquals("http://upos/a-4.m4a", audio.audioUrl);
        assertEquals(PlayerData.TYPE_VIDEO, audio.toPlayerData().type);
    }

    @Test
    public void playerDataMidComesFromSharedPreferences() {
        SharedPreferences sp = context.getSharedPreferences("download_section_test_sp", Context.MODE_PRIVATE);
        sp.edit().putLong("mid", 42424242L).commit();
        SharedPreferencesUtil.sharedPreferences = sp;

        PlayerData data = load(2).toPlayerData();
        assertEquals(42424242L, data.mid);
        assertEquals(PlayerData.TYPE_VIDEO, data.type);

        // 没有 SP（进程早期）时 mid 回退 0，不崩
        SharedPreferencesUtil.sharedPreferences = null;
        assertEquals(0L, load(2).toPlayerData().mid);
    }

    // ------------------------------------------------------------------ 工具

    private void insertRow(long id, String type, String state, long aid, long cid, int qn,
                           String title, String child, String cover, String downloadType,
                           String audioUrl, long epid, long seasonId, int seasonType) {
        db.execSQL(INSERT_V5, new Object[]{id, type, state, aid, cid, qn, title, child, cover,
                downloadType, audioUrl, epid, seasonId, seasonType});
    }

    /** 按生产读路径 {@code select * from download} 构造 Cursor，再交给 DownloadSection。 */
    private DownloadSection load(long id) {
        Cursor cursor = db.rawQuery("select * from download where id=?", new String[]{String.valueOf(id)});
        try {
            assertTrue("缺少 id=" + id + " 的测试数据", cursor.moveToFirst());
            return new DownloadSection(cursor);
        } finally {
            cursor.close();
        }
    }
}