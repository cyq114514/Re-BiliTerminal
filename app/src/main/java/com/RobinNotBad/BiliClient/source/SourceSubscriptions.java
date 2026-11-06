package com.RobinNotBad.BiliClient.source;

import com.RobinNotBad.BiliClient.source.model.SourceInfo;
import com.RobinNotBad.BiliClient.source.model.SourceException;
import com.RobinNotBad.BiliClient.source.net.SourceHttp;
import com.RobinNotBad.BiliClient.source.net.UrlGuard;
import com.RobinNotBad.BiliClient.source.rule.RuleLimits;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Animeko 数据源配置的解析：把订阅 / 导出 / 单条配置统一成 {@link SourceInfo} 列表。
 *
 * <p>支持三种实际存在的形态（都按「实测样本」写的，不是按文档猜的）：
 * <ol>
 *   <li>导出格式：{@code {"exportedMediaSourceDataList":{"mediaSources":[…]}}}（animeko-source/dist/online.json）</li>
 *   <li>订阅格式：{@code {"schemaVersion":2,"mediaSources":[…]}}</li>
 *   <li>单条：{@code {"factoryId":"web-selector","version":2,"arguments":{…}}}，或它的数组</li>
 * </ol>
 *
 * <p>只认识 {@code web-selector} v2（本端已实现）；其余类型计数后跳过，并在 UI 上明说
 * 「跳过 N 条不支持的类型」，不假装导入成功。
 */
public final class SourceSubscriptions {

    public static final String FACTORY_SELECTOR = "web-selector";
    public static final String FACTORY_JSON_API = "json-api";

    private SourceSubscriptions() {
    }

    public static final class Parsed {
        public final List<SourceInfo> sources = new ArrayList<>();
        /** 类型暂不支持（json-api / rss / jellyfin …） */
        public int skippedUnsupported;
        /** 结构不完整（缺 name / arguments 不是对象） */
        public int skippedInvalid;
        /** 非空表示整份配置不可用 */
        public String error = "";

        public boolean ok() {
            return error.isEmpty() && !sources.isEmpty();
        }

        public String summary() {
            StringBuilder builder = new StringBuilder();
            builder.append("可用 ").append(sources.size()).append(" 条");
            if (skippedUnsupported > 0) builder.append("，跳过不支持的类型 ").append(skippedUnsupported).append(" 条");
            if (skippedInvalid > 0) builder.append("，跳过不完整 ").append(skippedInvalid).append(" 条");
            return builder.toString();
        }
    }

    public static Parsed parse(String json) {
        Parsed parsed = new Parsed();
        if (json == null || json.trim().isEmpty()) {
            parsed.error = "配置内容为空";
            return parsed;
        }
        if (json.length() > RuleLimits.MAX_CONFIG_BYTES) {
            parsed.error = "配置过大（超过 " + (RuleLimits.MAX_CONFIG_BYTES / 1024) + " KB）";
            return parsed;
        }
        try {
            String text = json.trim();
            if (text.startsWith("[")) {
                collect(new JSONArray(text), parsed);
            } else {
                JSONObject root = new JSONObject(text);
                collect(root, parsed);
            }
        } catch (Exception e) {
            parsed.error = "不是合法 JSON：" + e.getMessage();
        }
        if (parsed.error.isEmpty() && parsed.sources.isEmpty()) {
            parsed.error = "没有解析到任何数据源（" + parsed.summary() + "）";
        }
        if (parsed.sources.size() > RuleLimits.MAX_SOURCES) {
            parsed.error = "数据源过多（" + parsed.sources.size() + " > " + RuleLimits.MAX_SOURCES + "）";
            parsed.sources.clear();
        }
        return parsed;
    }

    private static void collect(JSONObject root, Parsed parsed) {
        JSONObject list = root.optJSONObject("exportedMediaSourceDataList");
        if (list != null) {
            collect(list.optJSONArray("mediaSources"), parsed);
            return;
        }
        JSONArray mediaSources = root.optJSONArray("mediaSources");
        if (mediaSources != null) {
            collect(mediaSources, parsed);
            return;
        }
        //单条配置
        add(root, parsed);
    }

