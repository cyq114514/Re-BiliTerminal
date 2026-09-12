package com.RobinNotBad.BiliClient.api;

import com.RobinNotBad.BiliClient.model.ApiResult;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
创作中心
 */

public class CreativeCenterApi {
    public static JSONObject getVideoStat() throws IOException, JSONException {
        String url = "https://member.bilibili.com/x/web/index/stat";
        JSONObject result = NetWorkUtil.getJson(url);
        return result.optJSONObject("data");
    }

    public static ApiResult getBeUPTime() throws IOException, JSONException {
        String url = "https://member.bilibili.com/x/web/index/scrolls";
        JSONObject response = NetWorkUtil.getJson(url);

        ApiResult result = new ApiResult(response);
        try {
            JSONObject data = response.getJSONObject("data");
            JSONArray scrollsArray = data.getJSONArray("scrolls");
            JSONObject scroll = scrollsArray.getJSONObject(0);
            String name = scroll.optString("name");
            result.result = String.valueOf(extractNumber(name));
        } catch (Exception e){
            e.printStackTrace();
            result.result = "获取失败";
        }
        return result;
    }

    private static int extractNumber(String str) {
        if (str == null || str.isEmpty()) {
            return 0;
        }

        Pattern pattern = Pattern.compile("-?\\d+");
        Matcher matcher = pattern.matcher(str);

        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group());
            } catch (NumberFormatException ex) {
                return 0;
            }
        }
        return 0;
    }
}
