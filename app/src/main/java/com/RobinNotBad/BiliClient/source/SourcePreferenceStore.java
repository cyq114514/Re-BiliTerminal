package com.RobinNotBad.BiliClient.source;

import com.RobinNotBad.BiliClient.source.model.SourceInfo;
import com.RobinNotBad.BiliClient.source.model.SourceSubject;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import java.util.Locale;
import java.util.Map;

/**
 * 第三方播放的「记忆」：上次用的线路、上次看的集、是否开启网页嗅探。
 *
 * <p>作用域必须是<b>源 + 作品</b>，不能只用作品名：同名番剧在不同站点是不同的条目，
 * 串用会导致「换了源却跳回上一站的线路」。
 *
 * <p>键名清洗只保留字母数字与下划线，再附 8 位哈希兜底：SP 的键虽然允许 `/:`，
 * 但用户可能把整串 URL 带进来（含 `?`、`&`），长度与可读性都不可控。
 */
public final class SourcePreferenceStore {

    /** 网页嗅探总开关（默认关：手表上未必有 WebView，见方案 D1） */
    public static final String KEY_SNIFF = "third_sniff_enabled";

    private static final String PREFIX_LINE = "third_line_";
    private static final String PREFIX_EPISODE = "third_ep_";
    /** 记忆条数上限：超了就整体清空（手表存储紧张，不做 LRU 记账） */
    private static final int MAX_ENTRIES = 400;

    private SourcePreferenceStore() {
    }

    public static String scopeOf(SourceInfo info, SourceSubject subject) {
        String sourceId = info == null ? "" : info.id;
        String subjectKey = subject == null ? "" : subject.key();
        return sanitize(sourceId + "|" + subjectKey);
    }

    public static String getChannelId(String scope) {
        return SharedPreferencesUtil.getString(PREFIX_LINE + scope, "");
    }

    public static void setChannelId(String scope, String channelId) {
        SharedPreferencesUtil.putString(PREFIX_LINE + scope, channelId == null ? "" : channelId);
        trimIfNeeded();
    }

    /** @return 上次看的集序号，未记录返回 -1 */
    public static int getEpisodeIndex(String scope) {
        return SharedPreferencesUtil.getInt(PREFIX_EPISODE + scope, -1);
    }

    public static void setEpisodeIndex(String scope, int index) {
        if (index < 0) return;
        SharedPreferencesUtil.putInt(PREFIX_EPISODE + scope, index);
        trimIfNeeded();
    }

    /**
     * 键数量兜底。
     *
     * <p>只在写入时顺手检查一次，避免每帧遍历 `getAll()`；清空而不是逐条淘汰，
     * 是因为「记住上次线路」丢了的代价只是多点一次，不值得为它写 LRU。
     */
    private static void trimIfNeeded() {
        try {
            Map<String, ?> all = SharedPreferencesUtil.getSharedPreferences().getAll();
            int count = 0;
            for (String key : all.keySet()) {
                if (key.startsWith(PREFIX_LINE) || key.startsWith(PREFIX_EPISODE)) count++;
            }
            if (count <= MAX_ENTRIES) return;
            for (String key : all.keySet()) {
                if (key.startsWith(PREFIX_LINE) || key.startsWith(PREFIX_EPISODE))
                    SharedPreferencesUtil.removeValue(key);
            }
        } catch (Throwable ignored) {
            //记不住不影响播放
        }
    }

    private static String sanitize(String raw) {
        if (raw == null) raw = "";
        StringBuilder builder = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length() && builder.length() < 96; i++) {
            char c = raw.charAt(i);
            boolean keep = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-';
            builder.append(keep ? c : '_');
        }
        builder.append('_').append(Integer.toHexString(raw.hashCode()).toLowerCase(Locale.ROOT));
        return builder.toString();
    }
}
