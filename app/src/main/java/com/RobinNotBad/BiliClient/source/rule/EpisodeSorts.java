package com.RobinNotBad.BiliClient.source.rule;

/**
 * 集号解析（统一入口）。
 *
 * <p><b>为什么必须数值化</b>：字符串排序会把「第10集」排到「第2集」前面，
 * 分页/选集一旦错位，用户点第 2 集会播放第 10 集。这里统一抽出数值。
 *
 * <p>覆盖的真实形态：`第01话`、`第1.5话`、`12`、`12.5`、`SP1`、`OVA`、
 * `剧场版`、`1080P`、`正片`、`总集篇`。解析不出时回退为「位置序号」，
 * 绝不返回 0 让所有条目并列。
 */
public final class EpisodeSorts {

    private EpisodeSorts() {
    }

    /** 解析成功返回 >=0 的数值；解析不出返回 Double.NaN */
    public static double parseOrNaN(String raw) {
        if (raw == null) return Double.NaN;
        String text = raw.trim();
        if (text.isEmpty()) return Double.NaN;

        // 先找第一段连续数字（可含一个小数点），支持 "第01话" / "第1.5话" / "12集"
        int index = 0;
        while (index < text.length() && !isDigitOrSignStart(text, index)) index++;
        if (index >= text.length()) return Double.NaN;
        int start = index;
        while (index < text.length() && isDigitOrSignStart(text, index)) index++;
        String number = text.substring(start, index);
        try {
            double value = Double.parseDouble(number);
            if (Double.isNaN(value) || Double.isInfinite(value)) return Double.NaN;
            return value;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static boolean isDigitOrSignStart(String text, int index) {
        char ch = text.charAt(index);
        if (Character.isDigit(ch)) return true;
        if (ch == '.') return index + 1 < text.length() && Character.isDigit(text.charAt(index + 1));
        return false;
    }

    /**
     * 站点把「整部剧场版/正片」当作一集时，集号统一记为 1。
     *
     * <p>对齐 Animeko 的 {@code isPossiblyMovie}：名字是「正片」「高清版」
     * 或带分辨率字样，说明它不是按集编号的正片。
     */
    public static boolean isPossiblyMovie(String name) {
        if (name == null) return false;
        String text = name.trim();
        if (text.equals("正片") || text.equals("高清版")) return true;
        return text.contains("2160P") || text.contains("1440P") || text.contains("2K")
                || text.contains("4K") || text.contains("1080P") || text.contains("720P");
    }

    /** 剧集名是否是这些「非正片」类型（给 UI 分组/标注用） */
    public static String specialTag(String name) {
        if (name == null) return "";
        String text = name.toUpperCase(java.util.Locale.ROOT);
        if (text.contains("OVA")) return "OVA";
        if (text.contains("OAD")) return "OAD";
        if (text.contains("总集篇")) return "总集篇";
        if (text.contains("剧场版")) return "剧场版";
        if (text.contains("预告") || text.contains("PV")) return "预告";
        if (text.contains("特别篇") || text.contains("SP")) return "特别篇";
        return "";
    }

    /** 数值集号的展示形态：整数不带小数点，小数保留一位（1.5） */
    public static String format(double sort) {
        if (Double.isNaN(sort)) return "";
        if (Math.abs(sort - Math.rint(sort)) < 0.0001) return String.valueOf((long) Math.rint(sort));
        return String.valueOf(Math.round(sort * 10) / 10.0);
    }
}
