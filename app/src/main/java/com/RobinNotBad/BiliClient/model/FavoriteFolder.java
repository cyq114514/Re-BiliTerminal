package com.RobinNotBad.BiliClient.model;

import com.google.gson.annotations.SerializedName;

public class FavoriteFolder {
    @SerializedName(value = "fav_box", alternate = {"id", "fid"})
    public long id;
    @SerializedName(value = "id", alternate = {"media_id"})
    public long mediaId;
    public String name;
    public String cover;
    @SerializedName("count")
    public int videoCount;
    @SerializedName("max_count")
    public int maxCount;
    public boolean isDefault;

    public FavoriteFolder() {
    }
}
