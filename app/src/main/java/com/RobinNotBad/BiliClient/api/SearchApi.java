package com.RobinNotBad.BiliClient.api;

import com.RobinNotBad.BiliClient.model.ApiResponse;
import com.RobinNotBad.BiliClient.model.ArticleCard;
import com.RobinNotBad.BiliClient.model.UserInfo;
import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.util.GsonUtil;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.StringUtil;
import com.google.gson.annotations.SerializedName;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

public class SearchApi {

    public static String seid = "";
    public static String search_keyword = "";

    public static class SearchData {
        @SerializedName("seid")
        public String seid;
        @SerializedName("result")
        public List<SearchResultGroup> result;
    }

    public static class SearchResultGroup {
        @SerializedName("result_type")
        public String result_type;
        @SerializedName("data")
        public List<SearchVideoItem> data;
    }

    public static class SearchVideoItem {
        @SerializedName("type")
        public String type;
        @SerializedName("title")
        public String title;
        @SerializedName("bvid")
        public String bvid;
        @SerializedName("aid")
        public long aid;
        @SerializedName("pic")
        public String pic;
        @SerializedName("author")
        public String author;
        @SerializedName("play")
        public long play;
        @SerializedName("media_id")
        public long media_id;
        @SerializedName("season_id")
        public String season_id;
        @SerializedName("cover")
        public String cover;
        @SerializedName("areas")
        public String areas;
        @SerializedName("index_show")
        public String index_show;
    }

    public static class SearchUserItem {
        @SerializedName("mid")
        public long mid;
        @SerializedName("uname")
        public String uname;
        @SerializedName("upic")
        public String upic;
        @SerializedName("usign")
        public String usign;
        @SerializedName("fans")
        public int fans;
        @SerializedName("level")
        public int level;
    }

    public static class SearchArticleItem {
        @SerializedName("id")
        public long id;
        @SerializedName("image_urls")
        public List<String> image_urls;
        @SerializedName("category_name")
        public String category_name;
        @SerializedName("title")
        public String title;
        @SerializedName("view")
        public int view;
    }

    public static class SearchTypeData {
        @SerializedName("seid")
        public String seid;
        @SerializedName("result")
        public com.google.gson.JsonElement result;
    }

    public static class SearchResult {
        @SerializedName("tag")
        public List<TagItem> tag;
    }

    public static class TagItem {
        @SerializedName("value")
        public String value;
    }

    public static class DefaultSearchData {
        @SerializedName("show_name")
        public String show_name;
    }

    public static JSONArray search(String keyword, int page) throws IOException, JSONException {
        if (!search_keyword.equals(keyword)) { search_keyword = keyword; seid = ""; }
        String url = "https://api.bilibili.com/x/web-interface/wbi/search/all/v2?page=" + page + "&keyword=" + URLEncoder.encode(search_keyword, "UTF-8") + "&seid=" + seid;
        String json = NetWorkUtil.getJson(ConfInfoApi.signWBI(url)).toString();
        ApiResponse<SearchData> resp = GsonUtil.fromJson(json, new com.google.gson.reflect.TypeToken<ApiResponse<SearchData>>(){}.getType());
        if (resp == null || !resp.isSuccess() || resp.data == null) return null;
        seid = resp.data.seid;
        if (resp.data.result == null) return null;
        return new JSONArray(GsonUtil.toJson(resp.data.result));
    }

    public static com.google.gson.JsonElement searchType(String keyword, int page, String type) throws IOException, JSONException {
        if (!search_keyword.equals(keyword)) { search_keyword = keyword; seid = ""; }
        String url = "https://api.bilibili.com/x/web-interface/wbi/search/type?page=" + page + "&keyword=" + URLEncoder.encode(search_keyword, "UTF-8") + "&search_type=" + type + "&seid=" + seid;
        String json = NetWorkUtil.getJson(ConfInfoApi.signWBI(url)).toString();
        ApiResponse<SearchTypeData> resp = GsonUtil.fromJson(json, new com.google.gson.reflect.TypeToken<ApiResponse<SearchTypeData>>(){}.getType());
        if (resp == null || !resp.isSuccess() || resp.data == null) return null;
        seid = resp.data.seid;
        return resp.data.result;
    }

