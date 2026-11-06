package com.RobinNotBad.BiliClient.source.animeko;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.RobinNotBad.BiliClient.source.SourceType;
import com.RobinNotBad.BiliClient.source.ThirdPartySource;
import com.RobinNotBad.BiliClient.source.model.SourceChannel;
import com.RobinNotBad.BiliClient.source.model.SourceEpisode;
import com.RobinNotBad.BiliClient.source.model.SourceException;
import com.RobinNotBad.BiliClient.source.model.SourceInfo;
import com.RobinNotBad.BiliClient.source.model.SourceSubject;
import com.RobinNotBad.BiliClient.source.net.SourceHttp;
import com.RobinNotBad.BiliClient.source.net.UrlGuard;
import com.RobinNotBad.BiliClient.source.resolve.MediaRule;
import com.RobinNotBad.BiliClient.source.rule.EpisodeSorts;
import com.RobinNotBad.BiliClient.source.rule.JsonPathLite;
import com.RobinNotBad.BiliClient.source.rule.RuleLimits;
import com.RobinNotBad.BiliClient.source.rule.SafeRegex;
import com.RobinNotBad.BiliClient.source.rule.Templates;
import com.RobinNotBad.BiliClient.source.rule.WebFetch;
import com.RobinNotBad.BiliClient.util.Logu;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * Animeko `web-selector` 源实现（CSS Selector 路线）。
 *
 * <p>流程严格按上游引擎的 4 步：搜索页 → 条目 → 详情页 → 线路/剧集；
 * 第 5 步（播放页取流）交给 {@code MediaResolver}，因为那需要 WebView。
 *
 * <p>三个易错点在这里一次性处理掉：
 * <ol>
 *   <li>搜索结果的相对链接以<b>站点根</b>为基准；</li>
 *   <li>剧集的相对链接以<b>条目页目录</b>为基准（上游文档明确，搞错会 404）；</li>
 *   <li>{@code matchEpisodeSortFromName} 必须用命名分组 {@code ep}，且「正片/高清版」按 1 集算。</li>
 * </ol>
 */
public class AnimekoSelectorSource implements ThirdPartySource {

    private final SourceInfo info;
    private final AnimekoSelectorConfig config;
    private final MediaRule mediaRule;
    private volatile long lastRequestAt = 0;
    private volatile CancelSignal cancelSignal;

    public AnimekoSelectorSource(SourceInfo info) {
        this.info = info;
        this.config = AnimekoSelectorConfig.fromArguments(
                AnimekoSelectorConfig.parseObject(info.rawConfig).getAsJsonObject("arguments"));
        if (this.config.name == null || this.config.name.trim().isEmpty())
            this.config.name = info.name;
        this.mediaRule = new AnimekoMediaRule(config);
        //声明只支持 mpv/vlc 等本平台没有的播放器时直接拒绝，避免用户以为「点了没反应」
        String unsupported = config.unsupportedPlayer();
        if (!unsupported.isEmpty())
            Logu.w("third", info.name + " 声明仅支持播放器 " + unsupported + "，本端可能无法播放");
    }

    @Override
    public SourceInfo info() {
        return info;
    }

    @Override
    public MediaRule mediaRule() {
        return mediaRule;
    }

    @Override
    public void setCancelSignal(CancelSignal signal) {
        this.cancelSignal = signal;
    }

    // ==================== 搜索 ====================

