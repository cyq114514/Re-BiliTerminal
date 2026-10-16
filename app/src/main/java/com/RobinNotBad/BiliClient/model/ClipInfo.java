package com.RobinNotBad.BiliClient.model;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import com.google.gson.annotations.SerializedName;

/**
 * 番剧片头/片尾片段。
 *
 * <p>数据来自 B 站官方接口 {@code pgc/player/web/v2/playurl} 响应里的
 * {@code result.video_info.clip_info_list}，**无需任何第三方数据源**，随取流响应一次返回。
 * 实测响应示例（ep 21449）：
 * <pre>
 * [{"materialNo":0,"start":59,"end":148,"toastText":"即将跳过片头","clipType":"CLIP_TYPE_OP"},
 *  {"materialNo":0,"start":1330,"end":1419,"toastText":"即将跳过片尾","clipType":"CLIP_TYPE_ED"}]
 * </pre>
 * {@code start}/{@code end} 单位是<b>秒</b>（不是毫秒），与 {@code WatchProgress} 的毫秒口径不同。
 */
public class ClipInfo implements Parcelable {
    /** 片头 */
    public static final String TYPE_OP = "CLIP_TYPE_OP";
    /** 片尾 */
    public static final String TYPE_ED = "CLIP_TYPE_ED";

    @SerializedName("clipType")
    public String clipType;
    /** 片段起始（秒） */
    @SerializedName("start")
    public double start;
    /** 片段结束（秒） */
    @SerializedName("end")
    public double end;
    @SerializedName("materialNo")
    public int materialNo;
    /** 官方提示文案，如「即将跳过片头」 */
    @SerializedName("toastText")
    public String toastText;

    public ClipInfo() {
    }

    public ClipInfo(String clipType, double start, double end) {
        this.clipType = clipType;
        this.start = start;
        this.end = end;
    }

    public boolean isOp() {
        return TYPE_OP.equals(clipType);
    }

    /** 片段时长（秒）；异常数据（end<=start）返回 0 */
    public double duration() {
        return end > start ? end - start : 0;
    }

    /** 用于提示的中文名 */
    public String displayName() {
        if (isOp()) return "片头";
        if (TYPE_ED.equals(clipType)) return "片尾";
        return "片段";
    }

    /** 位置（毫秒）是否落在本片段区间内 */
    public boolean contains(long positionMs) {
        if (positionMs < 0) return false;
        long startMs = (long) (start * 1000);
        long endMs = (long) (end * 1000);
        return positionMs >= startMs && positionMs < endMs;
    }

    /** 片段结束位置（毫秒），即跳过目标 */
    public long endMs() {
        return (long) (end * 1000);
    }

    protected ClipInfo(Parcel in) {
        clipType = in.readString();
        start = in.readDouble();
        end = in.readDouble();
        materialNo = in.readInt();
        toastText = in.readString();
    }

    public static final Creator<ClipInfo> CREATOR = new Creator<>() {
        @Override
        public ClipInfo createFromParcel(Parcel in) {
            return new ClipInfo(in);
        }

        @Override
        public ClipInfo[] newArray(int size) {
            return new ClipInfo[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(clipType);
        dest.writeDouble(start);
        dest.writeDouble(end);
        dest.writeInt(materialNo);
        dest.writeString(toastText);
    }
}