    public static void getVideosFromSearchResult(JSONArray input, ArrayList<VideoCard> videoCardList, boolean first) {
        List<SearchResultGroup> groups = GsonUtil.fromJson(input.toString(),
                new com.google.gson.reflect.TypeToken<List<SearchResultGroup>>(){}.getType());
        if (groups == null) return;

        for (SearchResultGroup group : groups) {
            if (group == null || group.data == null) continue;
            if ("video".equals(group.result_type)) {
                for (SearchVideoItem card : group.data) {
                    if (card == null || !"video".equals(card.type)) continue;
                    String title = StringUtil.htmlToString(card.title.replace("<em class=\"keyword\">", "").replace("</em>", ""));
                    String cover = card.pic != null && !card.pic.isEmpty() ? "http:" + card.pic : "";
                    videoCardList.add(new VideoCard(title, card.author, StringUtil.toWan(card.play) + "观看", cover, card.aid, card.bvid, "video"));
                }
            } else if ("media_bangumi".equals(group.result_type) && first) {
                for (SearchVideoItem card : group.data) {
                    if (card == null) continue;
                    String title = StringUtil.htmlToString(card.title.replace("<em class=\"keyword\">", "").replace("</em>", ""));
                    videoCardList.add(new VideoCard(title, card.areas, card.index_show, card.cover, card.media_id, card.season_id, "media_bangumi"));
                }
            }
        }
    }

    public static void getUsersFromSearchResult(JSONArray input, List<UserInfo> userInfoList) {
        List<SearchUserItem> users = GsonUtil.fromJson(input.toString(),
                new com.google.gson.reflect.TypeToken<List<SearchUserItem>>(){}.getType());
        if (users == null) return;
        for (SearchUserItem u : users) {
            if (u == null) continue;
            String avatar = u.upic != null && !u.upic.isEmpty() ? "http:" + u.upic : "";
            userInfoList.add(new UserInfo(u.mid, u.uname, avatar, u.usign, u.fans, 0, u.level, false, "", 0, "", 0));
        }
    }

    public static void getArticlesFromSearchResult(JSONArray input, ArrayList<ArticleCard> articleCardList) {
        List<SearchArticleItem> articles = GsonUtil.fromJson(input.toString(),
                new com.google.gson.reflect.TypeToken<List<SearchArticleItem>>(){}.getType());
        if (articles == null) return;
        for (SearchArticleItem a : articles) {
            if (a == null) continue;
            ArticleCard card = new ArticleCard();
            card.id = a.id;
            card.cover = (a.image_urls != null && !a.image_urls.isEmpty()) ? "http:" + a.image_urls.get(0) : "";
            card.upName = a.category_name;
            card.title = StringUtil.htmlReString(a.title);
            card.view = StringUtil.toWan(a.view) + "阅读";
            articleCardList.add(card);
        }
    }

    public static ArrayList<String> getSearchSuggestions(String keyword) throws IOException, JSONException {
        ArrayList<String> suggestions = new ArrayList<>();
        if (keyword == null || keyword.trim().isEmpty()) return suggestions;
        String url = "https://s.search.bilibili.com/main/suggest?term=" + URLEncoder.encode(keyword, "UTF-8");
        String json = NetWorkUtil.getJson(url).toString();
        ApiResponse<SearchResult> resp = GsonUtil.fromJson(json, new com.google.gson.reflect.TypeToken<ApiResponse<SearchResult>>(){}.getType());
        if (resp == null || !resp.isSuccess() || resp.data == null || resp.data.tag == null) return suggestions;
        for (TagItem tag : resp.data.tag) {
            if (tag != null && tag.value != null && !tag.value.isEmpty()) suggestions.add(tag.value);
        }
        return suggestions;
    }

    public static String getDefaultSearchContent() throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/web-interface/wbi/search/default";
        String json = NetWorkUtil.getJson(ConfInfoApi.signWBI(url)).toString();
        ApiResponse<DefaultSearchData> resp = GsonUtil.fromJson(json, new com.google.gson.reflect.TypeToken<ApiResponse<DefaultSearchData>>(){}.getType());
        return (resp != null && resp.isSuccess() && resp.data != null) ? resp.data.show_name : null;
    }
}
