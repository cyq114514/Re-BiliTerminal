package com.RobinNotBad.BiliClient.model;

import android.os.Parcel;
import android.os.Parcelable;

import com.google.gson.annotations.SerializedName;

import java.io.Serializable;

public class LiveRoom implements Parcelable, Serializable {
    @SerializedName(value = "roomid", alternate = {"room_id"})
    public long roomid;
    public long short_id;
    public long uid;
    public String title;
    public String uname;
    public String tags;
    public String description;
    public int online;
    public int attention;
    public String user_cover;
    public int user_cover_flag;
    public String system_cover;
    public String cover;
    public String keyframe;
    public String show_cover;
    public String face;
    @SerializedName(value = "area_parent_id", alternate = {"area_v2_parent_id", "parent_area_id"})
    public int area_parent_id;
    @SerializedName(value = "area_parent_name", alternate = {"area_v2_parent_name", "parent_area_name"})
    public String area_parent_name;
    @SerializedName(value = "area_id", alternate = {"area_v2_id"})
    public int area_id;
    @SerializedName(value = "area_name", alternate = {"area_v2_name"})
    public String area_name;
    public String session_id;
    public long group_id;
    public String show_callback;
    public String click_callback;
    public String liveTime;
    public int live_status;
    public int old_area_id;
    public String background;
    public boolean is_portrait;
    public String room_silent_type;
    public int room_silent_level;
    public int room_silent_second;
    public int pk_status;
    public long pk_id;
    public long battle_id;
    public int allow_change_area_time;
    public int allow_upload_cover_time;
    public Verify verify;
    public Watched watched_show;
    public NewPendants new_pendants;
    public StudioInfo studio_info;

    public static class Verify implements Parcelable, Serializable {
        public int role;
        public String desc;
        public int type;

        public Verify() {
        }

        protected Verify(Parcel in) {
            role = in.readInt();
            desc = in.readString();
            type = in.readInt();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(role);
            dest.writeString(desc);
            dest.writeInt(type);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final Creator<Verify> CREATOR = new Creator<>() {
            @Override
            public Verify createFromParcel(Parcel in) {
                return new Verify(in);
            }

            @Override
            public Verify[] newArray(int size) {
                return new Verify[size];
            }
        };
    }

    public static class Watched implements Parcelable, Serializable {
        @SerializedName("switch")
        public boolean isSwitch;
        public int num;
        public String text_small;
        public String text_large;
        public String icon;
        public int icon_location;
        public String icon_web;

        public Watched() {
        }

        protected Watched(Parcel in) {
            isSwitch = in.readByte() != 0;
            num = in.readInt();
            text_small = in.readString();
            text_large = in.readString();
            icon = in.readString();
            icon_location = in.readInt();
            icon_web = in.readString();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeByte((byte) (isSwitch ? 1 : 0));
            dest.writeInt(num);
            dest.writeString(text_small);
            dest.writeString(text_large);
            dest.writeString(icon);
            dest.writeInt(icon_location);
            dest.writeString(icon_web);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final Creator<Watched> CREATOR = new Creator<>() {
            @Override
            public Watched createFromParcel(Parcel in) {
                return new Watched(in);
            }

            @Override
            public Watched[] newArray(int size) {
                return new Watched[size];
            }
        };
    }

    public static class NewPendants implements Parcelable, Serializable {
        public FrameInfo frame;
        public FrameInfo mobile_frame;
        public BadgeInfo badge;
        public BadgeInfo mobile_badge;

        public NewPendants() {
        }

        protected NewPendants(Parcel in) {
            frame = in.readParcelable(FrameInfo.class.getClassLoader());
            mobile_frame = in.readParcelable(FrameInfo.class.getClassLoader());
            badge = in.readParcelable(BadgeInfo.class.getClassLoader());
            mobile_badge = in.readParcelable(BadgeInfo.class.getClassLoader());
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeParcelable(frame, flags);
            dest.writeParcelable(mobile_frame, flags);
            dest.writeParcelable(badge, flags);
            dest.writeParcelable(mobile_badge, flags);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final Creator<NewPendants> CREATOR = new Creator<>() {
            @Override
            public NewPendants createFromParcel(Parcel in) {
                return new NewPendants(in);
            }

            @Override
            public NewPendants[] newArray(int size) {
                return new NewPendants[size];
            }
        };
    }

    public static class FrameInfo implements Parcelable, Serializable {
        public String name;
        public String value;
        public int position;
        public String desc;
        public int area;
        public int area_old;
        public String bg_color;
        public String bg_pic;
        public boolean use_old_area;

        public FrameInfo() {
        }

        protected FrameInfo(Parcel in) {
            name = in.readString();
            value = in.readString();
            position = in.readInt();
            desc = in.readString();
            area = in.readInt();
            area_old = in.readInt();
            bg_color = in.readString();
            bg_pic = in.readString();
            use_old_area = in.readByte() != 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(name);
            dest.writeString(value);
            dest.writeInt(position);
            dest.writeString(desc);
            dest.writeInt(area);
            dest.writeInt(area_old);
            dest.writeString(bg_color);
            dest.writeString(bg_pic);
            dest.writeByte((byte) (use_old_area ? 1 : 0));
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final Creator<FrameInfo> CREATOR = new Creator<>() {
            @Override
            public FrameInfo createFromParcel(Parcel in) {
                return new FrameInfo(in);
            }

            @Override
            public FrameInfo[] newArray(int size) {
                return new FrameInfo[size];
            }
        };
    }

