package com.RobinNotBad.BiliClient.util;

import android.annotation.SuppressLint;
import android.os.Build;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 被 luern0313 创建于 2019/10/13.
 * #以下代码来源于腕上哔哩的开源项目，感谢开源者做出的贡献！
 */

public class NetWorkUtil {
    private static final AtomicReference<OkHttpClient> INSTANCE = new AtomicReference<>();

    public static class Inet4Selector implements Dns {
        @NonNull
        @Override
        public List<InetAddress> lookup(@NonNull String hostname) throws UnknownHostException {
            List<InetAddress> hosts = Dns.SYSTEM.lookup(hostname);
            List<InetAddress> inet4Hosts = new ArrayList<>();
            for (InetAddress host : hosts) {
                if (host.getAddress().length == 4) inet4Hosts.add(host);
            }
            return inet4Hosts;    //筛选IPV4地址，IPV6请求有异常
        }
    }

    public static OkHttpClient getOkHttpInstance() {
        while (INSTANCE.get() == null) {
            INSTANCE.compareAndSet(null, setOkHttpSsl(new OkHttpClient.Builder())
                    .followRedirects(false)
                    .addInterceptor(chain -> {
                        Request request = chain.request();
                        Response response = chain.proceed(request);
                        RedirectHandler handler;
                        String location = response.header("Location");
                        boolean isSslRedirect = false;
                        try {
                            //相对路径 Location 的 getScheme()/getHost() 为 null，必须判空后再比较，
                            //否则 NPE 会从拦截器直接炸掉整个请求
                            URI redirectUri = location != null ? new URI(location) : null;
                            String scheme = redirectUri != null ? redirectUri.getScheme() : null;
                            String redirectHost = redirectUri != null ? redirectUri.getHost() : null;
                            isSslRedirect = scheme != null && !request.isHttps() && scheme.equalsIgnoreCase("https")
                                    && request.url().host().equalsIgnoreCase(redirectHost);
                        } catch (URISyntaxException ignored) {
                        }

                        if (response.isRedirect() && location != null) {
                            if (request.url().host().equals("b23.tv") && !isSslRedirect && (handler = request.tag(RedirectHandler.class)) != null) {
                                handler.handleRedirect(location);
                            } else {
                                HttpUrl target = HttpUrl.parse(location);
                                //手动跟跳必须有安全边界：目标必须在 B 站域名白名单内且为 https 才携带请求头跟随，
                                //否则原样返回（绝不把带 Cookie 的请求转发给任意域名，也绝不跟跳到明文 http）；
                                //跳数通过 request tag 累计，防恶意循环重定向打爆调用栈
                                if (target == null || !target.isHttps() || !isBilibiliHost(target.host())) return response;
                                int hops = request.tag(Integer.class) != null ? request.tag(Integer.class) : 0;
                                if (hops >= 5) return response;
                                Request newRequest = request.newBuilder()
                                        .url(target)
                                        .tag(Integer.class, hops + 1)
                                        .build();
                                return chain.proceed(newRequest);
                            }
                        }
                        return response;
                    })
                    .addInterceptor(new CookieSaveInterceptor())
                    .dns(new Inet4Selector())
                    .pingInterval(8, TimeUnit.SECONDS)
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(16, TimeUnit.SECONDS).build());
        }
        return INSTANCE.get();
    }

    public synchronized static OkHttpClient.Builder setOkHttpSsl(OkHttpClient.Builder okhttpBuilder) {
        if (Build.VERSION.SDK_INT > 22) return okhttpBuilder;
        try {
            //老设备 TLS 协议兼容仍走 SSLSocketFactoryCompat（启用 TLSv1.1/1.2），
            //但证书校验必须用系统默认 TrustManager——此前传入的空实现 trust-all
            //会让 API≤22 设备信任任意自签证书，登录 Cookie 可被同网段中间人整体窃取
            javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                    javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((java.security.KeyStore) null);
            final X509TrustManager systemTrustManager = (X509TrustManager) tmf.getTrustManagers()[0];
            final SSLSocketFactory sslSocketFactory = new SSLSocketFactoryCompat(systemTrustManager);
            okhttpBuilder.sslSocketFactory(sslSocketFactory, systemTrustManager);
        } catch (Exception e) {
            //初始化失败不能退化成 trust-all：抛出让调用方拿到明确的初始化错误
            throw new RuntimeException(e);
        }
        return okhttpBuilder;
    }

