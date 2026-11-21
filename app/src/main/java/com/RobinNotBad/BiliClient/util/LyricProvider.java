package com.RobinNotBad.BiliClient.util;

import android.content.Context;
import android.util.Log;

import com.RobinNotBad.BiliClient.api.NeteaseApi;
import com.RobinNotBad.BiliClient.api.PlayerApi;
import com.RobinNotBad.BiliClient.model.Subtitle;
import com.RobinNotBad.BiliClient.model.SubtitleLink;
import com.RobinNotBad.BiliClient.model.VideoCard;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 听歌模式的歌词来源：**B 站 CC 字幕优先，网易云歌词兜底**。
 *
 * <p>和 NeriPlayer 的 {@code BiliSubtitleLyricsLoader} 是同一套思路：
 * B 站字幕本身就是按时间轴排好的文本，直接当歌词用；没有字幕（或未登录取不到字幕列表）时，
 * 用标题里的关键词去网易云搜歌，按时长挑最匹配的一首取 LRC。
 *
 * <p>结果按 cid 缓存 24 小时：歌词不会变，反复切歌不该重复请求。
 * 全部失败返回空 {@link Lyrics}，调用方隐藏歌词区即可。
 *
 * <p><b>注意</b>：本方法会发起网络请求，必须在后台线程调用。
 */
public final class LyricProvider {

    /** 一行歌词 */
    public static class Line {
        public long timeMs;
        public String text = "";
    }

    public static class Lyrics {
        public List<Line> lines = new ArrayList<>();
        /** 来源文案，如「B站字幕」「网易云：歌名 - 歌手」，用于界面角落标注 */
        public String source = "";

        public boolean isEmpty() {
            return lines == null || lines.isEmpty();
        }
    }

    private static final long CACHE_TTL_MS = 24L * 3600 * 1000;
    /** [mm:ss.xx] / [mm:ss:xx] / [mm:ss] */
    private static final Pattern LRC_TAG = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    /** 综合评分低于这个值认为搜错了：宁可没有歌词，也不要配一首不相干的歌 */
    private static final double MIN_MATCH_SCORE = 0.45;
    /** B 站字幕最多等这么久；超时就不等了，直接看网易云那边有没有先回来 */
    private static final long BILI_TIMEOUT_MS = 1200;
    /** 网易云搜索+取词的等待上限（最多 4 个关键词，串行搜索本来就慢） */
    private static final long NETEASE_TIMEOUT_MS = 2500;