    public static class BadgeInfo implements Parcelable, Serializable {
        public String name;
        public int position;
        public String value;
        public String desc;

        public BadgeInfo() {
        }

        protected BadgeInfo(Parcel in) {
            name = in.readString();
            position = in.readInt();
            value = in.readString();
            desc = in.readString();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(name);
            dest.writeInt(position);
            dest.writeString(value);
            dest.writeString(desc);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final Creator<BadgeInfo> CREATOR = new Creator<>() {
            @Override
            public BadgeInfo createFromParcel(Parcel in) {
                return new BadgeInfo(in);
            }

            @Override
            public BadgeInfo[] newArray(int size) {
                return new BadgeInfo[size];
            }
        };
    }

    public static class StudioInfo implements Parcelable, Serializable {
        public int status;
        public java.util.List<Object> master_list;

        public StudioInfo() {
        }

        protected StudioInfo(Parcel in) {
            status = in.readInt();
            master_list = in.readArrayList(Object.class.getClassLoader());
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(status);
            dest.writeList(master_list);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final Creator<StudioInfo> CREATOR = new Creator<>() {
            @Override
            public StudioInfo createFromParcel(Parcel in) {
                return new StudioInfo(in);
            }

            @Override
            public StudioInfo[] newArray(int size) {
                return new StudioInfo[size];
            }
        };
    }

    public LiveRoom() {
    }

    protected LiveRoom(Parcel in) {
        roomid = in.readLong();
        short_id = in.readLong();
        uid = in.readLong();
        title = in.readString();
        uname = in.readString();
        tags = in.readString();
        description = in.readString();
        online = in.readInt();
        attention = in.readInt();
        user_cover = in.readString();
        user_cover_flag = in.readInt();
        system_cover = in.readString();
        cover = in.readString();
        keyframe = in.readString();
        show_cover = in.readString();
        face = in.readString();
        area_parent_id = in.readInt();
        area_parent_name = in.readString();
        area_id = in.readInt();
        area_name = in.readString();
        session_id = in.readString();
        group_id = in.readLong();
        show_callback = in.readString();
        click_callback = in.readString();
        liveTime = in.readString();
        live_status = in.readInt();
        old_area_id = in.readInt();
        background = in.readString();
        is_portrait = in.readByte() != 0;
        room_silent_type = in.readString();
        room_silent_level = in.readInt();
        room_silent_second = in.readInt();
        pk_status = in.readInt();
        pk_id = in.readLong();
        battle_id = in.readLong();
        allow_change_area_time = in.readInt();
        allow_upload_cover_time = in.readInt();
        verify = in.readParcelable(Verify.class.getClassLoader());
        watched_show = in.readParcelable(Watched.class.getClassLoader());
        new_pendants = in.readParcelable(NewPendants.class.getClassLoader());
        studio_info = in.readParcelable(StudioInfo.class.getClassLoader());
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(roomid);
        dest.writeLong(short_id);
        dest.writeLong(uid);
        dest.writeString(title);
        dest.writeString(uname);
        dest.writeString(tags);
        dest.writeString(description);
        dest.writeInt(online);
        dest.writeInt(attention);
        dest.writeString(user_cover);
        dest.writeInt(user_cover_flag);
        dest.writeString(system_cover);
        dest.writeString(cover);
        dest.writeString(keyframe);
        dest.writeString(show_cover);
        dest.writeString(face);
        dest.writeInt(area_parent_id);
        dest.writeString(area_parent_name);
        dest.writeInt(area_id);
        dest.writeString(area_name);
        dest.writeString(session_id);
        dest.writeLong(group_id);
        dest.writeString(show_callback);
        dest.writeString(click_callback);
        dest.writeString(liveTime);
        dest.writeInt(live_status);
        dest.writeInt(old_area_id);
        dest.writeString(background);
        dest.writeByte((byte) (is_portrait ? 1 : 0));
        dest.writeString(room_silent_type);
        dest.writeInt(room_silent_level);
        dest.writeInt(room_silent_second);
        dest.writeInt(pk_status);
        dest.writeLong(pk_id);
        dest.writeLong(battle_id);
        dest.writeInt(allow_change_area_time);
        dest.writeInt(allow_upload_cover_time);
        dest.writeParcelable(verify, flags);
        dest.writeParcelable(watched_show, flags);
        dest.writeParcelable(new_pendants, flags);
        dest.writeParcelable(studio_info, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<LiveRoom> CREATOR = new Creator<>() {
        @Override
        public LiveRoom createFromParcel(Parcel in) {
            return new LiveRoom(in);
        }

        @Override
        public LiveRoom[] newArray(int size) {
            return new LiveRoom[size];
        }
    };
}
