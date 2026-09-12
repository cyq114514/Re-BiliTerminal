package com.RobinNotBad.BiliClient.model;

import com.google.gson.annotations.SerializedName;

public class ApiResponse<T> {
    @SerializedName("code")
    public int code;

    @SerializedName("message")
    public String message;

    @SerializedName("data")
    public T data;

    public boolean isSuccess() {
        return code == 0;
    }
}
