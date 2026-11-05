package com.RobinNotBad.BiliClient.api;

import com.RobinNotBad.BiliClient.util.NetWorkUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Request;
import okhttp3.Response;

/**
 * 网易云音乐公开接口（非官方，仅作 B 站 CC 字幕缺失时的歌词兜底）。
 *
 * <p>用的是 `music.163.com/api/*` 这组老接口，匿名可访问、不需要 eapi/weapi 加密
 * （NeriPlayer 的 NeteaseClient 走的是加密的 interface.music.163.com/eapi，我们不需要）。
 *
 * <p><b>稳定性</b>：接口随时可能限流或变更，所有异常都往上抛给调用方，
 * 由调用方降级为"没有歌词"——绝不能因为取歌词失败影响播放。
 */
public class NeteaseApi {

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile";
    private static final String REFERER = "https://music.163.com/";

    /** 搜索结果里的一首歌 */
    public static class Song {
        public long id;
        public String name = "";
        public String artist = "";
        public String album = "";
        public long durationMs;
    }

    /** 歌词：原文 + 翻译（都可能为空串） */
    public static class Lyric {
        public String text = "";
        public String translated = "";
    }

    private NeteaseApi() {
    }

    /**
     * 按关键词搜歌。
     *
     * @return 永远非 null；请求失败时返回空列表（调用方据此判断"没找到"）
     */
    public static List<Song> search(String keyword, int limit) {
        List<Song> result = new ArrayList<>();
        if (keyword == null || keyword.trim().isEmpty()) return result;
        try {
            String url = "https://music.163.com/api/search/get/web?type=1&limit="
                    + Math.max(1, Math.min(limit, 20))
                    + "&s=" + URLEncoder.encode(keyword.trim(), "UTF-8");
            JSONObject root = getJson(url);
            JSONObject data = root.optJSONObject("result");
            if (data == null) data = root.optJSONObject("data");
            if (data == null) return result;
            JSONArray songs = data.optJSONArray("songs");
            if (songs == null) return result;
            for (int i = 0; i < songs.length(); i++) {
                JSONObject item = songs.optJSONObject(i);
                if (item == null) continue;
                Song song = new Song();
                song.id = item.optLong("id", 0);
                song.name = item.optString("name", "");
                song.durationMs = item.optLong("duration", 0);
                JSONObject album = item.optJSONObject("album");
                if (album != null) song.album = album.optString("name", "");
                //老接口是 artists[]，新接口是 ar[]
                JSONArray artists = item.optJSONArray("artists");
                if (artists == null) artists = item.optJSONArray("ar");
                StringBuilder sb = new StringBuilder();
                if (artists != null) {
                    for (int j = 0; j < artists.length(); j++) {
                        JSONObject artist = artists.optJSONObject(j);
                        if (artist == null) continue;
                        if (sb.length() > 0) sb.append('/');
                        sb.append(artist.optString("name", ""));
                    }
                }
                song.artist = sb.toString();
                if (song.id > 0) result.add(song);
            }
        } catch (Throwable ignored) {
            //接口不可用/结构变化：当作没搜到
        }
        return result;
    }

    /**
     * 取歌词。失败返回 null（调用方降级为无歌词）。
     */
    public static Lyric getLyric(long songId) {
        if (songId <= 0) return null;
        try {
            String url = "https://music.163.com/api/song/lyric?id=" + songId
                    + "&lv=-1&kv=-1&tv=-1";
            JSONObject root = getJson(url);
            if (root.optInt("code", 200) != 200) return null;
            Lyric lyric = new Lyric();
            JSONObject lrc = root.optJSONObject("lrc");
            if (lrc != null) lyric.text = lrc.optString("lyric", "");
            JSONObject tlyric = root.optJSONObject("tlyric");
            if (tlyric != null) lyric.translated = tlyric.optString("lyric", "");
            if (lyric.text.trim().isEmpty()) return null;
            return lyric;
        } catch (Throwable t) {
            return null;
        }
    }

    private static JSONObject getJson(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", REFERER)
                .build();
        try (Response response = NetWorkUtil.getOkHttpInstance().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }
            return new JSONObject(response.body().string());
        } catch (org.json.JSONException e) {
            throw new IOException("返回不是 JSON", e);
        }
    }
}
