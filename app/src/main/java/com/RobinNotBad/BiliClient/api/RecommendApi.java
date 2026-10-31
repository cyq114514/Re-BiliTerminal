package com.RobinNotBad.BiliClient.api;

import android.text.TextUtils;
import android.util.Log;

import com.RobinNotBad.BiliClient.model.ApiResponse;
import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.util.AppAuthStore;
import com.RobinNotBad.BiliClient.util.AppBuvid;
import com.RobinNotBad.BiliClient.util.AppSign;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

public class RecommendApi {
    private static final long UNIQ_ID = (long) (new Random().nextDouble() * (1500000000000L - 1300000000000L));

    /**
     * App 端客户端身份三件套：{@code build}、UA 里的 {@code build/innerVer}、{@code statistics}
     * 里的 {@code version} 必须互相自洽，改一个就要改另外两个，否则等于"自称两个不同版本的客户端"。
     * 取值与 PiliPlus {@code Constants.userAgentApp} / {@code statisticsApp} 同源（Android 8.43.0）。
     */
    private static final String APP_BUILD = "8430300";
    private static final String APP_VERSION = "8.43.0";
    private static final String APP_UA = "Mozilla/5.0 BiliDroid/" + APP_VERSION
            + " (bbcallen@gmail.com) os/android model/android mobi_app/android build/" + APP_BUILD
            + " channel/master innerVer/" + APP_BUILD + " osVer/15 network/2";
    private static final String APP_STATISTICS =
            "{\"appId\":1,\"platform\":3,\"version\":\"" + APP_VERSION + "\",\"abtest\":\"\"}";