    @Override
    public List<SourceSubject> search(String keyword) throws SourceException {
        if (keyword == null || keyword.trim().isEmpty())
            throw SourceException.config(info.name, "搜索关键词为空");
        String searchTemplate = config.search.searchUrl;
        if (searchTemplate == null || searchTemplate.trim().isEmpty())
            throw SourceException.config(info.name, "规则未配置 searchUrl");

        String cleaned = cleanKeyword(keyword, config.search.searchRemoveSpecial,
                config.search.searchUseOnlyFirstWord);
        final String url = Templates.replaceKeyword(searchTemplate, cleaned);
        String rejection = UrlGuard.check(url);
        if (rejection != null) throw SourceException.config(info.name, "搜索地址不合法：" + rejection);
        //明文 http 在本端会被 network_security_config 直接掐断，报「网络错误」会让人以为是站点挂了
        if (url.toLowerCase(java.util.Locale.ROOT).startsWith("http://"))
            throw SourceException.config(info.name, "该源是明文 http 地址，本端只支持 https");

        throttle();

        String format = config.search.subjectFormatId == null ? "a"
                : config.search.subjectFormatId.trim();
        //诊断日志：完整 URL + 关键词 + 格式。搜索词是用户自己输入的、不含凭证，
        //出问题时没有这一行就无法判断「URL 拼错」还是「站点返回壳页」
        Logu.d("third-diag", info.name + " 搜索 格式=" + format + " 关键词=[" + cleaned + "] URL=" + url);
        try {
            if ("json-path-indexed".equals(format)) return searchFromJson(url);

            //HTML 只抓一次：主格式、备用格式、通用兜底共用同一份文档，避免同一页请求 2~3 次
            Document document = WebFetch.getHtml(url, requestHeaders(), info.name,
                    SourceException.Phase.SEARCH, RuleLimits.SEARCH_TIMEOUT_MS);
            List<SourceSubject> subjects = null;
            SourceException primary = null;
            try {
                subjects = "a".equals(format)
                        ? searchFromAnchors(document, url)
                        : searchFromIndexed(document, url);
            } catch (SourceException e) {
                primary = e;
            }
            if (subjects != null && !subjects.isEmpty()) return subjects;

            //规则里的选择器没命中时换另一种条目格式再试：真实订阅里两种格式的配置往往都写着，
            //站点改版常常只是「a 的结构变了、indexed 的还活着」
            try {
                List<SourceSubject> alternate = "a".equals(format)
                        ? (hasIndexedSelectors() ? searchFromIndexed(document, url) : null)
                        : (hasAnchorSelector() ? searchFromAnchors(document, url) : null);
                if (alternate != null && !alternate.isEmpty()) {
                    Logu.d("third-diag", info.name + " 备用条目格式命中 " + alternate.size() + " 条");
                    return alternate;
                }
            } catch (SourceException ignored) {
                //备用格式失败不覆盖主格式的错误信息
            }

            //通用兜底：从页面里认「详情页链接」。选择器全面失效时仍能列出条目，
            //代价是可能混入侧栏的其它作品，所以日志里明确标「兜底」
            List<SourceSubject> generic = searchGenericAnchors(document, url);
            if (!generic.isEmpty()) {
                Logu.d("third-diag", info.name + " 通用兜底命中 " + generic.size() + " 条");
                return generic;
            }
            if (primary != null) throw primary;
            throw new SourceException(SourceException.Phase.SEARCH, SourceException.Kind.PARSE,
                    info.name, "没有解析到搜索结果（选择器与页面结构不匹配）");
        } catch (SourceException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new SourceException(SourceException.Phase.SEARCH, SourceException.Kind.CONFIG,
                    info.name, "规则配置错误：" + e.getMessage(), e);
        } catch (Throwable t) {
            throw new SourceException(SourceException.Phase.SEARCH, SourceException.Kind.PARSE,
                    info.name, "搜索结果解析失败：" + t.getMessage(), t);
        }
    }

    private boolean hasIndexedSelectors() {
        return config.search.selectorIndexed.selectNames != null
                && !config.search.selectorIndexed.selectNames.trim().isEmpty()
                && config.search.selectorIndexed.selectLinks != null
                && !config.search.selectorIndexed.selectLinks.trim().isEmpty();
    }

    private boolean hasAnchorSelector() {
        return config.search.selectorA.selectLists != null
                && !config.search.selectorA.selectLists.trim().isEmpty();
    }

    /** 详情页链接的通用特征（采集站/番剧站的主流路径），只用来兜底 */
    private static final java.util.regex.Pattern DETAIL_HREF = java.util.regex.Pattern.compile(
            "(?i)(/voddetail/|/acgdetail/|/bgmdetail/|/detail/|/subject/|/anime/|/bangumi/|/vod/|/media/)");

