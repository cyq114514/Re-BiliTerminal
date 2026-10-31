package com.RobinNotBad.BiliClient.api;

import com.RobinNotBad.BiliClient.model.ApiResponse;
import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.util.GsonUtil;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.StringUtil;
import com.google.gson.annotations.SerializedName;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;

import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.Response;

public class WatchLaterApi {

    /** 列表筛选档位：全部。对应网页端 {@code viewed=0}。 */
    public static final int VIEWED_ALL = 0;
    /** 列表筛选档位：未看完。服务端过滤（对照网页端/PiliPlus 的 {@code viewed=2}）。 */
    public static final int VIEWED_UNFINISHED = 2;

    /** {@code /toview/clear} 的 clean_type：清除「已看完」。另有 1=已失效、缺省=清空全部。 */
    public static final int CLEAN_TYPE_WATCHED = 2;

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
        @SerializedName("duration") public int duration; //总时长（秒），0 表示接口没给
    }

    /**
     * 取稍后再看完整列表（不筛选）。
     *
     * @deprecated 用 {@link #getWatchLaterList(int)} 显式传档位，避免调用点看不出筛没筛。
     */
    @Deprecated
    public static ArrayList<VideoCard> getWatchLaterList() throws IOException, JSONException {
        return getWatchLaterList(VIEWED_ALL);
    }

    /**
     * 取稍后再看列表。
     *
     * <p>{@code viewed} 是<b>服务端</b>筛选参数（all=0 / unfinished=2）——「未看完」不在本地重算，
     * 服务端返回的就是权威结果（它还知道本客户端拿不到的观看进度）。
     *
     * @param viewed {@link #VIEWED_ALL} 或 {@link #VIEWED_UNFINISHED}
     */
    public static ArrayList<VideoCard> getWatchLaterList(int viewed) throws IOException, JSONException {
        String json = NetWorkUtil.getJson(
                "https://api.bilibili.com/x/v2/history/toview/web?viewed=" + viewed).toString();
        ApiResponse<WatchLaterData> resp = GsonUtil.fromJson(json, new com.google.gson.reflect.TypeToken<ApiResponse<WatchLaterData>>(){}.getType());
        ArrayList<VideoCard> list = new ArrayList<>();
        if (resp == null || !resp.isSuccess() || resp.data == null || resp.data.list == null) return list;
        for (WatchLaterItem item : resp.data.list) {
            if (item == null) continue;
            String upName = item.owner != null ? item.owner.name : "";
            int viewCount = item.stat != null ? item.stat.view : 0;
            VideoCard card = new VideoCard(item.title, upName, StringUtil.toWan(viewCount) + "观看", item.pic, item.aid, item.bvid);
            //这两个字段只有稍后再看接口会给，用来区分「已看完 / 没看完」
            card.progress = item.progress;
            card.duration = item.duration;
            list.add(card);
        }
        return list;
    }

    /**
     * 判断一条稍后再看是否「没看完」。
     *
     * <p>语义是<b>排除已看完的，其余（含从未播放）都算未看完</b>：
     * <ul>
     *   <li>从未播放（progress &lt;= 0）→ 算未看完，否则「一直没点开过」的稿件会从列表里消失；</li>
     *   <li>总时长未知（duration &lt;= 0）→ 无法判断，一律算未看完（宁可多留不可错杀）；</li>
     *   <li>只有 progress &gt;= duration 且 duration &gt; 0 才算已看完。</li>
     * </ul>
     */
    public static boolean isUnfinished(long progress, long duration) {
        if (duration <= 0) return true;
        return progress < duration;
    }

    /**
     * 按「未看完」过滤。
     *
     * @param list           原始列表（可为 null）
     * @param onlyUnfinished true 只留没看完的；false 原样拷贝一份
     * @return 新列表，调用方可以安全地增删而不影响入参
     */
    public static ArrayList<VideoCard> filterUnfinished(java.util.List<VideoCard> list, boolean onlyUnfinished) {
        ArrayList<VideoCard> result = new ArrayList<>();
        if (list == null) return result;
        for (VideoCard card : list) {
            if (card == null) continue;
            if (!onlyUnfinished || isUnfinished(card.progress, card.duration)) result.add(card);
        }
        return result;
    }

    public static int delete(long aid) throws IOException, JSONException {
        String per = "aid=" + aid + "&csrf=" + NetWorkUtil.getLoginCsrf();
        return GsonUtil.fromJson(Objects.requireNonNull(NetWorkUtil.post("https://api.bilibili.com/x/v2/history/toview/del", per, NetWorkUtil.webHeaders).body()).string(), ApiResponse.class).code;
    }

    public static int add(long aid) throws IOException, JSONException {
        String per = "aid=" + aid + "&csrf=" + NetWorkUtil.getLoginCsrf();
        return GsonUtil.fromJson(Objects.requireNonNull(NetWorkUtil.post("https://api.bilibili.com/x/v2/history/toview/add", per, NetWorkUtil.webHeaders).body()).string(), ApiResponse.class).code;
    }

    /**
     * 清除所有「已看完」的稍后再看稿件（对应网页端「清除所有已看完」）。
     *
     * <p>接口契约：
     * <pre>
     * POST https://api.bilibili.com/x/v2/history/toview/clear
     * multipart/form-data : clean_type=2, csrf=&lt;当前 csrf&gt;
     * 响应 {"code":0,"message":"OK","ttl":1}
     * </pre>
     * clean_type：缺省=清空全部、1=已失效、2=已看完。
     *
     * @return 接口 code，0 表示成功；-101 未登录、-111 csrf 校验失败
     */
    public static int clearWatched() throws IOException, JSONException {
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("clean_type", String.valueOf(CLEAN_TYPE_WATCHED))
                .addFormDataPart("csrf", NetWorkUtil.getLoginCsrf())
                .build();

        Request.Builder builder = new Request.Builder()
                .url("https://api.bilibili.com/x/v2/history/toview/clear")
                .post(body);
        //复用全局 webHeaders（Cookie / Origin / Referer / UA），与其它 POST 一致
        for (int i = 0; i < NetWorkUtil.webHeaders.size(); i += 2) {
            builder.addHeader(NetWorkUtil.webHeaders.get(i), NetWorkUtil.webHeaders.get(i + 1));
        }

        try (Response response = NetWorkUtil.getOkHttpInstance().newCall(builder.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) return -1;
            String text = response.body().string();
            // 412 风控页 / 5xx 可能返回 HTML，直接 new JSONObject 会抛异常
            if (text.isEmpty() || text.charAt(0) != '{') return -1;
            return new JSONObject(text).optInt("code", -1);
        }
    }
}
