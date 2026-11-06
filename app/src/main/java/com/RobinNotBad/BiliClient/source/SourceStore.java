package com.RobinNotBad.BiliClient.source;

import com.RobinNotBad.BiliClient.source.model.SourceInfo;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 第三方源配置的本地存储（SP + JSON，不引入 Room）。
 *
 * <p>为什么存 {@link SourceInfo#rawConfig} 原文而不是解析后的字段：规则字段随上游演进，
 * 先解析成稳定模型会丢新字段；存原文可以在不升级 App 的情况下重新解析（与 Animeko
 * 「导出配置即数据源」的思路一致）。
 *
 * <p>为什么带锁：搜索页与源管理页可能在不同线程同时读写；SP 本身线程安全，
 * 但「读-改-写」序列不是，必须自己串起来。
 */
public final class SourceStore {

    /** 存储键：版本后缀便于将来结构变更时整表丢弃而不是崩在解析上 */
    private static final String KEY = "third_sources_v1";
    private static final Object LOCK = new Object();

    /**
     * 解析结果的内存缓存。
     *
     * <p>一次搜索会多次调用 {@link #load()}（取启用源、读健康度、写状态…），
     * 每次都要解析 ~170KB JSON 并重建 60+ 个对象；手表上这套开销肉眼可见。
     * 缓存后只在真正写入时失效。
     */
    private static List<SourceInfo> cache;

    private SourceStore() {
    }

    /** 读取全部源（保持用户在列表里的顺序）；坏数据跳过，不让一条脏记录拖垮整表 */
    public static List<SourceInfo> load() {
        synchronized (LOCK) {
            if (cache != null) return new ArrayList<>(cache);
            List<SourceInfo> list = new ArrayList<>();
            String raw = SharedPreferencesUtil.getString(KEY, "");
            if (raw == null || raw.isEmpty()) {
                cache = list;
                return new ArrayList<>(list);
            }
            try {
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject object = array.optJSONObject(i);
                    SourceInfo info = fromJson(object);
                    if (info != null) list.add(info);
                }
            } catch (Exception e) {
                //整表坏了就返回空，让用户重新导入，而不是崩在番剧页
                cache = new ArrayList<>();
                return new ArrayList<>();
            }
            cache = list;
            return new ArrayList<>(list);
        }
    }

    /** 外部改过 SP（如恢复备份）时手动失效 */
    public static void invalidateCache() {
        synchronized (LOCK) {
            cache = null;
        }
    }

    public static void save(List<SourceInfo> sources) {
        synchronized (LOCK) {
            JSONArray array = new JSONArray();
            if (sources != null) {
                for (SourceInfo info : sources) {
                    if (info == null) continue;
                    JSONObject object = toJson(info);
                    if (object != null) array.put(object);
                }
            }
            cache = sources == null ? new ArrayList<>() : new ArrayList<>(sources);
            SharedPreferencesUtil.putString(KEY, array.toString());
        }
    }

    /**
     * 合并导入：按 id 覆盖同一条，其余追加。
     *
     * @return 新增条数（覆盖的不计），供 UI 显示「新增 n 条 / 更新 m 条」
     */
    public static int upsert(List<SourceInfo> incoming) {
        if (incoming == null || incoming.isEmpty()) return 0;
        synchronized (LOCK) {
            List<SourceInfo> current = load();
            int added = 0;
            for (SourceInfo info : incoming) {
                if (info == null || info.id == null || info.id.isEmpty()) continue;
                int index = indexOf(current, info.id);
                if (index >= 0) {
                    //保留用户本地状态：启停、优先级、健康度不因订阅更新被重置
                    SourceInfo old = current.get(index);
                    info.enabled = old.enabled;
                    info.lastStatus = old.lastStatus;
                    info.lastError = old.lastError;
                    info.lastUsedAt = old.lastUsedAt;
                    current.set(index, info);
                } else {
                    current.add(info);
                    added++;
                }
            }
            save(current);
            return added;
        }
    }

    public static boolean setEnabled(String id, boolean enabled) {
        return update(id, info -> info.enabled = enabled);
    }

    public static boolean setTier(String id, int tier) {
        return update(id, info -> info.tier = Math.max(0, tier));
    }

    public static boolean remove(String id) {
        synchronized (LOCK) {
            List<SourceInfo> current = load();
            int index = indexOf(current, id);
            if (index < 0) return false;
            current.remove(index);
            save(current);
            return true;
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            save(Collections.emptyList());
        }
    }

    /** 调整顺序：delta = -1 上移，+1 下移（同 tier 内的次级排序） */
    public static boolean move(String id, int delta) {
        synchronized (LOCK) {
            List<SourceInfo> current = load();
            int index = indexOf(current, id);
            if (index < 0) return false;
            int target = index + delta;
            if (target < 0 || target >= current.size()) return false;
            Collections.swap(current, index, target);
            save(current);
            return true;
        }
    }

    public static int count() {
        return load().size();
    }

    public static int countEnabled() {
        int count = 0;
        for (SourceInfo info : load()) if (info.enabled) count++;
        return count;
    }

    private interface Change {
        void apply(SourceInfo info);
    }

    private static boolean update(String id, Change change) {
        synchronized (LOCK) {
            List<SourceInfo> current = load();
            int index = indexOf(current, id);
            if (index < 0) return false;
            change.apply(current.get(index));
            save(current);
            return true;
        }
    }

    private static int indexOf(List<SourceInfo> list, String id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id != null && list.get(i).id.equals(id)) return i;
        }
        return -1;
    }

    private static JSONObject toJson(SourceInfo info) {
        try {
            JSONObject object = new JSONObject();
            object.put("id", info.id == null ? "" : info.id);
            object.put("name", info.name == null ? "" : info.name);
            object.put("type", info.type == null ? "ANIMEKO_SELECTOR" : info.type.name());
            object.put("baseUrl", info.baseUrl == null ? "" : info.baseUrl);
            object.put("configVersion", info.configVersion == null ? "" : info.configVersion);
            object.put("factoryId", info.factoryId == null ? "" : info.factoryId);
            object.put("tier", info.tier);
            object.put("enabled", info.enabled);
            object.put("lastStatus", info.lastStatus);
            object.put("lastError", info.lastError == null ? "" : info.lastError);
            object.put("lastUsedAt", info.lastUsedAt);
            object.put("rawConfig", info.rawConfig == null ? "" : info.rawConfig);
            return object;
        } catch (Exception e) {
            return null;
        }
    }

    private static SourceInfo fromJson(JSONObject object) {
        if (object == null) return null;
        String raw = object.optString("rawConfig", "");
        String name = object.optString("name", "");
        if (name.isEmpty() || raw.isEmpty()) return null;
        SourceInfo info = new SourceInfo();
        info.id = object.optString("id", "");
        info.name = name;
        info.rawConfig = raw;
        info.type = parseType(object.optString("type", ""));
        info.baseUrl = object.optString("baseUrl", "");
        info.configVersion = object.optString("configVersion", "");
        info.factoryId = object.optString("factoryId", "");
        info.tier = object.optInt("tier", 2);
        info.enabled = object.optBoolean("enabled", true);
        info.lastStatus = object.optInt("lastStatus", 0);
        info.lastError = object.optString("lastError", "");
        info.lastUsedAt = object.optLong("lastUsedAt", 0);
        if (info.id.isEmpty()) info.id = SourceSubscriptions.buildId(info);
        return info;
    }

    private static SourceType parseType(String name) {
        try {
            return SourceType.valueOf(name);
        } catch (Exception e) {
            return SourceType.ANIMEKO_SELECTOR;
        }
    }
}
