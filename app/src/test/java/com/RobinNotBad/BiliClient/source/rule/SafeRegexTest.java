package com.RobinNotBad.BiliClient.source.rule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * {@link SafeRegex} 的纯 JVM 用例：编译期长度/合法性、命名分组解析、输入截断。
 *
 * <p>不测纯性能：所有断言都是可验证的返回值，长输入只用于验证「超过上限被截断」这一确定性行为。
 */
public class SafeRegexTest {

    // ---------------------------------------------------------------- 编译

    @Test
    public void compileReturnsNullForNullOrBlank() {
        assertNull(SafeRegex.compile(null));
        assertNull(SafeRegex.compile(""));
        assertNull(SafeRegex.compile("   "));
        assertNull(SafeRegex.compile("\t\n"));
    }

    @Test
    public void compileReturnsNullForInvalidExpression() {
        assertNull(SafeRegex.compile("("));
        assertNull(SafeRegex.compile("[unclosed"));
        assertNull(SafeRegex.compile("a{2,1}"));
    }

    @Test
    public void compileReturnsNullWhenExpressionExceedsCharLimit() {
        // 恰好等于上限应能编译，超过 1 个字符即拒绝
        String atLimit = charClassOfLength(RuleLimits.MAX_REGEX_CHARS);
        assertEquals(RuleLimits.MAX_REGEX_CHARS, atLimit.length());
        assertNotNull(SafeRegex.compile(atLimit));

        String overLimit = charClassOfLength(RuleLimits.MAX_REGEX_CHARS + 1);
        assertNull(SafeRegex.compile(overLimit));
    }

