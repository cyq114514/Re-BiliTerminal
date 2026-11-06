package com.RobinNotBad.BiliClient.source.model;

import java.io.Serializable;

/**
 * 剧集统一模型。
 *
 * <p><b>集数排序</b>：{@link #sort} 是数值化后的集号（正片 1、2、3…；特别篇/OVA 用
 * {@link #sortRaw} 的解析结果），排序一律用它，<b>禁止</b>直接字符串排序 ——
 * "第10集" 按字符串会排到 "第2集" 前面。
 *
 * <p>{@link #pageUrl} 是「剧集页面地址」，不是媒体地址。两者语义必须分清：
 * 把网页地址当 m3u8 丢给播放器一定播不了（见 SourceMedia 的说明）。
 */
public class SourceEpisode implements Serializable {

    /** 所在线路 ID */
    public String channelId = "";
    /** 线路序号，0 起（Animeko @roadIndex / Kazumi @roadIndex 同源语义） */
    public int channelIndex = 0;
    /** 集序号，0 起 */
    public int index = 0;

    /** 剧集名（站点原文，可能是「第01话」「正片」「1080P」） */
    public String name = "";
    /** 数值化集号，用于排序；解析不出时等于 {@link #index}+1 */
    public double sort = 0;
    /** 集号原文（如 "1.5"、"SP1"），解析不出为空 */
    public String sortRaw = "";

    /** 剧集页面 URL（需要再解析才得到媒体地址） */
    public String pageUrl = "";
    /** 该集直接给出的媒体地址；为空表示还要解析（两种语义不同，见内联注释） */
    public String directMediaUrl = "";

    public String sourceId = "";

    public String id() {
        return sourceId + "|" + channelId + "|" + index;
    }

    /** 展示名：站点名太脏时补上集号 */
    public String display() {
        if (name != null && !name.trim().isEmpty()) return name.trim();
        return "第" + (index + 1) + "集";
    }
}
