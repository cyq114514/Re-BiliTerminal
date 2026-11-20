package com.RobinNotBad.BiliClient.source.rule;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 规则来源的正则封装：长度限制 + 输入截断 + 命名分组提取。
 *
 * <p>为什么要包一层：规则里的 `matchVideoUrl` 是整页 HTML 上去匹配的，
 * 一个写坏的正则（可选项嵌套）在长文本上会指数回溯，主线程直接卡死。
 * Java 没有正则超时 API，只能靠「限制表达式长度 + 限制输入长度」把风险压到可接受。
 *
 * <p>命名分组的兼容性：{@link Matcher#group(String)} 在 Android 8（API 26）才支持，
 * 更低版本会抛 {@link UnsupportedOperationException}；旧实现只捕获 IAE/ISE，于是
 * API&lt;26 设备上第三方源解析会直接崩在 third-source 线程（1.3.9 / Android 7.1.2
 * 的 9198 崩溃即此）。这里在编译期把「组名 → 捕获组序号」解析出来，运行期统一走
 * {@link Matcher#group(int)}，同时保留 UOE 兜底，保证/低版本都不会再因此闪退。
 */
public final class SafeRegex {

    private final Pattern pattern;

    /** 命名分组名 → 捕获组序号（从 1 开始；表达式没有命名组时为空表） */
    private final Map<String, Integer> namedGroups;

    private SafeRegex(Pattern pattern) {
        this.pattern = pattern;
        this.namedGroups = parseNamedGroups(pattern.pattern());
    }

    /** @return null 表示表达式为空或非法（调用方按「未配置」处理，不抛异常） */
    public static SafeRegex compile(String expression) {
        if (expression == null) return null;
        String trimmed = expression.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > RuleLimits.MAX_REGEX_CHARS) return null;
        try {
            return new SafeRegex(Pattern.compile(trimmed));
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    /** 表达式非法时抛出，用于「规则声明了就必须生效」的字段校验 */
    public static SafeRegex compileOrThrow(String expression, String field) {
        String trimmed = expression == null ? "" : expression.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > RuleLimits.MAX_REGEX_CHARS)
            throw new IllegalArgumentException(field + " 正则过长（>" + RuleLimits.MAX_REGEX_CHARS + "）");
        try {
            return new SafeRegex(Pattern.compile(trimmed));
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(field + " 正则非法：" + e.getDescription());
        }
    }

    public boolean matches(String input) {
        return pattern.matcher(cap(input)).find();
    }

    /**
     * 取命名分组的值；分组不存在或值本身为空时返回 null。
     *
     * <p>与 Animeko 的 {@code findGroupOrFullText} 不同：那个是「没匹配到命名组就返回整段」，
     * 由调用方再决定怎么用。这里只返回真实提取值，语义更窄但不会把整段 URL 误当集号。
     */
    public String group(String input, String groupName) {
        Matcher matcher = pattern.matcher(cap(input));
        if (!matcher.find()) return null;
        Integer index = namedGroups.get(groupName);
        if (index == null) return null;
        // 解析器的组序号理论上一定 <= groupCount；这里再兜一道，
        // 防止表达式用了本解析器未覆盖的语法时越界（越界是 IndexOutOfBoundsException）
        if (index < 1 || index > matcher.groupCount()) return null;
        try {
            String value = matcher.group(index);
            return value == null ? null : value;
        } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException e) {
            return null;
        }
    }

    /** 匹配到就返回命名分组值，否则返回整段输入；完全没匹配返回 null（对齐 Animeko 语义） */
    public String groupOrFullText(String input, String groupName) {
        Matcher matcher = pattern.matcher(cap(input));
        if (!matcher.find()) return null;
        Integer index = namedGroups.get(groupName);
        if (index != null && index >= 1 && index <= matcher.groupCount()) {
            try {
                String value = matcher.group(index);
                if (value != null) return value;
            } catch (IllegalArgumentException | IllegalStateException
                     | UnsupportedOperationException ignored) {
                // 组不存在/匹配态异常：保持「取不到就返回整段」的旧语义
            }
        }
        return input;
    }

    /** 第一个捕获组 1，没有分组返回整个匹配 */
    public String firstGroupOrMatch(String input) {
        Matcher matcher = pattern.matcher(cap(input));
        if (!matcher.find()) return null;
        if (matcher.groupCount() >= 1) {
            String value = matcher.group(1);
            if (value != null && !value.isEmpty()) return value;
        }
        return matcher.group();
    }

    public Matcher matcher(String input) {
        return pattern.matcher(cap(input));
    }

    private static String cap(String input) {
        if (input == null) return "";
        if (input.length() <= RuleLimits.MAX_REGEX_INPUT_CHARS) return input;
        return input.substring(0, RuleLimits.MAX_REGEX_INPUT_CHARS);
    }

    /**
     * 编译期解析「命名分组名 → 捕获组序号」。
     *
     * <p>必须与 Java {@link Pattern} 的计数规则一致：从左到右，每遇到一个捕获组
     * （普通 {@code (} 或命名 {@code (?<name>}）序号 +1，从 1 开始；
     * {@code (?:}、{@code (?=}、{@code (?!}、{@code (?<=}、{@code (?<!}、{@code (?>}、
     * 内联标志 {@code (?i)} / {@code (?i:…)} 不计数。字符类 {@code [...]}（含嵌套/转义）、
     * 转义字符、{@code \Q…\E} 引用段内的括号也不参与计数。
     *
     * @return 组名→序号；没有命名组或解析异常时返回空表（调用方按「组不存在」处理）
     */
    private static Map<String, Integer> parseNamedGroups(String expression) {
        if (expression == null || expression.isEmpty()) return Collections.emptyMap();
        Map<String, Integer> names = new HashMap<>();
        int index = 0;
        int i = 0;
        int length = expression.length();
        boolean quoted = false;
        while (i < length) {
            char c = expression.charAt(i);
            if (quoted) {
                // \Q…\E：引用段内全部字面量，遇到 \E 结束；其余字符（含括号）一律跳过
                if (expression.startsWith("\\E", i)) {
                    quoted = false;
                    i += 2;
                } else {
                    i++;
                }
                continue;
            }
            if (c == '\\') {
                // 转义：下一个字符是字面量；\Q 开启引用段
                if (i + 1 < length && expression.charAt(i + 1) == 'Q') {
                    quoted = true;
                    i += 2;
                    continue;
                }
                i += 2;
                continue;
            }
            if (c == '[') {
                // 字符类整体跳过。Java 正则的字符类不支持嵌套：类内的 '[' 是普通字符，
                // 第一个未转义 ']' 就闭合；紧跟 '[' 或 '[^' 后的 ']' 也是字面量。
                i++;
                if (i < length && expression.charAt(i) == '^') i++;
                if (i < length && expression.charAt(i) == ']') i++;
                while (i < length) {
                    char cc = expression.charAt(i);
                    if (cc == '\\') {
                        i += 2;
                        continue;
                    }
                    if (cc == ']') {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == '(') {
                if (expression.startsWith("(?<", i)) {
                    // (?<= / (?<! 是后向断言，不计数；(?<name> 才是命名捕获组
                    if (i + 3 < length) {
                        char after = expression.charAt(i + 3);
                        if (after == '=' || after == '!') {
                            i += 4;
                            continue;
                        }
                        int close = expression.indexOf('>', i + 3);
                        if (close > i + 3) {
                            index++;
                            names.put(expression.substring(i + 3, close), index);
                            i = close + 1;
                            continue;
                        }
                    }
                    // 理论上到不了这里（Pattern.compile 已校验过语法）；按非捕获组跳过
                    i += 2;
                    continue;
                }
                if (expression.startsWith("(?:", i)
                        || expression.startsWith("(?=", i)
                        || expression.startsWith("(?!", i)
                        || expression.startsWith("(?>", i)) {
                    i += 3;
                    continue;
                }
                if (expression.startsWith("(?", i)) {
                    // 内联标志：(?i)、(?im-s)、(?i:…) —— 找到第一个 : 或 )
                    int colon = expression.indexOf(':', i);
                    int close = expression.indexOf(')', i);
                    if (colon > i && (close < 0 || colon < close)) {
                        i = colon + 1;
                    } else {
                        i = close >= 0 ? close + 1 : i + 2;
                    }
                    continue;
                }
                index++;
                i++;
                continue;
            }
            i++;
        }
        return names.isEmpty() ? Collections.<String, Integer>emptyMap() : names;
    }
}
