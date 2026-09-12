package com.RobinNotBad.BiliClient.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * DASH音频流信息
 */
public class DashAudioStream {
    public int id; // 音质代码
    public String baseUrl; // 默认流URL
    public List<String> backupUrl; // 备用流URL
    public long bandwidth; // 所需最低带宽（Byte）
    public String mimeType; // 格式mimetype类型
    public String codecs; // 音频类型
    public int codecid; // 编码标识（音频流恒为0）

    public DashAudioStream() {
        backupUrl = new ArrayList<>();
    }

    /**
     * 从JSON对象解析音频流
     */
    public static DashAudioStream fromJson(JSONObject json) throws JSONException {
        DashAudioStream stream = new DashAudioStream();
        stream.id = json.optInt("id", 0);
        stream.baseUrl = json.optString("baseUrl", json.optString("base_url", ""));
        stream.bandwidth = json.optLong("bandwidth", 0);
        stream.mimeType = json.optString("mimeType", json.optString("mime_type", ""));
        stream.codecs = json.optString("codecs", "");
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
     * 从JSON数组解析音频流列表
     */
    public static List<DashAudioStream> fromJsonArray(JSONArray jsonArray) throws JSONException {
        List<DashAudioStream> streams = new ArrayList<>();
        if (jsonArray != null) {
            for (int i = 0; i < jsonArray.length(); i++) {
                streams.add(fromJson(jsonArray.getJSONObject(i)));
            }
        }
        return streams;
    }

    /**
     * 获取描述信息（用于调试）
     */
    public String getDescription() {
        return String.format("ID:%d, codec:%s, bandwidth:%d",
                id, codecs, bandwidth);
    }

    /**
     * 获取音质名称
     */
    public String getQualityName() {
        switch (id) {
            case 30216:
                return "64K";
            case 30232:
                return "132K";
            case 30280:
                return "192K";
            case 30250:
                return "杜比全景声";
            case 30251:
                return "Hi-Res无损";
            default:
                return "音频";
        }
    }
}
