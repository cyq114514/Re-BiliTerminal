package com.RobinNotBad.BiliClient.api;

import android.os.Build;
import android.text.TextUtils;

import com.RobinNotBad.BiliClient.model.PlayerData;
import com.RobinNotBad.BiliClient.model.ShortVideoItem;
import com.RobinNotBad.BiliClient.util.DeviceIdentity;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 竖屏短视频取流：发请求、取播放地址、维护一次会话的分页状态。
 *
 * <p>参数拼装与响应解析都在纯逻辑 {@link StoryFeedQuery} 里；本类只负责「带登录态和设备指纹发请求」
 * 以及「把分页状态绑在一次会话上」（{@link StoryFeedPager}，页面销毁即丢弃，不留在静态字段里）。
 *
 * <p>移植自 Re：哔哩终端（Kotlin 版）的 {@code ShortVideoFeedApi} / {@code StoryFeedPager}。
 */
public final class ShortVideoFeedApi {

    private static final String TAG = "ShortVideoApi";
    /** 参考实现用的 App UA：网页 UA 在这个接口上容易掉进降级池 */
    private static final String USER_AGENT = "BiliApp/8130300 (android)";

    private static final String KEY_SEEN = "short_video_seen_aids";
    /** 跨会话「看过」集合上限：太大只是白写盘，太小会重复推荐 */
    private static final int SEEN_MAX = 300;

    private ShortVideoFeedApi() {
    }

    /** 「视频设置 → 分辨率」里保存的清晰度；老用户没有该键时用 16（360P） */
    public static int defaultPlayQuality() {
        return SharedPreferencesUtil.getInt("play_qn", 16);
    }