    /** 设置项：App 端推荐是否附带客户端签名（appkey/ts/sign）。默认开，出问题可一键关掉回退。 */
    public static final String KEY_APP_RECOMMEND_SIGN = "home_app_recommend_sign";
    /** 设置项：App 端推荐是否附带 access_key。默认关——未验证对推荐是否有收益，属于账号凭证，需手动开启。 */
    public static final String KEY_APP_RECOMMEND_ACCESS_KEY = "home_app_recommend_access_key";
    /** 设置项：按 UP 主 mid 屏蔽推荐，分隔符为逗号/空格/换行 */
    public static final String KEY_BLOCK_MIDS = "rcmd_block_mids";
    /** 设置项：按标题关键词屏蔽推荐，分隔符为逗号/空格/换行 */
    public static final String KEY_BLOCK_WORDS = "rcmd_block_words";

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
        /** web 端的稿件 id 就存在 {@code id} 字段里，不是 aid，也不与 bvid 同名 */
        @SerializedName("id")
        public long aid;
        @SerializedName("cid")
        public long cid;
        @SerializedName("duration")
        public int duration;
        @SerializedName("goto")
        public String gotoType;
        @SerializedName("uri")
        public String uri;
        @SerializedName("is_followed")
        public int isFollowed;
        @SerializedName("rcmd_reason")
        public RcmdReason rcmdReason;
        @SerializedName("pic")
        public String pic;
        @SerializedName("title")
        public String title;
        @SerializedName("owner")
        public Owner owner;
        @SerializedName("stat")
        public Stat stat;
    }

    public static class RcmdReason {
        @SerializedName("content")
        public String content;
    }

    public static class Owner {
        @SerializedName("name")
        public String name;
        @SerializedName("mid")
        public long mid;
    }

    public static class Stat {
        @SerializedName("view")
        public int view;
        @SerializedName("like")
        public int like;
    }

    /**
     * 设置项：首页是否使用 app 端推荐。
     * 默认开启（app 端与 web 端返回的是两套内容，app 端的推荐相关性更好）。
     */
    public static final String KEY_APP_RECOMMEND = "home_app_recommend";

    /** app 端推荐的分页游标：把上一页最后一条的 idx 回传即可继续翻页 */
    private static long appRecommendIdx = 0;

    /**
     * 取一页推荐。
     *
     * @param page 第几页（从 1 开始）。web 端要用它做 fresh_idx/brush 让服务端"换一批"，
     *             app 端要在第 1 页把游标复位（PiliPlus 的 onRefresh 同样把 page 归零）。
     * @return 本页拿到的条目数（0 表示没数据；调用方据此判断是否到底/失败）
     */
    public static int getRecommend(List<VideoCard> videoCardList, int page) throws IOException, JSONException {
        if (SharedPreferencesUtil.getBoolean(KEY_APP_RECOMMEND, true)) {
            return getRecommendApp(videoCardList, page);
        }
        return getRecommendWeb(videoCardList, page);
    }

    // ------------------------------------------------------------------ 屏蔽名单

    /** 解析设置页填的 UP 主 mid 名单：逗号/中文逗号/空白都当分隔符。填错的项直接忽略。 */
    static Set<Long> loadBlockedMids() {
        Set<Long> mids = new HashSet<>();
        for (String token : splitList(SharedPreferencesUtil.getString(KEY_BLOCK_MIDS, ""))) {
            try {
                long mid = Long.parseLong(token);
                if (mid > 0) mids.add(mid);
            } catch (NumberFormatException ignored) {
                //屏蔽名单是"尽力而为"：一个笔误不能让整页推荐加载失败
            }
        }
        return mids;
    }

    /** 解析标题屏蔽词，统一转小写后按"包含"匹配（不用正则：用户输入非法正则会让整页加载抛异常）。 */
    static List<String> loadBlockedWords() {
        List<String> words = new ArrayList<>();
        for (String token : splitList(SharedPreferencesUtil.getString(KEY_BLOCK_WORDS, ""))) {
            words.add(token.toLowerCase(Locale.ROOT));
        }
        return words;
    }

    private static String[] splitList(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new String[0];
        return raw.trim().split("[,，\\s]+");
    }

    /** 命中 UP 主名单或标题关键词则丢弃。两边都为空时只做一次集合判空，开销可忽略。 */
    private static boolean isBlocked(VideoCard card, Set<Long> blockMids, List<String> blockWords) {
        if (card.upMid > 0 && blockMids.contains(card.upMid)) return true;
        if (!blockWords.isEmpty() && !TextUtils.isEmpty(card.title)) {
            String lower = card.title.toLowerCase(Locale.ROOT);
            for (String word : blockWords) {
                if (!word.isEmpty() && lower.contains(word)) return true;
            }
        }
        return false;
    }

    /**
     * web 端推荐。
     *
     * <p><b>fresh_idx / brush / fresh_type / ps 是"换一批"的关键参数</b>（PiliPlus 同款）：
     * 实测不传这几个参数时，用<b>完全相同的参数</b>连打两次，服务端返回的都是同一批 30 条
     * （交集 30、顺序一致）——所以「下拉加载更多」会不断追加同一批内容。
     * 带上 fresh_idx=N/brush=N/fresh_type=4 后，相邻两页交集为 0。
     */
    private static int getRecommendWeb(List<VideoCard> videoCardList, int page) throws IOException, JSONException {
        int safePage = page <= 0 ? 1 : page;
        String url = "https://api.bilibili.com/x/web-interface/wbi/index/top/feed/rcmd";
        url += new NetWorkUtil.FormData().setUrlParam(true)
                .put("web_location", 1430650)
                .put("feed_version", "V8")
                .put("homepage_ver", 1)
                .put("uniq_id", UNIQ_ID)
                .put("screen", "1100-2056")
                .put("ps", 20)
                .put("fresh_idx", safePage)
                .put("brush", safePage)
                .put("fresh_type", 4);

        String json = NetWorkUtil.getJson(ConfInfoApi.signWBI(url)).toString();
        ApiResponse<RecommendResponse> resp = GsonUtil.fromJson(json,
                new com.google.gson.reflect.TypeToken<ApiResponse<RecommendResponse>>(){}.getType());
        if (resp == null || !resp.isSuccess() || resp.data == null || resp.data.item == null) return 0;

        //屏蔽名单每页只解析一次，避免逐条重复 split
        Set<Long> blockMids = loadBlockedMids();
        List<String> blockWords = loadBlockedWords();

        int added = 0;
        for (Item card : resp.data.item) {
            if (card == null) continue;
            //与 PiliPlus 的 _filterWebRcmd 对齐：只留普通投稿。番剧/直播/广告卡可能也带 bvid，
            //只靠"bvid 为空"判断会漏过它们，所以必须显式判 goto
            if (!TextUtils.isEmpty(card.gotoType) && !"av".equals(card.gotoType)) {
                Logu.d("recommend", "web 端跳过非普通投稿卡 goto=" + card.gotoType);
                continue;
            }
            if (TextUtils.isEmpty(card.bvid)) {
                Log.d("BiliClient", "RecommendApi getRecommend: isAd");
                continue;
            }
            String upName = card.owner != null ? card.owner.name : "";
            int viewCount = card.stat != null ? card.stat.view : 0;

            VideoCard videoCard = new VideoCard(card.title, upName,
                    StringUtil.toWan(viewCount) + "观看", card.pic, card.aid, card.bvid);
            videoCard.cid = card.cid;
            videoCard.duration = card.duration;
            videoCard.upMid = card.owner != null ? card.owner.mid : 0;
            videoCard.gotoType = card.gotoType;
            videoCard.uri = card.uri;
            videoCard.isFollowed = card.isFollowed == 1;
            videoCard.rcmdReason = card.rcmdReason != null ? card.rcmdReason.content : "";

            if (isBlocked(videoCard, blockMids, blockWords)) continue;
            videoCardList.add(videoCard);
            added++;
        }
        return added;
    }

    /**
     * app 端请求头：从全局网页请求头里**剥掉网页专用项**再补 App 身份。
     *
     * <p>必须"替换"而不是"追加"：{@code webHeaders} 里已有 {@code User-Agent}，直接 add 会变成
     * 两个 UA 头，服务端取哪个不确定。Origin/Referer/Sec-Ch-Ua* 是浏览器特征，带给 app 域名
     * 与 App 身份互相矛盾，一并去掉。
     *
     * <p>Cookie 仍然保留：实测带不带 Cookie 对 app 接口结果没有负向影响，保留可以不改动
     * 现有登录态相关行为（最小改动原则）。设备身份由 {@code buvid} 头承担，不再依赖 Cookie。
     */
    private static ArrayList<String> appRcmdHeaders() {
        ArrayList<String> source = NetWorkUtil.webHeaders;
        ArrayList<String> headers = new ArrayList<>(source.size() + 8);
        for (int i = 0; i + 1 < source.size(); i += 2) {
            String key = source.get(i);
            if (isWebOnlyHeader(key)) continue;
            headers.add(key);
            headers.add(source.get(i + 1));
        }
        headers.add("User-Agent");
        headers.add(APP_UA);
        headers.add("env");
        headers.add("prod");
        headers.add("app-key");
        headers.add("android_hd");
        //实测（2026-10-31，同参数同 IP 各 2~3 次）：带这个头 → 12 条且 4~5 条有推荐理由；
        //不带 → 10 条且 0 条有理由。Cookie 里的 buvid3 顶替不了它。详见 AppBuvid 类注释。
        headers.add("buvid");
        headers.add(AppBuvid.get());
        return headers;
    }

    private static boolean isWebOnlyHeader(String key) {
        return "User-Agent".equalsIgnoreCase(key)
                || "Origin".equalsIgnoreCase(key)
                || "Referer".equalsIgnoreCase(key)
                || key.toLowerCase(Locale.ROOT).startsWith("sec-ch-ua");
    }

    /** app 端推荐参数表：与 PiliPlus {@code rcmdVideoListApp} 同源（2026-10-31 实测可用）。 */
    private static TreeMap<String, String> appRcmdParams(boolean fresh) {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("build", APP_BUILD);
        params.put("c_locale", "zh_CN");
        params.put("channel", "master");
        params.put("column", "2");
        params.put("device", "phone");
        params.put("device_name", "android");
        params.put("device_type", "0");
        params.put("disable_rcmd", "0");
        params.put("flush", "8");
        params.put("fnval", "976");
        params.put("fnver", "0");
        params.put("force_host", "2");
        params.put("fourk", "1");
        params.put("guidance", "1");
        params.put("https_url_req", "1");
        params.put("idx", String.valueOf(appRecommendIdx));
        params.put("mobi_app", "android_i");
        params.put("network", "wifi");
        params.put("platform", "android");
        params.put("player_net", "1");
        params.put("pull", fresh ? "true" : "false");
        params.put("qn", "32");
        params.put("recsys_mode", "0");
        params.put("s_locale", "zh_CN");
        params.put("splash_id", "");
        params.put("statistics", APP_STATISTICS);
        params.put("voice_balance", "0");
        return params;
    }

    /**
     * app 端推荐（{@code https://app.bilibili.com/x/v2/feed/index}）。
     *
     * <p>与 web 端是两套完全不同的数据：条目在 {@code data.items[]}，身份字段是
     * {@code args.aid / player_args.aid}（**没有 bvid**），播放量只有格式化文本
     * {@code cover_left_text_1}，时长在 {@code player_args.duration}。
     *
     * <p>签名（appkey/ts/sign）与 access_key 都受设置项控制：
     * 签名默认开，是"像官方客户端"的一部分；access_key 默认关——它属于账号凭证，
     * 对推荐是否有收益尚未验证，需要用户自己在设置里打开。
     */
    private static int getRecommendApp(List<VideoCard> videoCardList, int page) throws IOException, JSONException {
        //第 1 页（含下拉刷新）必须把游标复位并带 pull=true：否则"刷新"拿到的是更早的下一批，
        //不是最新一批（PiliPlus 的 onRefresh 也是 page=0 + pull=true）
        final boolean fresh = page <= 1;
        if (fresh) appRecommendIdx = 0;

        TreeMap<String, String> params = appRcmdParams(fresh);
        String query;
        if (SharedPreferencesUtil.getBoolean(KEY_APP_RECOMMEND_SIGN, true)) {
            if (SharedPreferencesUtil.getBoolean(KEY_APP_RECOMMEND_ACCESS_KEY, false)) {
                long mid = NetWorkUtil.getLoginMid();
                String accessKey = mid > 0 ? AppAuthStore.getAccessKey(mid) : "";
                if (!accessKey.isEmpty()) params.put("access_key", accessKey);
                else Logu.d("recommend", "access_key 未启用或为空，按匿名态请求");
            }
            query = AppSign.sign(params);
        } else {
            query = AppSign.encodeQuery(params);
        }

        JSONObject json = NetWorkUtil.getJson("https://app.bilibili.com/x/v2/feed/index?" + query, appRcmdHeaders());
        int code = json.optInt("code", -1);
        if (code != 0) {
            //不打印完整响应体（可能含推荐上下文），只记状态码
            Logu.e("recommend", "app 端推荐返回 code=" + code);
            return 0;
        }
        JSONObject data = json.optJSONObject("data");
        JSONArray items = data != null ? data.optJSONArray("items") : null;
        if (items == null) return 0;

        Set<Long> blockMids = loadBlockedMids();
        List<String> blockWords = loadBlockedWords();

        int added = 0;
        int skippedType = 0;
        int withReason = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;

            String title = item.optString("title", "");
            if (TextUtils.isEmpty(title)) continue;   //广告位与异常条目没有标题

            //推广位过滤（PiliPlus 的 _filterAppRcmd 同款判据）
            String cardGoto = item.optString("card_goto", "");
            if ("ad_av".equals(cardGoto) || "ad_web_s".equals(cardGoto)) continue;
            if (item.optJSONObject("ad_info") != null) continue;
            if (item.optInt("can_play", 1) != 1) continue;

            //卡片类型：av 与 inline_av 都是普通投稿（inline_av 偶发出现，以前被"非 av 一律丢"误杀）；
            //bangumi 的 param 是剧集 epId，交给卡片适配器已有的 media_bangumi 分支；
            //直播/课程/课程包等没有对应跳转入口，跳过并计数（不是静默丢弃）
            String cardType;
            if (cardGoto.isEmpty() || "av".equals(cardGoto) || "inline_av".equals(cardGoto)) {
                cardType = "video";
            } else if ("bangumi".equals(cardGoto)) {
                cardType = "media_bangumi";
            } else {
                skippedType++;
                continue;
            }

            JSONObject args = item.optJSONObject("args");
            JSONObject playerArgs = item.optJSONObject("player_args");
            long param = item.optLong("param", 0);

            long aid = args != null ? args.optLong("aid", 0) : 0;
            if (aid == 0 && playerArgs != null) aid = playerArgs.optLong("aid", 0);
            String bvid = item.optString("bvid", "");
            if (TextUtils.isEmpty(bvid) && args != null) bvid = args.optString("bvid", "");

            if ("media_bangumi".equals(cardType)) {
                if (param <= 0) {
                    skippedType++;
                    continue;
                }
            } else if (aid == 0 && TextUtils.isEmpty(bvid)) {
                continue;
            }

            String upName = args != null ? args.optString("up_name", "") : "";
            String viewText = item.optString("cover_left_text_1", "");
            if (TextUtils.isEmpty(viewText)) viewText = "推荐";

            VideoCard videoCard = new VideoCard(title, upName, viewText, item.optString("cover", ""),
                    aid, bvid, cardType);
            videoCard.cid = playerArgs != null ? playerArgs.optLong("cid", 0) : 0;
            videoCard.duration = playerArgs != null ? playerArgs.optInt("duration", 0) : 0;
            videoCard.upMid = args != null ? args.optLong("up_id", 0) : 0;
            videoCard.gotoType = cardGoto;
            videoCard.uri = item.optString("uri", "");
            String reason = item.optString("rcmd_reason", "");
            //"竖屏"只是排版标记，不是推荐理由；"已关注/新关注"在 PiliPlus 里被转成 isFollowed
            if ("竖屏".equals(reason) || "已关注".equals(reason) || "新关注".equals(reason)) {
                videoCard.isFollowed = "已关注".equals(reason) || "新关注".equals(reason);
                reason = "";
            }
            videoCard.rcmdReason = reason;
            if ("media_bangumi".equals(cardType)) videoCard.epid = param;
            if (!reason.isEmpty()) withReason++;

            if (isBlocked(videoCard, blockMids, blockWords)) continue;
            videoCardList.add(videoCard);
            added++;

            //翻页游标：取最后一条的 idx，下一页从它继续
            long idx = item.optLong("idx", 0);
            if (idx > 0) appRecommendIdx = idx - 1;
        }
        //只记聚合结果，不打印任何卡片内容（标题/UP 名属于用户画像侧信息）
        Logu.d("recommend", "app 端解析：原始 " + items.length() + " 条，采用 " + added
                + " 条，带推荐理由 " + withReason + " 条，类型不支持跳过 " + skippedType + " 条");
        return added;
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
