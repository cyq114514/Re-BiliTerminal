package com.RobinNotBad.BiliClient.model;

/**
 * 充电用户数据模型
 */
public class ElectricUser {
    public String uname;        // 充电用户昵称
    public String avatar;       // 充电用户头像url
    public long mid;            // 充电对象mid
    public long pay_mid;        // 充电用户mid
    public int rank;            // 充电用户排名
    public int trend_type;      // 0 作用尚不明确
    public String message;      // 充电留言
    public int msg_hidden;      // 0 作用尚不明确
    public VipInfo vip_info;    // 充电用户会员信息

    public ElectricUser() {
    }

    public ElectricUser(String uname, String avatar, long mid, long pay_mid, int rank, String message) {
        this.uname = uname;
        this.avatar = avatar;
        this.mid = mid;
        this.pay_mid = pay_mid;
        this.rank = rank;
        this.message = message;
    }

    /**
     * 充电用户会员信息
     */
    public static class VipInfo {
        public long vipDueMsec;     // 大会员过期时间
        public int vipStatus;       // 大会员状态
        public int vipType;         // 大会员类型 0:无 1:月大会员 2:年度及以上大会员

        public VipInfo() {
        }

        public VipInfo(long vipDueMsec, int vipStatus, int vipType) {
            this.vipDueMsec = vipDueMsec;
            this.vipStatus = vipStatus;
            this.vipType = vipType;
        }
    }
}

