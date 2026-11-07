package com.RobinNotBad.BiliClient.model;

import android.os.Parcel;
import android.os.Parcelable;

import com.google.gson.annotations.SerializedName;

import java.io.Serializable;

public class VideoCard implements Parcelable, Serializable {
    public String title;
    public String upName;
    public String view;
    @SerializedName("pic")
    public String cover;
    public String type = "video";
    public long aid;
    public String bvid;
    public long cid = 0;
    public long epid = 0; //番剧剧集id，type 为 media_bangumi 且历史记录来源时用于反查 media_id
    public int progress = 0; //观看进度（秒），历史/稍后再看来源时携带；0 表示未看过

    // ↓↓↓ 以下字段只有推荐流会填，其它来源保持默认值即可（新增字段必须带默认值，避免影响旧链路）
    public long upMid = 0;      //UP 主 mid，用于"按 UP 屏蔽"
    public int duration = 0;    //时长（秒），0 表示未知
    public String gotoType = ""; //接口原始 card_goto / goto（av、inline_av、bangumi…），排查用
    public String uri = "";      //接口给出的跳转 uri（含 player_width/height/rotate，可判竖屏）
    public String rcmdReason = ""; //推荐理由，空串表示没有
    public boolean isFollowed = false; //是否已关注该 UP（app 端靠推荐理由推断，web 端靠 is_followed）
    public boolean invalid = false; //稿件已失效（收藏夹 attr!=0），音频队列等场景应跳过

    // ↓↓↓ 第三方番剧源条目（type = "third_source"）专用。
    // 不复用 aid/bvid：那些是 B 站 ID，填进第三方条目会让后续任何按 aid 的逻辑串到别的稿件上。
    public String sourceId = "";        // 数据源 id
    public String sourceDetailUrl = ""; // 站点条目页
    public String sourceSubjectId = ""; // 站点内部 id（仅展示/排查）
    public String sourceOriginal = "";  // 原名 / 别名，用于「用原名再搜一次」
    //本地媒体文件绝对路径（1.3.3 反馈：缓存的音频用听歌模式听）；非空 = 音频队列直读文件，不走取流
    public String localPath = "";

    public VideoCard(String title, String upName, String view, String cover, long aid, String bvid, String type) {
        this.title = title;
        this.upName = upName;
        this.view = view;
        this.cover = cover;
        this.aid = aid;
        this.bvid = bvid;
        this.type = type;
    }

    public VideoCard(String title, String upName, String view, String cover, long aid, String bvid, Long cid) {
        this.title = title;
        this.upName = upName;
        this.view = view;
        this.cover = cover;
        this.aid = aid;
        this.bvid = bvid;
        this.cid = cid;
    }

    public VideoCard(String title, String upName, String view, String cover, long aid, String bvid) {
        this.title = title;
        this.upName = upName;
        this.view = view;
        this.cover = cover;
        this.aid = aid;
        this.bvid = bvid;
    }

    public VideoCard() {
    }

    protected VideoCard(Parcel in) {
        title = in.readString();
        upName = in.readString();
        view = in.readString();
        cover = in.readString();
        type = in.readString();
        aid = in.readLong();
        bvid = in.readString();
        cid = in.readLong();
        epid = in.readLong();
        progress = in.readInt();
        //新增字段一律追加在末尾读/写：顺序必须与 writeToParcel 完全一致，否则跨页面传参会串字段
        upMid = in.readLong();
        duration = in.readInt();
        gotoType = in.readString();
        uri = in.readString();
        rcmdReason = in.readString();
        isFollowed = in.readInt() != 0;
        invalid = in.readInt() != 0;
        sourceId = in.readString();
        sourceDetailUrl = in.readString();
        sourceSubjectId = in.readString();
        sourceOriginal = in.readString();
        localPath = in.readString();
    }

    public static final Creator<VideoCard> CREATOR = new Creator<>() {
        @Override
        public VideoCard createFromParcel(Parcel in) {
            return new VideoCard(in);
        }

        @Override
        public VideoCard[] newArray(int size) {
            return new VideoCard[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeString(title);
        parcel.writeString(upName);
        parcel.writeString(view);
        parcel.writeString(cover);
        parcel.writeString(type);
        parcel.writeLong(aid);
        parcel.writeString(bvid);
        parcel.writeLong(cid);
        parcel.writeLong(epid);
        parcel.writeInt(progress);
        parcel.writeLong(upMid);
        parcel.writeInt(duration);
        parcel.writeString(gotoType);
        parcel.writeString(uri);
        parcel.writeString(rcmdReason);
        parcel.writeInt(isFollowed ? 1 : 0);
        parcel.writeInt(invalid ? 1 : 0);
        parcel.writeString(sourceId);
        parcel.writeString(sourceDetailUrl);
        parcel.writeString(sourceSubjectId);
        parcel.writeString(sourceOriginal);
        parcel.writeString(localPath);
    }
}
