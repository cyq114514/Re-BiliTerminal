package com.RobinNotBad.BiliClient.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 听歌续播记录的持久化测试（Robolectric 提供真实 SharedPreferences 实现）。
 *
 * <p>{@link AudioProgressStore} 没有可见的 key 常量，测试里直接用落盘键的字符串字面量
 * {@code "audio_progress_state"} 断言「写没写」，这正是用户数据兼容性要钉住的契约。
 */
@RunWith(RobolectricTestRunner.class)
public class AudioProgressStoreTest {

    /** AudioProgressStore 的落盘键（私有常量，作为持久化契约在此硬编码） */
    private static final String KEY_STATE = "audio_progress_state";
    private static final String KEY_PRIVACY = SharedPreferencesUtil.PRIVACY_MODE;

    private SharedPreferences sp;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        sp = context.getSharedPreferences("test", Context.MODE_PRIVATE);
        sp.edit().clear().commit();
        SharedPreferencesUtil.sharedPreferences = sp;
    }

    @After
    public void tearDown() {
        sp.edit().clear().commit();
        SharedPreferencesUtil.sharedPreferences = null;
    }

    private static AudioProgressStore.Progress progress() {
        AudioProgressStore.Progress p = new AudioProgressStore.Progress();
        p.mediaId = 123456L;
        p.order = "mtime";
        p.folderName = "我的收藏夹";
        p.index = 7;
        p.aid = 1001L;
        p.cid = 2002L;
        p.positionMs = 61234;
        p.durationMs = 300000;
        p.title = "某首中文歌";
        p.upName = "某个UP";
        p.cover = "https://example.invalid/cover.jpg";
        return p;
    }

    private String stored() {
        return sp.getString(KEY_STATE, "");
    }

    @Test
    public void saveThenLoadRoundTripKeepsAllFields() {
        AudioProgressStore.save(progress());

        assertFalse(stored().isEmpty());
        AudioProgressStore.Progress loaded = AudioProgressStore.load();
        assertEquals(123456L, loaded.mediaId);
        assertEquals("mtime", loaded.order);
        assertEquals("我的收藏夹", loaded.folderName);
        assertEquals(7, loaded.index);
        assertEquals(1001L, loaded.aid);
        assertEquals(2002L, loaded.cid);
        assertEquals(61234, loaded.positionMs);
        assertEquals(300000, loaded.durationMs);
        assertEquals("某首中文歌", loaded.title);
        assertEquals("某个UP", loaded.upName);
        assertEquals("https://example.invalid/cover.jpg", loaded.cover);
        assertTrue("savedAt 必须是写入时刻的毫秒时间戳",
                Math.abs(System.currentTimeMillis() - loaded.savedAt) < 60_000L);
    }

    @Test
    public void positionUnderFiveSecondsCollapsesToZero() {
        AudioProgressStore.Progress p = progress();
        p.positionMs = 3000;
        AudioProgressStore.save(p);
        assertEquals(0, AudioProgressStore.load().positionMs);

        p = progress();
        p.positionMs = 4999;
        AudioProgressStore.save(p);
        assertEquals(0, AudioProgressStore.load().positionMs);

        // 恰好 5 秒应保留（门槛是 < 5000）
        p = progress();
        p.positionMs = 5000;
        AudioProgressStore.save(p);
        assertEquals(5000, AudioProgressStore.load().positionMs);
    }

    @Test
    public void positionNearTailCollapsesToZero() {
        AudioProgressStore.Progress p = progress();
        p.positionMs = 298000; // 距结尾 2s
        AudioProgressStore.save(p);
        assertEquals(0, AudioProgressStore.load().positionMs);

        p = progress();
        p.positionMs = 295001; // 刚进入「结尾 5 秒内」
        AudioProgressStore.save(p);
        assertEquals(0, AudioProgressStore.load().positionMs);

        // 恰好结尾前 5 秒仍保留（守卫条件是严格大于 duration-TAIL_GUARD）
        p = progress();
        p.positionMs = 295000;
        AudioProgressStore.save(p);
        assertEquals(295000, AudioProgressStore.load().positionMs);
    }

    @Test
    public void zeroOrUnknownDurationKeepsPosition() {
        AudioProgressStore.Progress p = progress();
        p.positionMs = 30000;
        p.durationMs = 0; // 时长未知时不能做「距结尾」判断
        AudioProgressStore.save(p);

        AudioProgressStore.Progress loaded = AudioProgressStore.load();
        assertEquals(30000, loaded.positionMs);
        assertEquals(0, loaded.durationMs);
    }

    @Test
    public void invalidIdentifiersAreNotWritten() {
        // cid<=0：没有 cid 无法在列表里重新定位
        AudioProgressStore.Progress noCid = progress();
        noCid.cid = 0;
        noCid.title = "不该被写入";
        AudioProgressStore.save(noCid);
        assertEquals("", stored());

        // mediaId<=0：不是收藏夹队列
        AudioProgressStore.Progress noMedia = progress();
        noMedia.mediaId = -1;
        noMedia.title = "也不该被写入";
        AudioProgressStore.save(noMedia);
        assertEquals("", stored());

        AudioProgressStore.save(null);
        assertEquals("", stored());
        assertNull(AudioProgressStore.load());
    }

    @Test
    public void invalidSaveDoesNotOverwriteExistingRecord() {
        AudioProgressStore.save(progress());
        String first = stored();
        assertFalse(first.isEmpty());

        AudioProgressStore.Progress noCid = progress();
        noCid.cid = 0;
        noCid.title = "覆盖尝试";
        AudioProgressStore.save(noCid);

        assertEquals("无效记录不能把上一次的有效记录冲掉", first, stored());
        assertEquals("某首中文歌", AudioProgressStore.load().title);
    }

    @Test
    public void privacyModeNeitherWritesNorLoadsAndClearsOldRecord() {
        AudioProgressStore.save(progress());
        assertFalse(stored().isEmpty());

        sp.edit().putBoolean(KEY_PRIVACY, true).commit();
        assertNull(AudioProgressStore.load());

        AudioProgressStore.Progress p = progress();
        p.title = "隐私模式下不该写";
        AudioProgressStore.save(p);
        assertEquals("", stored()); // 隐私模式写入会顺手清掉旧记录
        assertNull(AudioProgressStore.load());

        sp.edit().putBoolean(KEY_PRIVACY, false).commit();
        assertNull(AudioProgressStore.load());
    }

    @Test
    public void corruptedOrIncompleteJsonLoadsAsNull() {
        sp.edit().putString(KEY_STATE, "{不是合法 JSON").commit();
        assertNull(AudioProgressStore.load());

        sp.edit().putString(KEY_STATE, "{}").commit(); // 缺 mediaId/cid
        assertNull(AudioProgressStore.load());

        sp.edit().putString(KEY_STATE, "{\"mediaId\":123,\"cid\":0}").commit();
        assertNull(AudioProgressStore.load());

        sp.edit().putString(KEY_STATE, "{\"mediaId\":0,\"cid\":456}").commit();
        assertNull(AudioProgressStore.load());

        sp.edit().putString(KEY_STATE, "null").commit();
        assertNull(AudioProgressStore.load());

        sp.edit().remove(KEY_STATE).commit();
        assertNull(AudioProgressStore.load()); // 空库
    }

    @Test
    public void recordOlderThanSevenDaysIsCleared() throws Exception {
        JSONObject json = new JSONObject();
        json.put("mediaId", 123456L);
        json.put("cid", 2002L);
        json.put("aid", 1001L);
        json.put("positionMs", 30000);
        json.put("durationMs", 300000);
        json.put("savedAt", System.currentTimeMillis() - 8L * 24 * 3600 * 1000);
        sp.edit().putString(KEY_STATE, json.toString()).commit();

        assertNull(AudioProgressStore.load());
        assertEquals("过期记录必须被清掉", "", stored());

        // 7 天内的记录照常恢复
        json.put("savedAt", System.currentTimeMillis() - 6L * 24 * 3600 * 1000);
        sp.edit().putString(KEY_STATE, json.toString()).commit();
        AudioProgressStore.Progress loaded = AudioProgressStore.load();
        assertEquals(123456L, loaded.mediaId);
        assertEquals(30000, loaded.positionMs);
        assertFalse(stored().isEmpty());
    }

    @Test
    public void formatTimeBoundaries() {
        assertEquals("0:00", AudioProgressStore.formatTime(0));
        assertEquals("0:00", AudioProgressStore.formatTime(999));
        assertEquals("0:00", AudioProgressStore.formatTime(-1500));
        assertEquals("0:01", AudioProgressStore.formatTime(1000));
        assertEquals("0:59", AudioProgressStore.formatTime(59000));
        assertEquals("1:00", AudioProgressStore.formatTime(60000));
        assertEquals("1:01", AudioProgressStore.formatTime(61000));
        assertEquals("59:59", AudioProgressStore.formatTime(3599000));
        assertEquals("60:00", AudioProgressStore.formatTime(3600000));
        assertEquals("62:03", AudioProgressStore.formatTime(3723000));
    }
}