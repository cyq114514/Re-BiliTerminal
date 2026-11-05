package com.RobinNotBad.BiliClient.model;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 本地缓存视频的元数据，只用于断点续播。
 *
 * <p>存在视频所在目录的 .video_meta.json 里；目录移动时进度跟着文件走。
 */
public class VideoMeta {
    public long progressMs = 0;
    public long durationMs = 0;

    public static VideoMeta fromJson(JSONObject json) {
        VideoMeta meta = new VideoMeta();
        if (json == null) return meta;
        meta.progressMs = json.optLong("progress", 0);
        meta.durationMs = json.optLong("duration", 0);
        return meta;
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("progress", progressMs);
            json.put("duration", durationMs);
        } catch (JSONException ignored) {
        }
        return json;
    }
}
