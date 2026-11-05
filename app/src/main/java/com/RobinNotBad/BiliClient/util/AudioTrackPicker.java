package com.RobinNotBad.BiliClient.util;

import com.RobinNotBad.BiliClient.model.DashAudioStream;
import com.RobinNotBad.BiliClient.model.DashData;

/**
 * 纯音频模式下的音轨选择。
 *
 * <p>刻意不用 {@link DashData#getBestAudioStream()}：那个方法优先 flac(30251)/杜比(30250)，
 * 这两类需要大会员权益且 ijkplayer 预编译 so 未必带 FLAC/EC-3 解码器，老设备上容易直接失败。
 * 队列播放优先 192K AAC(30280)，退化到普通音轨里码率最高的一路（不含 dolby/flac 字段）。
 */
public class AudioTrackPicker {

    /** 30280 = 192K AAC；30232 = 132K；30216 = 64K */
    private static final int[] PREFERRED_IDS = {30280, 30232, 30216};

    public static DashAudioStream pick(DashData dash) {
        if (dash == null || dash.audioStreams == null || dash.audioStreams.isEmpty()) return null;

        for (int id : PREFERRED_IDS) {
            for (DashAudioStream stream : dash.audioStreams) {
                if (stream != null && stream.id == id && stream.baseUrl != null && !stream.baseUrl.isEmpty())
                    return stream;
            }
        }

        DashAudioStream best = null;
        for (DashAudioStream stream : dash.audioStreams) {
            if (stream == null || stream.baseUrl == null || stream.baseUrl.isEmpty()) continue;
            if (best == null || stream.bandwidth > best.bandwidth) best = stream;
        }
        return best;
    }

    /** 音轨显示名（调试/提示用） */
    public static String describe(DashAudioStream stream) {
        return stream == null ? "无音轨" : stream.getQualityName();
    }
}
