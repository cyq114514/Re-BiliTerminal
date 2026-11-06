package com.RobinNotBad.BiliClient.source.danmaku;

import com.RobinNotBad.BiliClient.BuildConfig;
import com.RobinNotBad.BiliClient.source.model.SourceException;
import com.RobinNotBad.BiliClient.source.net.SourceHttp;
import com.RobinNotBad.BiliClient.util.Logu;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 弹弹play 开放 API（第三方弹幕源）。
 *
 * <p>鉴权与 Animeko 的 {@code DandanplayClient} 一致（对方文档要求，非抄实现）：
 * <pre>
 *   X-AppId:     appId
 *   X-Timestamp: 秒级时间戳
 *   X-Signature: Base64( SHA256(appId + timestamp + path + appSecret) )
 * </pre>
 * 其中 {@code path} 是**不含查询串**的路径。
 *
 * <p>凭据只从 {@code local.properties} 注入 BuildConfig，不入库；缺失时
 * {@link #available()} 为 false，所有调用直接返回空，不发起任何请求。
 */
public final class DandanplayClient {

    private static final String BASE = "https://api.dandanplay.net";
    /**
     * 单次请求的整次调用上限。
     *
     * <p>原来 8 秒：实测本机（有线宽带）拉一集弹幕正文要 1.5~3 秒，而这条请求是与取流
     * 并发发起的，手表走蓝牙共享/弱网时 8 秒很容易踩线；超时后弹幕静默消失，用户侧
     * 没有任何提示。放宽到 25 秒只是"多等一会儿"，请求本身仍在后台线程，不阻塞播放。
     */
    private static final long TIMEOUT_MS = 25_000L;

    private DandanplayClient() {
    }

    /** 服务端明确拒绝过凭据（403）：本次进程内不再重试，避免每集都白打一次请求 */
    private static volatile boolean credentialsRejected = false;

    /** 是否配置了 AppId + AppSecret，且尚未被服务端拒绝 */
    public static boolean available() {
        if (credentialsRejected) return false;
        String id = BuildConfig.DANDAN_APP_ID;
        String secret = BuildConfig.DANDAN_APP_SECRET;
        return id != null && !id.trim().isEmpty() && secret != null && !secret.trim().isEmpty();
    }

    /** 换凭据后（下次启动）自动恢复；测试/排障时可手动调 */
    public static void resetRejected() {
        credentialsRejected = false;
    }

    /** 匹配到的一集弹幕 */
    public static final class Match {
        public long episodeId;
        public String animeTitle = "";
        public String episodeTitle = "";
        public int episodeNumber = 0;

        public String display() {
            return animeTitle + " 第" + episodeNumber + "话 " + episodeTitle;
        }
    }

    /** 一条弹幕 */
    public static final class Comment {
        public double timeSeconds;
        /** 1=滚动 4=底部 5=顶部（弹弹play 的 m 字段） */
        public int mode = 1;
        public int color = 0xFFFFFF;
        public String text = "";
    }

    /**
     * 按番剧名 + 集号找弹幕。
     *
     * @param episodeNumber 1 起；<=0 表示只按番剧名找第一集
     */
    public static List<Match> match(String animeName, int episodeNumber) throws SourceException {
        List<Match> out = new ArrayList<>();
        if (!available() || animeName == null || animeName.trim().isEmpty()) return out;

        Map<String, String> query = new LinkedHashMap<>();
        query.put("anime", animeName.trim());
        query.put("episode", episodeNumber > 0 ? String.valueOf(episodeNumber) : "");

        String body = get("/api/v2/search/episodes", query, "匹配弹幕");
        try {
            JSONObject root = new JSONObject(body);
            JSONArray animes = root.optJSONArray("animes");
            if (animes == null) return out;
            for (int i = 0; i < animes.length() && out.size() < 20; i++) {
                JSONObject anime = animes.optJSONObject(i);
                if (anime == null) continue;
                String animeTitle = anime.optString("animeTitle", "");
                JSONArray episodes = anime.optJSONArray("episodes");
                if (episodes == null) continue;
                for (int j = 0; j < episodes.length() && out.size() < 20; j++) {
                    JSONObject episode = episodes.optJSONObject(j);
                    if (episode == null) continue;
                    Match match = new Match();
                    match.episodeId = episode.optLong("episodeId", 0);
                    match.animeTitle = animeTitle;
                    match.episodeTitle = episode.optString("episodeTitle", "");
                    //实测 /api/v2/search/episodes 的 episodes[] 不带 episodeNumber，
                    //只有「第N话 …」这类标题；集号要从标题解析，否则永远为 0 没法挑话
                    match.episodeNumber = episode.optInt("episodeNumber",
                            parseEpisodeNumber(match.episodeTitle));
                    if (match.episodeId <= 0) continue;
                    out.add(match);
                }
            }
        } catch (Exception e) {
            throw new SourceException(SourceException.Phase.EPISODE, SourceException.Kind.PARSE,
                    "弹弹play", "匹配结果解析失败：" + e.getMessage(), e);
        }
        return out;
    }

    /**
     * 拉某一集的弹幕。
     *
     * <p>不带 {@code withRelated}：带上它会合并爱奇艺/B站等站点的关联弹幕，正文体积从
     * 约 40KB 涨到约 1.1MB（实测同一集 42348 → 1159586 字节，耗时 1.5s → 3.1s），
     * 对手表这种弱网设备是纯粹的风险；只取弹弹play 本集弹幕，命中率与速度都更好。
     */
    public static List<Comment> comments(long episodeId) throws SourceException {
        List<Comment> out = new ArrayList<>();
        if (!available() || episodeId <= 0) return out;
        Map<String, String> query = new LinkedHashMap<>();
        query.put("chConvert", "1");
        String body = get("/api/v2/comment/" + episodeId, query, "拉取弹幕");
        try {
            JSONObject root = new JSONObject(body);
            JSONArray comments = root.optJSONArray("comments");
            if (comments == null) return out;
            for (int i = 0; i < comments.length(); i++) {
                JSONObject item = comments.optJSONObject(i);
                if (item == null) continue;
                Comment comment = new Comment();
                //弹弹play 的格式与 B 站一致：p = "时间,模式,颜色,uid"，m = 弹幕正文
                String p = item.optString("p", "");
                String[] parts = p.split(",");
                if (parts.length > 0) comment.timeSeconds = parseDouble(parts[0]);
                if (parts.length > 1) comment.mode = (int) parseDouble(parts[1]);
                if (parts.length > 2) comment.color = (int) parseDouble(parts[2]);
                comment.text = item.optString("m", "");
                if (comment.text == null || comment.text.isEmpty()) continue;
                out.add(comment);
            }
        } catch (Exception e) {
            throw new SourceException(SourceException.Phase.EPISODE, SourceException.Kind.PARSE,
                    "弹弹play", "弹幕解析失败：" + e.getMessage(), e);
        }
        return out;
    }

    // ==================== 请求与签名 ====================

    private static String get(String path, Map<String, String> query, String phase)
            throws SourceException {
        StringBuilder url = new StringBuilder(BASE).append(path);
        if (query != null && !query.isEmpty()) {
            boolean first = true;
            for (Map.Entry<String, String> entry : query.entrySet()) {
                if (entry.getValue() == null || entry.getValue().isEmpty()) continue;
                url.append(first ? '?' : '&')
                        .append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
                first = false;
            }
        }

        long timestamp = System.currentTimeMillis() / 1000;
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("X-AppId", BuildConfig.DANDAN_APP_ID.trim());
        headers.put("X-Timestamp", String.valueOf(timestamp));
        headers.put("X-Signature", signature(BuildConfig.DANDAN_APP_ID.trim(), timestamp, path,
                BuildConfig.DANDAN_APP_SECRET.trim()));

        try {
            long startAt = System.currentTimeMillis();
            SourceHttp.Response response = SourceHttp.get(url.toString(), headers, TIMEOUT_MS);
            //状态码/耗时/体积必须是 W 级：正式包 LOGD_ENABLED=false，D 级一行都不输出，
            //「第三方源没弹幕」在真机上曾经完全无迹可查。每次播放只有 1~2 条，不会刷屏。
            //403 可能是凭据无效，也可能是服务端按 IP/区域拦截，两者处理方式完全不同
            Logu.w("third-danmaku", path + " → HTTP " + response.code
                    + " 耗时=" + (System.currentTimeMillis() - startAt) + "ms"
                    + " 字节=" + response.body.length);
            if (response.code == 404) return "{}";
            if (response.code == 403 || response.code == 401) {
                //403 通常是 AppId 无效/应用未启用；签名正确与否都会返回它，先查凭据本身
                credentialsRejected = true;
                Logu.w("third-danmaku", "凭据被服务端拒绝（HTTP " + response.code
                        + "），本次运行不再请求弹幕；请核对开放平台 AppId/Secret");
            }
            if (!response.isSuccessful())
                throw SourceException.http("弹弹play", SourceException.Phase.EPISODE, response.code);
            return response.text();
        } catch (SecurityException e) {
            throw SourceException.config("弹弹play", "地址不合法：" + e.getMessage());
        } catch (java.io.IOException e) {
            throw new SourceException(SourceException.Phase.EPISODE, SourceException.Kind.NETWORK,
                    "弹弹play", "网络失败（" + phase + "）：" + e.getClass().getSimpleName(), e);
        }
    }

    /** Base64(SHA256(appId + timestamp + path + appSecret)) */
    static String signature(String appId, long timestamp, String path, String secret) {
        String data = appId + timestamp + path + secret;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes("UTF-8"));
            return android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            Logu.e("third-danmaku", "签名失败：" + e.getMessage());
            return "";
        }
    }

    /** 集标题形如「第12话 真正的勇者」/「第2集」；解析不出来返回 0 */
    private static final Pattern EPISODE_NUMBER = Pattern.compile("第\\s*(\\d+)\\s*[话話集]");

    static int parseEpisodeNumber(String episodeTitle) {
        if (episodeTitle == null) return 0;
        Matcher matcher = EPISODE_NUMBER.matcher(episodeTitle);
        if (!matcher.find()) return 0;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double parseDouble(String text) {
        try {
            return Double.parseDouble(text.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String encode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }
}
