package com.RobinNotBad.BiliClient.model;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import java.util.ArrayList;

public class PlayerData implements Parcelable {
    public static int TYPE_VIDEO = 0;
    public static int TYPE_BANGUMI = 1;
    public static int TYPE_LIVE = 2;
    public static int TYPE_LOCAL = 4;

    public String title = "";
    public String videoUrl = "";
    public String danmakuUrl = "";
    public int qn = -1;
    public String[] qnStrList;
    public int[] qnValueList;
    public long aid;
    public long cid;
    public long mid;
    public int progress = 0;
    public long cidHistory = 0;
    public int type = 0;
    public long timeStamp;
    public ArrayList<String> pagenames;
    public ArrayList<Long> cids;
    public int currentPageIndex = 0;
    public DashData dashData; // DASH格式数据
    public String audioUrl = ""; // 单独的音频URL（用于仅音频下载）

    //番剧(PGC)专用维度：上报观看进度必须走 x/click-interface/web/heartbeat，
    //该接口只有带 epid/sid/sub_type 才会把记录记成番剧；x/v2/history/report 没有这些字段。
    public long epid = 0;      //剧集 epid
    public long seasonId = 0;  //番剧 ssid（season_id）
    public int seasonType = 0; //剧集副类型，取值同 season_type：1番剧/2电影/3纪录片/4国创/5电视剧/7综艺
    public String bvid = "";   //剧集 bvid（心跳上报的第二种稿件标识；取不到为空串）
    //片头/片尾片段（取流响应里的 clip_info_list）。属于派生数据：每次取流都会重新填充，
    //因此不参与 Parcel 序列化，只在本进程内从"取流"传递到"进播放器"这一步
    public java.util.List<ClipInfo> clipInfoList;

    public PlayerData() {
    }

    public PlayerData(int type) {
        this.type = type;
    }

    protected PlayerData(Parcel in) {
        title = in.readString();
        videoUrl = in.readString();
        danmakuUrl = in.readString();
        qn = in.readInt();
        qnStrList = in.createStringArray();
        qnValueList = in.createIntArray();
        aid = in.readLong();
        cid = in.readLong();
        mid = in.readLong();
        progress = in.readInt();
        type = in.readInt();
        timeStamp = in.readLong();
        pagenames = in.createStringArrayList();
        cids = new ArrayList<>();
        in.readList(cids, Long.class.getClassLoader());
        currentPageIndex = in.readInt();
        audioUrl = in.readString();
        //新增字段只能追加在末尾，且必须与 writeToParcel 顺序严格一致，否则读出来会整体错位
        epid = in.readLong();
        seasonId = in.readLong();
        seasonType = in.readInt();
        //bvid 是心跳上报的第二身份字段，必须保持"非 null 空串"的默认语义：
        //Parcel.readString() 在写入侧为 null 时会读回 null，直接把字段默认值 "" 破坏掉，
        //真机日志里表现为 [开播续播] bvid=null、上报体缺 bvid
        String rawBvid = in.readString();
        bvid = rawBvid != null ? rawBvid : "";
        // dashData不序列化，下载时会重新获取
    }

    public static final Creator<PlayerData> CREATOR = new Creator<>() {
        @Override
        public PlayerData createFromParcel(Parcel in) {
            return new PlayerData(in);
        }

        @Override
        public PlayerData[] newArray(int size) {
            return new PlayerData[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(title);
        dest.writeString(videoUrl);
        dest.writeString(danmakuUrl);
        dest.writeInt(qn);
        dest.writeStringArray(qnStrList);
        dest.writeIntArray(qnValueList);
        dest.writeLong(aid);
        dest.writeLong(cid);
        dest.writeLong(mid);
        dest.writeInt(progress);
        dest.writeInt(type);
        dest.writeLong(timeStamp);
        dest.writeStringList(pagenames);
        dest.writeList(cids);
        dest.writeInt(currentPageIndex);
        dest.writeString(audioUrl);
        //新增字段只能追加在末尾，且必须与 PlayerData(Parcel) 顺序严格一致，否则读出来会整体错位
        dest.writeLong(epid);
        dest.writeLong(seasonId);
        dest.writeInt(seasonType);
        //写入侧兜 null：调用方可能把取不到的 bvid 直接赋成 null，
        //写 null 会让读取侧拿到 null（见构造器里的说明）
        dest.writeString(bvid != null ? bvid : "");
        // dashData不序列化，下载时会重新获取
    }

    public boolean isVideo() {
        return type == TYPE_VIDEO;
    }

    public boolean isBangumi() {
        return type == TYPE_BANGUMI;
    }

    public boolean isLive() {
        return type == TYPE_LIVE;
    }

    public boolean isLocal() {
        return type == TYPE_LOCAL;
    }
}