    /**
     * 通用兜底：按「详情页链接特征 + 链接文字/图片 alt」猜条目。
     *
     * <p>只在规则选择器全部失效时走到这里。刻意不做「同标题合并」：站点同名不同季是两条资源，
     * 合并会让用户少选一条。
     */
    private List<SourceSubject> searchGenericAnchors(Document document, String pageUrl) {
        List<SourceSubject> subjects = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        String root = siteRoot(pageUrl);
        for (Element anchor : document.select("a[href]")) {
            if (subjects.size() >= RuleLimits.MAX_SEARCH_RESULTS) break;
            String href = anchor.attr("href");
            if (href == null || href.isEmpty()) continue;
            if (!DETAIL_HREF.matcher(href).find()) continue;
            if (!seen.add(href)) continue;
            String name = anchor.attr("title");
            if (name == null || name.trim().isEmpty()) {
                Element image = anchor.selectFirst("img[alt]");
                name = image != null ? image.attr("alt") : anchor.text();
            }
            if (name == null || name.trim().length() < 2) continue;
            int before = subjects.size();
            addSubject(subjects, name, href, coverOf(anchor), root, "");
            if (subjects.size() == before) continue;
        }
        return subjects;
    }

    private List<SourceSubject> searchFromAnchors(Document document, String url) throws SourceException {
        String selector = config.search.selectorA.selectLists;
        if (selector == null || selector.trim().isEmpty())
            throw SourceException.config(info.name, "条目格式 a 缺少 selectLists");
        List<Element> anchors = document.select(selector);
        //诊断日志：选择器命中数与文档长度是判断「站点改版 / 反爬壳页 / 选择器写错」的唯一线索
        Logu.d("third-diag", info.name + " a分支 命中=" + anchors.size()
                + " 文档=" + (document == null ? 0 : document.outerHtml().length())
                + " 选择器=" + selector);
        List<SourceSubject> subjects = new ArrayList<>();
        String root = siteRoot(url);
        for (Element anchor : anchors) {
            if (subjects.size() >= RuleLimits.MAX_SEARCH_RESULTS) break;
            String name = anchor.attr("title");
            if (name == null || name.trim().isEmpty()) name = anchor.text();
            String href = anchor.attr("href");
            addSubject(subjects, name, href, coverOf(anchor), root,
                    config.search.selectorA.linkTemplate);
        }
        checkNotEmpty(subjects);
        return subjects;
    }

    private List<SourceSubject> searchFromIndexed(Document document, String url) throws SourceException {
        String nameSelector = config.search.selectorIndexed.selectNames;
        String linkSelector = config.search.selectorIndexed.selectLinks;
        if (nameSelector == null || nameSelector.trim().isEmpty())
            throw SourceException.config(info.name, "条目格式 indexed 缺少 selectNames");
        if (linkSelector == null || linkSelector.trim().isEmpty())
            throw SourceException.config(info.name, "条目格式 indexed 缺少 selectLinks");

        List<String> names = new ArrayList<>();
        for (Element element : document.select(nameSelector)) {
            String text = element.text();
            if (text != null && !text.trim().isEmpty()) names.add(text.trim());
        }
        List<String> links = new ArrayList<>();
        List<Element> linkElements = new ArrayList<>();
        for (Element element : document.select(linkSelector)) {
            String href = element.attr("href");
            if (href != null && !href.trim().isEmpty()) {
                links.add(href.trim());
                linkElements.add(element);
            }
        }

        Logu.d("third-diag", info.name + " indexed分支 名称=" + names.size()
                + " 链接=" + links.size() + " 文档=" + document.outerHtml().length()
                + " 名称选择器=" + nameSelector + " 链接选择器=" + linkSelector);
        List<SourceSubject> subjects = new ArrayList<>();
        int size = Math.min(names.size(), links.size());
        String root = siteRoot(url);
        for (int i = 0; i < size && subjects.size() < RuleLimits.MAX_SEARCH_RESULTS; i++) {
            Element linkElement = i < linkElements.size() ? linkElements.get(i) : null;
            addSubject(subjects, names.get(i), links.get(i),
                    linkElement == null ? "" : coverOf(linkElement), root,
                    config.search.selectorIndexed.linkTemplate);
        }
        checkNotEmpty(subjects);
        return subjects;
    }