    public static JSONObject getJson(String url) throws IOException, JSONException {
        return getJson(url, webHeaders, 0);
    }

    public static JSONObject getJson(String url, ArrayList<String> headers) throws IOException, JSONException {
        return getJson(url, headers, 0);
    }

    /**
     * 指定 doctype 重试次数的 getJson。
     * maxRetryTimes<=0 时走全局设置（api_retry_max_times，默认 5）。
     * 已带自身重试/降级逻辑的接口（如评论的 WBI 主备双路）应传较小值（如 2），
     * 否则外层 4 次 × 内层 5 次的乘法重试在弱网下会把一次加载拖到分钟级，表现为"列表卡死"。
     */
    public static JSONObject getJson(String url, ArrayList<String> headers, int maxRetryTimes) throws IOException, JSONException {
        String bodyString = getBodyStringWithDoctypeRetry(url, headers, maxRetryTimes);
        if (bodyString != null) return new JSONObject(bodyString);
        throw new JSONException("在访问" + url + "时返回数据为空");
    }

    public static JSONObject getJsonNoCookie(String url) throws IOException, JSONException {
        ArrayList<String> headers = new ArrayList<>(webHeaders);
        headers.set(1, "");
        String bodyString = getBodyStringWithDoctypeRetry(url, headers);
        if (bodyString != null) return new JSONObject(bodyString);
        throw new JSONException("在访问" + url + "时返回数据为空");
    }

    public static JSONObject getJsonPrivacy(String url) throws IOException, JSONException {
        ArrayList<String> headers = new ArrayList<>(webHeaders);
        headers.set(1, CookieGenerator.getCookieString(false));
        String bodyString = getBodyStringWithDoctypeRetry(url, headers);
        if (bodyString != null) return new JSONObject(bodyString);
        throw new JSONException("在访问" + url + "时返回数据为空");
    }

    public static Response get(String url) throws IOException {
        return get(url, webHeaders);
    }

    public static Response get(String url, ArrayList<String> headers) throws IOException {
        return get(url, headers, null);
    }

    public static Response get(String url, ArrayList<String> headers, RedirectHandler redirectHandler) throws IOException {
        Logu.d("get-url", url);
        OkHttpClient client = getOkHttpInstance();
        Request.Builder requestBuilder = new Request.Builder().url(url).get();
        for (int i = 0; i < headers.size(); i += 2)
            requestBuilder.addHeader(headers.get(i), headers.get(i + 1));
        if (redirectHandler != null) requestBuilder.tag(RedirectHandler.class, redirectHandler);
        Request request = requestBuilder
                //记录发出时的账号代际，切号后到达的旧账号响应不再回写 Cookie（见 saveCookiesFromResponse）
                .tag(Long.class, accountGeneration)
                .build();
        return executeWithDoctypeRetry(client, request);
    }

    public static Response post(String url, String data, List<String> headers, String contentType) throws IOException {
        Logu.d("post-url", url);
        Logu.d("post-data", maskSensitiveData(data));
        OkHttpClient client = getOkHttpInstance();
        RequestBody body = RequestBody.create(MediaType.parse(contentType + "; charset=utf-8"), data);
        Request.Builder requestBuilder = new Request.Builder().url(url).post(body);
        for (int i = 0; i < headers.size(); i += 2) {
            String key = headers.get(i);
            String val = headers.get(i + 1);
            if (key.equalsIgnoreCase("Content-Type")) val = contentType;
            requestBuilder.addHeader(key, val);
        }
        Request request = requestBuilder
                //记录发出时的账号代际，切号后到达的旧账号响应不再回写 Cookie（见 saveCookiesFromResponse）
                .tag(Long.class, accountGeneration)
                .build();
        //POST（点赞/投币/发弹幕等）不重试：弱网下服务端可能已执行成功但响应丢失，重试会造成重复动作
        return executeWithDoctypeRetry(client, request, false);
    }