    @Test
    public void compileOrThrowThrowsOnInvalidExpression() {
        try {
            SafeRegex.compileOrThrow("(", "matchVideoUrl");
            fail("非法正则应抛 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("matchVideoUrl"));
            assertTrue(e.getMessage().contains("正则非法"));
        }
    }

    @Test
    public void compileOrThrowThrowsOnOverlongExpression() {
        try {
            SafeRegex.compileOrThrow(charClassOfLength(RuleLimits.MAX_REGEX_CHARS + 1), "matchVideoUrl");
            fail("超长正则应抛 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("matchVideoUrl"));
            assertTrue(e.getMessage().contains("正则过长"));
            assertTrue(e.getMessage().contains(String.valueOf(RuleLimits.MAX_REGEX_CHARS)));
        }
    }

    @Test
    public void compileOrThrowBlankReturnsNullInsteadOfThrowing() {
        assertNull(SafeRegex.compileOrThrow(null, "f"));
        assertNull(SafeRegex.compileOrThrow("", "f"));
        assertNull(SafeRegex.compileOrThrow("   ", "f"));
    }

    // ---------------------------------------------------------------- matches

    @Test
    public void matchesUsesFindSemantics() {
        SafeRegex regex = SafeRegex.compile("video/(\\d+)");
        assertTrue(regex.matches("https://example.com/video/42?x=1"));
        assertTrue(regex.matches("prefix video/7 suffix"));
        assertFalse(regex.matches("https://example.com/episode/42"));
        // null 输入按空串处理，不抛异常
        assertFalse(regex.matches(null));
        assertFalse(SafeRegex.compile("abc").matches(""));
    }

    // ---------------------------------------------------------------- 命名分组

    @Test
    public void groupExtractsNamedGroups() {
        SafeRegex regex = SafeRegex.compile("(?<id>\\d+)-(?<name>[a-z]+)");
        assertEquals("123", regex.group("123-abc", "id"));
        assertEquals("abc", regex.group("123-abc", "name"));
    }

    @Test
    public void groupSupportsNamedAndPlainGroupsMixed() {
        SafeRegex regex = SafeRegex.compile("(?<a>x)(y)(?<b>z)");
        // 普通分组 (y) 也占用序号，命名组序号必须按 Pattern 规则计算
        assertEquals("x", regex.group("xyz", "a"));
        assertEquals("z", regex.group("xyz", "b"));

        SafeRegex nonCapturing = SafeRegex.compile("(?:x)(?<id>\\d+)");
        assertEquals("42", nonCapturing.group("x42", "id"));

        SafeRegex lookbehind = SafeRegex.compile("(?<=x)(?<id>\\d+)");
        assertEquals("99", lookbehind.group("x99", "id"));

        SafeRegex withInlineFlag = SafeRegex.compile("(?i)(?<ext>mp4)");
        assertEquals("MP4", withInlineFlag.group("MP4", "ext"));

        SafeRegex withCharClass = SafeRegex.compile("[(](?<id>\\d+)");
        assertEquals("42", withCharClass.group("(42)", "id"));
    }

    @Test
    public void groupReturnsNullForUnknownGroupNoMatchOrPlainOnly() {
        SafeRegex regex = SafeRegex.compile("(?<id>\\d+)");
        assertNull(regex.group("42", "missing"));
        assertNull(regex.group("no digits", "id"));
        assertNull(regex.group(null, "id"));

        SafeRegex plainOnly = SafeRegex.compile("(\\d+)");
        assertNull(plainOnly.group("42", "id"));
    }

    @Test
    public void groupOrFullTextFallsBackToWholeInputWhenGroupMissing() {
        SafeRegex regex = SafeRegex.compile("(?<id>\\d+)");
        assertEquals("42", regex.groupOrFullText("42", "id"));
        // 组名不存在时对齐 Animeko 语义：返回整段输入
        assertEquals("xx abc yy", SafeRegex.compile("abc").groupOrFullText("xx abc yy", "id"));
        // 完全没匹配到返回 null
        assertNull(regex.groupOrFullText("no digits", "id"));
        assertNull(regex.groupOrFullText(null, "id"));
    }

    @Test
    public void firstGroupOrMatchReturnsGroupOneOrWholeMatch() {
        assertEquals("123", SafeRegex.compile("(\\d+)").firstGroupOrMatch("abc123"));
        assertEquals("abc", SafeRegex.compile("abc").firstGroupOrMatch("xxabc"));
        // 第一个捕获组为空串时退回整个匹配
        assertEquals("prefixsuffix",
                SafeRegex.compile("prefix(x?)suffix").firstGroupOrMatch("prefixsuffix"));
        assertNull(SafeRegex.compile("(\\d+)").firstGroupOrMatch("no digits"));
        assertNull(SafeRegex.compile("(\\d+)").firstGroupOrMatch(null));
    }

    @Test
    public void matcherTreatsNullInputAsEmptyString() {
        assertFalse(SafeRegex.compile("abc").matcher(null).find());
        // matcher() 只负责截断输入、不改变 Pattern 语义：matches 是全串匹配，find 是子串查找
        assertTrue(SafeRegex.compile("abc").matcher("abc").matches());
        assertFalse(SafeRegex.compile("abc").matcher("xabcx").matches());
        assertTrue(SafeRegex.compile("abc").matcher("xabcx").find());
    }

    // ---------------------------------------------------------------- 输入截断

    @Test
    public void inputLongerThanLimitIsTruncatedBeforeMatching() {
        int cap = RuleLimits.MAX_REGEX_INPUT_CHARS;
        SafeRegex regex = SafeRegex.compile("TARGET");

        // 标记恰好结束在截断边界内：能匹配
        assertTrue(regex.matches(repeat('a', cap - "TARGET".length()) + "TARGET"));
        // 标记只差 1 个字符就被截断：匹配不到（证明真的按上限截断，而不是整段扫描）
        assertFalse(regex.matches(repeat('a', cap - "TARGET".length() + 1) + "TARGET"));
        // 远超上限的长文本同样不会看到标记
        assertFalse(regex.matches(repeat('a', cap + 1000) + "TARGET"));
    }

    @Test
    public void groupAlsoRespectsInputLimit() {
        int cap = RuleLimits.MAX_REGEX_INPUT_CHARS;
        SafeRegex regex = SafeRegex.compile("(?<v>v=\\d+)");

        // 4 个字符的 "v=42" 落在上限内
        assertEquals("v=42", regex.group(repeat('a', cap - 4) + "v=42", "v"));
        // 填满上限后再放 "v=42"：被截断，提取不到
        assertNull(regex.group(repeat('a', cap) + "v=42", "v"));
    }

    // ---------------------------------------------------------------- 辅助

    /** 构造长度恰为 {@code length} 的合法正则：[aaa...]，length 至少为 2。 */
    private static String charClassOfLength(int length) {
        return "[" + repeat('a', length - 2) + "]";
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) sb.append(c);
        return sb.toString();
    }
}