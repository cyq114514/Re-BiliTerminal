package com.RobinNotBad.BiliClient.api;

import android.content.Context;
import android.content.SharedPreferences;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.util.Logu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Bangumi 数据缓存（独立 SP 文件，与主配置、与第三方规则缓存完全隔离）。
 *
 * <p>为什么不复用 {@code SharedPreferencesUtil}：需求要求「Bangumi 数据与视频源数据独立缓存」，
 * 且季度列表单页就有十几 KB，混进主配置文件会把每次读配置都变成对大字符串的解码。
 *
 * <p>失败语义：读到过期数据仍然返回（由调用方决定是否先上屏再刷新），
 * 接口失败时**不清空**任何已有数据——这是断网可用的唯一保证。
 */
public class BgmCache {

    private static final String SP_NAME = "bgm_cache";
    private static final String TS_SUFFIX = "__ts";
    /** 季度列表最多保留几个季度（每季度若干分页），超出按时间淘汰最旧 */
    private static final int MAX_SEASON_KEYS = 24;

    private static SharedPreferences sp() {
        Context context = BiliTerminal.context;
        if (context == null) return null;
        return context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    public static void put(String key, String json) {
        SharedPreferences preferences = sp();
        if (preferences == null || key == null || json == null) return;
        try {
            preferences.edit()
                    .putString(key, json)
                    .putLong(key + TS_SUFFIX, System.currentTimeMillis())
                    .apply();
            if (key.startsWith("season_")) trimSeasonKeys(preferences);
        } catch (Throwable t) {
            Logu.e("bgm-cache", "写入失败 " + key + ": " + t.getMessage());
        }
    }

    public static String get(String key) {
        SharedPreferences preferences = sp();
        if (preferences == null || key == null) return null;
        try {
            return preferences.getString(key, null);
        } catch (ClassCastException e) {
            //历史存档类型错位时宁可当没有，也不要让缓存把页面整页打崩
            return null;
        }
    }

    public static long ageMs(String key) {
        SharedPreferences preferences = sp();
        if (preferences == null || key == null) return Long.MAX_VALUE;
        long ts = preferences.getLong(key + TS_SUFFIX, 0);
        if (ts <= 0) return Long.MAX_VALUE;
        return Math.max(0, System.currentTimeMillis() - ts);
    }

    public static boolean isFresh(String key, long ttlMs) {
        return get(key) != null && ageMs(key) <= ttlMs;
    }

    public static void remove(String key) {
        SharedPreferences preferences = sp();
        if (preferences == null || key == null) return;
        preferences.edit().remove(key).remove(key + TS_SUFFIX).apply();
    }

    /**
     * 季度缓存键数量封顶：手表存储紧张，季度缓存只用于「上次打开过的季度秒开」，
     * 留太多既没用又占空间。按写入时间淘汰最旧的。
     */
    private static void trimSeasonKeys(SharedPreferences preferences) {
        try {
            Map<String, ?> all = preferences.getAll();
            List<String> keys = new ArrayList<>();
            for (String key : all.keySet()) {
                if (key.startsWith("season_") && !key.endsWith(TS_SUFFIX)) keys.add(key);
            }
            int excess = keys.size() - MAX_SEASON_KEYS;
            if (excess <= 0) return;

            Collections.sort(keys, new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    return Long.compare(
                            preferences.getLong(a + TS_SUFFIX, 0),
                            preferences.getLong(b + TS_SUFFIX, 0));
                }
            });
            SharedPreferences.Editor editor = preferences.edit();
            for (int i = 0; i < excess && i < keys.size(); i++) {
                editor.remove(keys.get(i)).remove(keys.get(i) + TS_SUFFIX);
            }
            editor.apply();
        } catch (Throwable t) {
            Logu.e("bgm-cache", "清理失败: " + t.getMessage());
        }
    }
}
