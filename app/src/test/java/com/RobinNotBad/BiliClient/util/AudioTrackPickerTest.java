package com.RobinNotBad.BiliClient.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.RobinNotBad.BiliClient.model.DashAudioStream;
import com.RobinNotBad.BiliClient.model.DashData;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 纯音频模式的音轨挑选策略。
 *
 * <p>核心契约：优先 192K AAC(30280) &gt; 132K(30232) &gt; 64K(30216)，刻意不碰杜比/FLAC；
 * 都不命中时才在其余可用音轨里取带宽最大的一路。空 baseUrl 的音轨一律跳过。
 */
@RunWith(RobolectricTestRunner.class)
public class AudioTrackPickerTest {

    private static DashAudioStream stream(int id, long bandwidth, String baseUrl) {
        DashAudioStream s = new DashAudioStream();
        s.id = id;
        s.bandwidth = bandwidth;
        s.baseUrl = baseUrl;
        return s;
    }

    private static DashData dash(DashAudioStream... streams) {
        DashData dash = new DashData();
        dash.audioStreams = new ArrayList<>(Arrays.asList(streams));
        return dash;
    }

    @Test
    public void prefers192kAacOverHigherBandwidthDolbyOrFlac() {
        // 杜比/FLAC 带宽更高，但预编译 so 不一定带解码器，必须仍选 30280
        DashAudioStream dolby = stream(30250, 999_000, "http://d/dolby.m4s");
        DashAudioStream flac = stream(30251, 1_500_000, "http://f/flac.m4s");
        DashAudioStream k192 = stream(30280, 192_000, "http://a/192.m4s");
        DashAudioStream k64 = stream(30216, 64_000, "http://a/64.m4s");

        assertSame(k192, AudioTrackPicker.pick(dash(k64, flac, dolby, k192)));
        assertSame(k192, AudioTrackPicker.pick(dash(k192, k64)));
        // 顺序不影响优先级
        assertSame(k192, AudioTrackPicker.pick(dash(k192, flac)));
    }

    @Test
    public void fallsBackTo132kWhen192kIsAbsent() {
        DashAudioStream k132 = stream(30232, 132_000, "http://a/132.m4s");
        DashAudioStream k64 = stream(30216, 64_000, "http://a/64.m4s");
        DashAudioStream bigger = stream(30255, 900_000, "http://a/other.m4s");

        assertSame(k132, AudioTrackPicker.pick(dash(k64, bigger, k132)));
        // 只有 30232 时也命中
        assertSame(k132, AudioTrackPicker.pick(dash(k132)));
    }

    @Test
    public void fallsBackTo64kWhenOnly64kPreferredExists() {
        DashAudioStream k64 = stream(30216, 64_000, "http://a/64.m4s");
        DashAudioStream hiRes = stream(30251, 2_000_000, "http://a/flac.m4s");

        assertSame(k64, AudioTrackPicker.pick(dash(hiRes, k64)));
        assertEquals(30216, AudioTrackPicker.pick(dash(hiRes, k64)).id);
    }

    @Test
    public void skipsPreferredStreamsWithEmptyBaseUrl() {
        DashAudioStream empty192 = stream(30280, 192_000, "");
        DashAudioStream null192 = stream(30280, 192_000, null);
        DashAudioStream valid132 = stream(30232, 132_000, "http://a/132.m4s");

        assertSame(valid132, AudioTrackPicker.pick(dash(empty192, valid132)));
        assertSame(valid132, AudioTrackPicker.pick(dash(null192, valid132)));
        // 30280 只有空地址、30216 有病（null）时，退到 132K 而不是返回坏音轨
        DashAudioStream valid16 = stream(30216, 64_000, "http://a/64.m4s");
        assertSame(valid132, AudioTrackPicker.pick(dash(empty192, null, valid132, valid16)));
    }

    @Test
    public void picksHighestBandwidthWhenNoPreferredIdIsUsable() {
        DashAudioStream small = stream(30000, 32_000, "http://a/small.m4s");
        DashAudioStream large = stream(30255, 384_000, "http://a/large.m4s");
        DashAudioStream broken = stream(30256, 9_999_999, ""); // 带宽最高但无地址
        DashAudioStream medium = stream(30254, 128_000, "http://a/medium.m4s");

        assertSame(large, AudioTrackPicker.pick(dash(small, broken, large, medium)));
        assertEquals(384_000L, AudioTrackPicker.pick(dash(small, broken, large, medium)).bandwidth);

        // 带宽相同时取列表里靠前的一路（实现用严格大于比较）
        DashAudioStream tieFirst = stream(30001, 100_000, "http://a/1.m4s");
        DashAudioStream tieSecond = stream(30002, 100_000, "http://a/2.m4s");
        assertSame(tieFirst, AudioTrackPicker.pick(dash(tieFirst, tieSecond)));
    }

    @Test
    public void returnsNullForMissingOrUnusableDashData() {
        assertNull(AudioTrackPicker.pick(null));

        DashData noList = new DashData();
        noList.audioStreams = null;
        assertNull(AudioTrackPicker.pick(noList));

        assertNull(AudioTrackPicker.pick(new DashData())); // 空列表

        assertNull(AudioTrackPicker.pick(dash(
                stream(30280, 192_000, ""),
                stream(30232, 132_000, null),
                null)));
    }

    @Test
    public void describeMapsQualityNames() {
        assertEquals("无音轨", AudioTrackPicker.describe(null));
        assertEquals("64K", AudioTrackPicker.describe(stream(30216, 64_000, "u")));
        assertEquals("132K", AudioTrackPicker.describe(stream(30232, 132_000, "u")));
        assertEquals("192K", AudioTrackPicker.describe(stream(30280, 192_000, "u")));
        assertEquals("杜比全景声", AudioTrackPicker.describe(stream(30250, 1, "u")));
        assertEquals("Hi-Res无损", AudioTrackPicker.describe(stream(30251, 1, "u")));
        assertEquals("音频", AudioTrackPicker.describe(stream(99999, 1, "u")));
    }
}