    /**
     * 歌词加载专用线程池。
     *
     * <p>B 站字幕和网易云两条路并行跑：有字幕时约 300-500ms 就能返回，
     * 不必等网易云那串搜索；没字幕时才继续等网易云。串行做的话最坏要 3-4 秒。
     */
    private static final java.util.concurrent.ExecutorService POOL =
            java.util.concurrent.Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "audio-lyric");
                thread.setDaemon(true);
                return thread;
            });

    private LyricProvider() {
    }

    /**
     * 取歌词（带缓存）。失败返回空对象，不抛异常。
     *
     * @param durationMs 当前音频时长，用于在网易云搜索结果里挑最匹配的一首；0 表示未知
     */
    public static Lyrics load(Context context, VideoCard item, int durationMs) {
        if (item == null) return new Lyrics();
        //离线优先：下载时落盘的歌词不受 24h TTL 限制，也不依赖网络（2026-11-21 反馈：
        //听歌模式缓存音频后，离线时歌词没了）
        Lyrics local = readLocal(item);
        if (local != null && !local.isEmpty()) return local;
        if (item.cid <= 0 && !hasLocalPath(item)) return new Lyrics();

        if (item.cid > 0) {
            Lyrics cached = readCache(context, item.cid);
            if (cached != null && !cached.isEmpty()) {
                Log.i("audio-lyric", "cid=" + item.cid + " 命中缓存 source=" + cached.source);
                return cached;
            }
        }

        //两条来源并行：有字幕时 300-500ms 就能返回，不用等网易云那串搜索。
        //B 站字幕需要 cid，本地旧缓存条目没有 cid 就只走网易云；时长未知也能搜，
        //bestMatch 对未知时长给 0.5 的中性分，比"完全不搜"更有机会拿到歌词。
        java.util.concurrent.Future<Lyrics> biliFuture = item.cid > 0
                ? POOL.submit(() -> loadFromBiliSubtitle(item)) : null;
        java.util.concurrent.Future<Lyrics> neteaseFuture =
                POOL.submit(() -> loadFromNetease(item, durationMs));

        Lyrics lyrics = await(biliFuture, BILI_TIMEOUT_MS);
        if (lyrics != null && !lyrics.isEmpty()) {
            cancel(neteaseFuture);
        } else {
            Log.i("audio-lyric", "cid=" + item.cid + " B站无字幕（或超时），等网易云…");
            Lyrics netease = await(neteaseFuture, NETEASE_TIMEOUT_MS);
            if (netease != null && !netease.isEmpty()) {
                lyrics = netease;
            } else {
                //网易云也没结果，再给 B 站一点时间——可能只是接口慢
                lyrics = await(biliFuture, 500);
            }
        }
        if (lyrics == null) lyrics = new Lyrics();
        if (!lyrics.isEmpty()) {
            writeCache(context, item.cid, lyrics);
            //本地条目顺手回写一份：老缓存/在线听过一次之后，下次离线也有歌词
            writeLocal(item, lyrics);
        }
        Log.i("audio-lyric", "cid=" + item.cid + " source=" + lyrics.source
                + " lines=" + lyrics.lines.size());
        return lyrics;
    }

    /**
     * 预取下一首的歌词，和音频预取并行跑；切歌时直接命中缓存，不用等。
     * 失败无提示、不重试。
     */
    public static void prefetch(Context context, VideoCard item, int durationMs) {
        if (item == null) return;
        Lyrics local = readLocal(item);
        if (local != null && !local.isEmpty()) return;
        if (item.cid <= 0 && !hasLocalPath(item)) return;
        if (item.cid > 0 && readCache(context, item.cid) != null) return;
        POOL.execute(() -> {
            try {
                Lyrics lyrics = item.cid > 0 ? loadFromBiliSubtitle(item) : new Lyrics();
                if (lyrics.isEmpty()) lyrics = loadFromNetease(item, durationMs);
                if (!lyrics.isEmpty()) {
                    writeCache(context, item.cid, lyrics);
                    writeLocal(item, lyrics);
                    Log.i("audio-lyric", "预取歌词 cid=" + item.cid + " source=" + lyrics.source);
                }
            } catch (Throwable ignored) {
            }
        });
    }

    private static Lyrics await(java.util.concurrent.Future<Lyrics> future, long timeoutMs) {
        if (future == null) return null;
        try {
            return future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void cancel(java.util.concurrent.Future<?> future) {
        if (future == null) return;
        try {
            future.cancel(true);
        } catch (Throwable ignored) {
        }
    }

    // ==================== B 站 CC 字幕 ====================

    private static Lyrics loadFromBiliSubtitle(VideoCard item) {
        Lyrics lyrics = new Lyrics();
        try {
            SubtitleLink[] links = PlayerApi.getSubtitleLinks(item.aid, item.cid);
            SubtitleLink track = pickTrack(links);
            if (track == null) return lyrics;
            Subtitle[] subtitles = PlayerApi.getSubtitle(track.url);
            if (subtitles == null || subtitles.length == 0) return lyrics;
            for (Subtitle subtitle : subtitles) {
                if (subtitle == null || subtitle.content == null) continue;
                String text = subtitle.content.trim();
                if (text.isEmpty()) continue;
                Line line = new Line();
                line.timeMs = (long) (subtitle.from * 1000);
                line.text = text;
                lyrics.lines.add(line);
            }
            if (!lyrics.isEmpty()) {
                lyrics.source = track.isAI ? "B站AI字幕" : "B站字幕";
            }
        } catch (Throwable ignored) {
            //未登录/接口异常/字幕文件失效：交给网易云兜底
        }
        return lyrics;
    }

    /** 选轨顺序：中文人工 > 中文AI > 其他人工 > 其他AI（与 NeriPlayer 一致） */
    private static SubtitleLink pickTrack(SubtitleLink[] links) {
        if (links == null) return null;
        SubtitleLink chineseHuman = null, chineseAi = null, anyHuman = null, anyAi = null;
        for (SubtitleLink link : links) {
            if (link == null || link.url == null || link.url.isEmpty() || "null".equals(link.url)) continue;
            boolean chinese = link.lang != null && link.lang.contains("中");
            if (chinese) {
                if (!link.isAI) {
                    if (chineseHuman == null) chineseHuman = link;
                } else if (chineseAi == null) {
                    chineseAi = link;
                }
            } else if (!link.isAI) {
                if (anyHuman == null) anyHuman = link;
            } else if (anyAi == null) {
                anyAi = link;
            }
        }
        if (chineseHuman != null) return chineseHuman;
        if (chineseAi != null) return chineseAi;
        if (anyHuman != null) return anyHuman;
        return anyAi;
    }

    // ==================== 网易云兜底 ====================

    private static Lyrics loadFromNetease(VideoCard item, int durationMs) {
        Lyrics lyrics = new Lyrics();
        List<String> keywords = TitleCleaner.candidates(item.title);
        for (String keyword : keywords) {
            List<NeteaseApi.Song> songs = NeteaseApi.search(keyword, 5);
            NeteaseApi.Song best = bestMatch(songs, durationMs, keyword);
            if (best == null) {
                continue;
            }
            Log.i("audio-lyric", "网易云搜索「" + keyword + "」→ " + best.name
                    + " / " + best.artist + " (" + best.durationMs + "ms)");
            NeteaseApi.Lyric raw = NeteaseApi.getLyric(best.id);
            if (raw == null) continue;
            List<Line> lines = parseLrc(raw.text);
            if (lines.isEmpty()) continue;
            lyrics.lines = lines;
            lyrics.source = "网易云：" + best.name
                    + (best.artist.isEmpty() ? "" : " - " + best.artist);
            return lyrics;
        }
        return lyrics;
    }

    /**
     * 在搜索结果里挑最像的一首：歌名相似度 0.6 + 时长接近度 0.4，低于阈值直接放弃。
     *
     * <p>只看时长会把翻唱/remix 当成原曲（它们时长也接近）；只看歌名又会搜到同名不同曲。
     */
    private static NeteaseApi.Song bestMatch(List<NeteaseApi.Song> songs, long videoDurationMs,
                                             String keyword) {
        if (songs == null || songs.isEmpty()) return null;
        NeteaseApi.Song best = null;
        double bestScore = 0;
        for (int index = 0; index < songs.size(); index++) {
            NeteaseApi.Song song = songs.get(index);
            if (song == null || song.id <= 0) continue;
            double nameScore = nameSimilarity(keyword, song.name);
            //名字都不像的，时长相近也不能要——这是"翻唱/同名曲配错歌词"的主要来源
            if (nameScore < 0.3) continue;
            //搜索结果本身按相关度排序，越靠前越可信，给一点加权
            double rankBonus = Math.max(0, 1.0 - index * 0.12);
            double score = nameScore * 0.55
                    + durationScore(song.durationMs, videoDurationMs) * 0.35
                    + rankBonus * 0.10;
            if (score > bestScore) {
                bestScore = score;
                best = song;
            }
        }
        if (best == null || bestScore < MIN_MATCH_SCORE) {
            Log.i("audio-lyric", "网易云「" + keyword + "」最高分 " + String.format(java.util.Locale.US, "%.2f", bestScore)
                    + " 低于阈值，放弃");
            return null;
        }
        return best;
    }

    /** B 站视频通常比纯歌曲长（片头片尾），所以正向差容忍更大；歌比视频长太多说明不是同一版 */
    private static double durationScore(long songMs, long videoMs) {
        if (songMs <= 0 || videoMs <= 0) return 0.5;
        long delta = videoMs - songMs;
        if (delta >= 0) {
            if (delta > 25000) return 0;
            return 1.0 - delta / 50000.0;
        }
        long abs = -delta;
        if (abs > 12000) return 0;
        return 1.0 - abs / 24000.0;
    }

    /** 归一化后的包含关系 + 最长公共子串，避免标点/大小写/空格造成误判 */
    private static double nameSimilarity(String keyword, String songName) {
        String a = normalizeName(keyword);
        String b = normalizeName(songName);
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 1.0;
        if (b.contains(a)) return 0.92;
        if (a.contains(b)) return 0.85;
        int common = longestCommonSubstring(a, b);
        if (common < 2) return 0;
        return 0.8 * common / Math.max(a.length(), b.length());
    }

    private static String normalizeName(String text) {
        if (text == null) return "";
        return text.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。！？、；：·—…（）《》「」【】]", "");
    }

    private static int longestCommonSubstring(String a, String b) {
        int max = 0;
        int[] dp = new int[b.length() + 1];
        for (int i = 1; i <= a.length(); i++) {
            for (int j = b.length(); j >= 1; j--) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    dp[j] = dp[j - 1] + 1;
                    if (dp[j] > max) max = dp[j];
                } else {
                    dp[j] = 0;
                }
            }
        }
        return max;
    }

    private static List<Line> parseLrc(String lrc) {
        List<Line> lines = new ArrayList<>();
        if (lrc == null || lrc.isEmpty()) return lines;
        for (String raw : lrc.split("\\r?\\n")) {
            if (raw == null || raw.isEmpty()) continue;
            Matcher matcher = LRC_TAG.matcher(raw);
            List<Long> times = new ArrayList<>(2);
            int lastEnd = 0;
            while (matcher.find()) {
                long minute = Long.parseLong(matcher.group(1));
                long second = Long.parseLong(matcher.group(2));
                long millis = 0;
                String fraction = matcher.group(3);
                if (fraction != null) {
                    millis = fraction.length() == 1 ? Long.parseLong(fraction) * 100
                            : fraction.length() == 2 ? Long.parseLong(fraction) * 10
                            : Long.parseLong(fraction.substring(0, 3));
                }
                times.add(minute * 60000 + second * 1000 + millis);
                lastEnd = matcher.end();
            }
            if (times.isEmpty()) continue;
            String text = raw.substring(lastEnd).trim();
            if (text.isEmpty()) continue;
            for (long time : times) {
                Line line = new Line();
                line.timeMs = time;
                line.text = text;
                lines.add(line);
            }
        }
        Collections.sort(lines, (left, right) -> Long.compare(left.timeMs, right.timeMs));
        return lines;
    }

    // ==================== 缓存 ====================

    /** 本地缓存条目（有 media 路径）——歌词可以和音频放在同一个文件夹里离线读取 */
    public static boolean hasLocalPath(VideoCard item) {
        return item != null && item.localPath != null && !item.localPath.isEmpty();
    }

    /** 单曲身份：在线条目用 cid，本地条目用 media 路径（本地条目通常没有 cid） */
    public static String keyOf(VideoCard item) {
        if (item == null) return "";
        return item.cid > 0 ? "c" + item.cid : "p" + (item.localPath == null ? "" : item.localPath);
    }

    /** 与 media 文件同目录的 lyrics.json；非本地条目返回 null */
    private static File localFile(VideoCard item) {
        if (!hasLocalPath(item)) return null;
        File folder = new File(item.localPath).getParentFile();
        return folder == null ? null : new File(folder, "lyrics.json");
    }

    private static File cacheFile(Context context, long cid) {
        File dir = new File(context.getCacheDir(), "lyrics");
        if (!dir.exists() && !dir.mkdirs()) {
            return new File(context.getCacheDir(), "lyrics_" + cid + ".json");
        }
        return new File(dir, cid + ".json");
    }

    /** 读本地缓存的歌词；没有/过期返回 null（界面可先查它，命中就免掉「加载中」状态） */
    public static Lyrics readCache(Context context, long cid) {
        try {
            File file = cacheFile(context, cid);
            if (!file.exists()) return null;
            if (System.currentTimeMillis() - file.lastModified() > CACHE_TTL_MS) return null;
            return parseJson(FileUtil.readString(file));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 先查随缓存落盘的歌词，再查 24h 网络缓存。
     * 听歌页用它做「命中就不闪加载中」的首查，本地条目在离线时也能立刻出歌词。
     */
    public static Lyrics readCache(Context context, VideoCard item) {
        Lyrics local = readLocal(item);
        if (local != null && !local.isEmpty()) return local;
        if (item == null || item.cid <= 0) return null;
        return readCache(context, item.cid);
    }

    /**
     * 读与音频同目录的 lyrics.json（下载时写入，无 TTL）。
     * 这是「离线也能看歌词」的唯一来源：没有它就只能联网取。
     */
    public static Lyrics readLocal(VideoCard item) {
        File file = localFile(item);
        if (file == null || !file.exists()) return null;
        try {
            return parseJson(FileUtil.readString(file));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 把歌词写到缓存条目的文件夹（与 media 同级），供离线读取；已存在则不覆盖 */
    public static void writeLocal(VideoCard item, Lyrics lyrics) {
        File file = localFile(item);
        if (file != null && !file.exists()) writeJson(file, lyrics);
    }

    public static void writeLocal(File folder, Lyrics lyrics) {
        if (folder == null) return;
        File file = new File(folder, "lyrics.json");
        if (file.exists()) return;   // 离线优先：已有的本地歌词不被后来的网络结果覆盖
        writeJson(file, lyrics);
    }

    private static void writeCache(Context context, long cid, Lyrics lyrics) {
        if (cid <= 0) return;   // 本地条目没有 cid，只写 lyrics.json
        writeJson(cacheFile(context, cid), lyrics);
    }

    private static Lyrics parseJson(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray array = root.optJSONArray("lines");
            if (array == null) return null;
            Lyrics lyrics = new Lyrics();
            lyrics.source = root.optString("source", "");
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                Line line = new Line();
                line.timeMs = item.optLong("t", 0);
                line.text = item.optString("x", "");
                if (!line.text.isEmpty()) lyrics.lines.add(line);
            }
            return lyrics;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeJson(File file, Lyrics lyrics) {
        if (file == null || lyrics == null || lyrics.lines.isEmpty()) return;
        OutputStreamWriter writer = null;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            JSONObject root = new JSONObject();
            root.put("source", lyrics.source);
            JSONArray array = new JSONArray();
            for (Line line : lyrics.lines) {
                JSONObject item = new JSONObject();
                item.put("t", line.timeMs);
                item.put("x", line.text);
                array.put(item);
            }
            root.put("lines", array);
            writer = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
            writer.write(root.toString());
            writer.flush();
        } catch (Throwable ignored) {
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
