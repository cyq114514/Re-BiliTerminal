package com.RobinNotBad.BiliClient.model;

import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;

public class Bangumi {
    public Info info;
    public ArrayList<Section> sectionList;

    public static class Info {
        public long media_id;
        public long season_id;
        public int type;
        public int count;
        public float score;
        public String title;
        public String cover;
        @SerializedName("horizontal_picture")
        public String cover_horizontal;
        public String type_name;
        public String area_name;
        @SerializedName("index_show")
        public String indexShow;

        // 新增字段
        public String evaluate; // 简介
        public String staff; // 制作人员信息
        public String record; // 备案号
        public String subtitle; // 副标题
        public Publish publish; // 发布时间信息
        public ArrayList<String> styles; // 标签
        public Stat stat; // 状态数
        public UpInfo up_info; // UP主信息
        public Series series; // 系列信息
        public ArrayList<Season> seasons; // 同系列所有季信息
    }

    public static class Publish {
        public int is_finish; // 完结状态 0：未完结 1：已完结
        public int is_started; // 是否发布 0：未发布 1：已发布
        public String pub_time; // 发布时间
        public String pub_time_show; // 发布时间文字介绍
    }

    public static class Stat {
        public int favorites; // 收藏数
        public int series_follow; // 系列追番数
        public int views; // 播放数
        public int vt; // 虚拟观看数
    }

    public static class UpInfo {
        public long mid;
        public String name;
        public String avatar;
    }

    public static class Series {
        public long series_id;
        public String series_title;
    }

    public static class Season {
        public long media_id;
        public long season_id;
        public String season_title;
        public String cover;
        public String badge;
    }

    public static class Section {
        public long id;
        public int type;
        public String title;
        public ArrayList<Episode> episodeList;

        public Section() {
        }
    }

    public static class Episode {
        public long id;
        public long aid;
        public long cid;
        public String title;
        @SerializedName("long_title")
        public String title_long;
        public String cover;
        public String badge;//标记（如会员/限免）

        public Episode() {
        }

        public PlayerData toPlayerData() {
            PlayerData data = new PlayerData(PlayerData.TYPE_BANGUMI);
            data.aid = aid;
            data.cid = cid;
            data.title = title;
            data.mid = SharedPreferencesUtil.getLong("mid", 0);
            //epid 是番剧进度上报的必需维度（心跳接口的 epid），在这里设好，
            //任何调用方都不需要记得带；seasonId/seasonType 只有详情页拿得到 Info，由详情页补
            data.epid = id;
            return data;
        }
    }
}
