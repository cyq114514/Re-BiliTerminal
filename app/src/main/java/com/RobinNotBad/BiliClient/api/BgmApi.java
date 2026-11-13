package com.RobinNotBad.BiliClient.api;

import android.net.Uri;
import android.os.Build;

import com.RobinNotBad.BiliClient.BuildConfig;
import com.RobinNotBad.BiliClient.model.bgm.BgmSubject;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Bangumi 元数据 API（只读公开数据）。
 *
 * <p><b>为什么必须自建 OkHttpClient</b>：{@link NetWorkUtil} 的全局客户端会把 B 站 Cookie /
 * Origin / Referer 注入每个请求，且关闭了重定向跟随并只允许跳回 B 站域。拿它请求 api.bgm.tv
 * 等于把 SESSDATA 交给第三方，所以这里一切从零构建：不带 Cookie、只走 https、独立 UA。
 *
 * <p><b>接口源</b>：国内网络普遍无法直连 api.bgm.tv，主通道换成带 Basic 鉴权的反代
 * {@link #BASE_PROXY}（文档：Bangumi 反代 · 接入说明）。反代要求三个头——{@code Authorization}、
 * 以 {@code ReBiliClient/} 开头的 {@code User-Agent}、{@code X-Rebili-Device}——统一由网络拦截器
 * 加（见 {@link #client()}）。凭据编进 {@code BuildConfig.BGM_PROXY_AUTH}（local.properties /
 * CI secret 注入，不入库）：为空时反代不进候选，只走 api.bgm.tv 直连。
 *
 * <p>逐个候选真实请求，第一个成功者被记住 24 小时（见 {@link #candidateBases()}）。
 *
 * <p><b>封面图不走反代</b>：反代的 {@code /lain/*} 图片路由实测 404（302 跳过去是 nginx 404 页），
 * 图片链路维持 wsrv.nl 代理（见 {@link #imageFor(String, int)}），与接口源解耦。
 */
public class BgmApi {

    /** 反代入口：只开放 8443（HTTPS/HTTP2），域名证书正常。文档给的 IP 兜底（证书 6 天）未接入 */
    private static final String BASE_PROXY = "https://bangumi.moe.zsapp.asia:8443/basic";
    private static final String PROXY_HOST = "bangumi.moe.zsapp.asia";
    private static final int PROXY_PORT = 8443;
    private static final String BASE_DIRECT = "https://api.bgm.tv";
    /** 仅用于热门榜单（反代没有榜单接口）；通用 /v0 路径在它上面会 401 */
    private static final String BASE_KAZUMI = "https://api.kazumi.fyi";
    /** 反代按设备去重统计的标识：一台设备一个固定值，只存摘要，内容随意 */
    private static final String DEVICE_ID_KEY = "bgm_device_id";

    /** 服务端把 search 的 limit 硬钳在 20（请求 100 也只回 20），分页必须按 20 走 */
    public static final int PAGE_SIZE = 20;
    /** 单季全量上限：一季约 170 条，9 页；留余量到 12 页防接口行为变化后死循环 */
    private static final int MAX_PAGES = 12;
    private static final long MAX_BODY_BYTES = 2L * 1024 * 1024;
    private static final String ACTIVE_BASE_KEY = "active_base";
    private static final long BASE_TTL_MS = 24L * 60 * 60 * 1000;

    public static final long TTL_SEASON = 6L * 60 * 60 * 1000;
    public static final long TTL_SUBJECT = 24L * 60 * 60 * 1000;
    public static final long TTL_CALENDAR = 6L * 60 * 60 * 1000;

    private static final Object CLIENT_LOCK = new Object();
    private static volatile OkHttpClient client;
    private static volatile String activeBase;
    private static volatile String deviceIdCache;

    private BgmApi() {
    }

    // ==================== 网络底座 ====================

    /**
     * 反代对 JSON 接口做 UA 白名单：必须以 {@code ReBiliClient/} 开头（区分大小写），否则 403。
     * 同一个 UA 对 api.bgm.tv 与 kazumi 同样合法，没必要按域名分支。
     */
    private static String userAgent() {
        return "ReBiliClient/" + BuildConfig.VERSION_NAME
                + " (ReBiliTerminal; Android " + Build.VERSION.SDK_INT + ")";
    }

    /** 反代凭证：完整请求头值（含 "Basic " 前缀）；空字符串 = 未配置，反代不可用 */
    private static String proxyAuth() {
        String auth = BuildConfig.BGM_PROXY_AUTH;
        return auth == null ? "" : auth.trim();
    }

    public static boolean isProxyConfigured() {
        return !proxyAuth().isEmpty();
    }

    private static boolean isProxyUrl(HttpUrl url) {
        return url != null && url.isHttps() && url.port() == PROXY_PORT
                && PROXY_HOST.equalsIgnoreCase(url.host());
    }

    /**
     * 设备标识：一台设备固定一个 UUID。
     *
     * <p>为什么存主配置而不是 {@link BgmCache}：BgmCache 有按写入时间淘汰季度键的逻辑，
     * 设备标识被顺手淘汰就会让服务端把同一台设备算成新设备。
     * 取不到存储时退回进程内随机值——只影响“有多少台设备”这项统计，不影响功能。
     */
    private static String deviceId() {
        String cached = deviceIdCache;
        if (cached != null) return cached;
        synchronized (CLIENT_LOCK) {
            if (deviceIdCache == null) {
                String value = null;
                try {
                    value = SharedPreferencesUtil.getString(DEVICE_ID_KEY, null);
                } catch (Throwable ignored) {
                }
                if (value == null || value.isEmpty()) {
                    value = UUID.randomUUID().toString();
                    try {
                        SharedPreferencesUtil.putString(DEVICE_ID_KEY, value);
                    } catch (Throwable ignored) {
                    }
                }
                deviceIdCache = value;
            }
            return deviceIdCache;
        }
    }

    private static OkHttpClient client() {
        if (client != null) return client;
        synchronized (CLIENT_LOCK) {
            if (client == null) {
                client = NetWorkUtil.setOkHttpSsl(new OkHttpClient.Builder())
                        //跟随重定向有风险（反代可能把人带到任意域），但取封面确实需要跟一跳
                        //同 host 的 https 302；凭据只在网络拦截器里按目标 host 精确附加，跟到外域也不会带出去
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .retryOnConnectionFailure(true)
                        //反代三件套必须挂网络拦截器：应用拦截器只跑第一次请求，302 之后的第二跳
                        //收不到头（反代文档第 6 节专门警告的坑）；网络拦截器对每一跳都执行
                        .addNetworkInterceptor(chain -> {
                            Request request = chain.request();
                            if (!isProxyUrl(request.url())) return chain.proceed(request);
                            Request.Builder builder = request.newBuilder()
                                    .header("User-Agent", userAgent())
                                    .header("X-Rebili-Device", deviceId());
                            String auth = proxyAuth();
                            if (!auth.isEmpty()) builder.header("Authorization", auth);
                            return chain.proceed(builder.build());
                        })
                        .connectTimeout(6, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .dns(new NetWorkUtil.Inet4Selector())
                        .build();
            }
            return client;
        }
    }

    /**
     * 候选 base，按「最可能可用」排序。
     *
     * <p>顺序理由：目标用户在国内，api.bgm.tv 既被 DNS 污染又被限流，所以反代排前面；
     * 直连垫底（海外用户/开代理的用户命中它）。反代凭据没注入时不把反代放进候选——
     * 放进去只会白打一次 401。用户显式指定时只试那一个，
     * 避免「设置成直连却偷偷走反代」这种不可预期的行为。
     */
    private static List<String> candidateBases() {
        List<String> bases = new ArrayList<>();
        String mode = SharedPreferencesUtil.getString(SharedPreferencesUtil.BGM_BASE_MODE, "auto");
        if (!"auto".equals(mode)) {
            bases.add(toBase(mode));
            return bases;
        }

        String remembered = activeBase;
        if (remembered == null) remembered = BgmCache.get(ACTIVE_BASE_KEY);
        if (remembered != null && BgmCache.isFresh(ACTIVE_BASE_KEY, BASE_TTL_MS)
                && isKnownBase(remembered)) {
            bases.add(remembered);
        }
        //kazumi 不进通用候选：它的 /v0/* 需要自己的 AppID 签名（实测 401），只有
        ///kazumi/v1/popular/subjects 免签可用，热门榜仍固定用它，见 getPopular()
        if (isProxyConfigured() && !bases.contains(BASE_PROXY)) bases.add(BASE_PROXY);
        if (!bases.contains(BASE_DIRECT)) bases.add(BASE_DIRECT);
        return bases;
    }

    private static boolean isKnownBase(String base) {
        //只认当前两套。历史版本把 api.bgmapi.com / api.kazumi.fyi 记进过 active_base，
        //升级后必须判为无效，否则 24 小时内还会继续往已弃用的镜像打请求
        return BASE_PROXY.equals(base) || BASE_DIRECT.equals(base);
    }

    private static String toBase(String mode) {
        switch (mode) {
            case "direct":
                return BASE_DIRECT;
            case "proxy":
            case "bgmapi":
            case "kazumi":
            default:
                //旧版本的 bgmapi/kazumi 模式值一并映射到反代；凭据缺失时反代必然 401，
                //此时退回直连，而不是让用户看到整页错误
                return isProxyConfigured() ? BASE_PROXY : BASE_DIRECT;
        }
    }

    /** 当前实际生效的 base；null 表示还没成功过任何一次请求 */
    public static String getActiveBase() {
        String base = activeBase;
        if (base == null) base = BgmCache.get(ACTIVE_BASE_KEY);
        return isKnownBase(base) ? base : null;
    }

    public static boolean isUsingMirror() {
        String base = getActiveBase();
        return base != null && !BASE_DIRECT.equals(base);
    }

    private static String request(String pathAndQuery, String method, String jsonBody)
            throws IOException {
        List<String> bases = candidateBases();
        IOException last = null;
        StringBuilder tried = new StringBuilder();

        for (String base : bases) {
            String url = base + pathAndQuery;
            try {
                String body = execute(url, method, jsonBody);
                if (!base.equals(activeBase)) {
                    activeBase = base;
                    BgmCache.put(ACTIVE_BASE_KEY, base);
                    //用 W 级：V/D/I 在正式包里被开关关掉，只有 W/E 一定进 logcat，
                    //否则用户真机出问题时无法确认到底走了哪个接口源
                    Logu.w("bgm-api", "接口源=" + base);
                }
                return body;
            } catch (IOException e) {
                last = e;
                if (tried.length() > 0) tried.append(" | ");
                tried.append(hostOf(base)).append(':').append(e.getMessage());
            } catch (Throwable t) {
                last = new IOException(t.getMessage(), t);
                if (tried.length() > 0) tried.append(" | ");
                tried.append(hostOf(base)).append(':').append(t.getClass().getSimpleName());
            }
        }
        Logu.e("bgm-api", "全部接口源失败：" + tried);
        throw new IOException("Bangumi 接口不可用（" + tried + "）", last);
    }

    private static String hostOf(String base) {
        try {
            return Uri.parse(base).getHost();
        } catch (Throwable t) {
            return base;
        }
    }

    private static Request buildBgmRequest(String url, String method, String jsonBody) {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("User-Agent", userAgent())
                .header("Accept", "application/json")
                //绝不带 Cookie：这是第三方域名，且本接口不需要登录态
                .header("Cache-Control", "no-store");

        if ("POST".equals(method)) {
            RequestBody body = RequestBody.create(
                    MediaType.parse("application/json; charset=utf-8"),
                    jsonBody == null ? "{}" : jsonBody);
            builder.post(body);
        } else {
            builder.get();
        }
        return builder.build();
    }

    private static String execute(String url, String method, String jsonBody) throws IOException {
        Response response = null;
        try {
            response = client().newCall(buildBgmRequest(url, method, jsonBody)).execute();
            if (response.code() == 429) {
                //反代按 IP 限流（JSON 8 请求/秒，突发 20），文档要求退避重试。
                //只退避一次：全部 Bangumi 接口都是只读请求，重发安全；再失败就交给上层换源
                response.close();
                response = null;
                try {
                    Thread.sleep(800);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                response = client().newCall(buildBgmRequest(url, method, jsonBody)).execute();
            }
            int code = response.code();
            //反代把两种拒绝分得很清楚：先判 UA（403），再判凭证（401）。
            //文案写进异常是为了让真机 logcat 一眼看出该改 UA 还是该换密钥
            if (code == 401) throw new IOException("HTTP 401（反代凭证缺失或已失效）");
            if (code == 403) throw new IOException("HTTP 403（User-Agent 未通过反代白名单）");
            if (code != 200) throw new IOException("HTTP " + code);

            ResponseBody body = response.body();
            if (body == null) throw new IOException("空响应");
            long declared = body.contentLength();
            if (declared > MAX_BODY_BYTES) throw new IOException("响应过大 " + declared);

            String text = body.string();
            if (text == null || text.isEmpty()) throw new IOException("空响应体");
            if (text.length() > MAX_BODY_BYTES) throw new IOException("响应过大 " + text.length());
            return text;
        } finally {
            if (response != null) response.close();
        }
    }

    // ==================== 季度计算 ====================

    /** @return {year, quarter}，quarter ∈ 1..4，按自然季度（1-3/4-6/7-9/10-12） */
    public static int[] currentSeason() {
        Calendar calendar = Calendar.getInstance();
        int month = calendar.get(Calendar.MONTH) + 1;
        return new int[]{calendar.get(Calendar.YEAR), (month - 1) / 3 + 1};
    }

    public static int[] shiftSeason(int year, int quarter, int delta) {
        int index = year * 4 + (quarter - 1) + delta;
        return new int[]{index / 4, index % 4 + 1};
    }

    /** 季度起止（左闭右开），用于 air_date 过滤 */
    public static String[] seasonRange(int year, int quarter) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, (quarter - 1) * 3, 1);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String start = format.format(calendar.getTime());
        calendar.add(Calendar.MONTH, 3);
        return new String[]{start, format.format(calendar.getTime())};
    }

    /** 中文季度名：1 月=冬、4 月=春、7 月=夏、10 月=秋 */
    public static String seasonLabel(int year, int quarter) {
        String season;
        switch (quarter) {
            case 1:
                season = "冬";
                break;
            case 2:
                season = "春";
                break;
            case 3:
                season = "夏";
                break;
            default:
                season = "秋";
                break;
        }
        return year + "年" + season + "季";
    }

    // ==================== 关键词搜索 ====================

    /**
     * 按关键词搜 Bangumi 番剧条目（type=2）。
     *
     * <p>为什么第三方源入口要先过 Bangumi：站点搜索结果没有封面、标题脏（「第X季 1080P」），
     * 直接展示既难看也难判断匹配；Bangumi 提供封面 + 规范标题 + 别名，正好当「元数据层」，
     * 用户点卡片后再去各站点嗅探片源（与 RE 的「Bangumi 卡片 → 私有侧选源」一致）。
     *
     * <p>服务端 limit 被钳在 20（见 {@link #PAGE_SIZE}），按 offset 翻页。
     */
    public static List<BgmSubject> searchSubjects(String keyword, int offset, boolean allowCache)
            throws IOException {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) return new ArrayList<>();
        String cacheKey = "search_" + Integer.toHexString(kw.hashCode()) + "_o" + offset;
        if (allowCache && BgmCache.isFresh(cacheKey, TTL_SUBJECT)) {
            String cached = BgmCache.get(cacheKey);
            if (cached != null) {
                try {
                    return parseSubjectArray(new JSONArray(cached));
                } catch (JSONException ignored) {
                    //缓存坏了继续走网络
                }
            }
        }
        try {
            JSONObject filter = new JSONObject();
            filter.put("type", new JSONArray().put(2));
            filter.put("nsfw", false);
            JSONObject body = new JSONObject();
            body.put("keyword", kw);
            body.put("sort", "match");
            body.put("filter", filter);

            String text = request("/v0/search/subjects?limit=" + PAGE_SIZE + "&offset=" + offset,
                    "POST", body.toString());
            JSONObject root = new JSONObject(text);
            JSONArray data = root.optJSONArray("data");
            List<BgmSubject> list = new ArrayList<>();
            if (data != null) {
                for (int i = 0; i < data.length(); i++) {
                    JSONObject item = data.optJSONObject(i);
                    if (item == null) continue;
                    list.add(parseSubject(item, false));
                }
            }
            if (allowCache && !list.isEmpty()) {
                BgmCache.put(cacheKey, listToJson(list));
            }
            return list;
        } catch (JSONException e) {
            throw new IOException("Bangumi 搜索参数构造失败", e);
        }
    }

    // ==================== 季度列表 ====================

    public static class SeasonPage {
        public final List<BgmSubject> list = new ArrayList<>();
        public int total = 0;
        public int offset = 0;
        public boolean hasMore = false;
    }

    private static String seasonCacheKey(int year, int quarter, int offset) {
        return "season_" + year + "Q" + quarter + "_o" + offset;
    }

    public static String seasonFullCacheKey(int year, int quarter) {
        return "season_" + year + "Q" + quarter + "_full";
    }

    /**
     * 拉取某季度的一页。
     *
     * @param offset 从 0 开始，步长 {@link #PAGE_SIZE}
     */
    public static SeasonPage getSeasonPage(int year, int quarter, int offset, boolean allowCache)
            throws IOException {
        if (allowCache) {
            String cached = BgmCache.get(seasonCacheKey(year, quarter, offset));
            if (cached != null) {
                try {
                    return parseSeasonPage(cached, offset);
                } catch (JSONException ignored) {
                    //缓存坏了就当没有，继续走网络
                }
            }
        }

        String[] range = seasonRange(year, quarter);
        JSONObject filter = new JSONObject();
        try {
            filter.put("type", new JSONArray().put(2));
            filter.put("air_date", new JSONArray().put(">=" + range[0]).put("<" + range[1]));
            //NSFW 条目默认不展示（手表端场景不适合），需要时再放开
            filter.put("nsfw", false);

            JSONObject body = new JSONObject();
            body.put("keyword", "");
            body.put("sort", "rank");
            body.put("filter", filter);

            String path = "/v0/search/subjects?limit=" + PAGE_SIZE + "&offset=" + offset;
            String text = request(path, "POST", body.toString());
            SeasonPage page = parseSeasonPage(text, offset);
            BgmCache.put(seasonCacheKey(year, quarter, offset), text);
            return page;
        } catch (JSONException e) {
            throw new IOException("Bangumi 请求参数构造失败", e);
        }
    }

    private static SeasonPage parseSeasonPage(String text, int offset) throws JSONException {
        JSONObject root = new JSONObject(text);
        SeasonPage page = new SeasonPage();
        page.offset = offset;
        page.total = root.optInt("total", 0);

        JSONArray data = root.optJSONArray("data");
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.optJSONObject(i);
                if (item == null) continue;
                page.list.add(parseSubject(item, false));
            }
        }
        page.hasMore = data != null && data.length() >= PAGE_SIZE
                && (page.total <= 0 || offset + PAGE_SIZE < page.total);
        return page;
    }

    /** 读整季缓存（忽略 TTL）：用于接口不可用/未联网时先把上次数据上屏 */
    public static List<BgmSubject> readCachedSeason(int year, int quarter) {
        String cached = BgmCache.get(seasonFullCacheKey(year, quarter));
        if (cached == null) return new ArrayList<>();
        try {
            return parseSubjectArray(new JSONArray(cached));
        } catch (JSONException e) {
            return new ArrayList<>();
        }
    }

    /**
     * 拉整季（翻页），供「按开播日期/评分/名称」本地排序与星期筛选使用。
     *
     * <p>为什么整季拉：季度只有 ~170 条，但服务端 sort 只支持 match/heat/rank/score，
     * 不支持按开播日期。要满足「默认按开播日期」且分页不串序，只能本地排序。
     * 12 页 × 300ms 间隔 ≈ 4 秒，只在后台做，且缓存 6 小时。
     *
     * @param forceRefresh 下拉刷新时传 true：跳过缓存逐页重取
     * @param cancelled    由调用方提供，返回 true 时中断（切季度/退出页面）
     */
    public static List<BgmSubject> getSeasonAll(int year, int quarter, boolean forceRefresh,
                                                CancelSignal cancelled, Progress progress)
            throws IOException {
        String fullKey = seasonFullCacheKey(year, quarter);
        if (!forceRefresh && BgmCache.isFresh(fullKey, TTL_SEASON)) {
            List<BgmSubject> list = readCachedSeason(year, quarter);
            if (!list.isEmpty()) return list;
        }

        List<BgmSubject> all = new ArrayList<>();
        int offset = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            if (cancelled != null && cancelled.isCancelled()) break;
            SeasonPage result = getSeasonPage(year, quarter, offset, !forceRefresh);
            all.addAll(result.list);
            if (progress != null) progress.onProgress(all.size(), result.total);
            if (!result.hasMore || result.list.isEmpty()) break;
            offset += PAGE_SIZE;
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (all.isEmpty()) throw new IOException("该季度没有取到番剧数据");

        JSONArray array = new JSONArray();
        for (BgmSubject subject : all) array.put(toJson(subject));
        BgmCache.put(fullKey, array.toString());
        return all;
    }

    public interface CancelSignal {
        boolean isCancelled();
    }

    public interface Progress {
        void onProgress(int loaded, int total);
    }

    // ==================== 详情 ====================

    public static BgmSubject getSubject(long id, boolean allowCache) throws IOException {
        String key = "subject_" + id;
        if (allowCache) {
            String cached = BgmCache.get(key);
            if (cached != null) {
                try {
                    return parseSubject(new JSONObject(cached), true);
                } catch (JSONException ignored) {
                }
            }
        }
        String text = request("/v0/subjects/" + id, "GET", null);
        try {
            BgmSubject subject = parseSubject(new JSONObject(text), true);
            BgmCache.put(key, text);
            return subject;
        } catch (JSONException e) {
            throw new IOException("Bangumi 条目解析失败", e);
        }
    }

    // ==================== 每日放送（星期归属） ====================

    /**
     * 取「本周在播」条目的放送星期映射：subjectId → 1..7。
     *
     * <p>为什么不用开播日期推算：新番开播日与固定放送星期并不等价（首播可能是特番），
     * 需求明确禁止把未知/不确定的条目塞进某个星期。calendar 是官方给出的唯一权威来源。
     *
     * <p><b>为什么优先 /p1/calendar</b>：反代只转发 {@code /v0}、{@code /p1}、{@code /lain} 三类路径，
     * 官方的老 {@code /calendar} 不在保护范围内，实测直接断连（curl 返回 000）。p1 的同名接口
     * 返回结构不同（{@code {"1":[{"subject":{"id":…},"watchers":…}]}}），所以这里按响应首字符
     * 自动判形：{@code {} → p1}、{@code [] → 老版}。直连 api.bgm.tv 时 p1 会 404，自动退回老接口。
     */
    public static Map<Long, Integer> getCalendarWeekdays(boolean allowCache) throws IOException {
        String key = "calendar_weekday";
        String text = null;
        if (allowCache && BgmCache.isFresh(key, TTL_CALENDAR)) text = BgmCache.get(key);

        if (text == null) {
            try {
                text = request("/p1/calendar", "GET", null);
            } catch (IOException p1Error) {
                Logu.w("bgm-api", "p1/calendar 不可用，退回 /calendar：" + p1Error.getMessage());
                text = request("/calendar", "GET", null);
            }
            BgmCache.put(key, text);
        }

        Map<Long, Integer> map = new HashMap<>();
        try {
            String trimmed = text.trim();
            if (trimmed.startsWith("{")) parseCalendarP1(new JSONObject(trimmed), map);
            else parseCalendarLegacy(new JSONArray(trimmed), map);
        } catch (JSONException e) {
            throw new IOException("Bangumi 放送表解析失败", e);
        }
        return map;
    }

    /** p1 结构：{"1":[{subject:{id},watchers}], …, "7":[…]}，键就是放送星期 */
    private static void parseCalendarP1(JSONObject root, Map<Long, Integer> map) {
        Iterator<String> keys = root.keys();
        while (keys.hasNext()) {
            String weekdayKey = keys.next();
            int weekday;
            try {
                weekday = Integer.parseInt(weekdayKey);
            } catch (NumberFormatException e) {
                continue;
            }
            if (weekday < 1 || weekday > 7) continue;
            JSONArray items = root.optJSONArray(weekdayKey);
            if (items == null) continue;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                JSONObject subject = item.optJSONObject("subject");
                long id = subject == null ? 0 : subject.optLong("id", 0);
                if (id > 0) map.put(id, weekday);
            }
        }
    }

    /** 老结构：[{weekday:{id},items:[{id}]}] */
    private static void parseCalendarLegacy(JSONArray days, Map<Long, Integer> map) {
        for (int i = 0; i < days.length(); i++) {
            JSONObject day = days.optJSONObject(i);
            if (day == null) continue;
            JSONObject weekday = day.optJSONObject("weekday");
            int id = weekday == null ? 0 : weekday.optInt("id", 0);
            if (id < 1 || id > 7) continue;
            JSONArray items = day.optJSONArray("items");
            if (items == null) continue;
            for (int j = 0; j < items.length(); j++) {
                JSONObject item = items.optJSONObject(j);
                if (item == null) continue;
                map.put(item.optLong("id", 0), id);
            }
        }
    }

    // ==================== 热门番组 ====================

    /** 热门榜单单页条数：3 列布局下 60 条≈20 行，滑动两屏才触发续拉 */
    public static final int POPULAR_PAGE_SIZE = 60;
    private static final String POPULAR_PATH = "/kazumi/v1/popular/subjects";

    /**
     * 热门番组（对标 Kazumi 的「热门番组」页）。
     *
     * <p>数据源两级：
     * ①Kazumi 镜像的榜单接口 {@code /kazumi/v1/popular/subjects}（实测 200，支持 limit/offset，
     *   字段为驼峰 nameCN/metaTags，platform 是对象）。反代只转发 /v0、/p1、/lain，没有榜单接口，
     *   所以这一级保持原样，不随接口源切换而变；
     * ②Kazumi 不可用时退回 v0 搜索按排名取（{@code sort=rank} + tag=日本，走当前接口源）。
     * 第二条只是兜底，条数受服务端 limit=20 钳制，所以一次最多 20 条。
     */
    public static List<BgmSubject> getPopular(int limit, int offset) throws IOException {
        try {
            String text = requestAbsolute(BASE_KAZUMI + POPULAR_PATH
                    + "?limit=" + limit + "&offset=" + offset);
            List<BgmSubject> list = parsePopular(text);
            if (!list.isEmpty()) return list;
        } catch (IOException e) {
            Logu.w("bgm-api", "热门榜单镜像失败：" + e.getMessage());
        }
        return searchByRank(limit, offset);
    }

    private static List<BgmSubject> searchByRank(int limit, int offset) throws IOException {
        int pageSize = Math.min(limit, PAGE_SIZE);
        try {
            JSONObject filter = new JSONObject();
            filter.put("type", new JSONArray().put(2));
            filter.put("tag", new JSONArray().put("日本"));
            //排名区间与 Kazumi 官方同款：跳过 rank<=1 的极端条目，避免首页被同几部霸榜
            filter.put("rank", new JSONArray().put(">1").put("<=3000"));
            filter.put("nsfw", false);

            JSONObject body = new JSONObject();
            body.put("keyword", "");
            body.put("sort", "rank");
            body.put("filter", filter);

            String text = request("/v0/search/subjects?limit=" + pageSize + "&offset=" + offset,
                    "POST", body.toString());
            JSONArray data = new JSONObject(text).optJSONArray("data");
            return data == null ? new ArrayList<>() : parseSubjectArray(data);
        } catch (JSONException e) {
            throw new IOException("热门兜底请求构造失败", e);
        }
    }

    /** 镜像榜单响应：可能是 {data:[...]}，也可能直接是数组 */
    private static List<BgmSubject> parsePopular(String text) throws IOException {
        List<BgmSubject> list = new ArrayList<>();
        try {
            JSONArray data;
            String trimmed = text.trim();
            if (trimmed.startsWith("[")) {
                data = new JSONArray(trimmed);
            } else {
                JSONObject root = new JSONObject(trimmed);
                data = root.optJSONArray("data");
            }
            if (data == null) throw new IOException("热门榜单结构异常");

            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.optJSONObject(i);
                if (item == null) continue;
                BgmSubject subject = new BgmSubject();
                subject.id = item.optLong("id", 0);
                if (subject.id <= 0) continue;
                subject.name = item.optString("name", "");
                //镜像用驼峰 nameCN，官方用 name_cn，两个都认
                subject.nameCn = item.optString("nameCN", item.optString("name_cn", ""));
                subject.nsfw = item.optBoolean("nsfw", false);
                if (subject.nsfw) continue;

                JSONObject images = item.optJSONObject("images");
                if (images != null) {
                    subject.coverGrid = normalizeImage(images.optString("grid", ""), subject.id, "grid");
                    subject.coverCommon = normalizeImage(images.optString("common", ""), subject.id, "common");
                    subject.coverLarge = normalizeImage(images.optString("large", ""), subject.id, "large");
                }

                JSONObject rating = item.optJSONObject("rating");
                if (rating != null) {
                    subject.score = rating.optDouble("score", 0);
                    subject.rank = rating.optInt("rank", 0);
                    subject.ratingTotal = rating.optInt("total", 0);
                }

                JSONObject platform = item.optJSONObject("platform");
                if (platform != null) subject.platform = platform.optString("typeCN",
                        platform.optString("type", ""));
                else subject.platform = item.optString("platform", "");

                JSONArray tags = item.optJSONArray("metaTags");
                if (tags != null) {
                    for (int j = 0; j < tags.length() && subject.tags.size() < 12; j++) {
                        String tag = tags.optString(j, "");
                        if (!tag.isEmpty()) subject.tags.add(tag);
                    }
                }
                list.add(subject);
            }
        } catch (JSONException e) {
            throw new IOException("热门榜单解析失败", e);
        }
        return list;
    }

    private static String requestAbsolute(String url) throws IOException {
        return execute(url, "GET", null);
    }

    /**
     * 图片地址归一化：只做 http→https 与空值兜底，<b>不套代理</b>。
     *
     * <p>为什么不在解析时就套 wsrv：解析结果会写进本地缓存，读缓存时又要走一遍解析，
     * 于是"代理套代理"（wsrv?url=wsrv?url=…）——第二轮拿不到原图，只能出裂图或缩略图。
     * 代理必须留到真正加载的那一刻，并且要带上目标宽度。
     */
    private static String normalizeImage(String raw, long subjectId, String kind) {
        String url = raw == null ? "" : raw.trim();
        if (url.isEmpty()) {
            if (subjectId > 0) url = BASE_DIRECT + "/v0/subjects/" + subjectId + "/image?type=" + kind;
            else return "";
        }
        if (url.startsWith("http://")) url = "https://" + url.substring("http://".length());
        return url;
    }

    /**
     * 加载用地址：按目标显示宽度向图片代理取图。
     *
     * <p>Bangumi 的 images 是固定档位（grid 100px / common 400px / large 原图），
     * 卡片宽度却随列数变化：拿 100px 放大必糊，直接下原图又浪费流量。
     * 这里统一按像素宽请求（wsrv 会自动缩放到 w 指定的宽度）。
     * 用户关掉图片代理时原样返回，不破坏地址。
     *
     * <p><b>为什么不用反代取图</b>：反代 {@code /basic/v0/…/image} 会 302 到
     * {@code /basic/lain/…}，但该路由实测是 nginx 404（2026-11-14 用同一组请求头复测），
     * 直接切过去会全站裂图。图片链路继续走 wsrv，与接口源解耦；等反代补上 /lain 再改这一处。
     */
    public static String imageFor(String raw, int widthPx) {
        String url = raw == null ? "" : raw.trim();
        if (url.isEmpty()) return "";
        if (url.startsWith("http://")) url = "https://" + url.substring("http://".length());
        //已经是代理地址就不要再包一层（幂等）
        if (url.startsWith("https://wsrv.nl/?")) return url;
        if (!SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.BGM_IMAGE_PROXY, true)) return url;

        String source = url.startsWith("https://") ? url.substring("https://".length()) : url;
        StringBuilder builder = new StringBuilder("https://wsrv.nl/?url=")
                .append(source.replace("&", "%26"));
        if (widthPx > 0) builder.append("&w=").append(widthPx).append("&output=webp&q=88");
        return builder.toString();
    }

    /** 热门榜缓存：6 小时内不重复请求，超时才后台刷新（榜单不会分钟级变化） */
    public static final long TTL_POPULAR = 6L * 60 * 60 * 1000;

    public static String listToJson(List<BgmSubject> list) {
        JSONArray array = new JSONArray();
        for (BgmSubject subject : list) array.put(toJson(subject));
        return array.toString();
    }

    public static List<BgmSubject> parseCachedList(String json) {
        if (json == null || json.isEmpty()) return new ArrayList<>();
        try {
            return parseSubjectArray(new JSONArray(json));
        } catch (JSONException e) {
            return new ArrayList<>();
        }
    }

    // ==================== 解析 ====================

    private static BgmSubject parseSubject(JSONObject item, boolean detail) {
        BgmSubject subject = new BgmSubject();
        subject.id = item.optLong("id", 0);
        subject.name = item.optString("name", "");
        subject.nameCn = item.optString("name_cn", "");
        subject.summary = item.optString("summary", "");
        subject.date = item.optString("date", "");
        subject.platform = item.optString("platform", "");
        subject.nsfw = item.optBoolean("nsfw", false);
        subject.totalEpisodes = item.optInt("total_episodes", 0);

        JSONObject images = item.optJSONObject("images");
        if (images != null) {
            subject.coverGrid = normalizeImage(images.optString("grid", ""), subject.id, "grid");
            subject.coverCommon = normalizeImage(images.optString("common", ""), subject.id, "common");
            subject.coverLarge = normalizeImage(images.optString("large", ""), subject.id, "large");
        }

        JSONObject rating = item.optJSONObject("rating");
        if (rating != null) {
            subject.score = rating.optDouble("score", 0);
            subject.rank = rating.optInt("rank", 0);
            subject.ratingTotal = rating.optInt("total", 0);
        }

        subject.airWeekday = item.optInt("air_weekday", 0);
        if (subject.airWeekday < 1 || subject.airWeekday > 7) subject.airWeekday = 0;

        JSONArray tags = item.optJSONArray("tags");
        if (tags != null) {
            for (int i = 0; i < tags.length() && i < 12; i++) {
                JSONObject tag = tags.optJSONObject(i);
                if (tag == null) continue;
                String name = tag.optString("name", "");
                if (!name.isEmpty()) subject.tags.add(name);
            }
        }

        if (detail) parseInfobox(item.optJSONArray("infobox"), subject);
        return subject;
    }

    /** 详情 infobox：放送星期是 air_weekday 缺失时的唯一可信补充来源 */
    private static void parseInfobox(JSONArray infobox, BgmSubject subject) {
        if (infobox == null) return;
        for (int i = 0; i < infobox.length(); i++) {
            JSONObject entry = infobox.optJSONObject(i);
            if (entry == null) continue;
            String key = entry.optString("key", "");
            Object value = entry.opt("value");

            if ("放送星期".equals(key) && subject.airWeekday == 0) {
                subject.airWeekday = parseWeekday(String.valueOf(value));
            } else if ("别名".equals(key) && value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                for (int j = 0; j < array.length() && subject.aliases.size() < 8; j++) {
                    JSONObject alias = array.optJSONObject(j);
                    String text = alias == null ? "" : alias.optString("v", "");
                    if (!text.isEmpty()) subject.aliases.add(text);
                }
            }
        }
    }

    /** "星期五" / "每周五" / "周五" → 5；识别不出返回 0 */
    public static int parseWeekday(String text) {
        if (text == null) return 0;
        String value = text.trim();
        if (value.isEmpty()) return 0;
        String[] names = {"一", "二", "三", "四", "五", "六", "日"};
        for (int i = 0; i < names.length; i++) {
            if (value.contains(names[i])) return i + 1;
        }
        if (value.contains("天")) return 7;
        return 0;
    }

    // ==================== 排序 ====================

    public static final int SORT_DATE = 0;
    public static final int SORT_RANK = 1;
    public static final int SORT_NAME = 2;

    /** 默认按开播日期（早→晚），无日期的排末尾：季初先播的番先看到，符合发现新番的顺序 */
    public static void sort(List<BgmSubject> list, final int mode) {
        if (list == null || list.size() < 2) return;
        Collections.sort(list, new Comparator<BgmSubject>() {
            @Override
            public int compare(BgmSubject a, BgmSubject b) {
                switch (mode) {
                    case SORT_RANK: {
                        int rankA = a.rank > 0 ? a.rank : Integer.MAX_VALUE;
                        int rankB = b.rank > 0 ? b.rank : Integer.MAX_VALUE;
                        if (rankA != rankB) return Integer.compare(rankA, rankB);
                        return compareDate(a, b);
                    }
                    case SORT_NAME:
                        return a.title().compareToIgnoreCase(b.title());
                    default:
                        return compareDate(a, b);
                }
            }
        });
    }

    private static int compareDate(BgmSubject a, BgmSubject b) {
        boolean emptyA = a.date == null || a.date.isEmpty();
        boolean emptyB = b.date == null || b.date.isEmpty();
        if (emptyA && emptyB) return a.title().compareToIgnoreCase(b.title());
        if (emptyA) return 1;
        if (emptyB) return -1;
        int result = a.date.compareTo(b.date);
        return result != 0 ? result : a.title().compareToIgnoreCase(b.title());
    }

    /** 按星期筛选；weekday=0 表示「全部」。未知放送星期的条目只在「全部」出现 */
    public static List<BgmSubject> filterByWeekday(List<BgmSubject> source, int weekday) {
        if (source == null) return new ArrayList<>();
        if (weekday < 1 || weekday > 7) return source;
        List<BgmSubject> result = new ArrayList<>();
        for (BgmSubject subject : source) {
            if (subject.airWeekday == weekday) result.add(subject);
        }
        return result;
    }

    // ==================== 序列化（整季缓存用） ====================

    private static List<BgmSubject> parseSubjectArray(JSONArray array) {
        List<BgmSubject> list = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null) list.add(parseSubject(item, false));
        }
        return list;
    }

    private static JSONObject toJson(BgmSubject subject) {
        JSONObject item = new JSONObject();
        try {
            item.put("id", subject.id);
            item.put("name", subject.name);
            item.put("name_cn", subject.nameCn);
            item.put("summary", subject.summary);
            item.put("date", subject.date);
            item.put("platform", subject.platform);
            item.put("nsfw", subject.nsfw);
            item.put("total_episodes", subject.totalEpisodes);
            item.put("air_weekday", subject.airWeekday);

            JSONObject images = new JSONObject();
            images.put("grid", subject.coverGrid);
            images.put("common", subject.coverCommon);
            images.put("large", subject.coverLarge);
            item.put("images", images);

            JSONObject rating = new JSONObject();
            rating.put("score", subject.score);
            rating.put("rank", subject.rank);
            rating.put("total", subject.ratingTotal);
            item.put("rating", rating);

            JSONArray tags = new JSONArray();
            for (String tag : subject.tags) tags.put(tag);
            item.put("tags", tags);
        } catch (JSONException ignored) {
        }
        return item;
    }

    /** 仅供调试：当前接口源与候选顺序 */
    public static Map<String, String> debugInfo() {
        Map<String, String> info = new LinkedHashMap<>();
        //不打印凭据本身，只打印"是否已注入"，避免把密钥抄进日志/用户反馈截图
        info.put("proxyConfigured", String.valueOf(isProxyConfigured()));
        info.put("activeBase", String.valueOf(getActiveBase()));
        StringBuilder candidates = new StringBuilder();
        for (String base : candidateBases()) {
            if (candidates.length() > 0) candidates.append(" → ");
            candidates.append(hostOf(base));
        }
        info.put("candidates", candidates.toString());
        return info;
    }
}
