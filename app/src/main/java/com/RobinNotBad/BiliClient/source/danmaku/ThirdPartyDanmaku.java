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
        if (isUsableCache(target)) {
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
            writeAtomically(target, xml);
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

    /**
     * 缓存是否可用：存在、未过期、且**内容完整**。
     *
     * <p>为什么还要看末尾：写盘中途被系统杀掉会留下半截 XML，解析器只会静默返回 0 条，
     * 而旧实现只看「长度 &gt; 128」，于是这一集会被当成「有缓存」继续错 7 天。
     * 写盘已改成原子替换，这里再兜一道历史脏数据。
     */
    private static boolean isUsableCache(File file) {
        if (file == null || !file.exists() || file.length() <= 128) return false;
        if (System.currentTimeMillis() - file.lastModified() >= TTL_MS) return false;
        return endsWithClosingTag(file);
    }

    private static boolean endsWithClosingTag(File file) {
        java.io.RandomAccessFile raf = null;
        try {
            raf = new java.io.RandomAccessFile(file, "r");
            long length = raf.length();
            int tail = (int) Math.min(24, length);
            raf.seek(length - tail);
            byte[] buffer = new byte[tail];
            raf.readFully(buffer);
            return new String(buffer, "UTF-8").trim().endsWith("</i>");
        } catch (Throwable t) {
            return false;
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Throwable ignored) {
                    //关不上不影响结果
                }
            }
        }
    }

    /**
     * 原子写：先写 .tmp 再改名。
     *
     * <p>直接写目标文件时，进程被杀/断电会留下半截 XML，比「没有缓存」更糟——
     * 它会以「有缓存」的身份活 7 天，这期间这一集一直没有弹幕。
     */
    private static void writeAtomically(File target, String xml) throws java.io.IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        FileOutputStream stream = new FileOutputStream(tmp);
        OutputStreamWriter writer = new OutputStreamWriter(stream, "UTF-8");
        try {
            writer.write(xml);
        } finally {
            writer.close();
        }
        if (tmp.renameTo(target)) return;
        //部分文件系统不允许 rename 覆盖已存在文件：先删再改（删失败就明确报错，不留半成品）
        if (target.exists() && !target.delete()) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new java.io.IOException("无法覆盖弹幕缓存");
        }
        if (!tmp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new java.io.IOException("无法重命名弹幕缓存");
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
