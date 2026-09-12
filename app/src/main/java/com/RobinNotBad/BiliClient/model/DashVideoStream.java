package com.RobinNotBad.BiliClient.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * DASH视频流信息
 */
public class DashVideoStream {
    public int id; // 清晰度代码
    public String baseUrl; // 默认流URL
    public List<String> backupUrl; // 备用流URL
    public long bandwidth; // 所需最低带宽（Byte）
    public String mimeType; // 格式mimetype类型
    public String codecs; // 编码类型
    public int width; // 视频宽度（像素）
    public int height; // 视频高度（像素）
    public String frameRate; // 视频帧率
    public int codecid; // 码流编码标识代码

    public DashVideoStream() {
        backupUrl = new ArrayList<>();
    }

    /**
     * 从JSON对象解析视频流
     */
    public static DashVideoStream fromJson(JSONObject json) throws JSONException {
        DashVideoStream stream = new DashVideoStream();
        stream.id = json.optInt("id", 0);
        stream.baseUrl = json.optString("baseUrl", json.optString("base_url", ""));
        stream.bandwidth = json.optLong("bandwidth", 0);
        stream.mimeType = json.optString("mimeType", json.optString("mime_type", ""));
        stream.codecs = json.optString("codecs", "");
        stream.width = json.optInt("width", 0);
        stream.height = json.optInt("height", 0);
        stream.frameRate = json.optString("frameRate", json.optString("frame_rate", ""));
        stream.codecid = json.optInt("codecid", 0);

        // 解析备用URL
        JSONArray backupUrlArray = json.optJSONArray("backupUrl");
        if (backupUrlArray == null) {
            backupUrlArray = json.optJSONArray("backup_url");
        }
        if (backupUrlArray != null) {
            for (int i = 0; i < backupUrlArray.length(); i++) {
                stream.backupUrl.add(backupUrlArray.optString(i));
            }
        }

        return stream;
    }

    /**
     * 从JSON数组解析视频流列表
     */
    public static List<DashVideoStream> fromJsonArray(JSONArray jsonArray) throws JSONException {
        List<DashVideoStream> streams = new ArrayList<>();
        if (jsonArray != null) {
            for (int i = 0; i < jsonArray.length(); i++) {
                streams.add(fromJson(jsonArray.getJSONObject(i)));
            }
        }
        return streams;
    }
}
