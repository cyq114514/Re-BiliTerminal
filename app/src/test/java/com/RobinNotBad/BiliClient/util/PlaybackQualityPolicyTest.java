package com.RobinNotBad.BiliClient.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 清晰度策略的合成规则：手动值优先，否则按网络取默认，网络分档非法时回退全局默认。
 *
 * <p>16 = 360P、64 = 720P、80 = 1080P 是当前工程实际支持的请求值；-1 只作「跟随全局」占位，
 * 不是合法画质。
 */
@RunWith(RobolectricTestRunner.class)
public class PlaybackQualityPolicyTest {

    @Test
    public void validOnlyAcceptsSupportedQualityLevels() {
        assertTrue(PlaybackQualityPolicy.valid(16));
        assertTrue(PlaybackQualityPolicy.valid(64));
        assertTrue(PlaybackQualityPolicy.valid(80));

        assertFalse(PlaybackQualityPolicy.valid(0));
        assertFalse(PlaybackQualityPolicy.valid(PlaybackQualityPolicy.INHERIT));
        assertFalse(PlaybackQualityPolicy.valid(15));
        assertFalse(PlaybackQualityPolicy.valid(32));
        assertFalse(PlaybackQualityPolicy.valid(112));
    }

    @Test
    public void manualChoiceAlwaysWinsOverNetworkDefaults() {
        // 手动选了 1080P：无论网络、全局、分档怎么配，都必须请求 80
        assertEquals(80, PlaybackQualityPolicy.request(80, PlaybackQualityPolicy.MOBILE, 16, 16, 32));
        assertEquals(80, PlaybackQualityPolicy.request(80, PlaybackQualityPolicy.WIFI, 64, 64, 80));
        assertEquals(64, PlaybackQualityPolicy.request(64, PlaybackQualityPolicy.WIFI, 80, 80, 80));
        assertEquals(16, PlaybackQualityPolicy.request(16, PlaybackQualityPolicy.MOBILE, 80, 80, 80));
    }

    @Test
    public void mobileAndWifiUseTheirOwnConfiguredQuality() {
        assertEquals(64, PlaybackQualityPolicy.resolve(PlaybackQualityPolicy.MOBILE, 16, 64, 80));
        assertEquals(80, PlaybackQualityPolicy.resolve(PlaybackQualityPolicy.WIFI, 16, 64, 80));

        // 手动档不合法（-1/未知）时走网络分档
        assertEquals(64, PlaybackQualityPolicy.request(
                PlaybackQualityPolicy.INHERIT, PlaybackQualityPolicy.MOBILE, 16, 64, 80));
        assertEquals(80, PlaybackQualityPolicy.request(112, PlaybackQualityPolicy.WIFI, 16, 64, 80));
    }

    @Test
    public void unknownNetworkInheritsGlobalDefault() {
        assertEquals(80, PlaybackQualityPolicy.resolve(
                PlaybackQualityPolicy.UNKNOWN, 80, 64, 80));
        // 网络值连枚举都不在范围内（例如旧版本存档里的 99）同样按「未知」处理
        assertEquals(64, PlaybackQualityPolicy.resolve(99, 64, 80, 80));
        assertEquals(16, PlaybackQualityPolicy.resolve(-9, 16, 80, 80));
        assertEquals(16, PlaybackQualityPolicy.resolve(PlaybackQualityPolicy.INHERIT, 16, 80, 80));
    }

    @Test
    public void invalidGlobalFallsBackTo360p() {
        assertEquals(16, PlaybackQualityPolicy.resolve(PlaybackQualityPolicy.UNKNOWN, 0, 64, 80));
        assertEquals(16, PlaybackQualityPolicy.resolve(PlaybackQualityPolicy.UNKNOWN, 32, 64, 80));
        assertEquals(16, PlaybackQualityPolicy.resolve(PlaybackQualityPolicy.UNKNOWN, PlaybackQualityPolicy.INHERIT, 64, 80));
        // 全局非法的同时网络分档也非法：仍然是 16，不会把 -1/0 当画质发出去
        assertEquals(16, PlaybackQualityPolicy.resolve(PlaybackQualityPolicy.MOBILE, 0, 0, 80));
    }

    @Test
    public void invalidMobileOrWifiSettingFallsBackToGlobal() {
        assertEquals(80, PlaybackQualityPolicy.resolve(PlaybackQualityPolicy.MOBILE, 80, 0, 64));
        assertEquals(64, PlaybackQualityPolicy.resolve(PlaybackQualityPolicy.WIFI, 64, 80, 112));
        assertEquals(16, PlaybackQualityPolicy.resolve(
                PlaybackQualityPolicy.MOBILE, 16, PlaybackQualityPolicy.INHERIT, 80));

        // request 在没有手动值时才看分档，分档非法仍回到全局
        assertEquals(64, PlaybackQualityPolicy.request(0, PlaybackQualityPolicy.WIFI, 64, 16, 32));
    }
}