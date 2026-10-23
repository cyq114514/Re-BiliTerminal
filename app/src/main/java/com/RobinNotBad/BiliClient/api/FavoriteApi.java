package com.RobinNotBad.BiliClient.api;

import android.util.Pair;

import com.RobinNotBad.BiliClient.model.ApiResponse;
import com.RobinNotBad.BiliClient.model.Collection;
import com.RobinNotBad.BiliClient.model.FavoriteFolder;
import com.RobinNotBad.BiliClient.model.Opus;
import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.util.GsonUtil;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.RobinNotBad.BiliClient.util.StringUtil;
import com.google.gson.annotations.SerializedName;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 收藏夹接口。
 *
 * <p><b>2026-10 迁移</b>：B 站已经把这套老接口替换成 {@code x/v3/fav/*}（PiliPlus 同样是这一套），
 * 老链路在真机上的表现是：
 * <ul>
 *   <li>{@code space.bilibili.com/ajax/fav/getBoxList} —— 收藏页只剩「图文收藏夹」，
 *       视频收藏夹列表为空（请求成功、返回空表，页面不报错）；</li>
 *   <li>{@code x/space/fav/arc} —— 老的分页接口，官方前端已弃用；</li>
 *   <li>{@code medialist/gateway/coll/resource/deal|batch/del} —— 线上直接返回
 *       {@code -401 未授权}，收藏/取消收藏必然失败。</li>
 * </ul>
 *
 * <p>现在统一走 v3：
 * 列表 {@code x/v3/fav/folder/created/list}、内容 {@code x/v3/fav/resource/list}、
 * 增删 {@code x/v3/fav/resource/batch-deal}、详情 {@code x/v3/fav/folder/info}。
 * 关键变化是<b>媒体身份从 fid 变成 media_id</b>：v3 接口只认 media_id。
 * 老调用方只有 fid 时用 {@link #mediaIdOf(long, long)} 兜底换算
 * （B 站的 media_id 一直是 "fid*100 + mid 后两位"，老代码里也是这么拼的）。
 */
public class FavoriteApi {

    /** 收藏夹列表最多翻几页（一页 20 个），防止服务端异常时死循环 */
    private static final int MAX_FOLDER_PAGES = 5;
    /** 收藏夹内容每页条数：与旧接口的 ps=30 不同，v3 常用 20，PiliPlus 也用 20 */
    private static final int FOLDER_PAGE_SIZE = 20;

    // ==================== 模型 ====================

    /** v3 收藏夹列表（PiliPlus 的 userfavFolder 响应） */
    public static class V3FolderListData {
        @SerializedName("count")
        public int count;
        @SerializedName("has_more")
        public boolean has_more;
        @SerializedName("list")
        public List<V3Folder> list;
    }

    public static class V3Folder {
        @SerializedName("id")
        public long id;          // media_id
        @SerializedName("fid")
        public long fid;         // 老 fid
        @SerializedName("mid")
        public long mid;
        @SerializedName("attr")
        public int attr;         // 22 = 私密
        @SerializedName("title")
        public String title;
        @SerializedName("cover")
        public String cover;
        @SerializedName("intro")
        public String intro;
        @SerializedName("media_count")
        public int media_count;
    }

    /** v3 收藏夹内容（PiliPlus 的 favResourceList 响应） */
    public static class V3ResourceListData {
        @SerializedName("info")
        public V3Folder info;
        @SerializedName("medias")
        public List<V3Media> medias;
        @SerializedName("has_more")
        public boolean has_more;
    }

    public static class V3Media {
        @SerializedName("id")
        public long id;          // aid
        @SerializedName("type")
        public int type;         // 2 = 视频稿件；其它(合集/音频/番剧)卡片链路不支持
        @SerializedName("title")
        public String title;
        @SerializedName("cover")
        public String cover;
        @SerializedName("duration")
        public int duration;
        @SerializedName("attr")
        public int attr;         // 非 0 = 已失效
        @SerializedName("bvid")
        public String bvid;
        @SerializedName("fav_time")
        public long fav_time;
        @SerializedName("upper")
        public RecommendApi.Owner upper;
        @SerializedName("cnt_info")
        public V3Count cnt_info;
        @SerializedName("ugc")
        public V3Ugc ugc;
    }

    public static class V3Count {
        @SerializedName("play")
        public int play;
        @SerializedName("danmaku")
        public int danmaku;
        @SerializedName("collect")
        public int collect;
    }

    public static class V3Ugc {
        @SerializedName("first_cid")
        public long first_cid;
    }

    /** 收藏状态（list-all 带 rid 时返回：每个收藏夹一条，含本稿件是否已收藏） */
    public static class FavStateData {
        @SerializedName("list")
        public List<FavStateItem> list;
    }

    public static class FavStateItem {
        @SerializedName("title")
        public String title;
        @SerializedName("fid")
        public long fid;
        /** media_id：v3 增删收藏夹只认它 */
        @SerializedName("id")
        public long id;
        @SerializedName("fav_state")
        public int fav_state;
    }

    /** 收藏的合集（订阅别人收藏夹用的，当前 UI 未使用，保留接口与模型） */
    public static class CollectedListData {
        @SerializedName("has_more")
        public boolean has_more;
        @SerializedName("list")
        public List<CollectionItem> list;
    }

    public static class CollectionItem {
        @SerializedName("id")
        public int id;
        @SerializedName("mid")
        public long mid;
        @SerializedName("title")
        public String title;
        @SerializedName("cover")
        public String cover;
        @SerializedName("intro")
        public String intro;
        @SerializedName("view_count")
        public int view_count;
    }

    public static class OpusFavData {
        @SerializedName("has_more")
        public boolean has_more;
        @SerializedName("items")
        public List<OpusItem> items;
    }

    public static class OpusItem {
        @SerializedName("content")
        public String content;
        @SerializedName("cover")
        public String cover;
        @SerializedName("title")
        public String title;
        @SerializedName("opus_id")
        public String opus_id;
        @SerializedName("time_text")
        public String time_text;
    }

    public static class FolderInfo {
        public String title;
        public String intro;
        public int privacy;
    }

    public static class FavFolderInfoData {
        @SerializedName("title")
        public String title;
        @SerializedName("intro")
        public String intro;
        @SerializedName("privacy")
        public int privacy;
    }

    // ==================== 工具 ====================

    /**
     * 老 fid → media_id 的兜底换算。
     *
     * <p>B 站的 media_id = 老 fid * 100 + mid 的后两位（历史规则，老代码 addFavorite
     * 里也是 {@code fid + strMid.substring(len-2)} 这么拼的）。实测：
     * fid=636926, mid=2267573 → 63692673；fid=40267484, mid=8047632 → 4026748432。
     * 只在"调用方手里只有 fid"（老 Intent、老缓存数据）时使用，正常路径直接用接口返回的 media_id。
     */
    public static long mediaIdOf(long mid, long fid) {
        if (fid <= 0 || mid <= 0) return fid;
        String s = String.valueOf(mid);
        if (s.length() < 2) return fid;
        try {
            return fid * 100 + Long.parseLong(s.substring(s.length() - 2));
        } catch (NumberFormatException e) {
            return fid;
        }
    }

    // ==================== 读 ====================

    /**
     * 我的收藏夹列表（含默认收藏夹、私密收藏夹）。
     *
     * <p>替代原来的 {@code space.bilibili.com/ajax/fav/getBoxList}：那个接口现在对登录态
     * 只返回空表，收藏页因此只剩一张「图文收藏夹」卡片。
     */
    public static ArrayList<FavoriteFolder> getFavoriteFolders(long mid) throws IOException, JSONException {
        ArrayList<FavoriteFolder> folderList = new ArrayList<>();
        int page = 1;
        while (page <= MAX_FOLDER_PAGES) {
            String url = "https://api.bilibili.com/x/v3/fav/folder/created/list" + new NetWorkUtil.FormData()
                    .setUrlParam(true).put("pn", page).put("ps", 20).put("up_mid", mid);
            String json = NetWorkUtil.getJson(url).toString();
            ApiResponse<V3FolderListData> resp = GsonUtil.fromJson(json,
                    new com.google.gson.reflect.TypeToken<ApiResponse<V3FolderListData>>(){}.getType());
            if (resp == null || !resp.isSuccess() || resp.data == null || resp.data.list == null) break;

            for (V3Folder folder : resp.data.list) {
                if (folder == null) continue;
                FavoriteFolder fav = new FavoriteFolder();
                fav.mediaId = folder.id;
                fav.id = folder.fid;
                fav.name = folder.title;
                fav.cover = folder.cover == null ? "" : folder.cover;
                fav.videoCount = folder.media_count;
                //v3 不再返回收藏夹容量上限：显示层只显示数量（/上限 的旧样式已废弃）
                fav.maxCount = 0;
                //第一条就是默认收藏夹（v3 没有 is_default 字段）：默认收藏夹不允许改名/删除
                fav.isDefault = folderList.isEmpty();
                folderList.add(fav);
            }
            if (!resp.data.has_more) break;
            page++;
        }
        return folderList;
    }

    /**
     * 收藏夹里的内容（只保留普通视频稿件）。
     *
     * @param mediaId 收藏夹 media_id（不是老 fid）
     * @return 0 = 还有下一页；1 = 已到末尾/没有更多
     */
    public static int getFolderVideos(long mediaId, int page, ArrayList<VideoCard> videoList) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/v3/fav/resource/list" + new NetWorkUtil.FormData()
                .setUrlParam(true)
                .put("media_id", mediaId)
                .put("pn", page)
                .put("ps", FOLDER_PAGE_SIZE)
                .put("keyword", "")
                .put("order", "mtime")
                .put("type", 0)
                .put("tid", 0)
                .put("platform", "web");
        String json = NetWorkUtil.getJson(url).toString();
        ApiResponse<V3ResourceListData> resp = GsonUtil.fromJson(json,
                new com.google.gson.reflect.TypeToken<ApiResponse<V3ResourceListData>>(){}.getType());
        if (resp == null || !resp.isSuccess() || resp.data == null || resp.data.medias == null) return 1;

        for (V3Media item : resp.data.medias) {
            if (item == null) continue;
            //收藏夹里可能混有视频合集(21)/音频等，卡片链路只认普通视频，跳过避免点进去空白
            if (item.type != 2) continue;

            String upName = item.upper != null ? item.upper.name : "";
            int play = item.cnt_info != null ? item.cnt_info.play : 0;
            //attr != 0 表示稿件已失效（删除/私有）：仍然展示，但不再显示"xx观看"这种误导文案
            String viewText = item.attr != 0 ? "已失效" : StringUtil.toWan(play) + "观看";
            VideoCard card = new VideoCard(item.title, upName, viewText, item.cover,
                    item.id, item.bvid == null ? "" : item.bvid);
            if (item.ugc != null) card.cid = item.ugc.first_cid;
            videoList.add(card);
        }
        return resp.data.has_more ? 0 : 1;
    }

    /** 兼容老调用方（只有 fid）：内部换算成 media_id。 */
    public static int getFolderVideos(long mid, long fid, int page, ArrayList<VideoCard> videoList) throws IOException, JSONException {
        return getFolderVideos(mediaIdOf(mid, fid), page, videoList);
    }

    /** 图文收藏夹（polymer 接口，未受本次改动影响） */
    public static boolean getFavouriteOpus(ArrayList<Opus> list, int page) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/polymer/web-dynamic/v1/opus/favlist?page_size=10&page=" + page;
        String json = NetWorkUtil.getJson(url).toString();
        ApiResponse<OpusFavData> resp = GsonUtil.fromJson(json,
                new com.google.gson.reflect.TypeToken<ApiResponse<OpusFavData>>(){}.getType());
        if (resp == null || !resp.isSuccess() || resp.data == null) return false;
        if (resp.data.items != null) {
            for (OpusItem item : resp.data.items) {
                if (item == null) continue;
                Opus opus = new Opus();
                opus.content = item.content;
                opus.cover = item.cover;
                opus.title = item.title;
                if (opus.title == null || opus.title.isEmpty()) opus.title = opus.content;
                try { opus.id = Long.parseLong(item.opus_id); } catch (Exception ignored) {}
                opus.pubTime = item.time_text;
                list.add(opus);
            }
        }
        return resp.data.has_more;
    }

    /** 收藏的合集（分页） */
    public static Pair<Integer, Boolean> getFavoritedCollections(long mid, int page, List<Collection> collectionList) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/v3/fav/folder/collected/list" + new NetWorkUtil.FormData()
                .setUrlParam(true).put("platform", "web").put("up_mid", mid).put("pn", page).put("ps", 10);
        String json = NetWorkUtil.getJson(url).toString();
        ApiResponse<CollectedListData> resp = GsonUtil.fromJson(json,
                new com.google.gson.reflect.TypeToken<ApiResponse<CollectedListData>>(){}.getType());
        int code = resp != null ? resp.code : -1;
        boolean hasMore = false;
        if (resp != null && resp.data != null) {
            hasMore = resp.data.has_more;
            if (resp.data.list != null) {
                for (CollectionItem item : resp.data.list) {
                    if (item == null) continue;
                    Collection c = new Collection();
                    c.id = item.id;
                    c.mid = item.mid;
                    c.title = item.title;
                    c.cover = item.cover;
                    c.intro = item.intro;
                    c.view = StringUtil.toWan(item.view_count);
                    collectionList.add(c);
                }
            }
        }
        return new Pair<>(code, hasMore);
    }

    /**
     * 查询某稿件在"我的"各个收藏夹里的收藏状态。
     *
     * <p>这个接口（list-all + rid）本来就是 v3，本次只补了 media_id 字段：
     * 现在的"收藏/取消收藏"必须传 media_id，传老 fid 会被服务端拒绝。
     */
    public static void getFavoriteState(long aid, ArrayList<String> folderList,
                                        ArrayList<Long> mediaIdList, ArrayList<Boolean> stateList) throws IOException, JSONException {
        long mid = NetWorkUtil.getLoginMid();
        String url = "https://api.bilibili.com/x/v3/fav/folder/created/list-all?type=2&jsonp=jsonp&rid=" + aid + "&up_mid=" + mid;
        String json = NetWorkUtil.getJson(url).toString();
        ApiResponse<FavStateData> resp = GsonUtil.fromJson(json,
                new com.google.gson.reflect.TypeToken<ApiResponse<FavStateData>>(){}.getType());
        if (resp == null || resp.data == null || resp.data.list == null) return;
        for (FavStateItem item : resp.data.list) {
            if (item == null) continue;
            //media_id 缺失（老数据/字段改名）时按规则兜底换算，宁可多算一次也不要传 0 让请求失败
            long mediaId = item.id > 0 ? item.id : mediaIdOf(mid, item.fid);
            folderList.add(item.title);
            mediaIdList.add(mediaId);
            stateList.add(item.fav_state == 1);
        }
    }

    /** 收藏夹详情：编辑页用它预填简介与隐私。 */
    public static FolderInfo getFolderInfo(long mediaId) {
        try {
            String json = NetWorkUtil.getJson("https://api.bilibili.com/x/v3/fav/folder/info?media_id=" + mediaId).toString();
            ApiResponse<FavFolderInfoData> resp = GsonUtil.fromJson(json,
                    new com.google.gson.reflect.TypeToken<ApiResponse<FavFolderInfoData>>(){}.getType());
            if (resp == null || !resp.isSuccess() || resp.data == null) return null;
            FolderInfo info = new FolderInfo();
            info.title = resp.data.title;
            info.intro = resp.data.intro;
            info.privacy = resp.data.privacy;
            return info;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 写 ====================

    private static final String FAV_RESOURCE_BATCH_DEAL = "https://api.bilibili.com/x/v3/fav/resource/batch-deal";

    /**
     * 收藏稿件到指定收藏夹（v3 batch-deal）。
     *
     * @param mediaId 收藏夹 media_id（老 medialist 接口现在直接返回 -401）
     */
    public static int addFavorite(long aid, long mediaId) throws IOException, JSONException {
        String data = new NetWorkUtil.FormData()
                .put("resources", aid + ":2")
                .put("add_media_ids", mediaId)
                .put("del_media_ids", "")
                .put("csrf", SharedPreferencesUtil.getString("csrf", ""))
                .put("platform", "web")
                .toString();
        return GsonUtil.fromJson(Objects.requireNonNull(NetWorkUtil.post(FAV_RESOURCE_BATCH_DEAL, data, NetWorkUtil.webHeaders).body()).string(), ApiResponse.class).code;
    }

    /** 从指定收藏夹取消收藏（v3 batch-deal）。 */
    public static int deleteFavorite(long aid, long mediaId) throws IOException, JSONException {
        String data = new NetWorkUtil.FormData()
                .put("resources", aid + ":2")
                .put("add_media_ids", "")
                .put("del_media_ids", mediaId)
                .put("csrf", SharedPreferencesUtil.getString("csrf", ""))
                .put("platform", "web")
                .toString();
        return GsonUtil.fromJson(Objects.requireNonNull(NetWorkUtil.post(FAV_RESOURCE_BATCH_DEAL, data, NetWorkUtil.webHeaders).body()).string(), ApiResponse.class).code;
    }

    public static int addFolder(String title, String intro, int privacy) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/v3/fav/folder/add";
        String data = new NetWorkUtil.FormData().put("title", title).put("intro", intro != null ? intro : "").put("privacy", privacy).put("csrf", SharedPreferencesUtil.getString("csrf", "")).toString();
        return GsonUtil.fromJson(Objects.requireNonNull(NetWorkUtil.post(url, data, NetWorkUtil.webHeaders).body()).string(), ApiResponse.class).code;
    }

    public static int editFolder(long mediaId, String title, String intro, int privacy) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/v3/fav/folder/edit";
        String data = new NetWorkUtil.FormData().put("media_id", mediaId).put("title", title).put("intro", intro != null ? intro : "").put("privacy", privacy).put("csrf", SharedPreferencesUtil.getString("csrf", "")).toString();
        return GsonUtil.fromJson(Objects.requireNonNull(NetWorkUtil.post(url, data, NetWorkUtil.webHeaders).body()).string(), ApiResponse.class).code;
    }

    public static int deleteFolder(long mediaId) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/v3/fav/folder/del";
        String data = new NetWorkUtil.FormData().put("media_ids", String.valueOf(mediaId)).put("csrf", SharedPreferencesUtil.getString("csrf", "")).toString();
        return GsonUtil.fromJson(Objects.requireNonNull(NetWorkUtil.post(url, data, NetWorkUtil.webHeaders).body()).string(), ApiResponse.class).code;
    }
}
