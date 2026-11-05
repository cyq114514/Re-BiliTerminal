package com.RobinNotBad.BiliClient.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 B 站视频标题里提取"可能是歌名"的关键词，用于网易云歌词兜底搜索。
 *
 * <p>B 站音乐类稿件的标题格式非常杂：
 * <pre>
 * 【4K】如果聊天窗口就是她的整个世界 —— world.execute(me)
 * 《赛博朋克：边缘行者2》插曲「A Rusty Dream」百万级录音棚试听
 * 顶级品质试听《時を刻む唄》CLANNAD经典主题曲op
 * </pre>
 * 这里按"「」优先 → 《》→ 分隔符后半段 → 整句去噪"的顺序给候选，
 * 由 {@link LyricProvider} 逐个去搜、按时长与名称相似度挑最像的。
 *
 * <p>纯启发式，不保证准确；搜不到就当作没有歌词，不影响播放。
 */
public final class TitleCleaner {

    /** 「」里的内容最像歌名 */
    private static final Pattern CORNER_TITLE = Pattern.compile("「([^」]{2,40})」");
    /** 《》里可能是歌名，也可能是作品名（作为次级候选） */
    private static final Pattern BOOK_TITLE = Pattern.compile("《([^》]{2,40})》");
    /** 【】里常是英文原名或「作品名-歌手」，也值得单独搜一次 */
    private static final Pattern SQUARE_TITLE = Pattern.compile("【([^】]{2,40})】");
    /** feat. / ft. 后面的合作歌手对搜索是噪音 */
    private static final Pattern FEAT = Pattern.compile("(?i)\\s+(?:feat|ft)\\.?\\s+.*$");
    /** 常见分隔符：中文破折号、英文连字符、竖线 */
    private static final Pattern SEPARATOR = Pattern.compile("\\s*(?:——|—|\\s-\\s|\\||｜)\\s*");
    /**
     * 需要整段剔除的修饰词。只删全角方括号/全角圆括号，**保留半角圆括号**——
     * 像 world.execute(me) 这种歌名本身就带括号，删了就会搜错。
     */
    private static final Pattern NOISE = Pattern.compile(
            "【[^】]*】|\\[[^\\]]*\\]|（[^）]*）",
            Pattern.CASE_INSENSITIVE);
    private static final String[] NOISE_WORDS = {
            "4k", "8k", "1080p", "720p", "hi-res", "hires", "hi res", "无损", "母带", "百万级",
            "录音棚", "试听", "官方mv", "official mv", "official", "完整版", "完整", "中文字幕",
            "中日字幕", "中英字幕", "双语字幕", "字幕", "动态歌词", "附歌词", "歌词版", "歌词",
            "音频可视化", "可视化", "高音质", "神曲", "翻唱", "搬运", "自制", "中字", "mv", "pv",
            "op", "ed", "现场版", "live版", "纯音乐", "音乐", "原版", "中日", "日推歌单"
    };

    private TitleCleaner() {
    }

    /**
     * 返回 2-4 个搜索关键词候选，按优先级从高到低；永远非 null。
     */
    public static List<String> candidates(String title) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (title == null) return new ArrayList<>(result);
        String raw = title.trim();
        if (raw.isEmpty()) return new ArrayList<>(result);

        //1) 「」里最像歌名
        Matcher corner = CORNER_TITLE.matcher(raw);
        while (corner.find()) {
            addIfUsable(result, corner.group(1));
        }

        //2) 标题里独立成段的日文原名：中文译名常在《》里，原名才是网易云能搜到的
        //（如「原版《神的随波逐流》…… 神のまにまに」）
        String noBrackets = NOISE.matcher(raw).replaceAll(" ");
        for (String token : noBrackets.split("[\\s\\u3000,，。！？、；：|｜]+")) {
            //只收"干净的短段"：带书名号/中文标点的整段是标题残渣，搜出去只会得到无关结果
            if (token.length() >= 3 && token.length() <= 20
                    && !token.matches(".*[《》「」【】，。！？；：].*")
                    && token.matches(".*[\\u3040-\\u30ff].*")) {
                addIfUsable(result, token);
            }
        }

        //3) 《》里的内容（可能是歌名，也可能是作品名，排第三）
        Matcher book = BOOK_TITLE.matcher(raw);
        while (book.find()) {
            addIfUsable(result, book.group(1));
        }

        //4) 【】里的内容：常是英文原名，或「作品名-歌手」这种组合
        Matcher square = SQUARE_TITLE.matcher(raw);
        while (square.find()) {
            String inner = square.group(1).trim();
            addIfUsable(result, inner);
            int dash = inner.indexOf('-');
            if (dash > 1) addIfUsable(result, inner.substring(0, dash));
        }

        //5) 按分隔符拆段，取后半段——"作品名 —— 歌名" 很常见。
        //用原始标题拆，括号内容此时不清洗（world.execute(me) 这种歌名不能被砍）
        String[] parts = SEPARATOR.split(raw);
        if (parts.length >= 2) {
            addIfUsable(result, parts[parts.length - 1]);
        }

        //6) 整句去噪（英文名常常直接就是歌名）
        addIfUsable(result, stripNoiseWords(noBrackets));

        //7) 兜底：原始标题
        if (result.isEmpty()) addIfUsable(result, raw);
        return new ArrayList<>(result);
    }

    private static void addIfUsable(LinkedHashSet<String> target, String value) {
        if (value == null) return;
        //候选自身再清洗一次：全角括号里的修饰、feat. 后面的合作歌手，对搜索都是噪音
        String text = NOISE.matcher(value).replaceAll(" ");
        text = FEAT.matcher(text).replaceAll(" ");
        text = text.trim()
                .replaceAll("\\s{2,}", " ")
                .replaceAll("^[\\s,，.。:：/\\\\-]+", "")
                .replaceAll("[\\s,，.。:：/\\\\-]+$", "");
        if (text.length() < 2 || text.length() > 40) return;
        //纯符号/纯数字不搜
        if (!text.matches(".*[\\p{L}].*")) return;
        if (isNoiseOnly(text)) return;
        target.add(text);
    }

    /** 整个候选就是一个修饰词（4K / 试听 / 完整版）时不要拿去搜 */
    private static boolean isNoiseOnly(String text) {
        String lower = text.toLowerCase(java.util.Locale.ROOT).trim();
        for (String word : NOISE_WORDS) {
            if (lower.equals(word)) return true;
        }
        return false;
    }

    private static String stripNoiseWords(String text) {
        String result = text;
        for (String word : NOISE_WORDS) {
            result = result.replaceAll("(?i)" + Pattern.quote(word), " ");
        }
        return result.replaceAll("\\s{2,}", " ").trim();
    }
}
