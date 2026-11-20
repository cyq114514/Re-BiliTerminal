package com.RobinNotBad.BiliClient.source.rule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link RuleLimits} 的常量约束 + {@link SafeRegex} 是否真的引用这些上限。
 *
 * <p>这些数字是远程规则不可信输入的安全边界，改动常量时必须同步跑这里的用例：
 * 只看「> 0」不够，还要保证「SafeRegex 实际按新的上限工作」。
 */
public class RuleLimitsTest {

    @Test
    public void sizeLimitsArePositiveAndOrdered() {
        assertTrue(RuleLimits.MAX_CONFIG_BYTES > 0);
        assertTrue(RuleLimits.MAX_RULE_CHARS > 0);
        assertTrue(RuleLimits.MAX_PAGE_BYTES > 0);
        assertTrue(RuleLimits.MAX_SOURCES > 0);
        assertTrue(RuleLimits.MAX_REGEX_CHARS > 0);
        assertTrue(RuleLimits.MAX_REGEX_INPUT_CHARS > 0);

        // 单条规则能塞进配置文件；单个页面能塞下规则抓取到的 HTML
        assertTrue(RuleLimits.MAX_RULE_CHARS < RuleLimits.MAX_CONFIG_BYTES);
        assertTrue(RuleLimits.MAX_RULE_CHARS < RuleLimits.MAX_PAGE_BYTES);
        // 正则输入上限必须能覆盖一条短 URL/片段，否则规则形同虚设
        assertTrue(RuleLimits.MAX_REGEX_INPUT_CHARS >= 1024);
        assertTrue(RuleLimits.MAX_REGEX_CHARS >= 64);
    }

    @Test
    public void searchAndConcurrencyLimitsMatchBusinessRules() {
        assertEquals(60, RuleLimits.MAX_SEARCH_RESULTS);
        assertEquals(4, RuleLimits.MAX_CONCURRENT_SOURCES);
        assertTrue(RuleLimits.MAX_CHANNELS > 0);
        assertTrue(RuleLimits.MAX_EPISODES_PER_CHANNEL > 0);
        // 订阅源上限必须不小于一次搜索的结果上限，否则会先于分页截断
        assertTrue(RuleLimits.MAX_SOURCES >= RuleLimits.MAX_SEARCH_RESULTS);
    }

    @Test
    public void timeoutsArePositiveAndInternallyOrdered() {
        assertTrue(RuleLimits.CONNECT_TIMEOUT_SECONDS > 0);
        assertTrue(RuleLimits.READ_TIMEOUT_SECONDS > 0);
        assertTrue(RuleLimits.SEARCH_TIMEOUT_MS > 0L);
        assertTrue(RuleLimits.RESOLVE_PAGE_TIMEOUT_MS > 0L);
        assertTrue(RuleLimits.RESOLVE_TIMEOUT_MS > 0L);
        assertTrue(RuleLimits.SNIFF_TIMEOUT_MS > 0L);

        long connectMs = RuleLimits.CONNECT_TIMEOUT_SECONDS * 1000L;
        // 搜索总超时不短于一次连接、不长于整体解析预算
        assertTrue(RuleLimits.SEARCH_TIMEOUT_MS >= connectMs);
        assertTrue(RuleLimits.SEARCH_TIMEOUT_MS <= RuleLimits.RESOLVE_TIMEOUT_MS);
        assertTrue(RuleLimits.RESOLVE_PAGE_TIMEOUT_MS <= RuleLimits.RESOLVE_TIMEOUT_MS);
        assertTrue(RuleLimits.SNIFF_TIMEOUT_MS <= RuleLimits.RESOLVE_TIMEOUT_MS);
        // 单次嗅探失败后还有时间换线路
        assertTrue(RuleLimits.SNIFF_TIMEOUT_MS < RuleLimits.RESOLVE_TIMEOUT_MS);
    }

    @Test
    public void iterationLimitsAreSmallEnoughToAvoidRunaway() {
        assertEquals(2, RuleLimits.MAX_NESTED_PAGES);
        assertTrue(RuleLimits.MAX_NESTED_PAGES >= 1);
        assertTrue(RuleLimits.MAX_FAILOVER_ATTEMPTS >= 1);
        assertTrue(RuleLimits.MAX_FAILOVER_ATTEMPTS <= RuleLimits.MAX_CHANNELS);
    }

    @Test
    public void safeRegexHonorsMaxRegexChars() {
        // 恰好到上限的合法表达式可编译；超 1 个字符被 compile 静默拒绝、被 compileOrThrow 抛错
        String atLimit = "[" + repeat('a', RuleLimits.MAX_REGEX_CHARS - 2) + "]";
        assertEquals(RuleLimits.MAX_REGEX_CHARS, atLimit.length());
        assertNotNull(SafeRegex.compile(atLimit));

        String overLimit = "[" + repeat('a', RuleLimits.MAX_REGEX_CHARS - 1) + "]";
        assertEquals(RuleLimits.MAX_REGEX_CHARS + 1, overLimit.length());
        assertNull(SafeRegex.compile(overLimit));
    }

    @Test
    public void safeRegexHonorsMaxRegexInputChars() {
        int cap = RuleLimits.MAX_REGEX_INPUT_CHARS;
        SafeRegex regex = SafeRegex.compile("(?<mark>MARK)");

        // 截断边界内（含边界最后一个字符）可见
        assertEquals("MARK", regex.group(repeat('a', cap - 4) + "MARK", "mark"));
        // 越过边界一个字符即不可见，证明截断长度确实是 MAX_REGEX_INPUT_CHARS
        assertNull(regex.group(repeat('a', cap - 3) + "MARK", "mark"));
        assertFalse(regex.matches(repeat('a', cap) + "MARK"));
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) sb.append(c);
        return sb.toString();
    }
}