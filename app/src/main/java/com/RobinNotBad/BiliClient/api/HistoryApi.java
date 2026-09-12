package com.RobinNotBad.BiliClient.api;

import com.RobinNotBad.BiliClient.model.ApiResponse;
import com.RobinNotBad.BiliClient.model.ApiResult;
import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.util.GsonUtil;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.RobinNotBad.BiliClient.util.StringUtil;
import com.google.gson.annotations.SerializedName;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

import okhttp3.Response;

public class HistoryApi {

    public static class HistoryData {
        @SerializedName("list")
        public List<HistoryItem> list;
        @SerializedName("cursor")
        public CursorData cursor;
    }

    public static class HistoryItem {
        @SerializedName("title")
        public String title;
        @SerializedName("cover")
        public String cover;
        @SerializedName("author_name")
        public String author_name;
        @SerializedName("progress")
        public int progress;
        @SerializedName("business")
        public String business;
        @SerializedName("long_title")
        public String long_title;
        @SerializedName("history")
        public HistoryRef history;
    }

    public static class HistoryRef {
        @SerializedName("oid")
        public long oid;
        @SerializedName("bvid")
        public String bvid;
        @SerializedName("epid")
        public long epid;
        @SerializedName("business")
        public String business;
    }

    //历史记录业务类型
    public static final String BUSINESS_ARCHIVE = "archive";
    public static final String BUSINESS_PGC = "pgc";

    //"定位上次观看剧集"最多翻页数：命中通常在首页，翻太多页反而拖慢详情页
    private static final int LOCATE_MAX_PAGES = 5;

    public static class CursorData {
        @SerializedName("business")
        public String business;
        @SerializedName("max")
        public long max;
        @SerializedName("view_at")
        public long view_at;
    }