    public static Response post(String url, String data, List<String> headers) throws IOException {
        return post(url, data, headers, "application/x-www-form-urlencoded");
    }

    public static Response postJson(String url, String data, List<String> headers) throws IOException {
        return post(url, data, headers, "application/json");
    }

    public static Response postJson(String url, String data) throws IOException {
        return post(url, data, webHeaders, "application/json");
    }

    public static Response post(String url, String data) throws IOException {
        return post(url, data, webHeaders);
    }

    private static Response executeWithDoctypeRetry(OkHttpClient client, Request request) throws IOException {
        return executeWithDoctypeRetry(client, request, true);
    }

    /**
     * retryEnabled=false 时只请求一次。GET 幂等可安全重试；POST 不重试（见 post()）。
     */
    private static Response executeWithDoctypeRetry(OkHttpClient client, Request request, boolean retryEnabled) throws IOException {
        int maxTimes = retryEnabled
                ? Math.max(1, SharedPreferencesUtil.getInt(SharedPreferencesUtil.API_RETRY_MAX_TIMES, 5))
                : 1;
        float intervalSeconds = SharedPreferencesUtil.getFloat(SharedPreferencesUtil.API_RETRY_INTERVAL_SECONDS, 0.1f);
        long intervalMillis = Math.max(0L, (long) (intervalSeconds * 1000));

        IOException latestException = null;
        Response latestResponse = null;

        for (int attempt = 1; attempt <= maxTimes; attempt++) {
            if (latestResponse != null) {
                latestResponse.close();
                latestResponse = null;
            }

            try {
                latestResponse = client.newCall(request).execute();
                if (!isDoctypeResponse(latestResponse)) {
                    return latestResponse;
                }
            } catch (IOException e) {
                latestException = e;
            }

            if (attempt < maxTimes && intervalMillis > 0) {
                try {
                    Thread.sleep(intervalMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (latestResponse != null) return latestResponse;
        if (latestException != null) throw latestException;
        throw new IOException("请求失败");
    }

    private static String getBodyStringWithDoctypeRetry(String url, ArrayList<String> headers) throws IOException {
        return getBodyStringWithDoctypeRetry(url, headers, 0);
    }

    private static String getBodyStringWithDoctypeRetry(String url, ArrayList<String> headers, int maxRetryTimes) throws IOException {
        int maxTimes = maxRetryTimes > 0 ? maxRetryTimes
                : Math.max(1, SharedPreferencesUtil.getInt(SharedPreferencesUtil.API_RETRY_MAX_TIMES, 5));
        float intervalSeconds = SharedPreferencesUtil.getFloat(SharedPreferencesUtil.API_RETRY_INTERVAL_SECONDS, 0.1f);
        long intervalMillis = Math.max(0L, (long) (intervalSeconds * 1000));

        String latestBodyString = null;
        for (int attempt = 1; attempt <= maxTimes; attempt++) {
            try (ResponseBody body = get(url, headers).body()) {
                if (body == null) {
                    latestBodyString = null;
                    continue;
                }
                latestBodyString = body.string();
            }

            if (!isDoctypeResponse(latestBodyString)) {
                return latestBodyString;
            }

            if (attempt < maxTimes && intervalMillis > 0) {
                try {
                    Thread.sleep(intervalMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return latestBodyString;
    }

    private static boolean isDoctypeResponse(String responseBody) {
        if (responseBody == null) return false;
        return responseBody.trim().toLowerCase(Locale.ROOT).startsWith("<!doctype");
    }

    private static boolean isDoctypeResponse(Response response) {
        if (response == null || response.body() == null) return false;
        try {
            ResponseBody peekBody = response.peekBody(128);
            return isDoctypeResponse(peekBody.string());
        } catch (Exception ignored) {
            return false;
        }
    }


    public static byte[] readStream(InputStream inStream) throws IOException {
        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int len;
        while ((len = inStream.read(buffer)) != -1) {
            outStream.write(buffer, 0, len);
        }
        outStream.close();
        inStream.close();
        return outStream.toByteArray();
    }

    public static byte[] uncompress(byte[] inputByte) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(inputByte.length);
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(inputByte);
            byte[] buffer = new byte[4 * 1024];
            while (!inflater.finished()) {
                if (inflater.needsInput() || inflater.needsDictionary()) break;   //数据不完整时避免空转
                int count = inflater.inflate(buffer);
                outputStream.write(buffer, 0, count);
            }
        } catch (Exception e) {
            Logu.e("NetWorkUtil", "uncompress failed");
        } finally {
            inflater.end();    //释放底层zlib的native内存
        }
        byte[] output = outputStream.toByteArray();
        outputStream.close();
        return output;
    }

    public static String getInfoFromCookie(String name, String cookie) {
        String[] cookies = cookie.split("; ");
        for (String i : cookies) {
            //每条cookie形态为 name=value，应以前缀匹配，避免 name 是其他cookie名的子串时误中
            if (i.startsWith(name + "="))
                return i.substring(name.length() + 1);
        }
        return "";
    }

    /**POST body 日志脱敏：凭据类参数（表单 key=value 与 JSON "key":"..." 两种形态）一律打码。*/
    private static final Pattern SENSITIVE_PARAM_PATTERN = Pattern.compile(
            "(?i)(csrf|refresh_token|access_key|password|username|tel|sessdata|token|auth_code)(\\s*(?:=|\":\")\\s*)[^&\"]*");

    public static String maskSensitiveData(String data) {
        if (data == null) return null;
        return SENSITIVE_PARAM_PATTERN.matcher(data).replaceAll("$1$2***");
    }

    /**
     * 账号代际号：每次写入全局登录态（切号/登录）时自增。
     * 请求发出时把当前代际打进 tag，响应回写 Cookie 前校验——
     * 切号瞬间在途的旧账号响应（如旧账号的 SESSDATA 轮换 Set-Cookie）会被整体丢弃，
     * 不再把旧账号的 Cookie 合并进新账号的会话。
     */
    private static volatile long accountGeneration = 0;

    public static void bumpAccountGeneration() {
        accountGeneration++;
    }

    private static void saveCookiesFromResponse(Response response) {
        List<String> newCookies = response.headers("Set-Cookie");

        //如果没有新cookies，直接返回
        if (newCookies.isEmpty()) return;
        //旧账号时代发出的请求，其 Set-Cookie 属于旧账号会话，切号后到达必须丢弃
        Long requestGeneration = response.request().tag(Long.class);
        if (requestGeneration != null && requestGeneration != accountGeneration) {
            Logu.d("cookie-skip", "stale generation, response discarded");
            return;
        }

        synchronized (NetWorkUtil.class) {
            String cookiesStr = SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "");
            ArrayList<String> oldCookies = (cookiesStr.equals("") ? new ArrayList<>() : new ArrayList<>(Arrays.asList(cookiesStr.split("; "))));  //转list

            for (String newCookie : newCookies) {  //对每一条新cookie遍历

                Cookies cookies = new Cookies(newCookie);
                if (cookies.containsKey("Domain")) {
                    String domain = cookies.get("Domain");
                    String d = domain == null ? "" : domain.toLowerCase(Locale.ROOT);
                    if (d.startsWith(".")) d = d.substring(1);
                    //域属性校验必须带点后缀匹配：endsWith("bilibili.com") 会把 evilbilibili.com 放进来，
                    //浏览器语义是"该域及其子域"，等价于 d.equals("bilibili.com") || d.endsWith(".bilibili.com")
                    if (!(d.equals("bilibili.com") || d.endsWith(".bilibili.com")))
                        continue;
                } else if (!isBilibiliHost(response.request().url().host())) {
                    //无 Domain 属性的 Set-Cookie 按响应来源校验：
                    //第三方域（或被劫持的跳转目标）不能把 cookie 注入全局请求头
                    continue;
                }

                int index = newCookie.indexOf("; ");
                if (index != -1) newCookie = newCookie.substring(0, index);  //如果没有分号不做处理

                index = newCookie.indexOf("=") + 1;
                if (index == 0) continue;   //如果没有等号，跳过

                String key = newCookie.substring(0, index);    //key=
                //不打印 cookie 内容（含登录凭证），只记键名
                Logu.d("newCookie", newCookie.substring(0, Math.max(key.length() - 1, 0)));

                boolean added = false;
                for (int i = 0; i < oldCookies.size(); i++) {  //查找旧cookie表有没有
                    String oldCookie = oldCookies.get(i);
                    //必须前缀匹配：contains会把 sid= 误匹配到 b_lsid= 之类的项，导致互相覆盖
                    if (oldCookie.startsWith(key)) {
                        oldCookies.set(i, newCookie);    //有的话直接换掉
                        added = true;
                        break;
                    }
                }
                if (!added) {
                    oldCookies.add(newCookie);  //没有就加项
                }
            }

            StringBuilder setCookies = new StringBuilder();
            for (String setCookie : oldCookies) {
                setCookies.append(setCookie).append("; ");
            }
            //如果一次setCookies都没有，就不要存了， 因为是个空字符串
            if (setCookies.length() >= 2) {
                //不打印完整 cookie 串（含 SESSDATA 等登录凭证）
                Logu.d("save-result", "cookie jar updated, " + oldCookies.size() + " items");
                SharedPreferencesUtil.putString(SharedPreferencesUtil.cookies, setCookies.substring(0, setCookies.length() - 2));
                //只重建请求头，不触发 ensureCookies：本方法跑在 OkHttp 拦截器线程上，
                //嵌套网络请求会在弱网下递归占用请求线程并拖慢所有接口
                updateWebHeaders();
            }
        }
    }

    /**
     * 重定向跟随与 Cookie 来源校验共用的域名白名单：B 站主站、短链、视频/图片 CDN。
     * 手动跟随重定向的调用方（如 OpusApi 的网页抓取）也必须用本校验，保证带 Cookie 的请求不出域。
     */
    public static boolean isBilibiliHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        //akamai 镜像必须用精确主机名：akamaized.net 是 Akamai 的共享域而非 B 站资产，
        //后缀放行等于允许任意 *.akamaized.net 收到带 Cookie 的跟随跳转、并向全局 Cookie 注入数据
        return h.equals("b23.tv") || h.equals("bilibili.com") || h.endsWith(".bilibili.com")
                || h.endsWith(".bilivideo.com") || h.endsWith(".hdslb.com")
                || BILIBILI_AKAMAI_MIRROR_HOSTS.contains(h);
    }

    /**B 站视频 CDN 在 Akamai 上的已知镜像主机，有新增镜像时在这里补。*/
    private static final List<String> BILIBILI_AKAMAI_MIRROR_HOSTS = Arrays.asList(
            "upos-sz-mirrorakam.akamaized.net",
            "upos-hz-mirrorakam.akamaized.net");

    /**
     * 存储单个Cookie
     *
     * @param key 键
     * @param val 值
     */
    public static void putCookie(String key, String val) {
        synchronized (NetWorkUtil.class) {
            Cookies cookies = new Cookies(SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, ""));
            cookies.set(key, val);
            SharedPreferencesUtil.putString(SharedPreferencesUtil.cookies, cookies.toString());
            //锁内只做 SharedPreferences 写入；刷新请求头（可能触发 buvid/bili_ticket 的网络请求）
            //必须在锁外做，否则 getCookies() 的调用方（如弹幕连接）会被网络超时锁死数分钟
        }
        updateWebHeaders();
    }

    /**
     * 存储Cookies（覆盖写入）
     *
     * @param cookies cookies
     */
    public static void setCookies(Cookies cookies) {
        synchronized (NetWorkUtil.class) {
            SharedPreferencesUtil.putString(SharedPreferencesUtil.cookies, cookies.toString());
        }
        updateWebHeaders();
    }

    /**
     * 获取存储的Cookies
     *
     * @return 存储的Cookies
     */
    public static Cookies getCookies() {
        synchronized (NetWorkUtil.class) {
            return new Cookies(SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, ""));
        }
    }

    public static final String USER_AGENT_WEB = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.6261.95 Safari/537.36";
    //volatile + 整表替换（copy-on-write）：请求线程会按索引遍历本表，
    //拦截器线程更新 Cookie 时不能原地 set（旧值可见性无保证），必须重建列表整体替换引用，
    //读线程拿到的引用要么是旧快照要么是新快照，永远不会读到半更新的表
    public static volatile ArrayList<String> webHeaders = new ArrayList<>() {{
        add("Cookie");
        add(SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, ""));

        add("Origin");
        add("https://www.bilibili.com");

        add("Referer");
        add("https://www.bilibili.com/");

        add("User-Agent");
        add(USER_AGENT_WEB);

        add("Sec-Ch-Ua");
        add("\"Chromium\";v=\"122\", \"Not(A:Brand\";v=\"24\", \"Google Chrome\";v=\"122\"");

        add("Sec-Ch-Ua-Platform");
        add("\"Windows\"");

        add("Sec-Ch-Ua-Mobile");
        add("?0");
    }};

    /**
     * 补齐 buvid3/bili_ticket 等设备 Cookie 后刷新请求头。涉及网络请求，只能在后台线程调用。
     * 显式的初始化时机（Splash、登录成功）调用这个；其余场景一律用 {@link #updateWebHeaders()}。
     */
    public static void refreshHeaders() {
        CookieGenerator.ensureCookies();
        updateWebHeaders();
    }

    /**
     * 仅根据当前存储的 Cookie 重建请求头，不做任何网络请求。
     * CookieSaveInterceptor / putCookie / setCookies 必须走这里：
     * 它们运行在 OkHttp 线程上，若触发 ensureCookies 的嵌套网络请求，会递归占用请求线程并拖慢所有接口。
     */
    public static void updateWebHeaders() {
        ArrayList<String> updated = new ArrayList<>(webHeaders);
        updated.set(1, CookieGenerator.getCookieString(true));
        webHeaders = updated;
    }

    /**对 URL 参数值做表单编码；null 返回空串，编码失败原样返回（不应发生）。*/
    public static String urlEncode(String value) {
        if (value == null) return "";
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    public static class FormData {
        private final Map<String, String> data;
        private boolean isUrlParam;

        public FormData() {
            data = new HashMap<>();
        }

        public FormData remove(String key) {
            data.remove(key);
            return this;
        }

        public FormData put(String key, Object value) {
            data.put(key, String.valueOf(value));
            return this;
        }

        public FormData setUrlParam(boolean isUrlParam) {
            this.isUrlParam = isUrlParam;
            return this;
        }

        @NonNull
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();

            if (isUrlParam) sb.append("?");

            try {
                for (String key : data.keySet()) {
                    if (sb.length() > (isUrlParam ? 1 : 0)) {
                        sb.append("&");
                    }
                    sb.append(URLEncoder.encode(key, "UTF-8"));
                    sb.append("=");
                    sb.append(URLEncoder.encode(data.get(key), "UTF-8"));
                }
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }

            return sb.toString();
        }
    }

    public interface RedirectHandler {
        void handleRedirect(String location);
    }

    private static class CookieSaveInterceptor implements Interceptor {
        @NonNull
        @Override
        public Response intercept(Chain chain) throws IOException {
            Response response = chain.proceed(chain.request());
            saveCookiesFromResponse(response);
            return response;
        }
    }

}
