package com.RobinNotBad.BiliClient.source.danmaku;

import android.content.Context;

import com.RobinNotBad.BiliClient.util.Logu;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.List;

/**
 * 第三方播放的弹幕：按「番剧名 + 集号」去弹弹play 找，转成 B 站 XML 落盘。
 *
 * <p>调用方（播放页）拿到文件后直接走既有的 {@code streamDanmaku(path)}。
 * 缓存 7 天：同一集重看/重试不再重复请求。
 *
 * <p>凭据缺失时静默返回 null（播放照常，只是没弹幕）——不要弹窗打扰用户。
 */
public final class ThirdPartyDanmaku {

    private static final long TTL_MS = 7L * 24 * 60 * 60 * 1000;
    private static boolean warnedMissingCredential = false;

    private ThirdPartyDanmaku() {
    }

    public static boolean available() {
        return DandanplayClient.available();
    }

    /**
     * @return 已写好的 B 站 XML 文件；null 表示没有凭据/没匹配到/没拉到
     */
    public static File fetch(Context context, String animeTitle, int episodeNumber) {
        if (context == null || animeTitle == null || animeTitle.trim().isEmpty()) return null;
        if (!DandanplayClient.available()) {
            if (!warnedMissingCredential) {
                warnedMissingCredential = true;
                Logu.w("third-danmaku", "未配置弹弹play AppSecret，第三方弹幕关闭");
            }
            return null;
        }
        final File dir = new File(context.getCacheDir(), "third_danmaku");
        if (!dir.exists() && !dir.mkdirs()) return null;
        final File target = new File(dir, cacheName(animeTitle, episodeNumber));
        if (target.exists() && target.length() > 128
                && System.currentTimeMillis() - target.lastModified() < TTL_MS) {
            Logu.w("third-danmaku", "命中缓存：" + target.getName() + " " + target.length() + " 字节");
            return target;
        }

        long startAt = System.currentTimeMillis();
        try {
            List<DandanplayClient.Match> matches = DandanplayClient.match(animeTitle, episodeNumber);
            DandanplayClient.Match best = pick(matches, episodeNumber);
            if (best == null) {
                Logu.w("third-danmaku", "没有匹配到弹幕：" + animeTitle + " 第" + episodeNumber
                        + "话，候选 " + (matches == null ? 0 : matches.size()) + " 条");
                return null;
            }
            List<DandanplayClient.Comment> comments = DandanplayClient.comments(best.episodeId);
            if (comments.isEmpty()) {
                Logu.w("third-danmaku", "该集弹幕为空：" + best.display() + " episodeId=" + best.episodeId);
                return null;
            }
            String xml = DanmakuXml.build(comments);
            FileOutputStream stream = new FileOutputStream(target);
            OutputStreamWriter writer = new OutputStreamWriter(stream, "UTF-8");
            try {
                writer.write(xml);
            } finally {
                writer.close();
            }
            Logu.w("third-danmaku", "弹幕就绪：" + best.display() + " 共 " + comments.size() + " 条"
                    + " 耗时=" + (System.currentTimeMillis() - startAt) + "ms"
                    + " XML=" + target.length() + " 字节");
            return target;
        } catch (Throwable t) {
            //W 级：正式包也能落盘，这是「为什么这集没弹幕」唯一的现场证据
            Logu.w("third-danmaku", "拉取失败（耗时 " + (System.currentTimeMillis() - startAt)
                    + "ms）：" + t.getClass().getSimpleName()
                    + " " + (t.getMessage() == null ? "" : t.getMessage()));
            return null;
        }
    }

    private static DandanplayClient.Match pick(List<DandanplayClient.Match> matches, int episodeNumber) {
        if (matches == null || matches.isEmpty()) return null;
        if (episodeNumber > 0) {
            for (DandanplayClient.Match match : matches) {
                if (match.episodeNumber == episodeNumber) return match;
            }
        }
        return matches.get(0);
    }

    private static String cacheName(String title, int episodeNumber) {
        String raw = title.trim() + "|" + episodeNumber;
        return Integer.toHexString(raw.hashCode()) + "_" + Math.abs(raw.length()) + ".xml";
    }
}
