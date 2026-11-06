package com.RobinNotBad.BiliClient.source;

import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 线路健康度：自动屏蔽「确认播不了」的线路。
 *
 * <p>为什么按线路而不是按源：同一个站点里往往只有部分线路是死的（采集站的备用线路、
 * 失效的 CDN），一个源整体拉黑会误杀好线路；而每条坏线路解析都要等一次 8~10s 超时，
 * 几条坏线路叠起来就是用户看到的「加载特别慢」。
 *
 * <p>规则：
 * <ul>
 *   <li>连续 2 次解析不到地址 → 屏蔽 12 小时（站点临时抖动不会一次就误杀）</li>
 *   <li>播放器实际报错 1 次 → 屏蔽 12 小时（用户已经亲眼看到播不了）</li>
 *   <li>解析成功即清除失败计数与屏蔽</li>
 * </ul>
 * 屏蔽有期限且可在选集页一键「恢复」，不会因为一次误判永久丢线路。
 * 记录落 SharedPreferences，重启应用后仍然生效——否则每次冷启动都要重新为坏线路等超时。
 */
public final class SourceChannelHealth {

    private static final String KEY = "third_channel_health_v1";
    private static final long BLOCK_MS = 12 * 60 * 60 * 1000L;
    private static final int RESOLVE_FAIL_LIMIT = 2;
    private static final int MAX_RECORDS = 300;

    private static final SourceChannelHealth INSTANCE = new SourceChannelHealth();

    public static SourceChannelHealth get() {
        return INSTANCE;
    }

    private static final class Record {
        int resolveFails;
        long blockedUntil;
        long lastSuccess;
    }

    /** channel.id 已包含 sourceId + 作品键，跨作品不会串 */
    private final Map<String, Record> records = new LinkedHashMap<>();
    private boolean loaded = false;

    private SourceChannelHealth() {
    }

    public synchronized boolean isBlocked(String channelId) {
        if (channelId == null || channelId.isEmpty()) return false;
        ensureLoaded();
        Record record = records.get(channelId);
        if (record == null) return false;
        if (record.blockedUntil > 0 && System.currentTimeMillis() > record.blockedUntil) {
            //到期自动解封，并重置失败计数，避免「一解封又立刻被旧计数屏蔽」
            record.blockedUntil = 0;
            record.resolveFails = 0;
            save();
        }
        return record.blockedUntil > 0;
    }

    /** 解析不到播放地址 */
    public synchronized void noteResolveFailure(String channelId) {
        if (channelId == null || channelId.isEmpty()) return;
        ensureLoaded();
        Record record = recordOf(channelId);
        if (record.blockedUntil > 0) return;
        record.resolveFails++;
        if (record.resolveFails >= RESOLVE_FAIL_LIMIT) {
            record.blockedUntil = System.currentTimeMillis() + BLOCK_MS;
        }
        save();
    }

    /** 播放器实际报错：一次就够，用户已经看到「这条线路播不了」 */
    public synchronized void notePlaybackFailure(String channelId) {
        if (channelId == null || channelId.isEmpty()) return;
        ensureLoaded();
        Record record = recordOf(channelId);
        record.blockedUntil = System.currentTimeMillis() + BLOCK_MS;
        save();
    }

    /** 解析成功：清掉屏蔽与失败计数，并记下「上次成功」用于优先排序 */
    public synchronized void noteSuccess(String channelId) {
        if (channelId == null || channelId.isEmpty()) return;
        ensureLoaded();
        Record record = recordOf(channelId);
        record.resolveFails = 0;
        record.blockedUntil = 0;
        record.lastSuccess = System.currentTimeMillis();
        save();
    }

    /** 上次成功时间，0 表示没成功过；用于把好线路排到前面 */
    public synchronized long lastSuccess(String channelId) {
        if (channelId == null || channelId.isEmpty()) return 0;
        ensureLoaded();
        Record record = records.get(channelId);
        return record == null ? 0 : record.lastSuccess;
    }

    public synchronized int blockedCount() {
        ensureLoaded();
        long now = System.currentTimeMillis();
        int count = 0;
        for (Record record : records.values()) {
            if (record.blockedUntil > now) count++;
        }
        return count;
    }

    /** 选集页「恢复 N 条」用 */
    public synchronized void clearAll() {
        ensureLoaded();
        records.clear();
        save();
    }

    private Record recordOf(String channelId) {
        Record record = records.get(channelId);
        if (record == null) {
            record = new Record();
            records.put(channelId, record);
        }
        return record;
    }

    // ---------------------------------------------------------------- 持久化

    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        String raw = SharedPreferencesUtil.getString(KEY, "");
        if (raw == null || raw.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(raw);
            Iterator<String> keys = root.keys();
            while (keys.hasNext() && records.size() < MAX_RECORDS) {
                String key = keys.next();
                JSONObject item = root.optJSONObject(key);
                if (item == null) continue;
                Record record = new Record();
                record.resolveFails = item.optInt("f", 0);
                record.blockedUntil = item.optLong("u", 0);
                record.lastSuccess = item.optLong("s", 0);
                records.put(key, record);
            }
        } catch (Exception ignored) {
            //配置损坏就当没有记录，不能因为一段坏 JSON 让选集页打不开
            records.clear();
        }
    }

    private void save() {
        long now = System.currentTimeMillis();
        //只保留还有用的记录：已解封且没成功过的、以及早就过期的直接丢掉
        Iterator<Map.Entry<String, Record>> iterator = records.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Record> entry = iterator.next();
            Record record = entry.getValue();
            if (record.blockedUntil > 0 && record.blockedUntil < now) {
                record.blockedUntil = 0;
                record.resolveFails = 0;
            }
            if (record.blockedUntil == 0 && record.resolveFails == 0 && record.lastSuccess == 0) {
                iterator.remove();
            }
        }
        while (records.size() > MAX_RECORDS) {
            String oldest = records.keySet().iterator().next();
            records.remove(oldest);
        }
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, Record> entry : records.entrySet()) {
                Record record = entry.getValue();
                JSONObject item = new JSONObject();
                item.put("f", record.resolveFails);
                item.put("u", record.blockedUntil);
                item.put("s", record.lastSuccess);
                root.put(entry.getKey(), item);
            }
            SharedPreferencesUtil.putString(KEY, root.toString());
        } catch (Exception ignored) {
            //写 SP 失败只影响下次冷启动的加速，不影响本次播放
        }
    }
}
