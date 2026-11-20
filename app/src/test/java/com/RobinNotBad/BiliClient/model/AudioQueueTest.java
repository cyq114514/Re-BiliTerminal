package com.RobinNotBad.BiliClient.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 音频队列（进程内单例）的纯逻辑测试。
 *
 * <p>只覆盖已实现的行为，不替生产代码补规则：队列本身不做 {@code invalid} 过滤
 * （收藏夹入口在 {@code FavoriteVideoListActivity.startAudioQueue} 里先滤掉失效稿件），
 * 所以这里按实际契约断言「无效稿件由调用方过滤」，而不是期望队列过滤。
 */
@RunWith(RobolectricTestRunner.class)
public class AudioQueueTest {

    private AudioQueue queue;

    @Before
    public void setUp() {
        queue = AudioQueue.get();
        queue.clear();
        // clear() 有意不复位播放模式（模式是用户存档，跨队列保持），测试里显式归位
        queue.setPlayMode(AudioQueue.MODE_LIST_LOOP);
    }

    @After
    public void tearDown() {
        queue.clear();
        queue.setPlayMode(AudioQueue.MODE_LIST_LOOP);
    }

    private static VideoCard video(long aid, long cid) {
        VideoCard v = new VideoCard("t" + aid + "_" + cid, "up", "0", "", aid, "bv" + aid);
        v.cid = cid;
        return v;
    }

    private static VideoCard localFile(String path) {
        // 本地缓存条目：aid/cid 都是 0，靠 localPath 标识
        VideoCard v = new VideoCard("local:" + path, "", "", "", 0, "");
        v.localPath = path;
        return v;
    }

    private static List<VideoCard> list(VideoCard... cards) {
        return new ArrayList<>(Arrays.asList(cards));
    }

    @Test
    public void setQueueFiltersNonPositiveAidButKeepsLocalItems() {
        List<VideoCard> source = new ArrayList<>();
        source.add(null);
        source.add(video(0, 0));      // aid<=0 且非本地：丢弃
        source.add(video(-5, 0));     // 负数 aid：丢弃
        source.add(video(100, 6));    // 正常条目
        source.add(localFile("/sdcard/a.m4a"));
        source.add(localFile("/sdcard/b.m4a"));
        source.add(localFile("/sdcard/a.m4a")); // 同一路径重复：按路径去重

        queue.setQueue(source, 9527L, "mtime", "我的收藏");

        assertEquals(3, queue.size());
        assertEquals(3, queue.getItems().size());
        long aid = queue.getItems().get(0).aid;
        assertEquals(100L, aid);
        assertEquals(6L, queue.getItems().get(0).cid);
        assertEquals("/sdcard/a.m4a", queue.getItems().get(1).localPath);
        assertEquals("/sdcard/b.m4a", queue.getItems().get(2).localPath);
        assertTrue(queue.isActive());
        assertEquals(9527L, queue.getMediaId());
        assertEquals("mtime", queue.getOrder());
        assertEquals("我的收藏", queue.getFolderName());
        assertEquals(0, queue.getIndex());
        assertSame(queue.getItems().get(0), queue.current());
    }

    @Test
    public void setQueueDeduplicatesByAidAndCidButKeepsMultiParts() {
        List<VideoCard> source = list(
                video(100, 1001),
                video(100, 1002),   // 同一稿件不同分P：必须保留
                video(100, 1003),
                video(100, 1001),   // 完全重复：丢弃
                video(200, 0),
                video(200, 0));     // 同 aid 同 cid：丢弃

        queue.setQueue(source, 1L, "mtime", "多P");

        assertEquals(4, queue.size());
        assertEquals(1001L, queue.getItems().get(0).cid);
        assertEquals(1002L, queue.getItems().get(1).cid);
        assertEquals(1003L, queue.getItems().get(2).cid);
        assertEquals(200L, queue.getItems().get(3).aid);
    }

    @Test
    public void invalidFlagIsNotFilteredByQueueItself() {
        // 生产契约：失效稿件的过滤在调用方（收藏夹列表）完成，队列按 aid/cid 照收。
        // 这条用例把契约钉住：若以后有人在队列里加过滤，这里会先亮红灯，必须同步改调用方语义。
        VideoCard invalid = video(300, 7);
        invalid.invalid = true;

        queue.setQueue(list(invalid, video(301, 8)), 1L, "mtime", "f");

        assertEquals(2, queue.size());
        assertTrue(queue.getItems().get(0).invalid);
        assertFalse(queue.getItems().get(1).invalid);
    }

