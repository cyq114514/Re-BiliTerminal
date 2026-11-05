package com.RobinNotBad.BiliClient.model;

import java.util.ArrayList;
import java.util.List;

/** 视频笔记（只读）。正文是 Quill delta 序列，解析成 NoteBlock 列表。 */
public class Note {
    public String noteId = "";
    public String title = "";
    public String summary = "";
    public List<NoteBlock> blocks = new ArrayList<>();
    public long aid;
    public String bvid = "";
    public String videoTitle = "";
    public String videoDesc = "";
}
