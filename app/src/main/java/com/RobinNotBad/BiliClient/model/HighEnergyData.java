package com.RobinNotBad.BiliClient.model;

/**
 * 高能进度条数据模型
 */
public class HighEnergyData {
    public int stepSec; // 采样间隔时间（秒）
    public String tagStr; // 标签字符串（用途未明）
    public float[] events; // 顶点值列表（弹幕密度数据）
    public String debug; // 调试信息

    public HighEnergyData() {
    }

    public HighEnergyData(int stepSec, String tagStr, float[] events, String debug) {
        this.stepSec = stepSec;
        this.tagStr = tagStr;
        this.events = events;
        this.debug = debug;
    }

    /**
     * 检查是否有有效的高能数据
     */
    public boolean hasValidData() {
        return events != null && events.length > 0;
    }

    /**
     * 获取视频总时长（根据数据点数量和采样间隔估算）
     */
    public int getEstimatedDuration() {
        if (events == null || events.length == 0) {
            return 0;
        }
        return events.length * stepSec;
    }
}
