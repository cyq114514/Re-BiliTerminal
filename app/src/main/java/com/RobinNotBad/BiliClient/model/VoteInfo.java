package com.RobinNotBad.BiliClient.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**投票详情（/x/vote/vote_info），type 0=文字投票 1=图片投票*/
public class VoteInfo implements Serializable {
    public long voteId;
    public String title = "";
    public String desc = "";
    public long ctime;
    public long endTime;
    public int type;
    public int choiceCnt = 1;
    public long joinNum;
    public List<Option> options = new ArrayList<>();
    /**已投选项下标；空数组/缺失表示未投*/
    public int[] myVotes = new int[0];

    public boolean voted() { return myVotes != null && myVotes.length > 0; }

    public boolean ended() { return endTime > 0 && endTime * 1000L < System.currentTimeMillis(); }

    public boolean enabled() { return !voted() && !ended(); }

    public long totalCnt() {
        long total = 0;
        for (Option option : options) total += option.cnt;
        return total;
    }

    public static class Option implements Serializable {
        public int idx;
        public String desc = "";
        public long cnt;
        public String imgUrl = "";
    }

    /**
     * 解析 vote_info 接口的data（vote_info字段+同级my_votes），
     * 兼容do_vote返回的直接是vote_info对象的结构。
     */
    public static VoteInfo fromSeparatedJson(JSONObject data) {
        if (data == null) return null;
        JSONObject inner = data.optJSONObject("vote_info");
        VoteInfo voteInfo = fromJson(inner != null ? inner : data);
        if (voteInfo != null && inner != null) {
            JSONArray myVotes = data.optJSONArray("my_votes");
            if (myVotes != null) {
                voteInfo.myVotes = new int[myVotes.length()];
                for (int i = 0; i < myVotes.length(); i++) voteInfo.myVotes[i] = myVotes.optInt(i, 0);
            } else if (inner.has("my_votes")) {
                JSONArray innerMyVotes = inner.optJSONArray("my_votes");
                if (innerMyVotes != null) {
                    voteInfo.myVotes = new int[innerMyVotes.length()];
                    for (int i = 0; i < innerMyVotes.length(); i++) voteInfo.myVotes[i] = innerMyVotes.optInt(i, 0);
                }
            }
        }
        return voteInfo;
    }

    public static VoteInfo fromJson(JSONObject json) {
        if (json == null) return null;
        VoteInfo voteInfo = new VoteInfo();
        voteInfo.voteId = json.optLong("vote_id", 0);
        if (voteInfo.voteId == 0) return null;
        voteInfo.title = json.optString("title", "");
        voteInfo.desc = json.optString("desc", "");
        voteInfo.ctime = json.optLong("ctime", 0);
        voteInfo.endTime = json.optLong("end_time", 0);
        voteInfo.type = json.optInt("type", 0);
        voteInfo.choiceCnt = Math.max(1, json.optInt("choice_cnt", 1));
        voteInfo.joinNum = json.optLong("join_num", 0);
        JSONArray options = json.optJSONArray("options");
        if (options != null) {
            for (int i = 0; i < options.length(); i++) {
                JSONObject optionJson = options.optJSONObject(i);
                if (optionJson == null) continue;
                Option option = new Option();
                option.idx = optionJson.optInt("opt_idx", i);
                option.desc = optionJson.optString("opt_desc", "");
                option.cnt = optionJson.optLong("cnt", 0);
                option.imgUrl = optionJson.optString("img_url", "");
                voteInfo.options.add(option);
            }
        }
        JSONArray myVotes = json.optJSONArray("my_votes");
        if (myVotes != null) {
            voteInfo.myVotes = new int[myVotes.length()];
            for (int i = 0; i < myVotes.length(); i++) voteInfo.myVotes[i] = myVotes.optInt(i, 0);
        }
        return voteInfo;
    }
}
