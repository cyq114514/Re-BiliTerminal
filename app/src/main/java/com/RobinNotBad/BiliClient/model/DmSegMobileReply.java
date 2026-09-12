package com.RobinNotBad.BiliClient.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 弹幕分段响应
 * 对应 bilibili.community.service.dm.v1.DmSegMobileReply
 */
public class DmSegMobileReply {
    public List<DanmakuElem> elems;

    public DmSegMobileReply() {
        this.elems = new ArrayList<>();
    }

    public DmSegMobileReply(List<DanmakuElem> elems) {
        this.elems = elems;
    }
}
