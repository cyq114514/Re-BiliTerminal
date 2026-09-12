package com.RobinNotBad.BiliClient.model;

/**
 * 弹幕元素
 * 对应 bilibili.community.service.dm.v1.DanmakuElem
 */
public class DanmakuElem {
    public long id; // 弹幕 dmid
    public int progress; // 视频内弹幕出现时间（毫秒）
    public int mode; // 弹幕类型 1-3:普通 4:底部 5:顶部 6:逆向 7:高级 8:代码 9:BAS
    public int fontsize; // 弹幕字号 18:小 25:标准 36:大
    public int color; // 弹幕颜色（十进制 RGB888）
    public String midHash; // 发送者 mid 的 HASH
    public String content; // 弹幕内容
    public long ctime; // 弹幕发送时间（时间戳）
    public int weight; // 权重 0-10
    public String action; // 动作
    public int pool; // 弹幕池 0:普通 1:字幕 2:特殊
    public String idStr; // 弹幕 dmid 字符串形式
    public int attr; // 弹幕属性位
    public String animation; // 动画

    public DanmakuElem() {
    }

    public DanmakuElem(long id, int progress, int mode, int fontsize, int color,
                       String midHash, String content, long ctime, int weight,
                       String action, int pool, String idStr, int attr, String animation) {
        this.id = id;
        this.progress = progress;
        this.mode = mode;
        this.fontsize = fontsize;
        this.color = color;
        this.midHash = midHash;
        this.content = content;
        this.ctime = ctime;
        this.weight = weight;
        this.action = action;
        this.pool = pool;
        this.idStr = idStr;
        this.attr = attr;
        this.animation = animation;
    }
}
