package com.RobinNotBad.BiliClient.api;

import com.RobinNotBad.BiliClient.model.ApiResponse;
import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.util.GsonUtil;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.RobinNotBad.BiliClient.util.StringUtil;
import com.google.gson.annotations.SerializedName;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;

public class WatchLaterApi {

    public static class WatchLaterData {
        @SerializedName("list")
        public java.util.List<WatchLaterItem> list;
    }

    public static class WatchLaterItem {
        @SerializedName("aid") public long aid;
        @SerializedName("bvid") public String bvid;
        @SerializedName("title") public String title;
        @SerializedName("pic") public String pic;
        @SerializedName("owner") public RecommendApi.Owner owner;
        @SerializedName("stat") public RecommendApi.Stat stat;
        @SerializedName("progress") public int progress; //观看进度（秒），0 表示未看过
    }

    public static ArrayList<VideoCard> getWatchLaterList() throws IOException, JSONException {
        String json = NetWorkUtil.getJson("https://api.bilibili.com/x/v2/history/toview/web").toString();
        ApiResponse<WatchLaterData> resp = GsonUtil.fromJson(json, new com.google.gson.reflect.TypeToken<ApiResponse<WatchLaterData>>(){}.getType());
        ArrayList<VideoCard> list = new ArrayList<>();
        if (resp == null || !resp.isSuccess() || resp.data == null || resp.data.list == null) return list;
        for (WatchLaterItem item : resp.data.list) {
            if (item == null) continue;
            String upName = item.owner != null ? item.owner.name : "";
            int viewCount = item.stat != null ? item.stat.view : 0;
            VideoCard card = new VideoCard(item.title, upName, StringUtil.toWan(viewCount) + "观看", item.pic, item.aid, item.bvid);
            card.progress = item.progress;
            list.add(card);
        }
        return list;
    }

    public static int delete(long aid) throws IOException, JSONException {
        String per = "aid=" + aid + "&csrf=" + SharedPreferencesUtil.getString("csrf", "");
        return GsonUtil.fromJson(Objects.requireNonNull(NetWorkUtil.post("https://api.bilibili.com/x/v2/history/toview/del", per, NetWorkUtil.webHeaders).body()).string(), ApiResponse.class).code;
    }

    public static int add(long aid) throws IOException, JSONException {
        String per = "aid=" + aid + "&csrf=" + SharedPreferencesUtil.getString("csrf", "");
        return GsonUtil.fromJson(Objects.requireNonNull(NetWorkUtil.post("https://api.bilibili.com/x/v2/history/toview/add", per, NetWorkUtil.webHeaders).body()).string(), ApiResponse.class).code;
    }
}
