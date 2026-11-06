package com.RobinNotBad.BiliClient.source.play;

import com.RobinNotBad.BiliClient.source.net.UrlGuard;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * m3u8 改写：把播放列表里所有资源引用（key / init segment / 分片 / 子播放列表）
 * 换成调用方给出的本地地址。
 *
 * <p>纯 Java、不依赖 Android，可直接跑 JVM 断言（{@link LocalHlsProxy} 负责网络与线程）。
 * 相对地址以「该 m3u8 所在目录」为基准，这与 HLS 规范一致。
 */
public final class HlsPlaylist {

    private static final Pattern URI_ATTR = Pattern.compile("(?i)URI=\"([^\"]+)\"");

    private HlsPlaylist() {
    }

    /** 把一个绝对远程地址登记成本地路径 */
    public interface RouteSink {
        String route(String absoluteUrl);
    }

    public static String rewrite(String body, String baseUrl, RouteSink sink) {
        if (body == null || sink == null) return body;
        StringBuilder out = new StringBuilder(body.length() + 256);
        for (String rawLine : body.split("\n", -1)) {
            String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            String trimmed = line.trim();

            if (trimmed.startsWith("#")) {
                //#EXT-X-KEY / #EXT-X-MAP / #EXT-X-SESSION-KEY 的 URI="..."
                Matcher matcher = URI_ATTR.matcher(line);
                StringBuffer replaced = new StringBuffer();
                boolean changed = false;
                while (matcher.find()) {
                    String absolute = UrlGuard.resolveFromDirectory(baseUrl, matcher.group(1));
                    if (UrlGuard.check(absolute) != null) {
                        //非法地址原样保留：appendReplacement 不调用会让 appendTail 从上次位置
                        //直接续上，URI 属性会凭空消失
                        matcher.appendReplacement(replaced, Matcher.quoteReplacement(matcher.group()));
                        continue;
                    }
                    matcher.appendReplacement(replaced,
                            Matcher.quoteReplacement("URI=\"" + sink.route(absolute) + "\""));
                    changed = true;
                }
                if (changed) {
                    matcher.appendTail(replaced);
                    out.append(replaced.toString());
                } else {
                    out.append(line);
                }
            } else if (!trimmed.isEmpty()) {
                String absolute = UrlGuard.resolveFromDirectory(baseUrl, trimmed);
                if (UrlGuard.check(absolute) != null) {
                    out.append(line);
                } else {
                    out.append(sink.route(absolute));
                }
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** 转发回来的内容是不是又一级 m3u8（支持 #EXT-X-STREAM-INF 多级播放列表） */
    public static boolean isPlaylist(String contentType, byte[] bytes) {
        if (contentType != null) {
            String lower = contentType.toLowerCase(Locale.ROOT);
            if (lower.contains("mpegurl") || lower.contains("m3u8")) return true;
        }
        if (bytes == null || bytes.length == 0) return false;
        int length = Math.min(bytes.length, 16);
        return new String(bytes, 0, length, StandardCharsets.ISO_8859_1).contains("#EXTM3U");
    }
}
