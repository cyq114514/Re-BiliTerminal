package com.RobinNotBad.BiliClient.api;

import com.RobinNotBad.BiliClient.model.ShortVideoItem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 竖屏短视频取流的纯逻辑：查询参数拼装、降级池判定、响应解析与跨批去重。
 *
 * <p>刻意不碰 Context / SharedPreferences / Build：设备名、登录态这类运行时值由调用方
 * （{@link ShortVideoFeedApi}）传进来，因此整块逻辑可以直接在 JVM 里跑，也方便对着
 * 参考实现 diff 参数。
 *
 * <p>参数口径来自 Re：哔哩终端（Kotlin 版）的取流文档：
 * <ul>
 *   <li>{@code display_id} 是<b>批次序号</b>（首屏 1，每成功一批 +1），不是页码；</li>
 *   <li>{@code pull} 首屏 0、翻页 1（老实现传 true，不在取值范围内）；</li>
 *   <li>{@code trackid} 要回传上一批服务端下发的 {@code track_id}，否则每次翻页都被当成首屏，内容重复；</li>
 *   <li>降级通道（{@code track_id} 以 {@code gateway_fallback_} 开头）的值不回传；</li>
 *   <li>{@code from} / {@code from_spmid} / {@code spmid} 一律不发：三者混传服务端直接 -400。</li>
 * </ul>
 */
public final class StoryFeedQuery {

    private StoryFeedQuery() {
    }

    /** 竖屏主信息流 */
    public static final String PATH_STORY = "/x/v2/feed/index/story";
    /** 竖屏动态流：主信息流没有可用数据时的兜底 */
    public static final String PATH_STORY_DYNAMIC = "/x/v2/feed/index/dynamic/story";
    /** 以某条视频为锚点的相关推荐（列表刷到底时续接） */
    public static final String PATH_RELATE = "/x/v2/feed/index/relate/story";

    public static final String HOST = "https://app.bilibili.com";
    /** 场景/池选择器（0~6），官方默认 0 */
    public static final int DEFAULT_REQUEST_FROM = 0;
    /** 网关降级通道的 track_id 前缀 */
    public static final String DEGRADED_TRACK_PREFIX = "gateway_fallback_";

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** 一批取流结果 */
    public static final class FeedBatch {
        public final List<ShortVideoItem> items;
        /** 本批最后一条非空 track_id；全为空时 null */
        public final String trackId;
        /** 本批是否来自网关降级通道 */
        public final boolean degraded;

        public FeedBatch(List<ShortVideoItem> items, String trackId, boolean degraded) {
            this.items = items;
            this.trackId = trackId;
            this.degraded = degraded;
        }
    }

    public static final FeedBatch EMPTY_BATCH = new FeedBatch(new ArrayList<>(), null, false);

    /**
     * 构造一批取流的查询参数。
     *
     * @param displayId  批次序号，小于 1 按 1 处理
     * @param firstBatch 是否本次会话的首屏
     * @param trackId    上一批的 track_id，可为空
     * @param accessKey  登录态 access_key，未登录传空串
     * @param deviceName 设备名（Build.MODEL），取不到传空串
     */
    public static List<String[]> buildParams(int displayId, boolean firstBatch, String trackId,
                                             String accessKey, String deviceName) {
        return buildParams(displayId, firstBatch, trackId, accessKey, deviceName, DEFAULT_REQUEST_FROM);
    }

    public static List<String[]> buildParams(int displayId, boolean firstBatch, String trackId,
                                             String accessKey, String deviceName, int requestFrom) {
        List<String[]> params = new ArrayList<>(20);
        add(params, "mobi_app", "android");
        add(params, "platform", "android");
        add(params, "display_id", String.valueOf(Math.max(1, displayId)));
        add(params, "pull", firstBatch ? "0" : "1");
        add(params, "request_from", String.valueOf(Math.max(0, Math.min(6, requestFrom))));
        add(params, "open_event", "cold");
        add(params, "video_mode", "2");
        add(params, "voice_balance", "1");
        add(params, "s_locale", "zh_CN");
        add(params, "inline_sound", "2");
        add(params, "network", "wifi");
        add(params, "fnval", "272");
        add(params, "fnver", "1");
        add(params, "force_host", "2");
        add(params, "fourk", "1");
        add(params, "qn", "32");
        add(params, "story_mode", "1");
        if (trackId != null && !trackId.isEmpty() && !isDegradedTrackId(trackId)) add(params, "trackid", trackId);
        if (deviceName != null && !deviceName.isEmpty()) add(params, "device_name", deviceName);
        if (accessKey != null && !accessKey.isEmpty()) add(params, "access_key", accessKey);
        return params;
    }

    /** 相关推荐：复用主信息流那套公共参数，再追加锚点视频 aid */
    public static List<String[]> buildRelatedParams(long anchorAid, String accessKey, String deviceName) {
        List<String[]> params = new ArrayList<>(buildParams(1, true, null, accessKey, deviceName));
        add(params, "aid", String.valueOf(anchorAid));
        return params;
    }

