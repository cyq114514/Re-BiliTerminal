package com.RobinNotBad.BiliClient.model;

/** 视频笔记的一个块：文本或图片或时间标签。 */
public class NoteBlock {
    public static final int TYPE_TEXT = 0;
    public static final int TYPE_IMAGE = 1;
    public static final int TYPE_TAG = 2;

    public int type = TYPE_TEXT;
    public String text = "";
    public boolean bold;
    public boolean underline;
    public boolean strike;
    public String color = "";
    public String background = "";
    public String list = "";

    // 图片
    public String imageUrl = "";
    public int imageWidth;

    // 时间标签
    public long tagCid;
    public int tagIndex = -1;
    public long tagSeconds;
    public String tagTitle = "";
}
