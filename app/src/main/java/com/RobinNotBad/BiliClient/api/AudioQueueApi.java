package com.RobinNotBad.BiliClient.api;

import com.RobinNotBad.BiliClient.model.VideoCard;

import java.util.ArrayList;
import java.util.List;

/**
 * 收藏夹音频队列的数据来源。
 *
 * <p>起播只用列表页已加载的内容立即开始；随后由 {@code AudioPlayerService.ensureMoreItems}
 * 从已加载页的下一页起串行翻页（调 {@link #loadPage}）直到收藏夹到底，整夹内容一次性进队列。
 * 串行单页请求，不并发，避免触发 B 站 412 风控；单页失败重试后仍失败则停住，断点续拉。
 *
 * <p>hasMore 用接口原值：某页条目全部失效被过滤为空时，后面可能还有页——
 * 不能把"过滤后为空"当成"到底"。
 */
public class AudioQueueApi {

    /** 单页结果 */
    public static class Page {
        public final List<VideoCard> items = new ArrayList<>();
        public boolean hasMore;
    }

    /**
     * 拉取收藏夹某一页的可播放条目。
     * 过滤：非视频类型（FavoriteApi 已过滤）、已失效稿件（attr!=0）、缺少 aid 的脏数据。
     */
    public static Page loadPage(long mediaId, int page, String order) throws Exception {
        ArrayList<VideoCard> pageList = new ArrayList<>();
        int hasMore = FavoriteApi.getFolderVideos(mediaId, page, order, pageList);

        Page result = new Page();
        for (VideoCard card : pageList) {
            if (card == null || card.aid <= 0) continue;
            if (card.invalid) continue;
            result.items.add(card);
        }
        //hasMore：0=还有下一页，1=到底（接口返回值语义）。
        //必须用原值：某页 20 条全是失效稿件被滤空时，后面可能还有页——
        //此前 && !pageList.isEmpty() 会把这种页误判成到底，队列提前截断
        result.hasMore = hasMore == 0;
        return result;
    }
}
