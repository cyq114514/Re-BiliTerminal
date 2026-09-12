package com.RobinNotBad.BiliClient.api;

import com.RobinNotBad.BiliClient.model.ApiResponse;
import com.RobinNotBad.BiliClient.model.ExpLog;
import com.RobinNotBad.BiliClient.util.GsonUtil;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.google.gson.annotations.SerializedName;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ExpLogApi {
    public static class ExpLogData {
        @SerializedName("list") public List<ExpLogItem> list;
    }
    public static class ExpLogItem {
        @SerializedName("delta") public int delta;
        @SerializedName("time") public String time;
        @SerializedName("reason") public String reason;
    }

    public static List<ExpLog> getExpLog() throws IOException, JSONException {
        String json = NetWorkUtil.getJson("https://api.bilibili.com/x/member/web/exp/log?jsonp=jsonp&web_location=333.33").toString();
        ApiResponse<ExpLogData> resp = GsonUtil.fromJson(json, new com.google.gson.reflect.TypeToken<ApiResponse<ExpLogData>>(){}.getType());
        List<ExpLog> logs = new ArrayList<>();
        if (resp == null || !resp.isSuccess() || resp.data == null || resp.data.list == null) return logs;
        for (ExpLogItem item : resp.data.list) {
            if (item != null) logs.add(new ExpLog(item.delta, item.time, item.reason));
        }
        return logs;
    }
}