    /**
     * 取播放地址（结果缓存在 item 上）：与分页无关，静态方法供播放页直接调用。
     *
     * @return null 表示成功；否则是失败原因（会显示在短视频页的调试状态行上）
     */
    public static String fetchVideoUrl(ShortVideoItem item) {
        if (item == null) return "item 为空";
        if (!item.videoUrlList.isEmpty() || !TextUtils.isEmpty(item.videoUrl)) return null;

        //信息流偶尔只下发 aid 不给 cid（老竖屏形态）：先用 view 接口把 cid 补上。
        //不补的话 playurl 必然失败，用户看到的就是一条接一条的"获取播放地址失败"
        if (item.cid <= 0) {
            try {
                com.RobinNotBad.BiliClient.model.VideoInfo info =
                        com.RobinNotBad.BiliClient.api.VideoInfoApi.getVideoInfo(item.aid);
                if (info != null && info.cids != null && !info.cids.isEmpty() && info.cids.get(0) > 0) {
                    item.cid = info.cids.get(0);
                    Logu.d(TAG, "补 cid 成功 aid=" + item.aid + " cid=" + item.cid);
                }
            } catch (Exception e) {
                Logu.e(TAG, "补 cid 失败 aid=" + item.aid + "：" + e.getMessage());
            }
            if (item.cid <= 0) return "这条视频没有 cid，无法取流";
        }

        int qn = defaultPlayQuality();
        PlayerData playerData = new PlayerData();
        playerData.aid = item.aid;
        playerData.cid = item.cid;
        playerData.qn = qn;
        try {
            PlayerApi.getVideo(playerData, false);
            item.videoUrl = playerData.videoUrl;
            //候选地址（官方 CDN 优先、PCDN 兜底）：播放器失败时按下标逐个换
            item.videoUrlList = playerData.videoUrls == null
                    ? new ArrayList<String>() : new ArrayList<>(playerData.videoUrls);
            item.danmakuUrl = playerData.danmakuUrl;
            item.qnStrList = playerData.qnStrList;
            item.qnValueList = playerData.qnValueList;
            item.currentQuality = pickQuality(qn, playerData.qnValueList);
            Logu.d(TAG, "取流成功 aid=" + item.aid + " qn=" + item.currentQuality);
            return TextUtils.isEmpty(item.videoUrl) ? "playurl 返回了空地址" : null;
        } catch (Exception e) {
            Logu.e(TAG, "取流失败 aid=" + item.aid + "：" + e.getMessage());
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /**
     * 把 B 站媒体地址升级成 https（照抄播放页的 upgradeMediaUrlToHttps）。
     *
     * <p>ijkplayer 走 native 网络栈，不受 networkSecurityConfig 约束：http 地址会明文拉流，
     * 而请求头里带着全量登录 Cookie。B 站媒体 CDN 都支持 https，统一升级即可。
     */
    public static String upgradeMediaUrl(String url) {
        if (url == null) return null;
        okhttp3.HttpUrl parsed = okhttp3.HttpUrl.parse(url);
        if (parsed == null || parsed.isHttps()) return url;
        if (NetWorkUtil.isBilibiliHost(parsed.host()))
            return parsed.newBuilder().scheme("https").build().toString();
        return url;
    }

    /** playurl 的 qn 只是首选值：按响应里的 accept_quality 还原实际能拿到的最高档 */
    private static int pickQuality(int requested, int[] available) {
        if (available == null || available.length == 0) return requested;
        int best = Integer.MIN_VALUE;
        int lowest = Integer.MAX_VALUE;
        for (int q : available) {
            if (q <= 0) continue;
            if (q < lowest) lowest = q;
            if (q <= requested && q > best) best = q;
        }
        if (best != Integer.MIN_VALUE) return best;
        return lowest != Integer.MAX_VALUE ? lowest : requested;
    }

    /** 请求头：UA / Referer / Cookie / Buvid / Display-ID */
    private static ArrayList<String> buildHeaders() {
        ArrayList<String> headers = new ArrayList<>(14);
        headers.add("User-Agent");
        headers.add(USER_AGENT);
        headers.add("Referer");
        headers.add("https://www.bilibili.com/");
        headers.add("env");
        headers.add("prod");

        String cookies = SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "");
        if (!TextUtils.isEmpty(cookies)) {
            headers.add("Cookie");
            headers.add(cookies);
            String buvid3 = NetWorkUtil.getInfoFromCookie("buvid3", cookies);
            if (!TextUtils.isEmpty(buvid3)) {
                headers.add("Buvid");
                headers.add(buvid3);
            }
        }
        //设备指纹缺了会被判成「不可识别设备」，即使登录也更容易掉进降级池
        headers.add("Display-ID");
        headers.add(DeviceIdentity.displayId());
        return headers;
    }

    /** 请求一个取流路径并解析成一批；任何失败都返回空批 */
    static StoryFeedQuery.FeedBatch requestBatch(String path, List<String[]> params, Set<Long> seen) {
        String url = StoryFeedQuery.buildUrl(path, params);
        try {
            okhttp3.Response response = NetWorkUtil.get(url, buildHeaders());
            if (response == null || response.body() == null) return StoryFeedQuery.EMPTY_BATCH;
            String raw = response.body().string();
            if (TextUtils.isEmpty(raw)) return StoryFeedQuery.EMPTY_BATCH;
            JSONObject body = new JSONObject(raw);
            StoryFeedQuery.FeedBatch batch = StoryFeedQuery.parseFeed(body, seen);
            if (batch.items.isEmpty()) {
                int code = body.optInt("code", -1);
                if (code != 0) Logu.e(TAG, "接口错误 code=" + code + " " + body.optString("message"));
            } else {
                Logu.d(TAG, "拉到 " + batch.items.size() + " 条（降级=" + batch.degraded + "）");
            }
            return batch;
        } catch (Exception e) {
            Logu.e(TAG, "取流请求失败：" + e.getClass().getSimpleName() + ": " + e.getMessage());
            return StoryFeedQuery.EMPTY_BATCH;
        }
    }

    /** 请求相关推荐；失败返回空列表（调用方据此停下，不再重试） */
    static List<ShortVideoItem> requestRelated(List<String[]> params, Set<Long> seen) {
        String url = StoryFeedQuery.buildUrl(StoryFeedQuery.PATH_RELATE, params);
        try {
            okhttp3.Response response = NetWorkUtil.get(url, buildHeaders());
            if (response == null || response.body() == null) return new ArrayList<>();
            String raw = response.body().string();
            if (TextUtils.isEmpty(raw)) return new ArrayList<>();
            List<ShortVideoItem> items = StoryFeedQuery.parseRelated(new JSONObject(raw), seen);
            Logu.d(TAG, "相关推荐 " + items.size() + " 条");
            return items;
        } catch (Exception e) {
            Logu.e(TAG, "相关推荐失败：" + e.getMessage());
            return new ArrayList<>();
        }
    }

    // ------------------------------------------------------------------ 跨会话「看过」

    /** 读取最近看过的 aid（服务端换批时偶尔重复下发，退出再进也会把刚看过的推回来） */
    static Set<Long> loadSeen() {
        Set<Long> seen = new HashSet<>();
        try {
            String raw = SharedPreferencesUtil.getString(KEY_SEEN, "");
            if (TextUtils.isEmpty(raw)) return seen;
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) seen.add(array.optLong(i));
        } catch (Exception ignored) {
        }
        return seen;
    }

