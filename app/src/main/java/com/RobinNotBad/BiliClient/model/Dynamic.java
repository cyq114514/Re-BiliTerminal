package com.RobinNotBad.BiliClient.model;

import java.io.Serializable;

public class Dynamic implements Serializable {
    public final static String DYNAMIC_TYPE_UGC_SEASON = "DYNAMIC_TYPE_UGC_SEASON";
    public long dynamicId;
    public String type;
    public long comment_id;
    public int comment_type;

    public String title;
    public UserInfo userInfo;
    public CharSequence content;
    public String pubTime;

    public Stats stats;

    public String major_type;
    public Object major_object;
    public Dynamic dynamic_forward;
    public boolean canDelete;

    public Dynamic() {
    }

}
