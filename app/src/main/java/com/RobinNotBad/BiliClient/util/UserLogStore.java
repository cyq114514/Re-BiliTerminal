package com.RobinNotBad.BiliClient.util;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 经验/硬币等"变化记录"的本地累积存储（本地累积式同步）。
 *
 * 背景：服务端两个接口
 *   https://api.bilibili.com/x/member/web/exp/log
 *   https://api.bilibili.com/x/member/web/coin/log
 * 只返回最近一段时间的记录（社区文档：经验记录=最近一周，硬币记录=仅能查询最近一周），
 * 且没有分页参数。因此客户端无论如何都取不到更早的历史，用户感知就是"同步不了历史数据"。
 *
 * 这里的做法：每次拉到新记录就按 time+delta+reason 去重并入本地，越用越全。
 * 存储按 mid 隔离，多账号切换不会互相污染；只存本地、不上传。
 */
public class UserLogStore {

    //单类记录本地保留上限：手表存储紧张，2000 条足够覆盖很长时间的增量
    private static final int MAX_ENTRIES = 2000;
    private static final String FIELD_TIME = "time";
    private static final String FIELD_DELTA = "delta";
    private static final String FIELD_REASON = "reason";

    private UserLogStore() {
    }

    /**
     * 存储 key 带 mid：换账号后不会把上一个账号的记录混进来。
     * mid 用实时 Cookie 派生（与观看进度的读写同一口径）：本地快照只在"登录成功"那一刻写入，
     * Cookie 轮换/多账号切换/换设备恢复备份后它会错位，错位时记录就会并进别的账号的桶里。
     */
    private static String storageKey(String baseKey) {
        return baseKey + "_" + NetWorkUtil.getLoginMid();
    }

    /** 本地已累积的记录（时间倒序）；没有则返回空数组 */
    public static JSONArray load(String baseKey) {
        try {
            String raw = SharedPreferencesUtil.getString(storageKey(baseKey), "");
            if (raw == null || raw.isEmpty()) return new JSONArray();
            return new JSONArray(raw);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /** 清空某类记录的本地累积（换账号/退出登录时可用） */
    public static void clear(String baseKey) {
        try {
            SharedPreferencesUtil.putString(storageKey(baseKey), "");
        } catch (Exception ignored) {
        }
    }

    /**
     * 把本次接口返回的记录并入本地累积，返回合并后的完整列表（时间倒序）。
     * 去重键 = time + delta + reason：同一条记录重复拉取不会产生副本。
     *
     * @param baseKey 记录类型前缀（经验/硬币各自独立）
     * @param fresh   本次接口返回的记录数组，元素含 time/delta/reason
     */
    public static JSONArray merge(String baseKey, JSONArray fresh) {
        Map<String, JSONObject> merged = new LinkedHashMap<>();
        try {
            JSONArray stored = load(baseKey);
            for (int i = 0; i < stored.length(); i++) {
                JSONObject item = stored.optJSONObject(i);
                if (item == null) continue;
                merged.put(itemKey(item), item);
            }
        } catch (Exception ignored) {
        }
        if (fresh != null) {
            for (int i = 0; i < fresh.length(); i++) {
                JSONObject item = fresh.optJSONObject(i);
                if (item == null) continue;
                //新拿到的数据覆盖同键旧条目（服务端可能补全/修正 reason）
                merged.put(itemKey(item), item);
            }
        }

        List<JSONObject> list = new ArrayList<>(merged.values());
        //时间戳先解析成数值再排序（审计 P4-2）：在比较器里反复解析时间、反复 new SimpleDateFormat
        //都是 O(n log n) 次开销，2000 条上限时是数千次对象构造；这里一次解析、只比数值
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
        final long[] ts = new long[list.size()];
        for (int i = 0; i < list.size(); i++) ts[i] = parseTime(fmt, list.get(i).optString(FIELD_TIME, ""));
        Integer[] order = new Integer[list.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        //时间倒序；解析失败(0)的条目排在最后
        Arrays.sort(order, (a, b) -> Long.compare(ts[b], ts[a]));
        JSONArray result = new JSONArray();
        for (int i = 0; i < order.length && i < MAX_ENTRIES; i++) result.put(list.get(order[i]));
        try {
            SharedPreferencesUtil.putString(storageKey(baseKey), result.toString());
        } catch (Exception ignored) {
        }
        return result;
    }

    private static String itemKey(JSONObject item) {
        return item.optString(FIELD_TIME, "") + '|'
                + item.optInt(FIELD_DELTA, 0) + '|'
                + item.optString(FIELD_REASON, "");
    }

    /** fmt 由调用方构造并只在单线程内复用（SimpleDateFormat 非线程安全）。 */
    private static long parseTime(SimpleDateFormat fmt, String time) {
        if (time == null || time.isEmpty()) return 0;
        try {
            return fmt.parse(time).getTime();
        } catch (Exception e) {
            return 0;
        }
    }
}
