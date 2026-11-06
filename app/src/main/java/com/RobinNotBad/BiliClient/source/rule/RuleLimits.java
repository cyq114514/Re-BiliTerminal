package com.RobinNotBad.BiliClient.source.rule;

/**
 * 远程规则是不可信输入，所有「会随远程内容膨胀」的量都在这里封顶。
 *
 * <p>这些数字不是拍脑袋：项目要跑在手表和低端机上，一次解析占住几十 MB
 * 或几千个节点就会把整机拖死；规则作者写错一个 `//div` 也可能选出上万个节点。
 */
public final class RuleLimits {

    private RuleLimits() {
    }

    /** 单个订阅配置文件最大字节（online.json 实测约 170KB，留 4 倍余量） */
    public static final int MAX_CONFIG_BYTES = 1024 * 1024;
    /** 单条规则 JSON 最大长度 */
    public static final int MAX_RULE_CHARS = 200 * 1024;
    /** 单个页面 HTML 最大字节 */
    public static final int MAX_PAGE_BYTES = 4 * 1024 * 1024;
    /** 单次搜索最多保留的结果条数 */
    public static final int MAX_SEARCH_RESULTS = 60;
    /** 单作品最多线路数 */
    public static final int MAX_CHANNELS = 60;
    /** 单线路最多剧集数 */
    public static final int MAX_EPISODES_PER_CHANNEL = 500;
    /** 订阅里最多接受多少条源（超过直接拒绝，防恶意订阅撑爆内存） */
    public static final int MAX_SOURCES = 300;
    /** 正则表达式最大长度 */
    public static final int MAX_REGEX_CHARS = 512;
    /** 正则匹配时允许的最大输入长度（超长文本截断，防回溯爆炸） */
    public static final int MAX_REGEX_INPUT_CHARS = 8192;
    /** HTTP 连接超时 */
    public static final int CONNECT_TIMEOUT_SECONDS = 8;
    /** HTTP 读取超时 */
    public static final int READ_TIMEOUT_SECONDS = 12;
    /**
     * 单个源的搜索总超时。
     *
     * <p>readTimeout 只管「两次读之间」，站点慢吞吞吐字节能把单源拖到 12s+；
     * 60 个源批量搜索时这种长尾会把整页拖到一分钟以上，所以搜索用更短的整调用超时。
     */
    public static final long SEARCH_TIMEOUT_MS = 8_000L;
    /**
     * 取线路/取播放页的单次请求超时。
     *
     * <p>比搜索宽松一点（详情页可能要等站点渲染），但必须有总闸：否则一条慢线路
     * 就能把「点一集」拖到十几秒，而用户感知是「点了没反应」。
     */
    public static final long RESOLVE_PAGE_TIMEOUT_MS = 10_000L;
    /** 媒体解析（含 WebView）总超时 */
    public static final long RESOLVE_TIMEOUT_MS = 25_000L;
    /** 单次网页嗅探超时：比解析总预算短，失败就换线路，别让用户干等 */
    public static final long SNIFF_TIMEOUT_MS = 15_000L;
    /** 嵌套播放页最多跟进层数 */
    public static final int MAX_NESTED_PAGES = 2;
    /** 并发搜索的数据源上限（手表性能兜底） */
    public static final int MAX_CONCURRENT_SOURCES = 4;
    /** 自动故障切换最多尝试的线路数 */
    public static final int MAX_FAILOVER_ATTEMPTS = 4;
}
