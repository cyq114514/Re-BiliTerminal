package com.RobinNotBad.BiliClient.model;

import java.io.Serializable;

/**动态附加的投票卡片信息（module_additional ADDITIONAL_TYPE_VOTE）*/
public class DynamicVote implements Serializable {
    public long voteId;
    public String title = "";
    public long joinNum;

    public DynamicVote() {
    }

    public DynamicVote(long voteId, String title, long joinNum) {
        this.voteId = voteId;
        this.title = title;
        this.joinNum = joinNum;
    }
}