    /**
     * 上报投稿视频(archive)的观看进度。
     * 番剧不要用这个，见 {@link #reportHistoryPgc}。
     */
    public static void reportHistory(long aid, long cid, long progress) throws IOException {
        if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.PRIVACY_MODE, false)) {
            return;
        }
        String url = "https://api.bilibili.com/x/v2/history/report";
        String per = "aid=" + aid + "&cid=" + cid
                + "&progress=" + (progress >= 0 ? progress : "")
                + "&platform=pc"
                + "&csrf=" + SharedPreferencesUtil.getString(SharedPreferencesUtil.csrf, "");
        Response response = NetWorkUtil.post(url, per, NetWorkUtil.webHeaders);
        logReportResult("reportHistory", response, "aid=" + aid + " cid=" + cid + " progress=" + progress);
    }

    //番剧(PGC)的观看进度必须走心跳接口：x/v2/history/report 只有 aid/cid 维度，
    //没有 epid/sid/type/sub_type，番剧拿它上报不会被记成番剧记录——
    //表现就是"在终端里看的番剧，观看记录与进度都不更新，续播和详情页定位都拿不到数据"。
    private static final String HEARTBEAT_URL = "https://api.bilibili.com/x/click-interface/web/heartbeat";
    //type：3 投稿视频 / 4 剧集 / 10 课程
    private static final int HEARTBEAT_TYPE_SEASON = 4;
    //sub_type 取值与 season_type 一致；不在集合内就不发该字段，交给服务端按 epid/sid 判定
    private static final int[] HEARTBEAT_SUB_TYPES = {1, 2, 3, 4, 5, 7};

    /**
     * 上报番剧剧集的观看进度。
     *
     * @param epid       剧集 epid（即 {@link com.RobinNotBad.BiliClient.model.Bangumi.Episode#id}）
     * @param seasonId   番剧 season_id
     * @param seasonType 剧集副类型（{@link com.RobinNotBad.BiliClient.model.Bangumi.Info#type}）
     * @param progress   已观看秒数
     */
    public static void reportHistoryPgc(long aid, long cid, long epid, long seasonId, int seasonType, long progress) throws IOException {
        if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.PRIVACY_MODE, false)) {
            return;
        }
        //progress<=0 的上报零信息量且有害：服务端对本季"最近观看"按季粒度维护(kid=ssid)，
        //报 0 会把本季记录覆盖成该集+0 进度，详情页"定位上次观看分集"会因此失效
        if (progress <= 0) {
            Logu.d("history-report", "跳过 0 进度上报 epid=" + epid);
            return;
        }
        long nowSec = System.currentTimeMillis() / 1000;
        NetWorkUtil.FormData form = new NetWorkUtil.FormData()
                .put("aid", aid)
                .put("cid", cid)
                .put("epid", epid)
                .put("sid", seasonId)
                .put("mid", SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0))
                .put("type", HEARTBEAT_TYPE_SEASON)
                .put("played_time", progress)
                //接口文档明确说明这几个"持续时间"字段算不准时可以都填同一个值
                .put("realtime", progress)
                .put("real_played_time", progress)
                .put("last_play_progress_time", progress)
                .put("max_play_progress_time", progress)
                //start_ts 是"开始播放时刻"：已知看了 progress 秒，倒推大致起点，比直接填当前时间更接近语义
                .put("start_ts", nowSec - progress)
                .put("play_type", 0)   //0 播放中
                .put("dt", 2)
                .put("outer", 0)
                .put("csrf", SharedPreferencesUtil.getString(SharedPreferencesUtil.csrf, ""));
        if (isKnownSeasonType(seasonType)) form.put("sub_type", seasonType);

        Response response = NetWorkUtil.post(HEARTBEAT_URL, form.toString(), NetWorkUtil.webHeaders);
        logReportResult("reportHistoryPgc", response,
                "aid=" + aid + " cid=" + cid + " epid=" + epid + " sid=" + seasonId
                        + " sub_type=" + seasonType + " progress=" + progress);
    }

    private static boolean isKnownSeasonType(int seasonType) {
        for (int t : HEARTBEAT_SUB_TYPES) {
            if (t == seasonType) return true;
        }
        return false;
    }

    /**
     * 历史/进度上报是"静默失败"成本最高的调用之一，返回码必须落日志，
     * 否则 -101(未登录)/-111(csrf失效)/-400(请求错误) 之类的失败无从排查。
     * 用 peekBody 读返回体不会消费流，调用方仍可再次读取。
     */
    private static void logReportResult(String tag, Response response, String detail) throws IOException {
        try {
            if (response == null || response.body() == null) {
                Logu.e(tag, "无返回体 " + detail);
                return;
            }
            String body = response.body().string();
            Logu.d(tag, body);
            int code = new JSONObject(body).optInt("code", -1);
            if (code != 0) Logu.e(tag, "上报失败 code=" + code + " " + detail);
        } catch (JSONException e) {
            Logu.e(tag, "上报返回解析失败 " + detail);
        }
    }

    public static ApiResult getHistory(ApiResult lastResult, List<VideoCard> videoList) throws IOException, JSONException {
        //type=archive 只会返回投稿视频，番剧(business=pgc)必须用 type=all 才会出现在 list 中
        String url = "https://api.bilibili.com/x/web-interface/history/cursor?type=all&view_at=" + lastResult.timestamp + "&business=" + lastResult.business + "&max=" + lastResult.offset;
        String json = NetWorkUtil.getJson(url).toString();
        ApiResponse<HistoryData> resp = GsonUtil.fromJson(json,
                new com.google.gson.reflect.TypeToken<ApiResponse<HistoryData>>(){}.getType());

        ApiResult apiResult = new ApiResult();
        if (resp != null) {
            apiResult.code = resp.code;
            apiResult.message = resp.message;
        }

        if (resp == null || !resp.isSuccess() || resp.data == null) return apiResult;

        if (resp.data.list != null) {
            for (HistoryItem item : resp.data.list) {
                if (item == null) continue;

                String business = item.history != null && item.history.business != null
                        ? item.history.business
                        : (item.business != null ? item.business : BUSINESS_ARCHIVE);
                //直播/专栏等类型暂不在历史页展示，保持与使用 type=archive 时一致的可见范围
                if (!BUSINESS_ARCHIVE.equals(business) && !BUSINESS_PGC.equals(business)) continue;

                String viewStr = item.progress == 0 ? "还没看过" : "看到" + StringUtil.toTime(item.progress);
                long aid = item.history != null ? item.history.oid : 0;
                String bvid = item.history != null ? item.history.bvid : "";
                String title = item.title;
                if (BUSINESS_PGC.equals(business) && item.long_title != null && !item.long_title.isEmpty())
                    title = item.title + " " + item.long_title;

                VideoCard card = new VideoCard(title, item.author_name, viewStr, item.cover, aid, bvid);
                card.progress = item.progress;
                if (BUSINESS_PGC.equals(business)) {
                    card.type = "media_bangumi";
                    card.epid = item.history != null ? item.history.epid : 0;
                }
                videoList.add(card);
            }
            if (resp.data.list.isEmpty()) apiResult.isBottom = true;
        }

        if (resp.data.cursor != null) {
            apiResult.business = resp.data.cursor.business != null ? resp.data.cursor.business : "";
            apiResult.offset = resp.data.cursor.max;
            apiResult.timestamp = resp.data.cursor.view_at;
        }

        return apiResult;
    }

    /**
     * 在观看记录里查找"属于给定剧集集合"的最近一条记录，供番剧详情页自动定位上次观看的那一集。
     *
     * 为什么走观看记录而不是逐集查进度：历史列表一次请求就能拿到本季各集的 epid，
     * 而 {@link PlayerApi#getLastPlayProgress(long, long)} 是按 aid+cid 单集查询的，逐集探测一季要发几十个请求。
     * 历史列表不返回 cid，因此只能用 epid 匹配（epid 即 {@link com.RobinNotBad.BiliClient.model.Bangumi.Episode#id}）。
     *
     * 另：观看记录里可能出现 progress=0 的本季条目（历史版本缺陷或"打开即关"产生的记录），
     * 因此"最近一条"未必是真正看过的那一集，这里优先返回最近一条进度非 0 的记录。
     *
     * @param epIds 本季全部剧集的 epid 集合
     * @return 命中的 epid；未登录、无记录或翻页耗尽时返回 0
     */
    public static long findLastWatchedEpid(Collection<Long> epIds) {
        if (epIds == null || epIds.isEmpty()) return 0;
        //未登录时没有观看记录，直接跳过，避免发无谓请求
        if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) == 0) return 0;

        long viewAt = 0, max = 0;
        String business = "";
        long firstMatch = 0;              //最近一条命中（progress 可能为 0）
        long firstMatchWithProgress = 0;  //最近一条"确实看过"的命中

        try {
            for (int page = 1; page <= LOCATE_MAX_PAGES; page++) {
                String url = "https://api.bilibili.com/x/web-interface/history/cursor?type=all&view_at=" + viewAt
                        + "&business=" + business + "&max=" + max;
                String json = NetWorkUtil.getJson(url).toString();
                ApiResponse<HistoryData> resp = GsonUtil.fromJson(json,
                        new com.google.gson.reflect.TypeToken<ApiResponse<HistoryData>>(){}.getType());
                if (resp == null || !resp.isSuccess() || resp.data == null || resp.data.list == null) break;

                for (HistoryItem item : resp.data.list) {
                    if (item == null || item.history == null) continue;
                    String itemBusiness = item.history.business != null ? item.history.business : item.business;
                    if (!BUSINESS_PGC.equals(itemBusiness)) continue;
                    long epid = item.history.epid;
                    if (epid == 0 || !epIds.contains(epid)) continue;

                    if (firstMatch == 0) firstMatch = epid;
                    //progress=-1 是"已看完"（接口文档），也算确实看过；0 才是"没看过/被 0 进度上报污染过"
                    if (item.progress != 0) {
                        firstMatchWithProgress = epid;
                        break;
                    }
                }
                if (firstMatchWithProgress != 0) break;

                if (resp.data.list.isEmpty() || resp.data.cursor == null) break;
                long nextViewAt = resp.data.cursor.view_at;
                long nextMax = resp.data.cursor.max;
                String nextBusiness = resp.data.cursor.business != null ? resp.data.cursor.business : "";
                //游标没有前进说明已经没有更多数据，必须跳出，否则会无限翻页
                if (nextViewAt == viewAt && nextMax == max && nextBusiness.equals(business)) break;
                viewAt = nextViewAt;
                max = nextMax;
                business = nextBusiness;
            }
        } catch (Exception e) {
            Logu.e("history-locate", "查询观看记录失败: " + e.getMessage());
        }

        long result = firstMatchWithProgress != 0 ? firstMatchWithProgress : firstMatch;
        Logu.d("history-locate", "定位结果 epid=" + result + "（有进度命中=" + firstMatchWithProgress + "）");
        return result;
    }
}