    private List<SourceSubject> searchFromJson(String url) throws SourceException {
        SourceHttp.Response response = WebFetch.get(url, requestHeaders(), info.name,
                SourceException.Phase.SEARCH, RuleLimits.SEARCH_TIMEOUT_MS);
        String body = response.text();
        JsonElement root;
        try {
            root = JsonParser.parseString(body);
        } catch (Exception e) {
            throw new SourceException(SourceException.Phase.SEARCH, SourceException.Kind.PARSE,
                    info.name, "搜索响应不是 JSON：" + e.getMessage(), e);
        }
        JsonPathLite linkPath = JsonPathLite.parse(config.search.selectorJsonPath.selectLinks);
        JsonPathLite namePath = JsonPathLite.parse(config.search.selectorJsonPath.selectNames);

        List<String> links = new ArrayList<>();
        for (JsonElement element : linkPath.read(root)) {
            String value = JsonPathLite.firstScalar(element);
            if (!value.isEmpty()) links.add(value);
        }
        List<String> names = new ArrayList<>();
        for (JsonElement element : namePath.read(root)) {
            String value = JsonPathLite.firstScalar(element);
            if (!value.isEmpty()) names.add(value);
        }

        List<SourceSubject> subjects = new ArrayList<>();
        int size = Math.min(names.size(), links.size());
        String root2 = siteRoot(url);
        for (int i = 0; i < size && subjects.size() < RuleLimits.MAX_SEARCH_RESULTS; i++) {
            //JSON 结果里没有封面字段（订阅里也没有约定），留空由调用方退回 Bangumi 封面
            addSubject(subjects, names.get(i), links.get(i), "", root2,
                    config.search.selectorJsonPath.linkTemplate);
        }
        checkNotEmpty(subjects);
        return subjects;
    }

    private void addSubject(List<SourceSubject> out, String name, String rawHref, String coverRaw,
                            String root, String linkTemplate) {
        if (name == null || name.trim().isEmpty()) {
            Logu.d("third-diag", info.name + " 丢弃条目：名称为空");
            return;
        }
        if (rawHref == null || rawHref.trim().isEmpty()) {
            Logu.d("third-diag", info.name + " 丢弃条目：链接为空 名称=" + name);
            return;
        }
        String templated = Templates.replaceValue(linkTemplate, rawHref.trim());
        String full = UrlGuard.resolveFromRoot(root, templated);
        if (UrlGuard.check(full) != null) {
            //带上原始 href 与 base，才能看出是相对路径基准错了还是协议不对
            Logu.d("third-diag", info.name + " 丢弃条目：链接非法 raw=" + rawHref
                    + " full=[" + full + "] base=" + root);
            return;
        }
        SourceSubject subject = new SourceSubject();
        subject.sourceId = info.id;
        subject.sourceName = info.name;
        subject.title = name.trim();
        subject.detailUrl = full;
        subject.coverUrl = normalizeCover(root, coverRaw);
        subject.sourceSubjectId = guessId(templated);
        out.add(subject);
    }

    /**
     * 从条目节点附近取封面。
     *
     * <p>采集站的搜索结果一般把 &lt;img&gt; 放在 &lt;a&gt; 里或紧邻的兄弟节点里，
     * 没有统一的属性名，所以按 src/data-src/data-original 依次找，最后退到父节点。
     */
    private static String coverOf(Element anchor) {
        if (anchor == null) return "";
        Element image = anchor.selectFirst("img[src]");
        if (image == null) image = anchor.selectFirst("img[data-src]");
        if (image == null) image = anchor.selectFirst("img[data-original]");
        if (image == null && anchor.parent() != null)
            image = anchor.parent().selectFirst("img[src]");
        if (image == null) return "";
        String src = image.attr("src");
        if (src.trim().isEmpty()) src = image.attr("data-src");
        if (src.trim().isEmpty()) src = image.attr("data-original");
        return src;
    }

    /** 封面地址归一化：相对路径补全、http 升级 https（明文图片在 Glide 里会被拦） */
    private static String normalizeCover(String root, String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String url = UrlGuard.resolveFromRoot(root, raw.trim());
        if (url.isEmpty()) return "";
        if (url.startsWith("http://")) url = "https://" + url.substring(7);
        return UrlGuard.check(url) == null ? url : "";
    }

