package com.RobinNotBad.BiliClient.source.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 播放线路（Kazumi 叫「线路」，Animeko 叫 channel）。
 *
 * <p>不同站点的线路名、顺序、id 各不相同，{@link #id} 只在同一作品内唯一，
 * 跨站点比较线路必须同时看 {@link #sourceId}。
 */
public class SourceChannel implements Serializable {

    /** 线路 ID：sourceId + 作品键 + 序号，保证跨源不撞 */
    public String id = "";
    /** 线路名（站点原文；站点没有线路概念时为「默认线路」） */
    public String name = "";
    /** 线路在页面上的顺序，0 起 */
    public int index = 0;
    public String sourceId = "";

    public final List<SourceEpisode> episodes = new ArrayList<>();

    public SourceChannel() {
    }

    public SourceChannel(String id, String name, int index, String sourceId) {
        this.id = id;
        this.name = name;
        this.index = index;
        this.sourceId = sourceId;
    }

    public String display() {
        return name + "（" + episodes.size() + " 集）";
    }
}
