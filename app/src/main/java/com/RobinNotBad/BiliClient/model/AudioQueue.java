package com.RobinNotBad.BiliClient.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 收藏夹音频播放队列（进程内单例）。
 *
 * <p>为什么不用 Intent 传递：整夹可能有上百条，Parcel 事务有大小上限；
 * 且 B 站取流地址数小时即过期，队列只保留可重新取流的标识（aid/bvid/cid），
 * 每次播放前重新解析音频地址。
 *
 * <p>只服务「收藏夹音频模式」，普通视频播放不受影响；普通播放器启动时会清空本队列。
 */
public class AudioQueue {

    private static final AudioQueue INSTANCE = new AudioQueue();

    public static AudioQueue get() {
        return INSTANCE;
    }

    private final List<VideoCard> items = new ArrayList<>();
    //按 aid+cid 去重：列表页翻过的多页与分页接口的边界可能重叠，重复追加会出现同一首歌两遍。
    //不能再只按 aid：多P 稿件展开后同一 aid 会有多条（每P 一条），只按 aid 会把后续分P 全丢掉
    private final java.util.HashSet<String> keySet = new java.util.HashSet<>();
    //已经拉过并插入过其余分P 的稿件（每个稿件只展开一次，重播不会重复插入）
    private final java.util.HashSet<Long> expandedAids = new java.util.HashSet<>();
    private int index = 0;
    private long mediaId = 0;
    private String order = "";
    private String folderName = "";
    private boolean active = false;

    //懒加载：首播只用列表页已加载的一页，后续页在播放中按需追加
    private int loadedPage = 0;
    private boolean hasMore = false;
    private boolean loadingMore = false;

    private AudioQueue() {
    }

    public synchronized void setQueue(List<VideoCard> videos, long mediaId, String order, String folderName) {
        items.clear();
        keySet.clear();
        expandedAids.clear();
        if (videos != null) {
            for (VideoCard video : videos) addIfAbsent(video);
        }
        index = 0;
        this.mediaId = mediaId;
        this.order = order != null ? order : "";
        this.folderName = folderName != null ? folderName : "";
        //已加载页数按条数推算：入口可能把列表页已翻的多页一起传进来（一页 20 条）。
        //此前固定记 1，从第 2 页补拉会把列表页已加载的内容重复追加一遍
        this.loadedPage = Math.max(1,
                (int) Math.ceil(items.size() / (double) com.RobinNotBad.BiliClient.api.FavoriteApi.FOLDER_PAGE_SIZE));
        this.hasMore = true;
        this.loadingMore = false;
        active = !items.isEmpty();
    }

    /** 去重键：同一稿件的不同分P 是不同条目，所以必须把 cid 也算进去 */
    private static String keyOf(VideoCard video) {
        return video.aid + ":" + Math.max(video.cid, 0);
    }

    /** 去重追加：队列里已有同 aid+同 cid 的条目跳过 */
    private void addIfAbsent(VideoCard video) {
        if (video == null || video.aid <= 0) return;
        if (!keySet.add(keyOf(video))) return;
        items.add(video);
    }

    /**
     * 把同一稿件的其余分P 插到当前项之后，返回实际插入条数。
     *
     * <p>插在当前项后面而不是队尾：用户点第 3 首的多P 视频，期望接下来连着听这个稿件的
     * P2、P3，而不是听完整夹才轮到。
     */
    public synchronized int insertAfterCurrent(List<VideoCard> parts) {
        if (parts == null || parts.isEmpty()) return 0;
        int at = Math.min(index + 1, items.size());
        int added = 0;
        for (VideoCard video : parts) {
            if (video == null || video.aid <= 0) continue;
            if (!keySet.add(keyOf(video))) continue;
            items.add(at + added, video);
            added++;
        }
        if (added > 0) active = true;
        return added;
    }

    /** 标记该稿件的分P 已展开；返回 true 表示这是第一次（调用方据此决定要不要去拉分P 列表） */
    public synchronized boolean markExpanded(long aid) {
        return expandedAids.add(aid);
    }

    /** 撤销展开标记：拉分P 列表失败时用，下次播放同一稿件可以重试，而不是永远放弃 */
    public synchronized void unmarkExpanded(long aid) {
        expandedAids.remove(aid);
    }

    public synchronized void clear() {
        items.clear();
        keySet.clear();
        expandedAids.clear();
        index = 0;
        active = false;
        mediaId = 0;
        order = "";
        folderName = "";
        loadedPage = 0;
        hasMore = false;
        loadingMore = false;
    }

    public synchronized List<VideoCard> getItems() {
        return new ArrayList<>(items);
    }

    public synchronized int size() {
        return items.size();
    }

    public synchronized int getIndex() {
        return index;
    }

    public synchronized void setIndex(int index) {
        if (index >= 0 && index < items.size()) this.index = index;
    }

    public synchronized VideoCard current() {
        if (items.isEmpty()) return null;
        return items.get(Math.min(index, items.size() - 1));
    }

    public synchronized boolean hasNext() {
        return index + 1 < items.size();
    }

    public synchronized boolean hasPrevious() {
        return index > 0;
    }

    public synchronized VideoCard next() {
        if (!hasNext()) return null;
        index++;
        return items.get(index);
    }

    public synchronized VideoCard previous() {
        if (!hasPrevious()) return null;
        index--;
        return items.get(index);
    }

    public synchronized boolean isActive() {
        return active;
    }

    public synchronized void setActive(boolean active) {
        this.active = active;
    }

    public synchronized long getMediaId() {
        return mediaId;
    }

    public synchronized String getOrder() {
        return order;
    }

    public synchronized String getFolderName() {
        return folderName;
    }

    /** 队列进度文案：3/20 */
    public synchronized String positionText() {
        return (index + 1) + "/" + items.size();
    }

    // ==================== 懒加载 ====================

    public synchronized int getLoadedPage() {
        return loadedPage;
    }

    public synchronized void setLoadedPage(int page) {
        this.loadedPage = page;
    }

    public synchronized boolean hasMore() {
        return hasMore;
    }

    public synchronized void setHasMore(boolean hasMore) {
        this.hasMore = hasMore;
    }

    public synchronized boolean isLoadingMore() {
        return loadingMore;
    }

    public synchronized void setLoadingMore(boolean loadingMore) {
        this.loadingMore = loadingMore;
    }

    /** 当前项之后还剩几首（用于判断是否该提前拉下一页） */
    public synchronized int getRemaining() {
        return Math.max(0, items.size() - index - 1);
    }

    /** 追加一页（懒加载/全量拉取），保留顺序与当前播放位置；按 aid 去重，返回实际新增条数 */
    public synchronized int append(List<VideoCard> more) {
        if (more == null || more.isEmpty()) return 0;
        int before = items.size();
        for (VideoCard video : more) addIfAbsent(video);
        if (items.size() > before) active = true;
        return items.size() - before;
    }
}
