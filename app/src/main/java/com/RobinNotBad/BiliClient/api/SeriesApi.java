package com.RobinNotBad.BiliClient.api;

import com.RobinNotBad.BiliClient.model.ApiResponse;
import com.RobinNotBad.BiliClient.model.PageInfo;
import com.RobinNotBad.BiliClient.model.Series;
import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.util.GsonUtil;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.StringUtil;
import com.google.gson.annotations.SerializedName;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class SeriesApi {

    public static class SeriesListData {
        @SerializedName("items_lists") public ItemsLists items_lists;
    }
    public static class ItemsLists {
        @SerializedName("seasons_list") public List<SeriesItem> seasons_list;
        @SerializedName("series_list") public List<SeriesItem> series_list;
    }
    public static class SeriesItem {
        @SerializedName("meta") public SeriesMeta meta;
    }
    public static class SeriesMeta {
        @SerializedName("season_id") public int season_id;
        @SerializedName("series_id") public int series_id;
        @SerializedName("name") public String name;
        @SerializedName("cover") public String cover;
        @SerializedName("mid") public long mid;
        @SerializedName("description") public String description;
        @SerializedName("total") public String total;
    }

    public static class SeriesInfoData {
        @SerializedName("page") public SeriesPage page;
        @SerializedName("archives") public List<ArchiveItem> archives;
    }
    public static class SeriesPage {
        @SerializedName("num") public int num;
        @SerializedName("page_num") public int page_num;
        @SerializedName("size") public int size;
        @SerializedName("page_size") public int page_size;
        @SerializedName("total") public int total;
    }
    public static class ArchiveItem {
        @SerializedName("aid") public long aid;
        @SerializedName("bvid") public String bvid;
        @SerializedName("pic") public String pic;
        @SerializedName("title") public String title;
        @SerializedName("stat") public RecommendApi.Stat stat;
    }

    public static int getUserSeries(long mid, int page, List<Series> seasonList) throws IOException, JSONException {
        String json = NetWorkUtil.getJson(ConfInfoApi.signWBI("https://api.bilibili.com/x/polymer/web-space/seasons_series_list?mid=" + mid + "&page_num=" + page + "&page_size=20"), NetWorkUtil.webHeaders).toString();
        ApiResponse<SeriesListData> resp = GsonUtil.fromJson(json, new com.google.gson.reflect.TypeToken<ApiResponse<SeriesListData>>(){}.getType());
        if (resp == null || !resp.isSuccess() || resp.data == null || resp.data.items_lists == null) return -1;
        boolean finished = false;
        if (resp.data.items_lists.seasons_list != null) { for (SeriesItem s : resp.data.items_lists.seasons_list) { if (s != null) seasonList.add(buildSeries(s.meta)); } if (!resp.data.items_lists.seasons_list.isEmpty()) finished = true; }
        if (resp.data.items_lists.series_list != null) { for (SeriesItem s : resp.data.items_lists.series_list) { if (s != null) seasonList.add(buildSeries(s.meta)); } if (!resp.data.items_lists.series_list.isEmpty()) finished = true; }
        return finished ? 0 : 1;
    }

    public static PageInfo getSeriesInfo(String type, long mid, int id, int page, ArrayList<VideoCard> videoList) throws IOException, JSONException {
        String url;
        switch (type) {
            case "series": url = "https://api.bilibili.com/x/series/archives" + new NetWorkUtil.FormData().setUrlParam(true).put("mid", mid).put("series_id", id).put("pn", page).put("ps", 30); break;
            case "season": url = "https://api.bilibili.com/x/polymer/web-space/seasons_archives_list" + new NetWorkUtil.FormData().setUrlParam(true).put("mid", mid).put("season_id", id).put("page_num", page).put("page_size", 30); break;
            default: return new PageInfo();
        }
        String json = NetWorkUtil.getJson(url).toString();
        ApiResponse<SeriesInfoData> resp = GsonUtil.fromJson(json, new com.google.gson.reflect.TypeToken<ApiResponse<SeriesInfoData>>(){}.getType());
        PageInfo pageInfo = new PageInfo();
        if (resp == null || !resp.isSuccess() || resp.data == null) return pageInfo;
        if (resp.data.page != null) {
            SeriesPage p = resp.data.page;
            pageInfo.page_num = "series".equals(type) ? p.num : p.page_num;
            pageInfo.require_ps = "series".equals(type) ? p.size : p.page_size;
            pageInfo.total = p.total;
        }
        if (resp.data.archives != null) {
            pageInfo.return_ps = resp.data.archives.size();
            for (ArchiveItem a : resp.data.archives) {
                if (a == null) continue;
                int viewCount = a.stat != null ? a.stat.view : 0;
                videoList.add(new VideoCard(a.title, "", StringUtil.toWan(viewCount), a.pic, a.aid, a.bvid));
            }
        }
        return pageInfo;
    }

    private static Series buildSeries(SeriesMeta meta) {
        Series series = new Series();
        if (meta == null) return series;
        if (meta.season_id != 0) { series.type = "season"; series.id = meta.season_id; }
        else { series.type = "series"; series.id = meta.series_id; }
        series.title = meta.name; series.cover = meta.cover; series.mid = meta.mid;
        series.intro = meta.description; series.total = meta.total;
        return series;
    }
}
