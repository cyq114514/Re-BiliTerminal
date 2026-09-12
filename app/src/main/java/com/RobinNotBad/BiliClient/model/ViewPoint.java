package com.RobinNotBad.BiliClient.model;

public class ViewPoint {
    public String content;
    public int from;
    public int to;
    public int type;
    public String imgUrl;
    public String logoUrl;

    public ViewPoint(String content, int from, int to, int type, String imgUrl, String logoUrl) {
        this.content = content;
        this.from = from;
        this.to = to;
        this.type = type;
        this.imgUrl = imgUrl;
        this.logoUrl = logoUrl;
    }
}

