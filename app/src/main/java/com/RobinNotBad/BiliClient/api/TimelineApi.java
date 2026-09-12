package com.RobinNotBad.BiliClient.api;

import com.RobinNotBad.BiliClient.model.Timeline;
import com.RobinNotBad.BiliClient.util.GsonUtil;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class TimelineApi {
    public static List<Timeline.DayInfo> getTimeline(String types, int before, int after) throws JSONException, IOException {
        String url = "https://api.bilibili.com/pgc/web/timeline?types=" + types + "&before=" + before + "&after=" + after;

        JSONObject all = NetWorkUtil.getJson(url);
        if (all.optInt("code", -1) != 0) {
            throw new JSONException(all.optString("message", "请求失败"));
        }

        JSONArray result = all.optJSONArray("result");
        List<Timeline.DayInfo> dayInfoList = new ArrayList<>();

        if (result != null) {
            for (int i = 0; i < result.length(); i++) {
                JSONObject dayObj = result.optJSONObject(i);
                if (dayObj == null) continue;

                Timeline.DayInfo dayInfo = GsonUtil.fromJson(dayObj.toString(), Timeline.DayInfo.class);
                if (dayInfo != null) {
                    if (dayInfo.episodes == null) dayInfo.episodes = new ArrayList<>();
                    dayInfoList.add(dayInfo);
                }
            }
        }

        return dayInfoList;
    }
}
