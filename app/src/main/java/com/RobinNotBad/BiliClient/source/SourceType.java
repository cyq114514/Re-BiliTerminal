package com.RobinNotBad.BiliClient.source;

/**
 * 数据源类型。
 *
 * <p>四类必须分开：Animeko 的 CSS Selector / JSON API 与 Kazumi 的 XPath / API
 * 是两套互不相同的规则语言（字段名、变量语法、相对路径语义都不同），
 * 把 Kazumi 的 JSONPath 当成 Animeko JSON API 解析，结果一定是「看着像但跑不通」。
 */
public enum SourceType {

    /** Animeko web-selector：CSS Selector + 命名捕获组正则 */
    ANIMEKO_SELECTOR("Animeko Selector", "animeko"),
    /** Animeko json-api：三阶段请求模板 + JSONPath */
    ANIMEKO_JSON_API("Animeko JSON API", "animeko"),
    /** Kazumi XPath 规则（searchMode/chapterMode 均为 xpath） */
    KAZUMI_XPATH("Kazumi XPath", "kazumi"),
    /** Kazumi API 规则（searchMode 或 chapterMode 为 api，允许与 xpath 混用） */
    KAZUMI_API("Kazumi API", "kazumi");

    public final String label;
    /** 生态命名空间，参与 sourceId 生成，避免两个生态同名源互相覆盖 */
    public final String namespace;

    SourceType(String label, String namespace) {
        this.label = label;
        this.namespace = namespace;
    }
}
