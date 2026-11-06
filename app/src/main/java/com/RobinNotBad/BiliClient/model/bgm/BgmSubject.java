package com.RobinNotBad.BiliClient.model.bgm;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Bangumi 条目（仅覆盖本项目展示所需的公开字段）。
 *
 * <p>命名空间约定：{@link #id} 是 Bangumi Subject ID，只能用于元数据。
 * 第三方站点（B站/Animeko/Kazumi）的番剧 ID 必须另建映射（见 BgmMappingStore），
 * 绝不能拿 bgm id 去当 media_id / sourceId 用。
 */
public class BgmSubject implements Serializable {

    public long id;

    /** 原名（日文/原文） */
    public String name = "";
    /** 中文名，可能为空 */
    public String nameCn = "";

    public String summary = "";
    /** 开播日期 yyyy-MM-dd，可能为空（未定档） */
    public String date = "";
    /** TV / WEB / 剧场版 / OVA ... */
    public String platform = "";

    /** 列表缩略图（约 100px，省流量） */
    public String coverGrid = "";
    /** 详情用封面（约 400px） */
    public String coverCommon = "";
    public String coverLarge = "";

    public double score = 0;
    /** Bangumi 排名，0 表示暂无排名 */
    public int rank = 0;
    public int ratingTotal = 0;

    /**
     * 放送星期：1=周一 … 7=周日，0=未知。
     * 只来自 /calendar 的 air_weekday 或详情 infobox「放送星期」，
     * 取不到就是 0，不允许按开播日期猜——需求明确禁止把未定档的条目归入某一天。
     */
    public int airWeekday = 0;

    public int totalEpisodes = 0;
    public boolean nsfw = false;

    public List<String> aliases = new ArrayList<>();
    public List<String> tags = new ArrayList<>();

    public String title() {
        if (nameCn != null && !nameCn.isEmpty()) return nameCn;
        return name == null ? "" : name;
    }

    /** 原名与中文名不同时才需要展示原名 */
    public String originalTitle() {
        if (name == null || name.isEmpty()) return "";
        if (name.equals(nameCn)) return "";
        return name;
    }

    public boolean hasScore() {
        return score > 0 && ratingTotal > 0;
    }
}
