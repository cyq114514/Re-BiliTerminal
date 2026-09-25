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

    //作者是否置顶了该动态（module_author.is_top）
    public boolean isTop;
    //icon_badge文本，如"仅自己可见"
    public String badgeText = "";
    //作者的动作文案（pub_action），如"参与了投票"，存在时优先于pubTime显示
    public String pubAction = "";
    //动态附加的投票卡（module_additional ADDITIONAL_TYPE_VOTE）
    public DynamicVote vote;

    /**当前是否仅自己可见（依据icon_badge推断，切换后本地更新）*/
    public boolean isOnlySelf() {
        return badgeText != null && badgeText.contains("仅自己");
    }

    public Dynamic() {
    }

}
