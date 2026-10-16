package com.RobinNotBad.BiliClient.api;

import android.text.TextUtils;
import android.util.Log;

import com.RobinNotBad.BiliClient.model.ApiResponse;
import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.util.GsonUtil;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.StringUtil;
import com.google.gson.annotations.SerializedName;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import org.json.JSONArray;
import org.json.JSONObject;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class RecommendApi {
    private static final long UNIQ_ID = (long) (new Random().nextDouble() * (1500000000000L - 1300000000000L));

    public static class RecommendResponse {
        @SerializedName("item")
        public List<Item> item;
    }

    public static class PopularResponse {
        @SerializedName("list")
        public List<Item> list;
    }

    public static class Item {
        @SerializedName("bvid")
        public String bvid;
        @SerializedName("pic")
        public String pic;
        @SerializedName("title")
        public String title;
        @SerializedName("owner")
        public Owner owner;
        @SerializedName("stat")
        public Stat stat;
    }

    public static class Owner {
        @SerializedName("name")
        public String name;
    }

    public static class Stat {
        @SerializedName("view")
        public int view;
    }

    /**
     * 设置项：首页是否使用 app 端推荐。
     * 默认关闭＝保持与修复前完全一致的 web 端行为（PiliPlus 的「首页使用app端推荐」也是这样一个开关）。
     */
    public static final String KEY_APP_RECOMMEND = "home_app_recommend";

    /** app 端推荐的分页游标：把上一页最后一条的 idx 回传即可继续翻页 */
    private static long appRecommendIdx = 0;

    public static void getRecommend(List<VideoCard> videoCardList) throws IOException, JSONException {
        if (SharedPreferencesUtil.getBoolean(KEY_APP_RECOMMEND, false)) {
            getRecommendApp(videoCardList);
            return;
        }
        String url = "https://api.bilibili.com/x/web-interface/wbi/index/top/feed/rcmd";
        url += new NetWorkUtil.FormData().setUrlParam(true)
                .put("web_location", 1430650)
                .put("feed_version", "V8")
                .put("homepage_ver", 1)
                .put("uniq_id", UNIQ_ID)
                .put("screen", "1100-2056");

        String json = NetWorkUtil.getJson(ConfInfoApi.signWBI(url)).toString();
        ApiResponse<RecommendResponse> resp = GsonUtil.fromJson(json,
                new com.google.gson.reflect.TypeToken<ApiResponse<RecommendResponse>>(){}.getType());
        if (resp == null || !resp.isSuccess() || resp.data == null || resp.data.item == null) return;

        for (Item card : resp.data.item) {
            if (card == null || TextUtils.isEmpty(card.bvid)) {
                Log.d("BiliClient", "RecommendApi getRecommend: isAd");
                continue;
            }
            String upName = card.owner != null ? card.owner.name : "";
            int viewCount = card.stat != null ? card.stat.view : 0;
            videoCardList.add(new VideoCard(card.title, upName, StringUtil.toWan(viewCount) + "观看", card.pic, 0, card.bvid));
        }
    }

    /**
     * app 端推荐（{@code https://app.bilibili.com/x/v2/feed/index}）。
     *
     * <p>实测（2026-10-17，游客态）只需 build/mobi_app/platform/idx 几个参数即返回 {@code code=0}，
     * <b>既不需要 AppSign 签名，也不需要 access_key</b>，因此这里不引入第二套鉴权体系，
     * 直接沿用现有的 Cookie 请求头。
     *
     * <p>返回结构与 web 端完全不同，用 org.json 就地解析：条目在 data.items[]，
     * 身份字段是 args.aid / player_args.aid（不是 web 端的 bvid），
     * 所以不能沿用上面"bvid 为空即跳过"的判据，否则会把所有正常条目都当广告滤掉。
     */
    private static void getRecommendApp(List<VideoCard> videoCardList) throws IOException, JSONException {
        String url = "https://app.bilibili.com/x/v2/feed/index"
                + "?build=1&mobi_app=android&platform=android"
                + "&idx=" + appRecommendIdx + "&login_event=0&network=wifi";
        JSONObject json = NetWorkUtil.getJson(url, NetWorkUtil.webHeaders);
        int code = json.optInt("code", -1);
        if (code != 0) {
            //不打印完整响应体（可能含推荐上下文），只记状态码
            Logu.e("recommend", "app 端推荐返回 code=" + code);
            return;
        }
        JSONObject data = json.optJSONObject("data");
        JSONArray items = data != null ? data.optJSONArray("items") : null;
        if (items == null) return;

        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;

            String title = item.optString("title", "");
            if (TextUtils.isEmpty(title)) continue;   //广告位与异常条目没有标题

            JSONObject args = item.optJSONObject("args");
            JSONObject playerArgs = item.optJSONObject("player_args");
            long aid = args != null ? args.optLong("aid", 0) : 0;
            if (aid == 0 && playerArgs != null) aid = playerArgs.optLong("aid", 0);
            String bvid = item.optString("bvid", "");
            if (TextUtils.isEmpty(bvid) && args != null) bvid = args.optString("bvid", "");
            if (aid == 0 && TextUtils.isEmpty(bvid)) continue;

            String upName = args != null ? args.optString("up_name", "") : "";
            String viewText = item.optString("cover_left_text_1", "");
            if (TextUtils.isEmpty(viewText)) viewText = "推荐";
            videoCardList.add(new VideoCard(title, upName, viewText,
                    item.optString("cover", ""), aid, bvid));

            //翻页游标：取最后一条的 idx，下一页从它继续
            long idx = item.optLong("idx", 0);
            if (idx > 0) appRecommendIdx = idx - 1;
        }
    }

    public static ArrayList<VideoCard> getRelated(long aid) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/web-interface/archive/related?aid=" + aid;
        String json = NetWorkUtil.getJson(url).toString();
        ApiResponse<List<Item>> resp = GsonUtil.fromJson(json,
                new com.google.gson.reflect.TypeToken<ApiResponse<List<Item>>>(){}.getType());
        ArrayList<VideoCard> videoList = new ArrayList<>();
        if (resp == null || !resp.isSuccess() || resp.data == null) return videoList;

        for (Item card : resp.data) {
            if (card == null) continue;
            String upName = card.owner != null ? card.owner.name : "";
            int viewCount = card.stat != null ? card.stat.view : 0;
            videoList.add(new VideoCard(card.title, upName, StringUtil.toWan(viewCount) + "观看", card.pic, 0, card.bvid));
        }
        return videoList;
    }

    public static boolean getPopular(List<VideoCard> videoCardList, int page) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/web-interface/popular?pn=" + page + "&ps=10";
        String json = NetWorkUtil.getJson(url).toString();
        ApiResponse<PopularResponse> resp = GsonUtil.fromJson(json,
                new com.google.gson.reflect.TypeToken<ApiResponse<PopularResponse>>(){}.getType());
        if (resp == null || !resp.isSuccess() || resp.data == null || resp.data.list == null) return false;

        for (Item card : resp.data.list) {
            if (card == null) continue;
            String upName = card.owner != null ? card.owner.name : "";
            int viewCount = card.stat != null ? card.stat.view : 0;
            videoCardList.add(new VideoCard(card.title, upName, StringUtil.toWan(viewCount) + "观看", card.pic, 0, card.bvid));
        }
        return true;
    }

    public static boolean getPrecious(List<VideoCard> videoCardList, int page) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/web-interface/popular/precious?page=" + page + "&page_size=10";
        String json = NetWorkUtil.getJson(url).toString();
        ApiResponse<PopularResponse> resp = GsonUtil.fromJson(json,
                new com.google.gson.reflect.TypeToken<ApiResponse<PopularResponse>>(){}.getType());
        if (resp == null || !resp.isSuccess() || resp.data == null || resp.data.list == null) return false;

        for (Item card : resp.data.list) {
            if (card == null) continue;
            String upName = card.owner != null ? card.owner.name : "";
            int viewCount = card.stat != null ? card.stat.view : 0;
            videoCardList.add(new VideoCard(card.title, upName, StringUtil.toWan(viewCount) + "观看", card.pic, 0, card.bvid));
        }
        return true;
    }
}
