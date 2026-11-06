package com.RobinNotBad.BiliClient.source.danmaku;

import java.util.List;
import java.util.Locale;

/**
 * 把弹幕转成 B 站 XML，直接复用现有播放器的弹幕管线。
 *
 * <p>为什么不新写一个弹幕渲染通道：{@code PlayerActivity.streamDanmaku(file)} 已经能吃本地
 * B 站格式 XML（本地缓存视频走的就是这条），转格式比改渲染链路风险小得多。
 *
 * <p>格式：{@code <d p="时间,模式,字号,颜色,时间戳,弹幕池,用户,行号">正文</d>}，
 * 其中只有前四项被 DanmakuFlameMaster 的 BiliDanmakuParser 使用。
 */
public final class DanmakuXml {

    private DanmakuXml() {
    }

    public static String build(List<DandanplayClient.Comment> comments) {
        StringBuilder builder = new StringBuilder(comments == null ? 64 : comments.size() * 64 + 64);
        builder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<i>\n");
        if (comments != null) {
            for (DandanplayClient.Comment comment : comments) {
                if (comment == null || comment.text == null || comment.text.isEmpty()) continue;
                builder.append("<d p=\"")
                        .append(String.format(Locale.US, "%.2f", Math.max(0, comment.timeSeconds)))
                        .append(',').append(mapMode(comment.mode))
                        .append(",25,").append(comment.color)
                        .append(",0,0,0,0\">")
                        .append(escape(comment.text))
                        .append("</d>\n");
            }
        }
        builder.append("</i>");
        return builder.toString();
    }

    /** 弹弹play 的 m：1 滚动 / 4 底部 / 5 顶部，其余一律按滚动处理 */
    private static int mapMode(int mode) {
        if (mode == 4 || mode == 5) return mode;
        return 1;
    }

    private static String escape(String text) {
        StringBuilder builder = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&': builder.append("&amp;"); break;
                case '<': builder.append("&lt;"); break;
                case '>': builder.append("&gt;"); break;
                case '"': builder.append("&quot;"); break;
                case '\'': builder.append("&apos;"); break;
                default:
                    //控制字符会让 XML 解析失败，直接丢弃
                    if (c >= 0x20 || c == '\t') builder.append(c);
            }
        }
        return builder.toString();
    }
}