    private void checkNotEmpty(List<SourceSubject> subjects) throws SourceException {
        if (subjects.isEmpty())
            throw new SourceException(SourceException.Phase.SEARCH, SourceException.Kind.PARSE,
                    info.name, "没有解析到搜索结果（选择器与页面结构不匹配）");
    }

    // ==================== 线路 / 剧集 ====================

    @Override
    public List<SourceChannel> channels(SourceSubject subject) throws SourceException {
        if (subject == null || subject.detailUrl == null || subject.detailUrl.isEmpty())
            throw SourceException.config(info.name, "作品详情地址为空");
        if (subject.detailUrl.toLowerCase(java.util.Locale.ROOT).startsWith("http://"))
            throw SourceException.config(info.name, "该源是明文 http 地址，本端只支持 https");
        throttle();
        Document page = WebFetch.getHtml(subject.detailUrl, requestHeaders(), info.name,
                SourceException.Phase.CHANNEL, RuleLimits.RESOLVE_PAGE_TIMEOUT_MS);

        String format = config.search.channelFormatId == null ? "no-channel"
                : config.search.channelFormatId.trim();
        List<SourceChannel> channels;
        try {
            if ("no-channel".equals(format)) channels = channelsNoChannel(page, subject);
            else channels = channelsIndexGrouped(page, subject);
        } catch (SourceException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new SourceException(SourceException.Phase.CHANNEL, SourceException.Kind.CONFIG,
                    info.name, "规则配置错误：" + e.getMessage(), e);
        } catch (Throwable t) {
            throw new SourceException(SourceException.Phase.CHANNEL, SourceException.Kind.PARSE,
                    info.name, "线路解析失败：" + t.getMessage(), t);
        }
        if (channels.isEmpty())
            throw new SourceException(SourceException.Phase.CHANNEL, SourceException.Kind.PARSE,
                    info.name, "没有解析到剧集（选择器与页面结构不匹配）");
        return channels;
    }

    private List<SourceChannel> channelsIndexGrouped(Document page, SourceSubject subject)
            throws SourceException {
        AnimekoSelectorConfig.ChannelFormatIndexed cf = config.search.channelIndexed;
        if (cf.selectChannelNames == null || cf.selectChannelNames.trim().isEmpty())
            throw SourceException.config(info.name, "index-grouped 缺少 selectChannelNames");
        if (cf.selectEpisodeLists == null || cf.selectEpisodeLists.trim().isEmpty())
            throw SourceException.config(info.name, "index-grouped 缺少 selectEpisodeLists");
        if (cf.selectEpisodesFromList == null || cf.selectEpisodesFromList.trim().isEmpty())
            throw SourceException.config(info.name, "index-grouped 缺少 selectEpisodesFromList");

        SafeRegex channelRegex = SafeRegex.compileOrThrow(cf.matchChannelName, "matchChannelName");
        List<Element> nameElements = page.select(cf.selectChannelNames);
        List<String> labels = new ArrayList<>();
        for (Element element : nameElements) {
            String text = element.text();
            if (text == null || text.trim().isEmpty()) {
                labels.add(null);
                continue;
            }
            String label = text.trim();
            String channelName = channelRegex == null ? label : channelRegex.groupOrFullText(label, "ch");
            labels.add(channelName == null ? null : label + "\u0000" + channelName);
        }

        List<Element> lists = page.select(cf.selectEpisodeLists);
        //诊断日志：线路名/剧集容器的命中数，是判断「站点改版」还是「规则写错」的直接依据
        Logu.d("third-diag", info.name + " 线路 名称=" + nameElements.size()
                + " 剧集容器=" + lists.size() + " 文档=" + page.outerHtml().length()
                + " 线路选择器=" + cf.selectChannelNames);
        List<SourceChannel> channels = new ArrayList<>();
        int size = Math.min(lists.size(), labels.size());
        for (int i = 0; i < size && channels.size() < RuleLimits.MAX_CHANNELS; i++) {
            String packed = labels.get(i);
            if (packed == null) continue;
            int split = packed.indexOf('\u0000');
            String label = packed.substring(0, split);
            String channelName = packed.substring(split + 1);
            List<SourceEpisode> episodes = collectEpisodes(lists.get(i),
                    cf.selectEpisodesFromList, cf.selectEpisodeLinksFromList,
                    cf.matchEpisodeSortFromName, subject);
            if (episodes.isEmpty()) continue;
            SourceChannel channel = new SourceChannel(
                    info.id + "|" + subject.key() + "|" + i,
                    channelName == null || channelName.isEmpty() ? label : channelName,
                    i, info.id);
            channel.episodes.addAll(episodes);
            rebind(channel, subject);
            channels.add(channel);
        }
        return channels;
    }