    private static void collect(JSONArray array, Parsed parsed) {
        if (array == null) return;
        for (int i = 0; i < array.length(); i++) {
            JSONObject entry = array.optJSONObject(i);
            if (entry == null) {
                parsed.skippedInvalid++;
                continue;
            }
            add(entry, parsed);
        }
    }

    private static void add(JSONObject entry, Parsed parsed) {
        if (entry == null) return;
        String factoryId = entry.optString("factoryId", "");
        JSONObject arguments = entry.optJSONObject("arguments");
        if (factoryId.isEmpty() || arguments == null) {
            parsed.skippedInvalid++;
            return;
        }
        String name = arguments.optString("name", "").trim();
        if (name.isEmpty()) {
            parsed.skippedInvalid++;
            return;
        }
        SourceType type = typeOf(factoryId);
        if (type == null || type == SourceType.ANIMEKO_JSON_API) {
            //json-api 的类型先占位；本端暂无实现，不冒充支持
            parsed.skippedUnsupported++;
            return;
        }
        SourceInfo info = new SourceInfo();
        info.name = name;
        info.factoryId = factoryId;
        info.type = type;
        info.configVersion = String.valueOf(entry.opt("version"));
        info.tier = arguments.optInt("tier", 2);
        info.enabled = true;
        info.rawConfig = entry.toString();
        JSONObject search = arguments.optJSONObject("searchConfig");
        String searchUrl = search == null ? "" : search.optString("searchUrl", "");
        info.baseUrl = UrlGuard.originOf(searchUrl);
        info.id = buildId(info);
        parsed.sources.add(info);
    }

    /** id = 生态前缀 : factoryId : 名称 —— 与 Animeko 用 arguments.name 当 id 的语义对齐 */
    public static String buildId(SourceInfo info) {
        String namespace = info.type == null ? "animeko" : info.type.namespace;
        String factory = info.factoryId == null || info.factoryId.isEmpty()
                ? FACTORY_SELECTOR : info.factoryId;
        return namespace + ":" + factory + ":" + (info.name == null ? "" : info.name);
    }

    private static SourceType typeOf(String factoryId) {
        String id = factoryId == null ? "" : factoryId.trim().toLowerCase(Locale.ROOT);
        if (FACTORY_SELECTOR.equals(id)) return SourceType.ANIMEKO_SELECTOR;
        if (FACTORY_JSON_API.equals(id)) return SourceType.ANIMEKO_JSON_API;
        return null;
    }

    /**
     * 从订阅地址拉取配置。
     *
     * <p>只允许 https（本项目的明文策略不放开，http 订阅会被 {@link UrlGuard} 拒绝并明确报错，
     * 而不是静默失败）；体积上限与页面一致。
     */
    public static String fetch(String url) throws SourceException {
        String trimmed = url == null ? "" : url.trim();
        if (trimmed.isEmpty()) throw SourceException.config("订阅", "地址为空");
        if (!trimmed.toLowerCase(Locale.ROOT).startsWith("https://"))
            throw SourceException.config("订阅", "只支持 https 订阅地址");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json,text/plain,*/*");
        try {
            SourceHttp.Response response = SourceHttp.get(trimmed, headers);
            if (!response.isSuccessful())
                throw SourceException.http("订阅", SourceException.Phase.CONFIG, response.code);
            String text = response.text();
            if (text != null && text.length() > RuleLimits.MAX_CONFIG_BYTES)
                throw SourceException.config("订阅", "订阅内容过大");
            return text == null ? "" : text;
        } catch (SecurityException e) {
            throw SourceException.config("订阅", "地址不合法：" + e.getMessage());
        } catch (java.io.IOException e) {
            throw new SourceException(SourceException.Phase.CONFIG, SourceException.Kind.NETWORK,
                    "订阅", "网络失败：" + e.getClass().getSimpleName(), e);
        }
    }
}
