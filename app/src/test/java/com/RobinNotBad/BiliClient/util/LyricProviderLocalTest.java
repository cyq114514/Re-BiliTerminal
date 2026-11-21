package com.RobinNotBad.BiliClient.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.RobinNotBad.BiliClient.model.VideoCard;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;

/**
 * 随缓存落盘的歌词（R2，2026-11-21）：下载时写 `lyrics.json`，离线听歌直接读。
 *
 * <p>钉住的契约：文件名固定、JSON 字段与 24h 网络缓存一致、已有文件不被覆盖、
 * 空歌词不落盘。播放侧 `load/readCache` 的优先级由这些行为保证。
 */
@RunWith(RobolectricTestRunner.class)
public class LyricProviderLocalTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private VideoCard localItem(File media) {
        VideoCard item = new VideoCard();
        item.title = "测试歌曲";
        item.localPath = media.getAbsolutePath();
        return item;
    }

    private LyricProvider.Lyrics sample(String source, long timeMs, String text) {
        LyricProvider.Lyrics lyrics = new LyricProvider.Lyrics();
        lyrics.source = source;
        LyricProvider.Line line = new LyricProvider.Line();
        line.timeMs = timeMs;
        line.text = text;
        lyrics.lines.add(line);
        return lyrics;
    }

    @Test
    public void writeThenReadRoundTrip() throws Exception {
        File media = temp.newFile("audio.m4a");
        VideoCard item = localItem(media);
        LyricProvider.writeLocal(item, sample("网易云：歌名 - 歌手", 1234, "第一句"));

        assertTrue(new File(media.getParentFile(), "lyrics.json").exists());

        LyricProvider.Lyrics read = LyricProvider.readLocal(item);
        assertNotNull(read);
        assertEquals("网易云：歌名 - 歌手", read.source);
        assertEquals(1, read.lines.size());
        assertEquals(1234, read.lines.get(0).timeMs);
        assertEquals("第一句", read.lines.get(0).text);
    }

    @Test
    public void existingLocalLyricsAreNotOverwritten() throws Exception {
        File media = temp.newFile("audio.m4a");
        VideoCard item = localItem(media);
        LyricProvider.writeLocal(item, sample("B站字幕", 10, "原文"));
        LyricProvider.writeLocal(item, sample("网易云：另一首", 20, "覆盖"));

        LyricProvider.Lyrics read = LyricProvider.readLocal(item);
        assertNotNull(read);
        assertEquals("B站字幕", read.source);
        assertEquals("原文", read.lines.get(0).text);
    }

    @Test
    public void readLocalReturnsNullWhenNoFile() throws Exception {
        File media = temp.newFile("audio.m4a");
        assertNull(LyricProvider.readLocal(localItem(media)));
    }

    @Test
    public void emptyLyricsWriteNothing() throws Exception {
        File media = temp.newFile("audio.m4a");
        LyricProvider.writeLocal(localItem(media), new LyricProvider.Lyrics());
        assertFalse(new File(media.getParentFile(), "lyrics.json").exists());
    }

    @Test
    public void keySeparatesOnlineCidFromLocalPath() throws Exception {
        VideoCard online = new VideoCard();
        online.cid = 9527;
        assertEquals("c9527", LyricProvider.keyOf(online));

        File media = temp.newFile("audio.m4a");
        VideoCard local = localItem(media);
        assertEquals("p" + media.getAbsolutePath(), LyricProvider.keyOf(local));

        assertTrue(LyricProvider.hasLocalPath(local));
        assertFalse(LyricProvider.hasLocalPath(online));
    }
}