    @Test
    public void setQueueResetsIndexAndComputesLoadedPageFromItemCount() {
        List<VideoCard> firstPage = new ArrayList<>();
        for (int i = 0; i < 1; i++) firstPage.add(video(1000L + i, 1));
        queue.setQueue(firstPage, 10L, "mtime", "f");
        assertEquals(1, queue.getLoadedPage());

        List<VideoCard> exactlyOnePage = new ArrayList<>();
        for (int i = 0; i < 20; i++) exactlyOnePage.add(video(2000L + i, 1));
        queue.setQueue(exactlyOnePage, 10L, "mtime", "f");
        assertEquals(1, queue.getLoadedPage());
        assertTrue(queue.hasMore());

        List<VideoCard> twoPages = new ArrayList<>();
        for (int i = 0; i < 21; i++) twoPages.add(video(3000L + i, 1));
        queue.setQueue(twoPages, 10L, "mtime", "f");
        assertEquals(2, queue.getLoadedPage());
        assertEquals(0, queue.getIndex());
        assertSame(twoPages.get(0), queue.current());
        assertEquals(20, queue.getRemaining());

        queue.setQueue(new ArrayList<>(), 10L, "mtime", "f");
        assertEquals(0, queue.size());
        assertEquals(1, queue.getLoadedPage());
        assertFalse(queue.isActive());
        assertNull(queue.current());
    }

    @Test
    public void nextPreviousAndListLoopWrap() {
        queue.setQueue(list(video(1, 11), video(2, 22), video(3, 33)), 10L, "mtime", "f");

        assertEquals(0, queue.getIndex());
        assertTrue(queue.hasNext());
        assertFalse(queue.hasPrevious());

        VideoCard second = queue.next();
        assertSame(queue.getItems().get(1), second);
        assertNotNull(second);
        assertEquals(1, queue.getIndex());
        assertTrue(queue.hasPrevious());

        assertSame(queue.getItems().get(2), queue.next());
        assertFalse(queue.hasNext());

        // 列表循环：到尾再 next 回到第一首
        assertSame(queue.getItems().get(0), queue.next());
        assertEquals(0, queue.getIndex());
        // 列表循环 past 开头：previous 不回卷（与 next 的回卷语义不对称）
        assertNull(queue.previous());
        assertEquals(0, queue.getIndex());
    }

    @Test
    public void singleLoopModeDoesNotAdvanceAtTail() {
        queue.setQueue(list(video(1, 11), video(2, 22)), 10L, "mtime", "f");
        queue.setPlayMode(AudioQueue.MODE_SINGLE_LOOP);
        queue.setIndex(1);

        assertNull(queue.next());
        assertEquals(1, queue.getIndex()); // 位置不动，由播放器自己重播
        assertEquals("2/2", queue.positionText());

        // 单曲循环只影响到尾自动切歌；手动上一首仍然可用
        assertSame(queue.getItems().get(0), queue.previous());
        assertEquals(0, queue.getIndex());
        assertNotNull(queue.next());
        assertEquals(1, queue.getIndex());
    }

    @Test
    public void randomModeOnlyMovesIndexWithoutChangingList() {
        queue.setQueue(list(video(1, 11), video(2, 22), video(3, 33), video(4, 44), video(5, 55)),
                10L, "mtime", "f");
        queue.setPlayMode(AudioQueue.MODE_RANDOM);
        int sizeBefore = queue.size();

        for (int i = 0; i < 20; i++) {
            int before = queue.getIndex();
            VideoCard picked = queue.next();
            assertNotNull(picked);
            assertTrue("随机播放必须真的换位", queue.getIndex() != before);
            assertSame(queue.getItems().get(queue.getIndex()), picked);
            assertEquals(sizeBefore, queue.size());
            assertEquals(5, queue.getItems().size());
        }
        // 随机的目标永远在已加载区间内，且不会把队列顺序改掉
        assertEquals(1L, queue.getItems().get(0).aid);

        // 只有一首时随机模式无处可跳：返回 null 而不是原地死循环
        queue.setQueue(list(video(9, 99)), 10L, "mtime", "f");
        queue.setPlayMode(AudioQueue.MODE_RANDOM);
        assertNull(queue.next());
        assertEquals(0, queue.getIndex());

        // 非法模式值不生效
        queue.setPlayMode(99);
        assertEquals(AudioQueue.MODE_RANDOM, queue.getPlayMode());
        queue.setPlayMode(-1);
        assertEquals(AudioQueue.MODE_RANDOM, queue.getPlayMode());
    }

