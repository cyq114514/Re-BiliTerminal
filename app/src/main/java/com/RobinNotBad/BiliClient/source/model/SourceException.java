package com.RobinNotBad.BiliClient.source.model;

/**
 * 第三方源统一异常：带「阶段 + 分类」，用于日志与故障切换判定。
 *
 * <p>为什么要分类而不是只扔 IOException：自动故障切换必须区分
 * 「地址失效」与「普通缓冲/用户暂停」，只有前者才允许换线路。
 * 这里把判定依据固化成 {@link Kind}，避免在 UI 层靠字符串猜。
 */
public class SourceException extends Exception {

    /** 出错阶段 */
    public enum Phase {
        CONFIG("配置"),
        SEARCH("搜索"),
        CHANNEL("线路"),
        EPISODE("选集"),
        RESOLVE("解析播放地址"),
        UNSUPPORTED("不支持的能力");

        public final String label;

        Phase(String label) {
            this.label = label;
        }
    }

    /** 错误分类，决定能不能自动切换 */
    public enum Kind {
        /** 规则/配置本身错误（字段缺失、JSONPath 不合法）——换线路也没用 */
        CONFIG(false),
        /** 网络不可达/超时——可换源重试，但不是线路失效 */
        NETWORK(false),
        /** HTTP 403 / 404 / 5xx —— 地址失效类，允许换线路 */
        HTTP(false),
        /** 解析失败（页面结构变了）——允许换线路 */
        PARSE(false),
        /** 需要 WebView/验证码等本版本不支持的能力 */
        UNSUPPORTED(false),
        /** 媒体地址已过期或疑似失效——应当换线路 */
        MEDIA_INVALID(true);

        /** 是否属于「换一条线路/源就可能成功」的可切换错误 */
        public final boolean switchable;

        Kind(boolean switchable) {
            this.switchable = switchable;
        }
    }

    public final Phase phase;
    public final Kind kind;
    /** 出错的源（人类可读名），便于日志定位 */
    public final String sourceName;

    public SourceException(Phase phase, Kind kind, String sourceName, String message) {
        super(message);
        this.phase = phase;
        this.kind = kind;
        this.sourceName = sourceName == null ? "" : sourceName;
    }

    public SourceException(Phase phase, Kind kind, String sourceName, String message, Throwable cause) {
        super(message, cause);
        this.phase = phase;
        this.kind = kind;
        this.sourceName = sourceName == null ? "" : sourceName;
    }

    /** 用户可见的一句话（不含堆栈、不含 URL 里的敏感 query） */
    public String userMessage() {
        StringBuilder builder = new StringBuilder();
        if (!sourceName.isEmpty()) builder.append(sourceName).append(' ');
        builder.append(phase.label).append("失败");
        if (getMessage() != null && !getMessage().isEmpty())
            builder.append("：").append(getMessage());
        return builder.toString();
    }

    public static SourceException config(String source, String message) {
        return new SourceException(Phase.CONFIG, Kind.CONFIG, source, message);
    }

    public static SourceException unsupported(String source, String message) {
        return new SourceException(Phase.UNSUPPORTED, Kind.UNSUPPORTED, source, message);
    }

    public static SourceException http(String source, Phase phase, int code) {
        return new SourceException(phase, Kind.HTTP, source, describeHttp(code));
    }

    /**
     * 状态码 → 一句人话。
     *
     * <p>为什么要翻译：用户看到的只有 {@code userMessage()} 这一行，
     * 「HTTP 429」和「HTTP 403」的处置方式完全不同（等一会儿 / 换源或开网页解析），
     * 光给数字等于让用户猜。
     */
    private static String describeHttp(int code) {
        switch (code) {
            case 401:
                return "HTTP 401（站点要求登录）";
            case 403:
                return "HTTP 403（站点拒绝访问）";
            case 404:
                return "HTTP 404（页面不存在）";
            case 429:
                return "HTTP 429（访问过于频繁）";
            case 468:
                return "HTTP 468（站点要求验证码）";
            default:
                return code >= 500 ? "HTTP " + code + "（站点服务器错误）" : "HTTP " + code;
        }
    }
}