    /** 按 HOST + path + 查询串拼出完整 URL；参数名与值都做百分号编码 */
    public static String buildUrl(String path, List<String[]> params) {
        StringBuilder builder = new StringBuilder(HOST).append(path);
        if (params != null && !params.isEmpty()) {
            builder.append('?');
            for (int i = 0; i < params.size(); i++) {
                if (i > 0) builder.append('&');
                builder.append(percentEncode(params.get(i)[0])).append('=')
                        .append(percentEncode(params.get(i)[1]));
            }
        }
        return builder.toString();
    }

    /**
     * 百分号编码：只保留 {@code A-Z a-z 0-9 - _ . ~}，其余按 UTF-8 字节转 %XX（大写）。
     * 不用 {@link java.net.URLEncoder}：它把空格编成 {@code +}，与接口要求的 %20 不一致。
     */
    public static String percentEncode(String value) {
        if (value == null) return "";
        byte[] bytes = value.getBytes(UTF8);
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int code = b & 0xFF;
            char c = (char) code;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                builder.append(c);
            } else {
                builder.append('%').append(HEX[code >> 4]).append(HEX[code & 0x0F]);
            }
        }
        return builder.toString();
    }

    public static boolean isDegradedTrackId(String trackId) {
        return trackId != null && !trackId.isEmpty() && trackId.startsWith(DEGRADED_TRACK_PREFIX);
    }

    /**
     * 明确的横屏卡片：有 {@code dimension} 且 width &gt; height 且 rotate == 0。
     *
     * <p>缺 dimension、宽高非正、或素材旋转过，一律不判为横屏——宁可播一条横屏，
     * 也不能在字段变化时把整批竖屏内容误删。
     */
    public static boolean isClearlyHorizontal(JSONObject item) {
        if (item == null) return false;
        JSONObject dimension = item.optJSONObject("dimension");
        if (dimension == null) return false;
        int width = dimension.optInt("width", 0);
        int height = dimension.optInt("height", 0);
        if (width <= 0 || height <= 0 || width <= height) return false;
        return dimension.optInt("rotate", 0) == 0;
    }

    /** 解析一批主信息流响应；code != 0 / 缺 data / 缺 items 一律返回空批 */
    public static FeedBatch parseFeed(JSONObject root, Set<Long> alreadySeenAids) {
        if (root == null) return EMPTY_BATCH;
        if (root.optInt("code", -1) != 0) return EMPTY_BATCH;
        JSONObject data = root.optJSONObject("data");
        if (data == null) return EMPTY_BATCH;
        JSONArray array = data.optJSONArray("items");
        if (array == null) return EMPTY_BATCH;

        List<ShortVideoItem> items = new ArrayList<>(array.length());
        Set<Long> batchAids = new HashSet<>();
        String trackId = null;
        boolean degraded = false;

        for (int i = 0; i < array.length(); i++) {
            JSONObject json = array.optJSONObject(i);
            if (json == null) continue;
            String itemTrackId = json.optString("track_id", "");
            if (!itemTrackId.isEmpty()) {
                trackId = itemTrackId;
                if (isDegradedTrackId(itemTrackId)) degraded = true;
            }
            if (isClearlyHorizontal(json)) continue;
            ShortVideoItem item = ShortVideoItem.fromFeedItem(json);
            if (item == null) continue;
            if (alreadySeenAids != null && alreadySeenAids.contains(item.aid)) continue;
            if (!batchAids.add(item.aid)) continue;
            items.add(item);
        }
        return new FeedBatch(items, trackId, degraded);
    }

    /**
     * 解析相关推荐响应：实测存在 {@code data.items[]} 与 {@code data[]} 两种形态，都要兼容。
     */
    public static List<ShortVideoItem> parseRelated(JSONObject root, Set<Long> alreadySeenAids) {
        List<ShortVideoItem> empty = new ArrayList<>();
        if (root == null) return empty;
        if (root.optInt("code", -1) != 0) return empty;

        JSONArray array = root.optJSONArray("data");
        if (array == null) {
            JSONObject data = root.optJSONObject("data");
            if (data != null) array = data.optJSONArray("items");
        }
        if (array == null) return empty;

        List<ShortVideoItem> items = new ArrayList<>(array.length());
        Set<Long> batchAids = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject json = array.optJSONObject(i);
            if (json == null) continue;
            if (isClearlyHorizontal(json)) continue;
            ShortVideoItem item = ShortVideoItem.fromFeedItem(json);
            if (item == null) continue;
            if (alreadySeenAids != null && alreadySeenAids.contains(item.aid)) continue;
            if (!batchAids.add(item.aid)) continue;
            items.add(item);
        }
        return items;
    }

    private static void add(List<String[]> params, String name, String value) {
        params.add(new String[]{name, value});
    }
}
