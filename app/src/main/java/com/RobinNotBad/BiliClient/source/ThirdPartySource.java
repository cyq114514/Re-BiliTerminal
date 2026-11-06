package com.RobinNotBad.BiliClient.source;

import com.RobinNotBad.BiliClient.source.model.SourceChannel;
import com.RobinNotBad.BiliClient.source.model.SourceException;
import com.RobinNotBad.BiliClient.source.model.SourceInfo;
import com.RobinNotBad.BiliClient.source.model.SourceSubject;

import java.util.List;

/**
 * 统一第三方源接口。
 *
 * <p>只有三个能力：搜作品、取线路/剧集、（由 MediaResolver 完成）解析媒体地址。
 * 四个实现（Animeko Selector / Animeko JSON API / Kazumi XPath / Kazumi API）
 * 都输出同一套模型，UI 与播放器完全不需要知道规则语言差异。
 *
 * <p><b>线程约定</b>：所有方法都是阻塞 IO，必须在后台线程调用，
 * 由 {@link SourceSearchCoordinator} / {@link EpisodePlayback} 负责调度与取消。
 */
public interface ThirdPartySource {

    SourceInfo info();

    /**
     * 搜索作品。
     *
     * @param keyword 关键词（已由调用方做过清洗，源内部不再二次改写）
     * @return 结果列表，可能为空；失败抛 {@link SourceException}
     */
    List<SourceSubject> search(String keyword) throws SourceException;

    /**
     * 取某作品的线路与剧集。
     *
     * @return 至少一条线路；站点没有线路概念时返回单条「默认线路」
     */
    List<SourceChannel> channels(SourceSubject subject) throws SourceException;

    /**
     * 该源的「视频识别规则」。
     *
     * <p>Animeko 用 matchVideoUrl 正则、Kazumi 用扩展名嗅探，UI/解析器不需要区分。
     */
    com.RobinNotBad.BiliClient.source.resolve.MediaRule mediaRule();

    /** 取消信号：页面退出时中断在途请求，避免后台白跑（引用计数式，不强制） */
    interface CancelSignal {
        boolean isCancelled();
    }

    /** 部分实现需要感知取消；不支持时忽略即可 */
    default void setCancelSignal(CancelSignal signal) {
    }
}
