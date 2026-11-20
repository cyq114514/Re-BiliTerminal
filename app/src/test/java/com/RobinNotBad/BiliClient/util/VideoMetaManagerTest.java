package com.RobinNotBad.BiliClient.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.RobinNotBad.BiliClient.model.VideoMeta;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 本地缓存视频断点续播（{@link VideoMetaManager}）的单测。
 *
 * <p>用 Robolectric 提供真实的 {@code org.json} 实现；文件读写全部落在
 * {@link TemporaryFolder} 里，不碰真实视频目录、不联网。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class VideoMetaManagerTest {

    private static final String META_NAME = ".video_meta.json";
    private static final String TMP_NAME = ".video_meta.json.tmp";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private File videoFolder;

    @Before
    public void setUp() throws Exception {
        videoFolder = temporaryFolder.newFolder("video");
    }

    private File metaFile() {
        return new File(videoFolder, META_NAME);
    }

    private void writeMetaContent(String content) throws Exception {
        try (FileOutputStream out = new FileOutputStream(metaFile())) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private String readMetaContent() throws Exception {
        return new String(Files.readAllBytes(metaFile().toPath()), StandardCharsets.UTF_8);
    }

    // ================= readResumeMs：异常数据一律从头播 =================

    @Test
    public void readResumeMs_missingMetaFile_returnsZero() {
        assertEquals(0L, VideoMetaManager.readResumeMs(videoFolder));
    }

    @Test
    public void readResumeMs_nullFolder_returnsZero() {
        assertEquals(0L, VideoMetaManager.readResumeMs(null));
    }

    @Test
    public void readResumeMs_corruptJson_returnsZero() {
        try {
            writeMetaContent("{not-json");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertEquals(0L, VideoMetaManager.readResumeMs(videoFolder));
    }

    @Test
    public void readResumeMs_emptyFile_returnsZero() {
        try {
            writeMetaContent("");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertEquals(0L, VideoMetaManager.readResumeMs(videoFolder));
    }

    @Test
    public void readResumeMs_whitespaceOnlyFile_returnsZero() {
        try {
            writeMetaContent("  \n\t ");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertEquals(0L, VideoMetaManager.readResumeMs(videoFolder));
    }

    @Test
    public void readResumeMs_progressAtOrBelowFiveSeconds_returnsZero() {
        for (long progress : new long[]{0L, 1L, 5_000L}) {
            try {
                writeMetaContent("{\"progress\":" + progress + ",\"duration\":600000}");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            assertEquals("progress=" + progress + " 视为没看", 0L, VideoMetaManager.readResumeMs(videoFolder));
        }
    }

    @Test
    public void readResumeMs_nearEnd_returnsZero() {
        // 距结尾 3 秒、恰好 5 秒（边界）、1 秒：都视为看完
        for (long progress : new long[]{597_000L, 595_000L, 599_000L}) {
            try {
                writeMetaContent("{\"progress\":" + progress + ",\"duration\":600000}");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            assertEquals("progress=" + progress + " 距结尾太近", 0L, VideoMetaManager.readResumeMs(videoFolder));
        }
    }

    @Test
    public void readResumeMs_justOverFiveSecondsFromEnd_returnsProgress() {
        // 距结尾 5001ms：边界外，照常续播
        try {
            writeMetaContent("{\"progress\":594999,\"duration\":600000}");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertEquals(594_999L, VideoMetaManager.readResumeMs(videoFolder));
    }

    @Test
    public void readResumeMs_progressBeyondDuration_returnsZero() {
        try {
            writeMetaContent("{\"progress\":700000,\"duration\":600000}");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertEquals(0L, VideoMetaManager.readResumeMs(videoFolder));
    }

    @Test
    public void readResumeMs_normalValue_returnsAsIs() {
        try {
            writeMetaContent("{\"progress\":421000,\"duration\":1440000}");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertEquals(421_000L, VideoMetaManager.readResumeMs(videoFolder));
    }

    @Test
    public void readResumeMs_unknownDuration_returnsProgressWithoutBounds() {
        // duration=0（旧数据/未知时长）时不做越界判断
        try {
            writeMetaContent("{\"progress\":60000,\"duration\":0}");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertEquals(60_000L, VideoMetaManager.readResumeMs(videoFolder));
    }

    // ================= saveProgress / readMeta 往返 =================

    @Test
    public void saveProgress_thenRead_roundTrips() {
        VideoMetaManager.saveProgress(videoFolder, 60_000L, 600_000L);

        assertEquals(60_000L, VideoMetaManager.readResumeMs(videoFolder));
        VideoMeta meta = VideoMetaManager.readMeta(videoFolder);
        assertEquals(60_000L, meta.progressMs);
        assertEquals(600_000L, meta.durationMs);
    }

    @Test
    public void saveProgress_nonPositivePosition_savesZero() {
        VideoMetaManager.saveProgress(videoFolder, 60_000L, 600_000L);
        VideoMetaManager.saveProgress(videoFolder, 0L, 600_000L);

        assertEquals(0L, VideoMetaManager.readResumeMs(videoFolder));
        assertEquals(0L, VideoMetaManager.readMeta(videoFolder).progressMs);
    }

    @Test
    public void saveProgress_nearEnd_savesZeroAndClearsDuration() {
        VideoMetaManager.saveProgress(videoFolder, 60_000L, 600_000L);
        // 距结尾 4 秒：写完视为看完，同时清时长，避免下次被误判成续播
        VideoMetaManager.saveProgress(videoFolder, 596_000L, 600_000L);

        assertEquals(0L, VideoMetaManager.readResumeMs(videoFolder));
        VideoMeta meta = VideoMetaManager.readMeta(videoFolder);
        assertEquals(0L, meta.progressMs);
        assertEquals(0L, meta.durationMs);
    }

    @Test
    public void saveProgress_zeroDuration_keepsPositivePosition() {
        // duration=0 不做越界/结尾判断，位置照常落盘并可读回
        VideoMetaManager.saveProgress(videoFolder, 60_000L, 0L);

        assertEquals(60_000L, VideoMetaManager.readResumeMs(videoFolder));
        VideoMeta meta = VideoMetaManager.readMeta(videoFolder);
        assertEquals(60_000L, meta.progressMs);
        assertEquals(0L, meta.durationMs);
    }

    // ================= 旧数据 / 脏数据兼容 =================

    @Test
    public void legacyMeta_progressOnly_returnsProgress() throws Exception {
        // 旧版本只写了 progress、没有 duration
        writeMetaContent("{\"progress\":12345}");

        assertEquals(12_345L, VideoMetaManager.readResumeMs(videoFolder));
        assertEquals(0L, VideoMetaManager.readMeta(videoFolder).durationMs);
    }

    @Test
    public void legacyMeta_durationOnly_returnsZero() throws Exception {
        writeMetaContent("{\"duration\":600000}");

        assertEquals(0L, VideoMetaManager.readResumeMs(videoFolder));
        assertEquals(0L, VideoMetaManager.readMeta(videoFolder).progressMs);
        assertEquals(600_000L, VideoMetaManager.readMeta(videoFolder).durationMs);
    }

    @Test
    public void metaWithUnknownFields_ignoresThem() throws Exception {
        writeMetaContent("{\"progress\":60000,\"duration\":600000,"
                + "\"cid\":123456,\"title\":\"e1\",\"future_field\":{\"a\":[1,2]}}");

        assertEquals(60_000L, VideoMetaManager.readResumeMs(videoFolder));
    }

    @Test
    public void corruptJson_doesNotThrow_andFallsBackToDefaults() {
        try {
            writeMetaContent("{\"progress\":60000,\"duration\":");
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        VideoMeta meta = VideoMetaManager.readMeta(videoFolder);
        assertEquals(0L, meta.progressMs);
        assertEquals(0L, meta.durationMs);
        assertEquals(0L, VideoMetaManager.readResumeMs(videoFolder));
    }

    // ================= saveMeta 原子性 =================

    @Test
    public void saveMeta_leavesNoTemporaryFile() {
        VideoMeta meta = new VideoMeta();
        meta.progressMs = 60_000L;
        meta.durationMs = 600_000L;

        VideoMetaManager.saveMeta(videoFolder, meta);

        assertFalse("原子替换后不应残留 .tmp",
                new File(videoFolder, TMP_NAME).exists());
        assertTrue(metaFile().isFile());
        assertEquals(60_000L, VideoMetaManager.readResumeMs(videoFolder));
    }

    @Test
    public void saveMeta_repeatedSameValue_keepsFileReadable() throws Exception {
        VideoMeta meta = new VideoMeta();
        meta.progressMs = 60_000L;
        meta.durationMs = 600_000L;

        VideoMetaManager.saveMeta(videoFolder, meta);
        String first = readMetaContent();
        VideoMetaManager.saveMeta(videoFolder, meta);

        assertFalse(new File(videoFolder, TMP_NAME).exists());
        assertEquals("重复写同一份数据不应损坏文件", first, readMetaContent());
        assertEquals(60_000L, VideoMetaManager.readResumeMs(videoFolder));
    }

    @Test
    public void saveProgress_repeatedSameValue_doesNotCorruptFile() throws Exception {
        VideoMetaManager.saveProgress(videoFolder, 60_000L, 600_000L);
        String first = readMetaContent();
        VideoMetaManager.saveProgress(videoFolder, 60_000L, 600_000L);

        assertEquals(first, readMetaContent());
        assertEquals(60_000L, VideoMetaManager.readResumeMs(videoFolder));
        assertFalse(new File(videoFolder, TMP_NAME).exists());
    }

    @Test
    public void saveMeta_nullArguments_doNotThrowOrWrite() {
        VideoMetaManager.saveMeta(null, new VideoMeta());
        VideoMetaManager.saveMeta(videoFolder, null);

        assertFalse(metaFile().exists());
    }

    @Test
    public void readMeta_nullFolder_returnsDefaults() {
        VideoMeta meta = VideoMetaManager.readMeta(null);
        assertEquals(0L, meta.progressMs);
        assertEquals(0L, meta.durationMs);
    }
}