    /** 把这一批看过的 aid 合并写回（新的在后，超过上限丢最早的） */
    static void recordSeen(List<ShortVideoItem> items) {
        if (items == null || items.isEmpty()) return;
        try {
            JSONArray array = new JSONArray();
            Set<Long> merged = new HashSet<>(loadSeen());
            for (Long aid : merged) array.put(aid);
            for (ShortVideoItem item : items) {
                if (!merged.contains(item.aid)) {
                    merged.add(item.aid);
                    array.put(item.aid);
                }
            }
            int drop = array.length() - SEEN_MAX;
            if (drop > 0) {
                JSONArray trimmed = new JSONArray();
                for (int i = drop; i < array.length(); i++) trimmed.put(array.optLong(i));
                array = trimmed;
            }
            SharedPreferencesUtil.putString(KEY_SEEN, array.toString());
        } catch (Exception ignored) {
        }
    }

    /**
     * 一次「进入短视频页」的分页状态机。
     *
     * <p>翻页协议要点：display_id 是批次序号（首屏 1，每成功一批 +1）；首屏 pull=0、翻页 pull=1；
     * 上一批的 track_id 要回传（降级通道的值不回传）；主信息流没有可用数据时改走竖屏动态流兜底。
     */
    public static final class StoryFeedPager {

        private int displayId = 1;
        private String lastTrackId = null;
        private boolean firstBatchLoaded = false;
        private final Set<Long> seenAids = new HashSet<>();
        private boolean degradedLogged = false;

        /** 拉取下一批；返回空列表表示这一批彻底失败 */
        public synchronized List<ShortVideoItem> fetchPage() {
            int page = displayId;
            boolean firstBatch = !firstBatchLoaded;
            String accessKey = SharedPreferencesUtil.getString(SharedPreferencesUtil.access_key, "");
            String deviceName = Build.MODEL == null ? "" : Build.MODEL;
            List<String[]> params = StoryFeedQuery.buildParams(page, firstBatch, lastTrackId, accessKey, deviceName);

            Set<Long> seen = new HashSet<>(seenAids);
            seen.addAll(loadSeen());

            StoryFeedQuery.FeedBatch batch = requestBatch(StoryFeedQuery.PATH_STORY, params, seen);
            if (batch.items.isEmpty()) {
                Logu.d(TAG, "主信息流没有可用数据，改走动态流兜底");
                batch = requestBatch(StoryFeedQuery.PATH_STORY_DYNAMIC, params, seen);
            }
            if (batch.items.isEmpty()) return new ArrayList<>();

            displayId = page + 1;
            firstBatchLoaded = true;
            if (batch.trackId != null) lastTrackId = batch.trackId;
            for (ShortVideoItem item : batch.items) seenAids.add(item.aid);
            recordSeen(batch.items);
            if (batch.degraded && !degradedLogged) {
                degradedLogged = true;
                Logu.d(TAG, "本批来自网关降级池（track_id 以 " + StoryFeedQuery.DEGRADED_TRACK_PREFIX + " 开头），内容可能偏泛");
            }
            return batch.items;
        }

        /** 以某条视频为锚点拉一批相关推荐（列表到底时的续接通道） */
        public synchronized List<ShortVideoItem> fetchRelated(long anchorAid) {
            if (anchorAid <= 0) return new ArrayList<>();
            String accessKey = SharedPreferencesUtil.getString(SharedPreferencesUtil.access_key, "");
            String deviceName = Build.MODEL == null ? "" : Build.MODEL;
            List<String[]> params = StoryFeedQuery.buildRelatedParams(anchorAid, accessKey, deviceName);

            Set<Long> seen = new HashSet<>(seenAids);
            seen.addAll(loadSeen());
            List<ShortVideoItem> items = requestRelated(params, seen);
            if (items.isEmpty()) return items;
            for (ShortVideoItem item : items) seenAids.add(item.aid);
            recordSeen(items);
            return items;
        }
    }
}
