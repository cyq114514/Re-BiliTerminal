package com.RobinNotBad.BiliClient.api;

import android.annotation.SuppressLint;
import android.text.SpannableStringBuilder;
import android.util.Pair;

import com.RobinNotBad.BiliClient.model.ApiResponse;
import com.RobinNotBad.BiliClient.model.At;
import com.RobinNotBad.BiliClient.model.Collection;
import com.RobinNotBad.BiliClient.model.Stats;
import com.RobinNotBad.BiliClient.model.UserInfo;
import com.RobinNotBad.BiliClient.model.VideoInfo;
import com.RobinNotBad.BiliClient.util.GsonUtil;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.RobinNotBad.BiliClient.util.StringUtil;
import com.google.gson.annotations.SerializedName;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

public class VideoInfoApi {

    public static class VideoInfoData {
        @SerializedName("title")
        public String title;
        @SerializedName("pic")
        public String pic;
        @SerializedName("desc")
        public String desc;
        @SerializedName("desc_v2")
        public List<DescV2> desc_v2;
        @SerializedName("bvid")
        public String bvid;
        @SerializedName("aid")
        public long aid;
        @SerializedName("pubdate")
        public long pubdate;
        @SerializedName("duration")
        public int duration;
        @SerializedName("copyright")
        public int copyright;
        @SerializedName("stat")
        public StatData stat;
        @SerializedName("pages")
        public List<PageData> pages;
        @SerializedName("is_upower_exclusive")
        public boolean is_upower_exclusive;
        @SerializedName("rights")
        public RightsData rights;
        @SerializedName("staff")
        public List<StaffData> staff;
        @SerializedName("owner")
        public OwnerData owner;
        @SerializedName("argue_info")
        public ArgueInfoData argue_info;
        @SerializedName("redirect_url")
        public String redirect_url;
        @SerializedName("ugc_season")
        public UgcSeasonData ugc_season;
    }

    public static class DescV2 {
        @SerializedName("type")
        public int type;
        @SerializedName("raw_text")
        public String raw_text;
        @SerializedName("biz_id")
        public long biz_id;
    }

    public static class StatData {
        @SerializedName("view")
        public int view;
        @SerializedName("like")
        public int like;
        @SerializedName("coin")
        public int coin;
        @SerializedName("reply")
        public int reply;
        @SerializedName("danmaku")
        public int danmaku;
        @SerializedName("favorite")
        public int favorite;
    }

    public static class PageData {
        @SerializedName("part")
        public String part;
        @SerializedName("cid")
        public long cid;
    }

    public static class RightsData {
        @SerializedName("is_cooperation")
        public int is_cooperation;
        @SerializedName("is_stein_gate")
        public int is_stein_gate;
        @SerializedName("is_360")
        public int is_360;
    }

    public static class StaffData {
        @SerializedName("mid")
        public long mid;
        @SerializedName("title")
        public String title;
        @SerializedName("name")
        public String name;
        @SerializedName("face")
        public String face;
        @SerializedName("follower")
        public int follower;
        @SerializedName("official")
        public OfficialData official;
    }

    public static class OfficialData {
        @SerializedName("role")
        public int role;
        @SerializedName("title")
        public String title;
    }

    public static class OwnerData {
        @SerializedName("name")
        public String name;
        @SerializedName("face")
        public String face;
        @SerializedName("mid")
        public long mid;
    }

    public static class ArgueInfoData {
        @SerializedName("argue_msg")
        public String argue_msg;
    }

    public static class UgcSeasonData {
        @SerializedName("id")
        public int id;
        @SerializedName("title")
        public String title;
        @SerializedName("intro")
        public String intro;
        @SerializedName("cover")
        public String cover;
        @SerializedName("mid")
        public long mid;
        @SerializedName("stat")
        public UgcStatData stat;
        @SerializedName("sections")
        public List<UgcSectionData> sections;
    }

    public static class UgcStatData {
        @SerializedName("view")
        public long view;
    }

    public static class UgcSectionData {
        @SerializedName("season_id")
        public int season_id;
        @SerializedName("id")
        public int id;
        @SerializedName("title")
        public String title;
        @SerializedName("episodes")
        public List<UgcEpisodeData> episodes;
    }

    public static class UgcEpisodeData {
        @SerializedName("season_id")
        public int season_id;
        @SerializedName("section_id")
        public int section_id;
        @SerializedName("id")
        public int id;
        @SerializedName("aid")
        public long aid;
        @SerializedName("cid")
        public long cid;
        @SerializedName("title")
        public String title;
        @SerializedName("arc")
        public VideoInfoData arc;
        @SerializedName("bvid")
        public String bvid;
    }

    public static class TagData {
        @SerializedName("tag_name")
        public String tag_name;
    }

