package com.RobinNotBad.BiliClient.model;

import android.os.Parcel;
import android.os.Parcelable;

import java.io.Serializable;

public class OpusCard implements Parcelable, Serializable {
    public String title;
    public long id;
    public String cover;
    public String upName;
    public String view;

    public OpusCard() {
    }

    public OpusCard(String title, long id, String cover, String upName, String view) {
        this.title = title;
        this.id = id;
        this.cover = cover;
        this.upName = upName;
        this.view = view;
    }

    protected OpusCard(Parcel in) {
        title = in.readString();
        id = in.readLong();
        cover = in.readString();
        upName = in.readString();
        view = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(title);
        dest.writeLong(id);
        dest.writeString(cover);
        dest.writeString(upName);
        dest.writeString(view);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<OpusCard> CREATOR = new Creator<>() {
        @Override
        public OpusCard createFromParcel(Parcel in) {
            return new OpusCard(in);
        }

        @Override
        public OpusCard[] newArray(int size) {
            return new OpusCard[size];
        }
    };
}
