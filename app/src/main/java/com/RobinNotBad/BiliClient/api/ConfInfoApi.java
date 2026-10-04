package com.RobinNotBad.BiliClient.api;

import android.net.Uri;

import com.RobinNotBad.BiliClient.util.FileUtil;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.RobinNotBad.BiliClient.util.ToolsUtil;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import okhttp3.HttpUrl;

/**
 * 被 luern0313 创建于 2019/8/25.
 * (人尽皆知的)绝 · 密 · 档 · 案
 * #以下代码修改自腕上哔哩的开源项目，感谢开源者做出的贡献！
 */

public class ConfInfoApi {

    //WBI mixin key 的缓存时长。B 站大约小时级轮换密钥，取 30 分钟兼顾请求量与失效窗口
    private static final long WBI_KEY_TTL_MS = 30L * 60 * 1000;


    /*
    这里是WBI签名校验
    https://socialsisteryi.github.io/bilibili-API-collect/docs/misc/sign/wbi.html#wbi-%E7%AD%BE%E5%90%8D%E7%AE%97%E6%B3%95
     */
    private static final int[] MIXIN_KEY_ENC_TAB = {46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
            61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
            36, 20, 34, 44, 52};

    public static String getWBIRawKey() throws IOException, JSONException {
        JSONObject getJson = NetWorkUtil.getJson("https://api.bilibili.com/x/web-interface/nav");
        JSONObject wbi_img = getJson.getJSONObject("data").getJSONObject("wbi_img");  //不要被名称骗了，这玩意是签名用的
        String img_key = FileUtil.getFileFirstName(FileUtil.getFileNameFromLink(wbi_img.getString("img_url")));  //得到文件名
        String sub_key = FileUtil.getFileFirstName(FileUtil.getFileNameFromLink(wbi_img.getString("sub_url")));

        return img_key + sub_key;  //相连
    }

    public static String getWBIMixinKey(String raw_key) {
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < 32; i++) {
            key.append(raw_key.charAt(MIXIN_KEY_ENC_TAB[i]));
        }

        return key.toString();
    }

    public static String signWBI(String url_query) throws JSONException, IOException {
        //取密钥要联网且写两份缓存，并发时会出现互相覆盖/半写入状态，签名互斥到方法级
        synchronized (ConfInfoApi.class) {
            return signWBIInternal(url_query);
        }
    }

    private static String signWBIInternal(String url_query) throws JSONException, IOException {
        String mixin_key = SharedPreferencesUtil.getString("wbi_mixin_key", "");
        //B 站轮换 img_key/sub_key 比一天频繁得多，按“日”缓存会在服务端轮换后让当天所有 WBI
        //请求签名失败（表现为番剧进度等静默失败），改为固定 TTL
        long now = System.currentTimeMillis();
        if (mixin_key.isEmpty() || now - SharedPreferencesUtil.getLong("last_wbi_time", 0L) > WBI_KEY_TTL_MS) {
            Logu.d("检查WBI");
            //必须先取到密钥再落缓存时间：取密钥要联网，失败时若已经写了时间戳，
            //TTL 内后续所有 WBI 请求都会拿着空/过期密钥签名
            String rawKey = ConfInfoApi.getWBIRawKey();
            mixin_key = ConfInfoApi.getWBIMixinKey(rawKey);
            SharedPreferencesUtil.putString("wbi_mixin_key", mixin_key);
            SharedPreferencesUtil.putLong("last_wbi_time", now);
        }

        String wts = String.valueOf(System.currentTimeMillis() / 1000);
        //官方 WBI 算法要求先剔除参数值里的 !'()* 再编码：不过滤时这些字符会参与签名，
        //算出与服务端不同的 w_rid（表现为含特殊字符的搜索词等静默 403/-352）
        String calc_str = sortUrlParams(Uri.encode(filterWbiParamValues(url_query), "@#&=*+-_.,:!?()/~'%") + "&wts=" + wts) + mixin_key;
        //不打印 calc_str（签名输入含用户查询内容，且尾部拼接的 mixin_key 是会话级密钥）

        String w_rid = ToolsUtil.md5(calc_str);

        return Objects.requireNonNull(HttpUrl.parse(url_query)).newBuilder().addQueryParameter("w_rid", w_rid).addQueryParameter("wts", wts).build().toString();
    }

    /**对 raw query 的每个参数值剔除 WBI 签名黑名单字符（!'()*），参数名不动。*/
    private static String filterWbiParamValues(String url_query) {
        int q = url_query.indexOf('?');
        String prefix = q == -1 ? "" : url_query.substring(0, q + 1);
        String query = q == -1 ? url_query : url_query.substring(q + 1);
        StringBuilder sb = new StringBuilder(prefix);
        String[] pairs = query.split("&");
        for (int i = 0; i < pairs.length; i++) {
            String pair = pairs[i];
            if (i > 0) sb.append('&');
            int eq = pair.indexOf('=');
            if (eq == -1) {
                sb.append(pair);
                continue;
            }
            sb.append(pair, 0, eq + 1);
            String value = pair.substring(eq + 1);
            for (int j = 0; j < value.length(); j++) {
                char c = value.charAt(j);
                if (c != '!' && c != '\'' && c != '(' && c != ')') sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String sortUrlParams(String url) {
        String encodedParam = Objects.requireNonNull(HttpUrl.parse(url)).encodedQuery();
        if (encodedParam == null) encodedParam = "";
        // 解析URL参数
        Map<String, String> paramMap = new HashMap<>();
        String[] params = encodedParam.split("&");
        for (String param : params) {
            //必须按第一个 = 切分：base64/URL 类参数的值里含 =，limit=2 否则整条被丢弃，
            //签名串与服务端计算不一致，接口直接报签名失败
            int eq = param.indexOf('=');
            if (eq < 0) {
                paramMap.put(param, "");
            } else {
                paramMap.put(param.substring(0, eq), param.substring(eq + 1));
            }
        }

        // 使用TreeMap对参数进行排序
        Map<String, String> sortedMap = new TreeMap<>(paramMap);

        // 构建排序后的URL
        StringBuilder sortedUrl = new StringBuilder();
        boolean isFirst = true;
        for (Map.Entry<String, String> entry : sortedMap.entrySet()) {
            if (!isFirst) {
                sortedUrl.append("&");
            } else {
                isFirst = false;
            }
            sortedUrl.append(entry.getKey()).append("=").append(entry.getValue());
        }

        return sortedUrl.toString();
    }


    public static int getDateCurr() {
        Calendar calendar = Calendar.getInstance();
        //MONTH 是 0-based（1月=0），必须 +1，否则日期缓存键在语义上错位一个月
        return calendar.get(Calendar.YEAR) * 10000 + (calendar.get(Calendar.MONTH) + 1) * 100 + calendar.get(Calendar.DATE);
    }
}
