package com.RobinNotBad.BiliClient.api;

import com.RobinNotBad.BiliClient.model.ApiResponse;
import com.RobinNotBad.BiliClient.model.LoginRecord;
import com.RobinNotBad.BiliClient.util.GsonUtil;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.google.gson.annotations.SerializedName;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class LoginRecordApi {
    public static class LoginRecordData {
        @SerializedName("mid") public long mid;
        @SerializedName("device_name") public String device_name;
        @SerializedName("login_type") public String login_type;
        @SerializedName("login_time") public String login_time;
        @SerializedName("location") public String location;
        @SerializedName("ip") public String ip;
    }

    public static List<LoginRecord> getLoginRecord(long mid, String buvid) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/safecenter/login_notice?mid=" + mid;
        if (buvid != null && !buvid.isEmpty()) url += "&buvid=" + buvid;
        String json = NetWorkUtil.getJson(url).toString();
        ApiResponse<LoginRecordData> resp = GsonUtil.fromJson(json, new com.google.gson.reflect.TypeToken<ApiResponse<LoginRecordData>>(){}.getType());
        List<LoginRecord> records = new ArrayList<>();
        if (resp == null || !resp.isSuccess() || resp.data == null) return records;
        records.add(new LoginRecord(resp.data.mid, resp.data.device_name, resp.data.login_type, resp.data.login_time, resp.data.location, resp.data.ip));
        return records;
    }
}
