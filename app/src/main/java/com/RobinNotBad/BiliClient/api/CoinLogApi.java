package com.RobinNotBad.BiliClient.api;

import com.RobinNotBad.BiliClient.model.ApiResponse;
import com.RobinNotBad.BiliClient.util.GsonUtil;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.google.gson.annotations.SerializedName;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class CoinLogApi {
    public static class CoinLogData {
        @SerializedName("list") public List<CoinLogItem> list;
    }
    public static class CoinLogItem {
        @SerializedName("time") public String time;
        @SerializedName("delta") public int delta;
        @SerializedName("reason") public String reason;
    }

    public static List<com.RobinNotBad.BiliClient.model.CoinLog> getCoinLog() throws IOException, JSONException {
        String json = NetWorkUtil.getJson("https://api.bilibili.com/x/member/web/coin/log").toString();
        ApiResponse<CoinLogData> resp = GsonUtil.fromJson(json, new com.google.gson.reflect.TypeToken<ApiResponse<CoinLogData>>(){}.getType());
        List<com.RobinNotBad.BiliClient.model.CoinLog> logs = new ArrayList<>();
        if (resp == null || !resp.isSuccess() || resp.data == null || resp.data.list == null) return logs;
        for (CoinLogItem item : resp.data.list) {
            if (item != null) logs.add(new com.RobinNotBad.BiliClient.model.CoinLog(item.time, item.delta, item.reason));
        }
        return logs;
    }
}
