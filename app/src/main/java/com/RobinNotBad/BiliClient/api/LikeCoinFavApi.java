package com.RobinNotBad.BiliClient.api;

import com.RobinNotBad.BiliClient.model.ApiResponse;
import com.RobinNotBad.BiliClient.model.VideoInfo;
import com.RobinNotBad.BiliClient.util.GsonUtil;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.google.gson.annotations.SerializedName;

import org.json.JSONException;

import java.io.IOException;
import java.util.Objects;

public class LikeCoinFavApi {

    public static class RelationData {
        @SerializedName("attention") public boolean attention;
        @SerializedName("like") public boolean like;
        @SerializedName("dislik") public boolean dislik;
        @SerializedName("favorite") public boolean favorite;
        @SerializedName("coin") public int coin;
    }

    /**
     * csrf（bili_jct）：优先用实时 Cookie 里的值，退回本地快照。
     *
     * <p>直接用 {@code SharedPreferencesUtil.getString("csrf","")} 的隐患：Cookie 刷新器/切换账号
     * 之后这个快照可能已经过期甚至为空，而服务端只认 Cookie 里那个，于是点赞/投币一律返回
     * -111（csrf 校验失败）——界面看着"点了没反应"。
     * {@link NetWorkUtil#getLoginCsrf()} 就是为这个场景准备的，这里统一走它。
     */
    private static String csrf() {
        String value = NetWorkUtil.getLoginCsrf();
        return value == null ? "" : value;
    }

    public static int triple(long aid) throws IOException, JSONException {
        String per = "aid=" + aid + "&csrf=" + csrf();
        return GsonUtil.fromJson(Objects.requireNonNull(NetWorkUtil.post("https://api.bilibili.com/x/web-interface/archive/like/triple", per, NetWorkUtil.webHeaders).body()).string(), ApiResponse.class).code;
    }

    public static int like(long aid, int likeState) throws IOException, JSONException {
        String per = "aid=" + aid + "&like=" + likeState + "&csrf=" + csrf();
        return GsonUtil.fromJson(Objects.requireNonNull(NetWorkUtil.post("https://api.bilibili.com/x/web-interface/archive/like", per, NetWorkUtil.webHeaders).body()).string(), ApiResponse.class).code;
    }

    public static int coin(long aid, int multiply) throws IOException, JSONException {
        String per = "aid=" + aid + "&multiply=" + multiply + "&csrf=" + csrf();
        return GsonUtil.fromJson(Objects.requireNonNull(NetWorkUtil.post("https://api.bilibili.com/x/web-interface/coin/add", per, NetWorkUtil.webHeaders).body()).string(), ApiResponse.class).code;
    }

    public static int favorite(long aid, long fid) throws IOException, JSONException {
        String strMid = String.valueOf(SharedPreferencesUtil.getLong("mid", 0));
        String per = "rid=" + aid + "&type=2&add_media_ids=" + (fid + strMid.substring(strMid.length() - 2)) + "&del_media_ids=&csrf=" + csrf();
        return GsonUtil.fromJson(Objects.requireNonNull(NetWorkUtil.post("https://api.bilibili.com/medialist/gateway/coll/resource/deal", per, NetWorkUtil.webHeaders).body()).string(), ApiResponse.class).code;
    }

    public static ApiResponse getVideoStats(VideoInfo videoInfo) {
        ApiResponse apiResult = new ApiResponse();
        if (SharedPreferencesUtil.getLong("mid", 0) == 0) return apiResult;
        try {
            String json = NetWorkUtil.getJson("https://api.bilibili.com/x/web-interface/archive/relation?aid=" + videoInfo.aid).toString();
            ApiResponse<RelationData> resp = GsonUtil.fromJson(json, new com.google.gson.reflect.TypeToken<ApiResponse<RelationData>>(){}.getType());
            if (resp != null) { apiResult.code = resp.code; apiResult.message = resp.message; }
            if (resp != null && resp.data != null && videoInfo.stats != null) {
                videoInfo.stats.followed = resp.data.attention;
                videoInfo.stats.liked = resp.data.like;
                videoInfo.stats.disliked = resp.data.dislik;
                videoInfo.stats.favoured = resp.data.favorite;
                videoInfo.stats.coined = resp.data.coin;
            }
        } catch (Exception e) { MsgUtil.err(apiResult.message, e); }
        return apiResult;
    }
}
