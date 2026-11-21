package com.RobinNotBad.BiliClient.model;

import java.util.ArrayList;

public class LocalVideo {
    public String cover;
    public String title;
    public ArrayList<String> pageList;
    public ArrayList<String> videoFileList;
    public ArrayList<String> danmakuFileList;
    public ArrayList<Long> sizeList;
    public long size;
    /** 整条缓存只有音频（没有 video.mp4）；单集看 audio.m4a，多 P 需全部为音频 */
    public boolean audioOnly;

    public LocalVideo() {
    }

    public void calcTotalSize() {
        size = 0;
        for (long pageSize : sizeList) {
            size += pageSize;
        }
    }
}
