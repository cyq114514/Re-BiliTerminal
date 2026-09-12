package com.RobinNotBad.BiliClient.model;

import java.util.List;

public class VipInfo {
    public boolean isShortVip;
    public boolean isFreightOpen;
    public int level;
    public long curExp;
    public long nextExp;
    public boolean isVip;
    public int isSeniorMember;
    public int format060102;
    public boolean isOverdueVip;
    public int vipStatus;
    public int vipType;
    public long keeptimeEnd;
    public long vipDueDate;
    public boolean vipIsAnnual;
    public boolean vipIsMonth;
    public boolean vipIsNewUser;
    public String bindPhone;
    public String taobaoAccount;
    public List<Privilege> privilegeList;

    public static class Privilege {
        public int type;
        public int state;
        public long expireTime;
        public int vipType;
        public int nextReceiveDays;
        public long periodEndUnix;
    }
}

