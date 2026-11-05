package com.RobinNotBad.BiliClient.api;

import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 番剧索引 / 推荐 / 动态筛选条件 API
 * <p>
 * 结果接口: https://api.bilibili.com/pgc/season/index/result
 * 条件接口: https://api.bilibili.com/pgc/season/index/condition
 * <p>
 * 2026-11 迁移记录：
 * 1) 旧结果路径 /pgc/web/season/index/result 已下线（返回 HTML 错误页），改用与 PiliPlus 相同的
 *    /pgc/season/index/result；新响应字段：score 字符串评分、index_show 更新进度、
 *    order 字符串追番数（旧的 rating/styles/order 对象字段已不存在）。
 * 2) 筛选项不再硬编码：condition 接口下发 order[]（排序）与 filter[]（地区/风格/类型/付费/配音/
 *    版权/状态/年份/季度），keyword 原样作为 query 值（可能是 "1,6,7" 或 "[2026,2027)" 这种形式）。
 */
public class BangumiIndexApi {

    private static final String INDEX_URL = "https://api.bilibili.com/pgc/season/index/result";
    private static final String CONDITION_URL = "https://api.bilibili.com/pgc/season/index/condition";

    /** 单个选项：value 为 query 值（排序的 field 或筛选的 keyword），name 为显示文案 */
    public static class Option {
        public final String value;
        public final String name;

        Option(String value, String name) {
            this.value = value;
            this.name = name;
        }
    }

    /** 一组筛选条件（地区/风格/年份等） */
    public static class Group {
        public final String field;
        public final String name;
        public final List<Option> options = new ArrayList<>();

        Group(String field, String name) {
            this.field = field;
            this.name = name;
        }
    }

    /** 动态筛选条件：orders 为排序项，filters 为筛选组 */
    public static class Condition {
        public final List<Option> orders = new ArrayList<>();
        public final List<Group> filters = new ArrayList<>();
    }

    /**
     * 拉取动态筛选条件（排序项 + 各组筛选项）。
     * 接口 /pgc/season/index/condition?season_type=1&type=0，无 WBI，Cookie 可选。
     */
    public static Condition getCondition(int seasonType) throws IOException {
        try {
            String url = CONDITION_URL + new NetWorkUtil.FormData().setUrlParam(true)
                    .put("season_type", seasonType)
                    .put("type", 0);
            JSONObject root = NetWorkUtil.getJson(url);
            int code = root.optInt("code", -1);
            if (code != 0)
                throw new IOException("筛选条件接口 code=" + code + " msg=" + root.optString("message"));
            JSONObject data = root.optJSONObject("data");
            if (data == null) throw new IOException("筛选条件接口 data 为空");

            Condition condition = new Condition();
            JSONArray orders = data.optJSONArray("order");
            if (orders != null) {
                for (int i = 0; i < orders.length(); i++) {
                    JSONObject item = orders.optJSONObject(i);
                    if (item == null) continue;
                    condition.orders.add(new Option(item.optString("field", ""), item.optString("name", "")));
                }
            }
            JSONArray filters = data.optJSONArray("filter");
            if (filters != null) {
                for (int i = 0; i < filters.length(); i++) {
                    JSONObject item = filters.optJSONObject(i);
                    if (item == null) continue;
                    Group group = new Group(item.optString("field", ""), item.optString("name", ""));
                    JSONArray values = item.optJSONArray("values");
                    if (values != null) {
                        for (int j = 0; j < values.length(); j++) {
                            JSONObject value = values.optJSONObject(j);
                            if (value == null) continue;
                            group.options.add(new Option(value.optString("keyword", ""), value.optString("name", "")));
                        }
                    }
                    condition.filters.add(group);
                }
            }
            return condition;
        } catch (IOException e) {
            throw e;
        } catch (JSONException e) {
            throw new IOException("筛选条件解析失败", e);
        } catch (Exception e) {
            throw new IOException("筛选条件请求异常", e);
        }
    }

    /** 索引默认筛选参数：全部（各字段 -1） */
    public static Map<String, String> defaultFilters() {
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("season_version", "-1");
        filters.put("spoken_language_type", "-1");
        filters.put("area", "-1");
        filters.put("is_finish", "-1");
        filters.put("copyright", "-1");
        filters.put("season_status", "-1");
        filters.put("season_month", "-1");
        filters.put("year", "-1");
        filters.put("style_id", "-1");
        return filters;
    }

    /**
     * 索引/推荐结果。
     *
     * @param seasonType 1=番剧
     * @param filters    用户选中的筛选条件 field → keyword（见 {@link #getCondition}），可为 null
     * @param order      排序 field（condition.orders 里的 value），空串按综合排序
     * @param page       页码，从 1 开始
     * @param list       结果列表
     * @return hasNext（仅表示分页状态）
     * @throws IOException 网络或业务错误：与「没有下一页」严格区分，调用方必须分别处理，
     *                     否则接口挂掉会被误判成到底
     */
    public static boolean getIndex(int seasonType, Map<String, String> filters, String order,
                                   int page, List<VideoCard> list) throws IOException {
        try {
            Map<String, String> params = defaultFilters();
            if (filters != null) params.putAll(filters);

            NetWorkUtil.FormData form = new NetWorkUtil.FormData().setUrlParam(true);
            for (Map.Entry<String, String> entry : params.entrySet())
                form.put(entry.getKey(), entry.getValue());
            form.put("order", order == null || order.isEmpty() ? "8" : order)
                    .put("sort", 0)
                    .put("page", page)
                    .put("season_type", seasonType)
                    .put("pagesize", 20)
                    .put("type", 1)
                    .put("web_location", "333.934");

            JSONObject root = NetWorkUtil.getJson(INDEX_URL + form);
            int code = root.optInt("code", -1);
            if (code != 0)
                throw new IOException("索引接口 code=" + code + " msg=" + root.optString("message"));
            JSONObject data = root.optJSONObject("data");
            if (data == null) throw new IOException("索引接口 data 为空");
            int total = data.optInt("total", 0);
            JSONArray items = data.optJSONArray("list");
            if (items == null) throw new IOException("索引接口 list 为空");

            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String title = item.optString("title", "");
                String cover = item.optString("cover", "");
                long mediaId = item.optLong("media_id", 0);
                if (mediaId <= 0) mediaId = item.optLong("season_id", 0);
                String score = item.optString("score", "");
                String indexShow = item.optString("index_show", "");
                String orderText = item.optString("order", "");

                StringBuilder sub = new StringBuilder();
                if (!score.isEmpty() && !"0.0".equals(score)) sub.append(score).append("分");
                if (!indexShow.isEmpty()) {
                    if (sub.length() > 0) sub.append(" · ");
                    sub.append(indexShow);
                }

                VideoCard card = new VideoCard(title, sub.toString(), orderText, cover, mediaId, null);
                card.type = "media_bangumi";
                list.add(card);
            }
            return data.optInt("has_next", 0) == 1 && (total <= 0 || page * 20 < total);
        } catch (IOException e) {
            throw e;
        } catch (JSONException e) {
            throw new IOException("索引接口解析失败", e);
        } catch (Exception e) {
            throw new IOException("索引接口请求异常", e);
        }
    }
}
