package com.RobinNotBad.BiliClient.source.rule;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 规则来源的正则封装：长度限制 + 输入截断 + 命名分组提取。
 *
 * <p>为什么要包一层：规则里的 `matchVideoUrl` 是整页 HTML 上去匹配的，
 * 一个写坏的正则（可选项嵌套）在长文本上会指数回溯，主线程直接卡死。
 * Java 没有正则超时 API，只能靠「限制表达式长度 + 限制输入长度」把风险压到可接受。
 */
public final class SafeRegex {

    private final Pattern pattern;

    private SafeRegex(Pattern pattern) {
        this.pattern = pattern;
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
        try {
            String value = matcher.group(groupName);
            return value == null ? null : value;
        } catch (IllegalArgumentException e) {
            return null;
        } catch (IllegalStateException e) {
            return null;
        }
    }

    /** 匹配到就返回命名分组值，否则返回整段输入；完全没匹配返回 null（对齐 Animeko 语义） */
    public String groupOrFullText(String input, String groupName) {
        Matcher matcher = pattern.matcher(cap(input));
        if (!matcher.find()) return null;
        try {
            String value = matcher.group(groupName);
            if (value != null) return value;
        } catch (IllegalArgumentException ignored) {
        } catch (IllegalStateException ignored) {
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
}
