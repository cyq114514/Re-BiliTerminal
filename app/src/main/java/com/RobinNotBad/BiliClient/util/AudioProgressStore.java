package com.RobinNotBad.BiliClient.util;

import org.json.JSONObject;

import java.util.Locale;

/**
 * 听歌模式的本地续播记录（只保留最近一次「听全部」）。
 *
 * <p>只记标识与位置，不存播放地址：B 站取流地址几小时就过期，恢复时必须重新解析。
 * 队列本身也不整份存（可能上百条且收藏夹会变），恢复时按 cid 在当前列表里定位；
 * 定位不到就从头播，不会错播成别的歌。
 *
 * <p>隐私模式下既不读也不写，并清掉已有记录；记录 7 天后失效。
 */
public final class AudioProgressStore {

    private static final String KEY = "audio_progress_state";
    /** 超过这个时间的记录不再恢复（收藏夹内容可能早已变化） */
    private static final long EXPIRE_MS = 7L * 24 * 3600 * 1000;
    /** 播了不到 5 秒视为没听，下次从头 */
    private static final int MIN_POSITION_MS = 5000;
    /** 距结尾 5 秒内视为听完，下次从头 */
    private static final int TAIL_GUARD_MS = 5000;

    public static final class Progress {
        public long mediaId;
        public String order = "";
        public String folderName = "";
        public int index;
        public long aid;
        public long cid;
        public int positionMs;
        public int durationMs;
        public String title = "";
        public String upName = "";
        public String cover = "";
        public long savedAt;
    }

    private AudioProgressStore() {
    }

    public static void save(Progress progress) {
        if (progress == null || progress.cid <= 0 || progress.mediaId <= 0) return;
        if (isPrivacyMode()) {
            clear();
            return;
        }
        int position = progress.positionMs;
        if (position < MIN_POSITION_MS) position = 0;
        if (progress.durationMs > 0 && position > progress.durationMs - TAIL_GUARD_MS) position = 0;
        try {
            JSONObject json = new JSONObject();
            json.put("mediaId", progress.mediaId);
            json.put("order", progress.order == null ? "" : progress.order);
            json.put("folderName", progress.folderName == null ? "" : progress.folderName);
            json.put("index", progress.index);
            json.put("aid", progress.aid);
            json.put("cid", progress.cid);
            json.put("positionMs", Math.max(0, position));
            json.put("durationMs", Math.max(0, progress.durationMs));
            json.put("title", progress.title == null ? "" : progress.title);
            json.put("upName", progress.upName == null ? "" : progress.upName);
            json.put("cover", progress.cover == null ? "" : progress.cover);
            json.put("savedAt", System.currentTimeMillis());
            SharedPreferencesUtil.putString(KEY, json.toString());
        } catch (Throwable ignored) {
        }
    }

    /** 读取续播记录；隐私模式、过期、字段不完整都返回 null */
    public static Progress load() {
        if (isPrivacyMode()) return null;
        try {
            String raw = SharedPreferencesUtil.getString(KEY, "");
            if (raw == null || raw.isEmpty()) return null;
            JSONObject json = new JSONObject(raw);
            Progress progress = new Progress();
            progress.mediaId = json.optLong("mediaId", 0);
            progress.order = json.optString("order", "");
            progress.folderName = json.optString("folderName", "");
            progress.index = json.optInt("index", 0);
            progress.aid = json.optLong("aid", 0);
            progress.cid = json.optLong("cid", 0);
            progress.positionMs = json.optInt("positionMs", 0);
            progress.durationMs = json.optInt("durationMs", 0);
            progress.title = json.optString("title", "");
            progress.upName = json.optString("upName", "");
            progress.cover = json.optString("cover", "");
            progress.savedAt = json.optLong("savedAt", 0);
            if (progress.mediaId <= 0 || progress.cid <= 0) return null;
            if (System.currentTimeMillis() - progress.savedAt > EXPIRE_MS) {
                clear();
                return null;
            }
            return progress;
        } catch (Throwable t) {
            return null;
        }
    }

    public static void clear() {
        try {
            SharedPreferencesUtil.removeValue(KEY);
        } catch (Throwable ignored) {
        }
    }

    /** 0:00 形式的时长文案，用于「从上次 03:21 继续」提示 */
    public static String formatTime(int ms) {
        int totalSeconds = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private static boolean isPrivacyMode() {
        return SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.PRIVACY_MODE, false);
    }
}