    @Test
    public void insertAfterCurrentKeepsOrderAndSkipsDuplicates() {
        queue.setQueue(list(video(1, 11), video(2, 22), video(3, 33)), 10L, "mtime", "f");
        queue.setIndex(0);

        int added = queue.insertAfterCurrent(list(
                video(1, 12),   // 当前稿件的 P2：新键，插入
                video(2, 22),   // 已在队列里：跳过
                null,
                video(0, 0),    // aid<=0 且非本地：跳过
                video(4, 44))); // 新稿件：插入

        assertEquals(2, added);
        assertEquals(5, queue.size());
        assertEquals(11L, queue.getItems().get(0).cid);
        assertEquals(12L, queue.getItems().get(1).cid);
        assertEquals(4L, queue.getItems().get(2).aid);
        assertEquals(2L, queue.getItems().get(3).aid);
        assertEquals(3L, queue.getItems().get(4).aid);
        assertEquals(0, queue.getIndex()); // 播放位置不动
        assertTrue(queue.isActive());

        // 分P 已存在时再次插入不重复
        assertEquals(0, queue.insertAfterCurrent(list(video(1, 12))));
        assertEquals(5, queue.size());

        // 空列表/空入参不改变队列
        assertEquals(0, queue.insertAfterCurrent(new ArrayList<>()));
        assertEquals(0, queue.insertAfterCurrent(null));
    }

    @Test
    public void markExpandedOnlyReturnsTrueOnceAndUnmarkAllowsRetry() {
        assertTrue(queue.markExpanded(123L));
        assertFalse(queue.markExpanded(123L));
        assertTrue(queue.markExpanded(456L));

        queue.unmarkExpanded(123L);
        assertTrue(queue.markExpanded(123L));

        // 未标记过的稿件 unmark 是安全的空操作
        queue.unmarkExpanded(789L);
        assertTrue(queue.markExpanded(789L));
    }

    @Test
    public void appendDeduplicatesAndActivatesQueue() {
        queue.clear();
        assertFalse(queue.isActive());

        assertEquals(0, queue.append(null));
        assertEquals(0, queue.append(new ArrayList<>()));
        assertEquals(0, queue.append(list(video(0, 0))));
        assertFalse(queue.isActive());

        queue.setQueue(list(video(1, 11), video(2, 22)), 10L, "mtime", "f");
        queue.setIndex(1);
        int added = queue.append(list(
                video(2, 22),        // 重复
                video(1, 12),        // 同稿件异分P：新增
                video(3, 33),
                localFile("/sdcard/c.m4a")));

        assertEquals(3, added);
        assertEquals(5, queue.size());
        assertEquals(1, queue.getIndex()); // 追加不动当前播放位置
        assertTrue(queue.isActive());
        assertEquals("/sdcard/c.m4a", queue.getItems().get(4).localPath);

        // 追加本地条目后再次追加同一路径：按路径去重
        assertEquals(0, queue.append(list(localFile("/sdcard/c.m4a"))));
        assertEquals(1, queue.append(list(localFile("/sdcard/d.m4a"))));
    }

    @Test
    public void positionTextRemainingAndSetIndexBounds() {
        queue.setQueue(list(video(1, 11), video(2, 22), video(3, 33), video(4, 44), video(5, 55)),
                10L, "mtime", "f");
        assertEquals("1/5", queue.positionText());
        assertEquals(4, queue.getRemaining());

        queue.setIndex(2);
        assertEquals("3/5", queue.positionText());
        assertEquals(2, queue.getRemaining());
        assertSame(queue.getItems().get(2), queue.current());

        // 越界下标必须忽略，不能把播放位置弄丢
        queue.setIndex(-1);
        assertEquals(2, queue.getIndex());
        queue.setIndex(5);
        assertEquals(2, queue.getIndex());
        queue.setIndex(4);
        assertEquals("5/5", queue.positionText());
        assertEquals(0, queue.getRemaining());
        assertFalse(queue.hasNext());

        queue.clear();
        assertEquals(0, queue.getRemaining());
        assertEquals("1/0", queue.positionText());
        assertNull(queue.next());
        assertNull(queue.previous());
    }

    @Test
    public void localCacheQueueHasNoMorePagesAndNegativeMediaId() {
        queue.setQueue(list(localFile("/sdcard/1.m4a"), localFile("/sdcard/2.m4a")),
                -1L, "", "本地缓存");

        assertEquals(-1L, queue.getMediaId());
        assertFalse("本地缓存队列是一次性灌满的，不能再置 hasMore",
                queue.hasMore());
        assertEquals(2, queue.size());
        assertTrue(queue.isActive());
        assertEquals(1, queue.getLoadedPage());
        assertEquals("", queue.getOrder());

        // 收藏夹队列（mediaId>0）才可能有下一页
        queue.setQueue(list(video(1, 11)), 88L, "", "f");
        assertTrue(queue.hasMore());
        queue.setHasMore(false);
        assertFalse(queue.hasMore());
    }
}