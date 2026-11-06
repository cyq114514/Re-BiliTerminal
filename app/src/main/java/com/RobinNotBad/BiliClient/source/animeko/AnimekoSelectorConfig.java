package com.RobinNotBad.BiliClient.source.animeko;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.RobinNotBad.BiliClient.source.rule.RuleLimits;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Animeko `web-selector` 数据源配置。
 *
 * <p><b>字段全部来自线上真实配置，不是自造格式</b>：
 * 参照 {@code https://raw.githubusercontent.com/MajoSissi/animeko-source/main/dist/online.json}
 * （实测 63 条源，全部 {@code factoryId=web-selector}、{@code version=2}）与
 * Animeko 源码 {@code SelectorSearchConfig / SelectorSubjectFormat / SelectorChannelFormat}。
 *
 * <p>真实取值分布（统计自该订阅）：
 * subjectFormatId = a(41) / indexed(21) / json-path-indexed(1)；
 * channelFormatId = index-grouped(59) / no-channel(4)；
 * 缺失字段（如九兔动漫没有 searchRemoveSpecial）必须能容忍，用默认值而不是报错。
 */
public class AnimekoSelectorConfig {

    public String factoryId = "web-selector";
    public int version = 2;
    public String name = "";
    public String description = "";
    public String iconUrl = "";
    public int tier = 2;
    public SearchConfig search = new SearchConfig();

    // ==================== 搜索/条目/线路/视频 ====================

    public static class SearchConfig {
        public String searchUrl = "";
        public boolean searchUseOnlyFirstWord = false;
        public boolean searchRemoveSpecial = false;
        public int searchUseSubjectNamesCount = 1;
        public String rawBaseUrl = "";
        public int requestInterval = 0;

        /** Animeko 全程使用的 UA（搜索页、详情页、WebView、播放请求） */
        public String userAgent = "";

        //默认值必须与上游一致：缺字段时上游按 a / no-channel 解析（SelectorSearchConfig.kt:86,91），
        //写成 indexed / index-grouped 会让省略这两个键的源直接解析成空结果
        public String subjectFormatId = "a";
        public SubjectFormatA selectorA = new SubjectFormatA();
        public SubjectFormatIndexed selectorIndexed = new SubjectFormatIndexed();
        public SubjectFormatJsonPathIndexed selectorJsonPath = new SubjectFormatJsonPathIndexed();

        public String channelFormatId = "no-channel";
        public ChannelFormatIndexed channelIndexed = new ChannelFormatIndexed();
        public ChannelFormatNoChannel channelNoChannel = new ChannelFormatNoChannel();

        public String defaultResolution = "";
        public String defaultSubtitleLanguage = "";
        public final java.util.List<String> onlySupportsPlayers = new java.util.ArrayList<>();

        public MatchVideo matchVideo = new MatchVideo();
    }

    public static class SubjectFormatA {
        public String selectLists = "div.video-info-header > a";
        public String linkTemplate = "";
    }

    public static class SubjectFormatIndexed {
        public String selectNames = "";
        public String selectLinks = "";
        public String linkTemplate = "";
    }

    public static class SubjectFormatJsonPathIndexed {
        public String selectLinks = "$[*]['url','link']";
        public String selectNames = "$[*]['title','name']";
        public String linkTemplate = "";
    }

    public static class ChannelFormatIndexed {
        public String selectChannelNames = "";
        /** 命名捕获组 ch；空串表示用整段文本 */
        public String matchChannelName = "";
        public String selectEpisodeLists = "";
        public String selectEpisodesFromList = "a";
        public String selectEpisodeLinksFromList = "";
        public String matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]";
    }

    public static class ChannelFormatNoChannel {
        public String selectEpisodes = "";
        public String selectEpisodeLinks = "";
        public String matchEpisodeSortFromName = "第\\s*(?<ep>.+)\\s*[话集]";
    }

    public static class MatchVideo {
        //上游缺省值（SelectorSearchConfig.MatchVideoConfig:150-156），缺字段的源也能取流
        public boolean enableNestedUrl = true;
        public String matchNestedUrl = "^.+(m3u8|vip|xigua\\.php).+\\?";
        public String matchVideoUrl =
                "(^http(s)?:\\/\\/(?!.*http(s)?:\\/\\/).+((\\.mp4)|(\\.mkv)|(m3u8)).*(\\?.+)?)|(akamaized)|(bilivideo.com)";
        /** 每行一个 name=value，加载播放页时注入 WebView */
        public String cookies = "quality=1080";
        public String referer = "";
        public String legacyUserAgent = "";
    }

    // ==================== 解析 ====================

    /**
     * 从 `arguments` 对象解析。
     *
     * @throws IllegalArgumentException 配置结构不可用（缺 name / searchConfig 不是对象等）
     */
    public static AnimekoSelectorConfig fromArguments(JsonObject arguments) {
        if (arguments == null) throw new IllegalArgumentException("缺少 arguments");
        AnimekoSelectorConfig config = new AnimekoSelectorConfig();
        config.name = str(arguments, "name", "");
        config.description = str(arguments, "description", "");
        config.iconUrl = str(arguments, "iconUrl", "");
        config.tier = optInt(arguments, "tier", 2);

        JsonObject search = object(arguments, "searchConfig");
        if (search == null) throw new IllegalArgumentException("缺少 searchConfig");
        SearchConfig sc = config.search;
        sc.searchUrl = str(search, "searchUrl", "");
        sc.rawBaseUrl = str(search, "rawBaseUrl", "");
        sc.requestInterval = optInt(search, "requestInterval", 0);
        sc.userAgent = str(search, "userAgent", "");
        sc.searchUseSubjectNamesCount = Math.max(1, optInt(search, "searchUseSubjectNamesCount", 1));

        // 自动匹配字段既可能在 searchConfig 顶层（旧写法），也可能在 autoMatch 下（新写法）
        JsonObject autoMatch = object(search, "autoMatch");
        JsonObject autoSource = autoMatch == null ? search : autoMatch;
        //上游 SelectorAutoMatchConfig 的缺省是 true/true（SelectorSearchConfig.kt:261-265）
        sc.searchUseOnlyFirstWord = optBool(autoSource, "searchUseOnlyFirstWord", true);
        sc.searchRemoveSpecial = optBool(autoSource, "searchRemoveSpecial", true);

        sc.subjectFormatId = str(search, "subjectFormatId", "a");
        sc.channelFormatId = str(search, "channelFormatId", "no-channel");
        sc.defaultResolution = str(search, "defaultResolution", "");
        sc.defaultSubtitleLanguage = str(search, "defaultSubtitleLanguage", "");

        JsonElement onlyPlayers = search.get("onlySupportsPlayers");
        if (onlyPlayers != null && onlyPlayers.isJsonArray()) {
            for (JsonElement element : onlyPlayers.getAsJsonArray()) {
                if (element != null && element.isJsonPrimitive())
                    sc.onlySupportsPlayers.add(element.getAsString());
            }
        }

        parseSubjectFormats(search, sc);
        parseChannelFormats(search, sc);

        JsonObject matchVideo = object(search, "matchVideo");
        if (matchVideo != null) {
            MatchVideo mv = sc.matchVideo;
            mv.enableNestedUrl = optBool(matchVideo, "enableNestedUrl", false);
            mv.matchNestedUrl = str(matchVideo, "matchNestedUrl", "");
            mv.matchVideoUrl = str(matchVideo, "matchVideoUrl", "");
            mv.cookies = str(matchVideo, "cookies", "");
            JsonObject headers = object(matchVideo, "addHeadersToVideo");
            if (headers != null) {
                mv.referer = str(headers, "referer", "");
                mv.legacyUserAgent = str(headers, "userAgent", "");
            }
        }
        return config;
    }

    private static void parseSubjectFormats(JsonObject search, SearchConfig sc) {
        JsonObject a = object(search, "selectorSubjectFormatA");
        if (a != null) {
            sc.selectorA.selectLists = str(a, "selectLists", sc.selectorA.selectLists);
            sc.selectorA.linkTemplate = str(a, "linkTemplate", "");
        }
        JsonObject indexed = object(search, "selectorSubjectFormatIndexed");
        if (indexed != null) {
            sc.selectorIndexed.selectNames = str(indexed, "selectNames", "");
            sc.selectorIndexed.selectLinks = str(indexed, "selectLinks", "");
            sc.selectorIndexed.linkTemplate = str(indexed, "linkTemplate", "");
        }
        JsonObject jsonPath = object(search, "selectorSubjectFormatJsonPathIndexed");
        if (jsonPath != null) {
            sc.selectorJsonPath.selectLinks = str(jsonPath, "selectLinks",
                    sc.selectorJsonPath.selectLinks);
            sc.selectorJsonPath.selectNames = str(jsonPath, "selectNames",
                    sc.selectorJsonPath.selectNames);
            sc.selectorJsonPath.linkTemplate = str(jsonPath, "linkTemplate", "");
        }
    }

    private static void parseChannelFormats(JsonObject search, SearchConfig sc) {
        // JSON 里的字段名沿用历史命名 Flattened（类名才是 IndexGrouped），两个都认
        JsonObject indexed = object(search, "selectorChannelFormatFlattened");
        if (indexed == null) indexed = object(search, "selectorChannelFormatIndexGrouped");
        if (indexed != null) {
            ChannelFormatIndexed cf = sc.channelIndexed;
            cf.selectChannelNames = str(indexed, "selectChannelNames", "");
            cf.matchChannelName = str(indexed, "matchChannelName", "");
            cf.selectEpisodeLists = str(indexed, "selectEpisodeLists", "");
            cf.selectEpisodesFromList = str(indexed, "selectEpisodesFromList", "a");
            cf.selectEpisodeLinksFromList = str(indexed, "selectEpisodeLinksFromList", "");
            cf.matchEpisodeSortFromName = str(indexed, "matchEpisodeSortFromName",
                    cf.matchEpisodeSortFromName);
        }
        JsonObject noChannel = object(search, "selectorChannelFormatNoChannel");
        if (noChannel != null) {
            ChannelFormatNoChannel cf = sc.channelNoChannel;
            cf.selectEpisodes = str(noChannel, "selectEpisodes", "");
            cf.selectEpisodeLinks = str(noChannel, "selectEpisodeLinks", "");
            cf.matchEpisodeSortFromName = str(noChannel, "matchEpisodeSortFromName",
                    cf.matchEpisodeSortFromName);
        }
    }

    /** 视频请求头：searchConfig.userAgent 优先，兼容旧的 addHeadersToVideo.userAgent */
    public Map<String, String> videoHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        String ua = search.userAgent;
        if (ua == null || ua.trim().isEmpty()) ua = search.matchVideo.legacyUserAgent;
        if (ua != null && !ua.trim().isEmpty()) headers.put("User-Agent", ua.trim());
        String referer = search.matchVideo.referer;
        if (referer != null && !referer.trim().isEmpty()) headers.put("Referer", referer.trim());
        return headers;
    }

    /** 该源是否声明了只支持我们没实现的播放器 */
    public String unsupportedPlayer() {
        if (search.onlySupportsPlayers.isEmpty()) return "";
        for (String player : search.onlySupportsPlayers) {
            if ("exoplayer".equalsIgnoreCase(player)) return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String player : search.onlySupportsPlayers) {
            if (builder.length() > 0) builder.append('/');
            builder.append(player);
        }
        return builder.toString();
    }

    public String toJson() {
        return com.RobinNotBad.BiliClient.source.rule.Templates.encodeComponent(name);
    }

    // ==================== Gson 小工具 ====================

    static JsonObject object(JsonObject parent, String key) {
        if (parent == null) return null;
        JsonElement element = parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    static String str(JsonObject parent, String key, String def) {
        if (parent == null) return def;
        JsonElement element = parent.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return def;
        try {
            String value = element.getAsString();
            return value == null ? def : value;
        } catch (Exception e) {
            return def;
        }
    }

    static boolean optBool(JsonObject parent, String key, boolean def) {
        if (parent == null) return def;
        JsonElement element = parent.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return def;
        try {
            return element.getAsBoolean();
        } catch (Exception e) {
            try {
                return Boolean.parseBoolean(element.getAsString());
            } catch (Exception ignored) {
                return def;
            }
        }
    }

    static int optInt(JsonObject parent, String key, int def) {
        if (parent == null) return def;
        JsonElement element = parent.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return def;
        try {
            return element.getAsInt();
        } catch (Exception e) {
            try {
                return (int) Double.parseDouble(element.getAsString());
            } catch (Exception ignored) {
                return def;
            }
        }
    }

    /** 供测试与订阅解析共用：限制单条规则体积 */
    public static JsonObject parseObject(String raw) {
        if (raw == null || raw.length() > RuleLimits.MAX_RULE_CHARS)
            throw new IllegalArgumentException("规则 JSON 过大或为空");
        JsonElement element = JsonParser.parseString(raw);
        if (element == null || !element.isJsonObject())
            throw new IllegalArgumentException("规则不是 JSON 对象");
        return element.getAsJsonObject();
    }
}
