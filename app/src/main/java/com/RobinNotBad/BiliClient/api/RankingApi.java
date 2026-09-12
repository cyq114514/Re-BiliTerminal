package com.RobinNotBad.BiliClient.api;

import com.RobinNotBad.BiliClient.model.ApiResponse;
import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.util.GsonUtil;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.StringUtil;
import com.google.gson.annotations.SerializedName;

import org.json.JSONException;

import java.io.IOException;
import java.util.List;

public class RankingApi {

    public static class RankingItem {
        @SerializedName("aid")
        public long aid;
        @SerializedName("bvid")
        public String bvid;
        @SerializedName("pic")
        public String pic;
        @SerializedName("title")
        public String title;
        @SerializedName("owner")
        public RecommendApi.Owner owner;
        @SerializedName("stat")
        public RecommendApi.Stat stat;
    }

    public static void getRanking(List<VideoCard> videoCardList, int rid, String type) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/web-interface/ranking/v2";
        url += new NetWorkUtil.FormData().setUrlParam(true)
                .put("rid", rid)
                .put("type", type)
                .put("web_location", "333.934");

        String json = NetWorkUtil.getJson(ConfInfoApi.signWBI(url)).toString();
        ApiResponse<List<RankingItem>> resp = GsonUtil.fromJson(json,
                new com.google.gson.reflect.TypeToken<ApiResponse<List<RankingItem>>>(){}.getType());
        if (resp == null || !resp.isSuccess() || resp.data == null) return;

        for (RankingItem card : resp.data) {
            if (card == null) continue;
            String upName = card.owner != null ? card.owner.name : "";
            int viewCount = card.stat != null ? card.stat.view : 0;
            videoCardList.add(new VideoCard(card.title, upName, StringUtil.toWan(viewCount) + "观看", card.pic, card.aid, card.bvid));
        }
    }
}
