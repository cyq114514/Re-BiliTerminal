package com.RobinNotBad.BiliClient.model;

import org.json.JSONObject;

/**
 * 私信会话模型（参照 PiliPlus session_ss/data.dart）
 */
public class PrivateMsgSession {
    public long talkerUid = 0;
    public int unread = 0;
    public int contentType = 0;
    public JSONObject content;
    // 从 account_info 解析的用户名和头像（PiliPlus 风格：直接从 API 取而不二次请求）
    public String talkerName = "";
    public String talkerFace = "";
    public long maxSeqno = 0;
    public long ackSeqno = 0;
    public long lastMsgTimestamp = 0;
    //服务端置顶时间戳（微秒级），0 表示未置顶；解析自 session 的 top_ts 字段
    public long topTs = 0;

    public PrivateMsgSession() {
    }

    public boolean isTop() {
        return topTs > 0;
    }
}