    private List<SourceChannel> channelsNoChannel(Document page, SourceSubject subject)
            throws SourceException {
        AnimekoSelectorConfig.ChannelFormatNoChannel cf = config.search.channelNoChannel;
        if (cf.selectEpisodes == null || cf.selectEpisodes.trim().isEmpty())
            throw SourceException.config(info.name, "no-channel 缺少 selectEpisodes");
        List<SourceEpisode> episodes = collectEpisodes(page, cf.selectEpisodes,
                cf.selectEpisodeLinks, cf.matchEpisodeSortFromName, subject);
        Logu.d("third-diag", info.name + " 无线路 剧集=" + episodes.size()
                + " 文档=" + page.outerHtml().length() + " 选择器=" + cf.selectEpisodes);
        List<SourceChannel> channels = new ArrayList<>();
        if (episodes.isEmpty()) return channels;
        SourceChannel channel = new SourceChannel(info.id + "|" + subject.key() + "|0",
                "默认线路", 0, info.id);
        channel.episodes.addAll(episodes);
        rebind(channel, subject);
        channels.add(channel);
        return channels;
    }

    private List<SourceEpisode> collectEpisodes(Element scope, String episodesSelector,
                                                String linksSelector, String sortRegexText,
                                                SourceSubject subject) {
        SafeRegex sortRegex = SafeRegex.compile(sortRegexText);
        List<Element> episodeElements = scope.select(episodesSelector);
        List<String> overrideLinks = new ArrayList<>();
        if (linksSelector != null && !linksSelector.trim().isEmpty()) {
            for (Element element : scope.select(linksSelector)) {
                String href = element.attr("href");
                if (href != null && !href.trim().isEmpty()) overrideLinks.add(href.trim());
            }
        }
        List<SourceEpisode> episodes = new ArrayList<>();
        for (int i = 0; i < episodeElements.size(); i++) {
            if (episodes.size() >= RuleLimits.MAX_EPISODES_PER_CHANNEL) break;
            Element element = episodeElements.get(i);
            String name = element.text();
            name = name == null ? "" : name.trim();
            String href = i < overrideLinks.size() ? overrideLinks.get(i) : element.attr("href");
            String playUrl = UrlGuard.resolveFromDirectory(subject.detailUrl, href);
            if (playUrl == null || playUrl.isEmpty()) continue;
            if (UrlGuard.check(playUrl) != null) continue;

            SourceEpisode episode = new SourceEpisode();
            episode.sourceId = info.id;
            episode.index = episodes.size();
            episode.name = name;
            String raw = sortRegex == null ? null : sortRegex.groupOrFullText(name, "ep");
            double sort = EpisodeSorts.parseOrNaN(raw);
            if (Double.isNaN(sort)) sort = EpisodeSorts.parseOrNaN(name);
            if (Double.isNaN(sort)) {
                sort = EpisodeSorts.isPossiblyMovie(name) ? 1 : episodes.size() + 1;
            }
            episode.sort = sort;
            episode.sortRaw = raw == null ? "" : raw.trim();
            episode.pageUrl = playUrl;
            episodes.add(episode);
        }
        return episodes;
    }

    private void rebind(SourceChannel channel, SourceSubject subject) {
        for (int i = 0; i < channel.episodes.size(); i++) {
            SourceEpisode episode = channel.episodes.get(i);
            episode.channelId = channel.id;
            episode.channelIndex = channel.index;
            episode.index = i;
            episode.sourceId = info.id;
        }
    }

    // ==================== 工具 ====================

