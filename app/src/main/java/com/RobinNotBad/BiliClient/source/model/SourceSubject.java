package com.RobinNotBad.BiliClient.source.model;

import java.io.Serializable;

/**
 * 第三方作品的统一模型。
 *
 * <p><b>ID 命名空间</b>：{@link #sourceId} + {@link #sourceSubjectId} 才是全局唯一的作品键。
 * 同一个 Bangumi 条目在不同站点有不同 id，绝不能用 {@code bgmId} 当第三方 id，
 * 也不能把 A 站的 id 拿去 B 站请求 —— 这是本项目明确划的红线。
 */
public class SourceSubject implements Serializable {

    /** 数据源唯一 ID（含类型前缀，如 animeko:web-selector:酱紫社） */
    public String sourceId = "";
    /** 数据源展示名 */
    public String sourceName = "";
    /** 来源内部作品 ID（站点自己的一串，未解析出来时用 URL 摘要顶替） */
    public String sourceSubjectId = "";
    /** 作品标题（站点原文） */
    public String title = "";
    /** 详情页 URL（后续取线路用） */
    public String detailUrl = "";
    /** 站点搜索结果里的封面（可能为空；站点没有图时由调用方退回 Bangumi 封面） */
    public String coverUrl = "";

    /**
     * 弹幕匹配用的规范标题（来自 Bangumi 卡片）。
     *
     * <p>站点自己的标题常带「HD/第X季/全集」等后缀，直接拿去弹弹play 搜会匹配不上；
     * 为空时退回 {@link #title}，保证不经「选择片源」页直接进选集的路径也能用。
     */
    public String danmakuTitle = "";

    /** 可选：已确认对应的 Bangumi Subject ID，0 表示未绑定 */
    public long bangumiSubjectId = 0;

    /** 搜索相关度（0~1，仅用于排序展示，不参与「是否同一作品」判定） */
    public float score = 0;

    public String key() {
        return sourceId + "|" + (sourceSubjectId.isEmpty() ? detailUrl : sourceSubjectId);
    }

    public String display() {
        return title + "  ·  " + sourceName;
    }
}
