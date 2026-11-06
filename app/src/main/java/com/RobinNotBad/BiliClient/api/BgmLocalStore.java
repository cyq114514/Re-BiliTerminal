package com.RobinNotBad.BiliClient.api;

import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 本地追番表（不依赖 Bangumi 登录）。
 *
 * <p>为什么不复用 B 站追番：那是云端状态且必须登录。需求要求第一期以本地收藏为主，
 * Bangumi 的收藏系统不能成为强制登录依赖。
 *
 * <p>存储形态：主配置里一个 JSON 对象 {bgmId: {status, ts}}，条目量级在几十到几百，
 * 单键写入足够；手表存储紧张，不为此建数据库。
 */
public class BgmLocalStore {

    public static final String STATUS_WISH = "wish";       // 想看
    public static final String STATUS_DOING = "doing";     // 追番中
    public static final String STATUS_DONE = "done";       // 已看
    public static final String STATUS_ON_HOLD = "on_hold"; // 暂停

    private static final String KEY = "bgm_local_follow";

    public static String statusLabel(String status) {
        if (STATUS_WISH.equals(status)) return "想看";
        if (STATUS_DOING.equals(status)) return "追番中";
        if (STATUS_DONE.equals(status)) return "已看完";
        if (STATUS_ON_HOLD.equals(status)) return "暂停追番";
        return "";
    }

    /** @return 空串表示未收藏 */
    public static String getStatus(long bgmId) {
        JSONObject all = readAll();
        JSONObject entry = all.optJSONObject(String.valueOf(bgmId));
        return entry == null ? "" : entry.optString("status", "");
    }

    public static long getUpdatedAt(long bgmId) {
        JSONObject all = readAll();
        JSONObject entry = all.optJSONObject(String.valueOf(bgmId));
        return entry == null ? 0 : entry.optLong("ts", 0);
    }

    /** status 传空串＝取消收藏 */
    public static void setStatus(long bgmId, String status) {
        JSONObject all = readAll();
        try {
            if (status == null || status.isEmpty()) {
                all.remove(String.valueOf(bgmId));
            } else {
                JSONObject entry = new JSONObject();
                entry.put("status", status);
                entry.put("ts", System.currentTimeMillis());
                all.put(String.valueOf(bgmId), entry);
            }
            SharedPreferencesUtil.putString(KEY, all.toString());
        } catch (JSONException e) {
            Logu.e("bgm-local", "写入失败: " + e.getMessage());
        }
    }

    /** 某状态下的全部 bgmId，按最近更新倒序 */
    public static List<Long> listIds(String status) {
        List<Long> ids = new ArrayList<>();
        JSONObject all = readAll();
        List<long[]> pairs = new ArrayList<>();
        Iterator<String> keys = all.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject entry = all.optJSONObject(key);
            if (entry == null) continue;
            if (status != null && !status.isEmpty() && !status.equals(entry.optString("status", "")))
                continue;
            try {
                pairs.add(new long[]{Long.parseLong(key), entry.optLong("ts", 0)});
            } catch (NumberFormatException ignored) {
            }
        }
        java.util.Collections.sort(pairs, (a, b) -> Long.compare(b[1], a[1]));
        for (long[] pair : pairs) ids.add(pair[0]);
        return ids;
    }

    private static JSONObject readAll() {
        String raw = SharedPreferencesUtil.getString(KEY, "");
        if (raw == null || raw.isEmpty()) return new JSONObject();
        try {
            return new JSONObject(raw);
        } catch (JSONException e) {
            //存档损坏时按空处理，不能让详情页因为一条脏数据整页打不开
            Logu.e("bgm-local", "存档解析失败，已忽略: " + e.getMessage());
            return new JSONObject();
        }
    }
}