    /** 站点根（搜索结果链接的解析基准） */
    private String siteRoot(String url) {
        String raw = config.search.rawBaseUrl;
        if (raw != null && !raw.trim().isEmpty()) {
            String origin = UrlGuard.originOf(raw.trim());
            if (!origin.isEmpty()) return origin;
        }
        String origin = UrlGuard.originOf(url);
        return origin.isEmpty() ? url : origin;
    }

    private java.util.Map<String, String> requestHeaders() {
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        String ua = config.search.userAgent;
        if (ua != null && !ua.trim().isEmpty()) headers.put("User-Agent", ua.trim());
        String referer = config.search.matchVideo.referer;
        if (referer != null && !referer.trim().isEmpty()) headers.put("Referer", referer.trim());
        return headers;
    }

    /** requestInterval：同一源连续请求之间的最小间隔（上游按源节流） */
    private void throttle() throws SourceException {
        int interval = config.search.requestInterval;
        if (interval <= 0) return;
        long wait = lastRequestAt + interval - System.currentTimeMillis();
        lastRequestAt = System.currentTimeMillis();
        if (wait <= 0) return;
        long capped = Math.min(wait, 5000);
        try {
            Thread.sleep(capped);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceException(SourceException.Phase.SEARCH,
                    SourceException.Kind.NETWORK, info.name, "已取消", e);
        }
        CancelSignal signal = cancelSignal;
        if (signal != null && signal.isCancelled())
            throw new SourceException(SourceException.Phase.SEARCH,
                    SourceException.Kind.NETWORK, info.name, "已取消");
    }

    /**
     * Animeko 的搜索关键词清洗：先去标记词/特殊符号，再按空格取首词。
     *
     * <p>为什么先去标记词：上游注释写明「剧场版 命运石之门」取首词会只剩「剧场版」，
     * 搜索必然失败。
     */
    static String cleanKeyword(String keyword, boolean removeSpecial, boolean onlyFirstWord) {
        String text = keyword == null ? "" : keyword.trim();
        if (removeSpecial) text = removeSpecials(text);
        if (onlyFirstWord) {
            int space = text.indexOf(' ');
            if (space > 0) text = text.substring(0, space);
        }
        if (text.trim().isEmpty()) text = keyword == null ? "" : keyword.trim();
        return text.trim();
    }

    /**
     * 移除「剧场版 / OVA / 第X季」等标记与常见标点。
     *
     * <p>这是对上游 {@code MediaListFilters.removeSpecials} 的行为近似实现：
     * 目标是把「标题里会干扰站点搜索的装饰词」去掉；边界情况（非常规标点、
     * 罗马数字季数）可能与上游有差异，已在交付报告中列为已知限制。
     */
    static String removeSpecials(String text) {
        if (text == null) return "";
        String result = text;
        String[] markers = {"剧场版", "总集篇", "特别篇", "OVA", "OAD", "TV版", "第1季", "第2季",
                "第3季", "第4季", "第二季", "第三季", "第二期", "第三期"};
        for (String marker : markers) result = result.replace(marker, " ");
        result = result.replaceAll("(?i)\\bseason\\s*\\d+", " ");
        result = result.replaceAll("(?i)\\bs\\d+\\b", " ");
        result = result.replaceAll("[\\p{Punct}，。、！？：；…—“”‘’《》〈〉【】（）「」『』·～]", " ");
        result = result.replaceAll("\\s{2,}", " ");
        return result.trim();
    }

    /** 对齐上游 guessIdFromUrl：去掉尾部斜杠、去掉 .html、取最后一段 */
    static String guessId(String href) {
        if (href == null) return "";
        String clean = href.trim();
        while (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
        int html = clean.lastIndexOf(".html");
        if (html > 0) clean = clean.substring(0, html);
        int slash = clean.lastIndexOf('/');
        if (slash >= 0) clean = clean.substring(slash + 1);
        int question = clean.indexOf('?');
        if (question >= 0) clean = clean.substring(0, question);
        return clean;
    }

    /** 供订阅解析使用：类型判定 */
    public static SourceType typeOf() {
        return SourceType.ANIMEKO_SELECTOR;
    }
}
