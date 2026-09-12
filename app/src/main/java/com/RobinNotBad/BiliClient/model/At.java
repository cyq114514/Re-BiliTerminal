package com.RobinNotBad.BiliClient.model;

import android.os.Parcel;
import android.os.Parcelable;

import java.io.Serializable;

public class At implements Parcelable, Serializable {
    public final long id;
    public int start;
    public int end;
    public String name;

    public At(long id, int startIndex, int endIndex) {
        this.id = id;
        this.start = startIndex;
        this.end = endIndex;
    }

    public At(long id, String name) {
        this.id = id;
        this.name = name;
    }

    protected At(Parcel in) {
        id = in.readLong();
        start = in.readInt();
        end = in.readInt();
        name = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(id);
        dest.writeInt(start);
        dest.writeInt(end);
        dest.writeString(name);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<At> CREATOR = new Creator<>() {
        @Override
        public At createFromParcel(Parcel in) {
            return new At(in);
        }

        @Override
        public At[] newArray(int size) {
            return new At[size];
        }
    };
}
