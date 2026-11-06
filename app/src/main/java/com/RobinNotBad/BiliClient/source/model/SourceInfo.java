package com.RobinNotBad.BiliClient.source.model;

import com.RobinNotBad.BiliClient.source.SourceType;

import java.io.Serializable;

/**
 * 数据源元信息（一个可启用的源）。
 *
 * <p>{@link #rawConfig} 保存的是原始规则 JSON（Animeko 的一个 mediaSource 条目 /
 * Kazumi 的一个规则对象）。这样做的原因：规则字段随上游演进，先按字段解析成一个
 * 稳定模型会丢掉新字段；保存原文可以在不升级 App 的情况下重新解析。
 */
public class SourceInfo implements Serializable {

    /** 全局唯一：type + 序号（或订阅内 id） */
    public String id = "";
    public String name = "";
    public SourceType type = SourceType.ANIMEKO_SELECTOR;
    /** 站点基础地址（Kazumi baseURL；Animeko 从 searchUrl 推导，可能为空） */
    public String baseUrl = "";
    /** 配置版本（Animeko version / Kazumi version 字符串） */
    public String configVersion = "";
    /** 订阅里的 factoryId */
    public String factoryId = "";
    /** 优先级，数值越小越优先（Animeko tier 同义） */
    public int tier = 2;
    public boolean enabled = true;

    /** 最近一次请求状态：0=未用过 1=成功 -1=失败 */
    public int lastStatus = 0;
    /** 最近一次错误（人类可读，便于设置页排障） */
    public String lastError = "";
    public long lastUsedAt = 0;

    /** 规则原文 */
    public String rawConfig = "";

    public String displayName() {
        return name == null || name.isEmpty() ? id : name;
    }
}
