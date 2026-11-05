package com.RobinNotBad.BiliClient.util;

import com.RobinNotBad.BiliClient.model.VideoMeta;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * 读写本地视频目录里的 .video_meta.json，只用于本地缓存断点续播。
 *
 * <p>不联网、不依赖数据库；损坏文件回退成“从头播”。
 */
public final class VideoMetaManager {
    private static final String META_FILE = ".video_meta.json";

    private VideoMetaManager() {
    }

    private static File getMetaFile(File videoFolder) {
        return new File(videoFolder, META_FILE);
    }

    public static VideoMeta readMeta(File videoFolder) {
        VideoMeta meta = new VideoMeta();
        if (videoFolder == null) return meta;
        File metaFile = getMetaFile(videoFolder);
        if (!metaFile.exists()) return meta;
        try (FileInputStream in = new FileInputStream(metaFile)) {
            byte[] buffer = new byte[(int) Math.min(metaFile.length(), 64 * 1024)];
            int read = in.read(buffer);
            if (read <= 0) return meta;
            String content = new String(buffer, 0, read, StandardCharsets.UTF_8).trim();
            if (content.isEmpty()) return meta;
            meta = VideoMeta.fromJson(new JSONObject(content));
        } catch (Exception ignored) {
        }
        return meta;
    }

    public static synchronized void saveMeta(File videoFolder, VideoMeta meta) {
        if (videoFolder == null || meta == null) return;
        File metaFile = getMetaFile(videoFolder);
        File tmp = new File(videoFolder, ".video_meta.json.tmp");
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new java.io.FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            writer.write(meta.toJson().toString());
            writer.flush();
        } catch (IOException ignored) {
            return;
        }
        //原子替换：写一半被杀时旧 meta 仍在，不会得到半截 JSON
        if (metaFile.exists() && !metaFile.delete()) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(metaFile)) {
            tmp.delete();
        }
    }

    /** 读取续播位置（毫秒），0 表示从头播。已按“已看完/位置过小”归一化。 */
    public static long readResumeMs(File videoFolder) {
        VideoMeta meta = readMeta(videoFolder);
        if (meta.progressMs <= 5000L) return 0L;
        if (meta.durationMs > 0L && meta.durationMs - meta.progressMs <= 5000L) return 0L;
        if (meta.durationMs > 0L && meta.progressMs > meta.durationMs) return 0L;
        return meta.progressMs;
    }

    /** 落盘本地播放位置；位置过小或已看完写 0，同时清时长，避免误导下次判断。 */
    public static void saveProgress(File videoFolder, long positionMs, long durationMs) {
        VideoMeta meta = readMeta(videoFolder);
        long target = positionMs;
        if (target <= 0L) target = 0L;
        if (durationMs > 0L && durationMs - target <= 5000L) target = 0L;
        if (target == meta.progressMs && (target == 0L || meta.durationMs == durationMs)) return;
        meta.progressMs = target;
        meta.durationMs = target > 0L ? durationMs : 0L;
        saveMeta(videoFolder, meta);
    }
}