    public static VideoInfo getVideoInfo(String bvid) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/web-interface/view?bvid=" + bvid;
        boolean privacyMode = SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.PRIVACY_MODE, false);
        String json = privacyMode ? NetWorkUtil.getJsonPrivacy(url).toString() : NetWorkUtil.getJson(url).toString();
        ApiResponse<VideoInfoData> resp = GsonUtil.fromJson(json,
                new com.google.gson.reflect.TypeToken<ApiResponse<VideoInfoData>>(){}.getType());
        if (resp == null || !resp.isSuccess() || resp.data == null) return null;
        VideoInfo videoInfo = buildVideoInfo(resp.data);
        LikeCoinFavApi.getVideoStats(videoInfo);
        return videoInfo;
    }

    public static VideoInfo getVideoInfo(long aid) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/web-interface/view?aid=" + aid;
        boolean privacyMode = SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.PRIVACY_MODE, false);
        String json = privacyMode ? NetWorkUtil.getJsonPrivacy(url).toString() : NetWorkUtil.getJson(url).toString();
        ApiResponse<VideoInfoData> resp = GsonUtil.fromJson(json,
                new com.google.gson.reflect.TypeToken<ApiResponse<VideoInfoData>>(){}.getType());
        if (resp == null || !resp.isSuccess() || resp.data == null) return null;
        VideoInfo videoInfo = buildVideoInfo(resp.data);
        LikeCoinFavApi.getVideoStats(videoInfo);
        return videoInfo;
    }

    public static String getTags(String bvid) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/tag/archive/tags?bvid=" + bvid;
        String json = NetWorkUtil.getJson(url).toString();
        ApiResponse<List<TagData>> resp = GsonUtil.fromJson(json,
                new com.google.gson.reflect.TypeToken<ApiResponse<List<TagData>>>(){}.getType());
        if (resp == null || resp.data == null) return "";
        StringBuilder tags = new StringBuilder();
        for (int i = 0; i < resp.data.size(); i++) {
            if (i > 0) tags.append("/");
            TagData tag = resp.data.get(i);
            if (tag != null) tags.append(tag.tag_name);
        }
        return tags.toString();
    }

    public static String getTags(long aid) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/tag/archive/tags?aid=" + aid;
        String json = NetWorkUtil.getJson(url).toString();
        ApiResponse<List<TagData>> resp = GsonUtil.fromJson(json,
                new com.google.gson.reflect.TypeToken<ApiResponse<List<TagData>>>(){}.getType());
        if (resp == null || resp.data == null) return "";
        StringBuilder tags = new StringBuilder();
        for (int i = 0; i < resp.data.size(); i++) {
            if (i > 0) tags.append("/");
            TagData tag = resp.data.get(i);
            if (tag != null) tags.append(tag.tag_name);
        }
        return tags.toString();
    }

    public static Collection analyzeUgcSeason(UgcSeasonData data) {
        Collection collection = new Collection();
        collection.id = data.id;
        collection.title = data.title;
        collection.intro = data.intro;
        collection.cover = data.cover;
        collection.mid = data.mid;
        collection.view = StringUtil.toWan(data.stat != null ? data.stat.view : 0);

        if (data.sections != null) {
            List<Collection.Section> sectionList = new ArrayList<>();
            for (UgcSectionData sectionData : data.sections) {
                if (sectionData == null) continue;
                Collection.Section section = new Collection.Section();
                section.season_id = sectionData.season_id;
                section.id = sectionData.id;
                section.title = sectionData.title;
                if (sectionData.episodes != null) {
                    List<Collection.Episode> episodeList = new ArrayList<>();
                    for (UgcEpisodeData epData : sectionData.episodes) {
                        if (epData == null) continue;
                        Collection.Episode episode = new Collection.Episode();
                        episode.season_id = epData.season_id;
                        episode.section_id = epData.section_id;
                        episode.id = epData.id;
                        episode.aid = epData.aid;
                        episode.cid = epData.cid;
                        episode.title = epData.title;
                        episode.bvid = epData.bvid;
                        if (epData.arc != null) episode.arc = buildVideoInfo(epData.arc);
                        episodeList.add(episode);
                    }
                    section.episodes = episodeList;
                }
                sectionList.add(section);
            }
            collection.sections = sectionList;
        }
        return collection;
    }

    private static VideoInfo buildVideoInfo(VideoInfoData data) {
        VideoInfo videoInfo = new VideoInfo();
        Logu.v("视频信息", "--------");

        videoInfo.title = data.title != null ? data.title : "";
        videoInfo.cover = data.pic != null ? data.pic : "";
        videoInfo.bvid = data.bvid != null ? data.bvid : "";
        videoInfo.aid = data.aid;

        // desc_v2: 需要特殊处理SpannableStringBuilder和At
        if (data.desc_v2 != null && !data.desc_v2.isEmpty()) {
            SpannableStringBuilder sb = new SpannableStringBuilder();
            ArrayList<At> ats = new ArrayList<>();
            for (DescV2 desc : data.desc_v2) {
                if (desc == null) continue;
                if (desc.type == 2) {
                    Pair<Integer, Integer> indexs = StringUtil.appendString(sb, "@" + desc.raw_text);
                    ats.add(new At(desc.biz_id, indexs.first, indexs.second));
                } else {
                    sb.append(desc.raw_text != null ? desc.raw_text : "");
                }
            }
            videoInfo.description = sb.toString();
            videoInfo.descAts = ats;
        } else {
            videoInfo.description = data.desc != null ? data.desc : "";
        }

        @SuppressLint("SimpleDateFormat") SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        if (data.pubdate > 0) videoInfo.timeDesc = sdf.format(data.pubdate * 1000);
        videoInfo.duration = StringUtil.toTime(data.duration);
        videoInfo.copyright = data.copyright;

        // stat
        if (data.stat != null) {
            Stats stats = new Stats();
            stats.view = data.stat.view;
            stats.like = data.stat.like;
            stats.coin = data.stat.coin;
            stats.reply = data.stat.reply;
            stats.danmaku = data.stat.danmaku;
            stats.favorite = data.stat.favorite;
            stats.coin_limit = (videoInfo.copyright == VideoInfo.COPYRIGHT_REPRINT) ? 1 : 2;
            videoInfo.stats = stats;
        }

        // pages
        if (data.pages != null) {
            ArrayList<String> pagenames = new ArrayList<>();
            ArrayList<Long> cids = new ArrayList<>();
            for (PageData page : data.pages) {
                if (page == null) continue;
                pagenames.add(page.part != null ? page.part : "");
                cids.add(page.cid);
            }
            videoInfo.pagenames = pagenames;
            videoInfo.cids = cids;
        }

        videoInfo.upowerExclusive = data.is_upower_exclusive;

        // rights
        if (data.rights != null) {
            videoInfo.isCooperation = data.rights.is_cooperation == 1;
            videoInfo.isSteinGate = data.rights.is_stein_gate == 1;
            videoInfo.is360 = data.rights.is_360 == 1;
        }

        // staff / owner
        ArrayList<UserInfo> staff_list = new ArrayList<>();
        if (videoInfo.isCooperation && data.staff != null) {
            for (StaffData s : data.staff) {
                if (s == null) continue;
                UserInfo member = new UserInfo();
                member.mid = s.mid;
                member.sign = s.title != null ? s.title : "";
                member.name = s.name != null ? s.name : "";
                member.avatar = s.face != null ? s.face : "";
                member.fans = s.follower;
                member.level = 6;
                member.followed = false;
                member.notice = "";
                if (s.official != null) {
                    member.official = s.official.role;
                    member.officialDesc = s.official.title != null ? s.official.title : "";
                }
                staff_list.add(member);
            }
        } else if (data.owner != null) {
            UserInfo userInfo = new UserInfo();
            userInfo.name = data.owner.name != null ? data.owner.name : "";
            userInfo.avatar = data.owner.face != null ? data.owner.face : "";
            userInfo.mid = data.owner.mid;
            userInfo.sign = "UP主";
            staff_list.add(userInfo);
        }
        videoInfo.staff = staff_list;

        // argue_info
        if (data.argue_info != null) {
            videoInfo.argueMsg = data.argue_info.argue_msg;
        }

        // redirect_url (bangumi)
        try {
            if (data.redirect_url != null && !data.redirect_url.isEmpty() && data.redirect_url.contains("bangumi")) {
                videoInfo.epid = Long.parseLong(data.redirect_url.replace("https://www.bilibili.com/bangumi/play/ep", ""));
            } else {
                videoInfo.epid = -1;
            }
        } catch (Exception e) {
            videoInfo.epid = -1;
        }

        // ugc_season
        if (data.ugc_season != null) {
            videoInfo.collection = analyzeUgcSeason(data.ugc_season);
        }

        return videoInfo;
    }

    public static String getWatching(long aid, long cid) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/player/online/total?aid=" + aid + "&cid=" + cid;
        String json = NetWorkUtil.getJson(url).toString();
        ApiResponse<TotalData> resp = GsonUtil.fromJson(json,
                new com.google.gson.reflect.TypeToken<ApiResponse<TotalData>>(){}.getType());
        if (resp == null || resp.data == null || resp.data.total == null) return "";
        if (resp.data.total instanceof String) return (String) resp.data.total;
        return StringUtil.toWan(((Number) resp.data.total).longValue());
    }

    public static class TotalData {
        @SerializedName("total")
        public Object total;
    }
}
