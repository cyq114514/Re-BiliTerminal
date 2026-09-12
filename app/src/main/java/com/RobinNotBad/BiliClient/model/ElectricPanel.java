package com.RobinNotBad.BiliClient.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 充电公示数据模型
 */
public class ElectricPanel {
    public int count;                           // 本月充电人数
    public List<ElectricUser> list;             // 本月充电用户列表
    public int total_count;                     // 总计充电次数
    public int total;                           // 总计充电次数（同total_count）
    public int special_day;                     // 0 作用尚不明确

    public ElectricPanel() {
        this.list = new ArrayList<>();
    }

    public ElectricPanel(int count, List<ElectricUser> list, int total_count) {
        this.count = count;
        this.list = list != null ? list : new ArrayList<>();
        this.total_count = total_count;
        this.total = total_count;
    }

    /**
     * 判断是否有充电数据
     */
    public boolean hasData() {
        return count > 0 && list != null && !list.isEmpty();
    }
}

