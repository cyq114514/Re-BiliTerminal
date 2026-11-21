package com.RobinNotBad.BiliClient.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 版本名语义化比较的回归测试：应用内更新以 versionName 为唯一「是否新版」依据，
 * 正式版 / 预发布 / fix 后缀与 v 前缀的排序必须稳定。
 */
@RunWith(RobolectricTestRunner.class)
public class VersionNameUtilTest {

    @Test
    public void minorVersionWins() {
        assertTrue(VersionNameUtil.parse("1.4.0") > VersionNameUtil.parse("1.3.9"));
        assertTrue(VersionNameUtil.parse("1.10.0") > VersionNameUtil.parse("1.9.9"));
    }

    @Test
    public void preReleaseIsOlderThanPlain() {
        assertTrue(VersionNameUtil.parse("1.4.0-BETA1") < VersionNameUtil.parse("1.4.0"));
        assertTrue(VersionNameUtil.parse("1.4.0-BETA2") > VersionNameUtil.parse("1.4.0-BETA1"));
    }

    @Test
    public void fixIsBetweenSameVersionAndNext() {
        assertTrue(VersionNameUtil.parse("1.3.9") < VersionNameUtil.parse("1.3.9fix"));
        assertTrue(VersionNameUtil.parse("1.3.9fix") < VersionNameUtil.parse("1.4.0"));
        assertTrue(VersionNameUtil.parse("1.3.9fix2") > VersionNameUtil.parse("1.3.9fix"));
    }

    @Test
    public void leadingVPrefixIsIgnored() {
        assertEquals(VersionNameUtil.parse("1.4.0"), VersionNameUtil.parse("v1.4.0"));
        assertEquals(VersionNameUtil.parse("1.4.0"), VersionNameUtil.parse("V1.4.0"));
    }

    @Test
    public void invalidNamesReturnMinusOne() {
        assertEquals(-1, VersionNameUtil.parse(""));
        assertEquals(-1, VersionNameUtil.parse("release-latest"));
        assertEquals(-1, VersionNameUtil.parse("1.4.0-unknown"));
    }
}