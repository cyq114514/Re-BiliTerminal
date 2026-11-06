package com.RobinNotBad.BiliClient.source.model;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 可直接交给播放器的统一播放对象。
 *
 * <p><b>三类地址必须分清</b>：
 * <ol>
 *   <li>直接媒体地址（.m3u8 / .mp4 / .flv）—— 只有这类能进 {@link #mediaUrl}；</li>
 *   <li>播放页面 URL —— 放在 {@link #pageUrl}，需要 MediaResolver 再解析一次；</li>
 *   <li>中间地址（跳转/嵌套播放器）—— 由解析环节继续跟进，不能直接播放。</li>
 * </ol>
 * 把第 2、3 类当第 1 类传进播放器，表现就是「起播转圈然后报错」，
 * 这种「看起来进入播放状态但实际播不了」是本项目明令禁止的假成功。
 */
public class SourceMedia implements Serializable {

    /** 最终媒体地址（HLS/MP4/FLV） */
    public String mediaUrl = "";
    /** 媒体类型：application/vnd.apple.mpegurl / video/mp4 / 空=未知，交由播放器嗅探 */
    public String mimeType = "";
    /** 来源页面（解析时所在的播放页），用于排查与 Referer 兜底 */
    public String pageUrl = "";

    public String sourceId = "";
    public String subjectKey = "";
    public String episodeId = "";

    /** 请求头（只允许白名单键，见 {@link #addHeader}） */
    public final Map<String, String> headers = new LinkedHashMap<>();

    /** 链接有效期（毫秒时间戳），0 表示未知 */
    public long expireAt = 0;

    public SourceMedia() {
    }

    private static final String[] ALLOWED_HEADERS = {
            "Referer", "User-Agent", "Origin", "Cookie", "Accept", "Accept-Language"
    };

    /**
     * 只登记白名单里的请求头。
     *
     * <p>远程规则是<b>不可信输入</b>，不允许它往播放器塞任意头（例如伪造
     * Authorization 或超大 Cookie 撑爆 native 层）。
     */
    public void addHeader(String key, String value) {
        if (key == null || value == null) return;
        for (String allowed : ALLOWED_HEADERS) {
            if (allowed.equalsIgnoreCase(key)) {
                headers.put(allowed, value);
                return;
            }
        }
    }

    public boolean isExpired() {
        return expireAt > 0 && System.currentTimeMillis() > expireAt;
    }

    /** 给播放器的 Intent 用：Map 无法直接放，需要扁平化成 "k\nv\nk\nv" */
    public String headersForIntent() {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (builder.length() > 0) builder.append('\n');
            builder.append(entry.getKey()).append('\u0001').append(entry.getValue());
        }
        return builder.toString();
    }

    public static Map<String, String> headersFromIntent(String packed) {
        Map<String, String> map = new LinkedHashMap<>();
        if (packed == null || packed.isEmpty()) return map;
        String[] lines = packed.split("\n");
        for (String line : lines) {
            int index = line.indexOf('\u0001');
            if (index <= 0) continue;
            map.put(line.substring(0, index), line.substring(index + 1));
        }
        return map;
    }
}
