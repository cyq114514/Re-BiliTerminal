package com.RobinNotBad.BiliClient.util;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.StatFs;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * 把 DASH 分离的「纯视频流」与「纯音频流」重新封装成一个普通 MP4 单文件。
 *
 * 背景：B 站 1080P 及以上只提供 DASH，一条纯视频（video.m4s）+ 一条纯音频（audio.m4s），
 * 两者都是<b>分片 MP4</b>（moov 里的采样表是空的，真正的采样在 moof/mdat 里）。
 * 这里在下载完成后把两条流 remux（只换容器、不重新编码）成一个标准 MP4：
 *
 * <ul>
 * <li>用 {@link MediaExtractor} 读两条流的采样：它内部会解析 moof，Android 4.4（API 19）起就支持分片 MP4；</li>
 * <li>用 {@link MediaMuxer} 按 {@code sampleTime} 归并写入，输出标准（非分片）MP4，moov 在文件尾；</li>
 * <li>不重新编码，画质无损、几秒内完成、几乎不占 CPU。</li>
 * </ul>
 *
 * <p>失败时（找不到轨道、编码不被 MediaMuxer 支持、空间不足、被中止）返回非 {@link #RESULT_OK}，
 * 调用方应当保留原来的双文件继续用，<b>不要因此判定下载失败</b>。
 *
 * <p>注意：{@link MediaMuxer} 无法写入杜比全景声（ec-3/eac3）与无损 FLAC 音轨；
 * 选流时已经优先选 AVC + AAC，正常路径不会撞上。
 */
public final class MediaMerger {

    /** 合并成功，产物可用 */
    public static final int RESULT_OK = 0;
    /** 输入里找不到可用的视频轨或音频轨（容器异常、不是分片 MP4 等） */
    public static final int RESULT_NO_TRACK = 1;
    /** 磁盘剩余空间不足以同时放下源文件与合并产物 */
    public static final int RESULT_NO_SPACE = 2;
    /** 被 shouldStop 中止（服务停止等） */
    public static final int RESULT_STOPPED = 3;
    /** 其他失败：IO 异常、MediaMuxer 不支持该编码、产物不完整等 */
    public static final int RESULT_FAILED = 4;

    private static final String TAG = "MediaMerger";
    /** 每次读写的缓冲区大小 */
    private static final int BUFFER_SIZE = 1024 * 1024;
    /** 合并产物在「源文件大小之和」之外额外需要的余量（moov 采样表、对齐开销） */
    private static final long SPACE_MARGIN_BYTES = 4L * 1024 * 1024;
    /** 进度回调节流：进度增量至少 1% */
    private static final float PROGRESS_STEP = 0.01f;
    /** 进度回调节流：两次回调至少间隔 200ms */
    private static final long PROGRESS_MIN_INTERVAL_MS = 200L;
    /** MediaFormat 里旋转角度的键；常量 KEY_ROTATION 在 API 23 才公开，这里用字面量 */
    private static final String KEY_ROTATION = "rotation-degrees";

    private MediaMerger() {
    }

    public interface ProgressListener {
        void onProgress(float ratio);
    }

    public interface StopChecker {
        boolean shouldStop();
    }

    /**
     * 把两条流合并成 mergedFile 单文件（remux，不重新编码）。
     *
     * @param onProgress 合并进度回调，参数为 0..1 的比例；可为 null
     * @param shouldStop 返回 true 时中止合并；可为 null
     * @return {@link #RESULT_OK} 成功；{@link #RESULT_STOPPED} 被中止；其它值为失败/跳过，
     *         此时 mergedFile 已被清理，调用方应保留源文件
     */
    public static int mergeAv(File videoFile, File audioFile, File mergedFile,
                              ProgressListener onProgress, StopChecker shouldStop) {
        if (videoFile == null || audioFile == null || !videoFile.isFile() || !audioFile.isFile()) {
            Logu.e(TAG, "合并跳过：输入文件不存在（video="
                    + (videoFile == null ? "null" : videoFile.getAbsolutePath())
                    + " audio=" + (audioFile == null ? "null" : audioFile.getAbsolutePath()) + "）");
            return RESULT_FAILED;
        }

        File parent = mergedFile.getAbsoluteFile().getParentFile();
        if (parent != null) parent.mkdirs();
        if (mergedFile.exists() && !mergedFile.delete()) {
            Logu.e(TAG, "合并失败：上次残留的合并文件删不掉（" + mergedFile.getAbsolutePath() + "）");
            return RESULT_FAILED;
        }

        long inputBytes = videoFile.length() + audioFile.length();
        if (!hasEnoughSpace(mergedFile, inputBytes)) {
            Logu.e(TAG, "合并跳过：剩余空间不足（至少需要 " + (inputBytes + SPACE_MARGIN_BYTES) + " 字节）");
            return RESULT_NO_SPACE;
        }

        int result = RESULT_FAILED;
        MediaExtractor videoExtractor = null;
        MediaExtractor audioExtractor = null;
        MediaMuxer muxer = null;
        boolean muxerStarted = false;

        try {
            videoExtractor = new MediaExtractor();
            videoExtractor.setDataSource(videoFile.getAbsolutePath());
            audioExtractor = new MediaExtractor();
            audioExtractor.setDataSource(audioFile.getAbsolutePath());

            int videoTrackIndex = selectTrack(videoExtractor, "video/");
            int audioTrackIndex = selectTrack(audioExtractor, "audio/");
            if (videoTrackIndex < 0 || audioTrackIndex < 0) {
                Logu.e(TAG, "合并跳过：找不到可用轨道（videoTrack=" + videoTrackIndex
                        + " audioTrack=" + audioTrackIndex + "）");
                return RESULT_NO_TRACK;
            }
            videoExtractor.selectTrack(videoTrackIndex);
            audioExtractor.selectTrack(audioTrackIndex);

            MediaFormat videoFormat = videoExtractor.getTrackFormat(videoTrackIndex);
            MediaFormat audioFormat = audioExtractor.getTrackFormat(audioTrackIndex);

            muxer = new MediaMuxer(mergedFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videoOutTrack = muxer.addTrack(videoFormat);
            int audioOutTrack = muxer.addTrack(audioFormat);
            int rotation = videoFormat.containsKey(KEY_ROTATION) ? videoFormat.getInteger(KEY_ROTATION) : 0;
            if (rotation != 0) muxer.setOrientationHint(rotation);
            muxer.start();
            muxerStarted = true;

            result = copySamples(videoExtractor, audioExtractor, muxer,
                    videoOutTrack, audioOutTrack, inputBytes, onProgress, shouldStop);
        } catch (Exception e) {
            Logu.e(TAG, "合并失败：" + videoFile.getName() + " + " + audioFile.getName() + "：" + e.getMessage());
            result = RESULT_FAILED;
        } finally {
            releaseExtractor(videoExtractor);
            releaseExtractor(audioExtractor);
            if (muxerStarted) {
                try {
                    muxer.stop();
                } catch (Exception e) {
                    // stop 失败说明 moov 没写完整，产物不可用
                    Logu.e(TAG, "合并失败：MediaMuxer 收尾失败，产物不完整：" + e.getMessage());
                    result = RESULT_FAILED;
                }
            }
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (Exception e) {
                    Logu.e(TAG, "MediaMuxer 释放失败：" + e.getMessage());
                }
            }
            if (result == RESULT_OK && (!mergedFile.isFile() || mergedFile.length() <= 0L)) {
                Logu.e(TAG, "合并失败：产物为空");
                result = RESULT_FAILED;
            }
            if (result != RESULT_OK) mergedFile.delete();
        }
        return result;
    }

    /**
     * 按 sampleTime 归并两条轨的采样写入 muxer。
     *
     * 每轮比较两条轨当前样本的时间戳，先写早的那个；某条轨读完就只写另一条（音频通常比视频长）。
     * 这样输出的 interleave 是单调的，播放器 seek 与音画同步都正常。
     */
    private static int copySamples(MediaExtractor videoExtractor, MediaExtractor audioExtractor,
                                   MediaMuxer muxer, int videoOutTrack, int audioOutTrack,
                                   long totalBytes, ProgressListener onProgress, StopChecker shouldStop) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        boolean videoDone = false;
        boolean audioDone = false;
        long readBytes = 0;
        float lastReportedProgress = -1f;
        long lastReportAt = 0L;

        while (!videoDone || !audioDone) {
            if (shouldStop != null && shouldStop.shouldStop()) return RESULT_STOPPED;

            long videoTime = videoDone ? -1L : videoExtractor.getSampleTime();
            long audioTime = audioDone ? -1L : audioExtractor.getSampleTime();
            if (videoTime < 0L) videoDone = true;
            if (audioTime < 0L) audioDone = true;
            if (videoDone && audioDone) break;

            boolean takeVideo = !videoDone && (audioDone || videoTime <= audioTime);
            MediaExtractor extractor = takeVideo ? videoExtractor : audioExtractor;
            int outTrack = takeVideo ? videoOutTrack : audioOutTrack;

            int size = extractor.readSampleData(buffer, 0);
            if (size < 0) {
                //与 sampleTime<0 等价，标记该轨结束，下一轮只处理另一条
                if (takeVideo) videoDone = true;
                else audioDone = true;
                continue;
            }

            buffer.position(0);
            buffer.limit(size);
            // MediaMuxer 只把 flags 当「是否关键帧」看，非 0 即可；
            // SAMPLE_FLAG_SYNC(1) 与 BUFFER_FLAG_KEY_FRAME(1) 值相同，用前者可避开 API 21 常量
            boolean isSyncFrame = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
            bufferInfo.set(0, size, extractor.getSampleTime(), isSyncFrame ? MediaExtractor.SAMPLE_FLAG_SYNC : 0);
            muxer.writeSampleData(outTrack, buffer, bufferInfo);
            extractor.advance();
            readBytes += size;

            if (onProgress != null && totalBytes > 0L) {
                float progress = Math.max(0f, Math.min(1f, (float) ((double) readBytes / totalBytes)));
                long now = System.currentTimeMillis();
                if (progress - lastReportedProgress >= PROGRESS_STEP && now - lastReportAt >= PROGRESS_MIN_INTERVAL_MS) {
                    lastReportedProgress = progress;
                    lastReportAt = now;
                    try {
                        onProgress.onProgress(progress);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return RESULT_OK;
    }

    /** 找第一条 MIME 以 mimePrefix 开头的轨道，找不到返回 -1 */
    private static int selectTrack(MediaExtractor extractor, String mimePrefix) {
        for (int index = 0; index < extractor.getTrackCount(); index++) {
            String mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(mimePrefix)) return index;
        }
        return -1;
    }

    /**
     * 剩余空间是否够：源文件还在盘上，合并期间要额外再写一份同样大小的产物。
     * 读不到剩余空间时放行——真没空间时 MediaMuxer 会抛 IO 异常，走失败兜底。
     */
    private static boolean hasEnoughSpace(File targetFile, long inputBytes) {
        try {
            File dir = targetFile.getAbsoluteFile().getParentFile();
            if (dir == null) return true;
            StatFs statFs = new StatFs(dir.getAbsolutePath());
            return statFs.getAvailableBytes() >= inputBytes + SPACE_MARGIN_BYTES;
        } catch (Exception e) {
            Logu.w(TAG, "读取剩余空间失败，跳过空间检查：" + e.getMessage());
            return true;
        }
    }

    /** 释放 extractor，释放失败不影响合并结果 */
    private static void releaseExtractor(MediaExtractor extractor) {
        if (extractor == null) return;
        try {
            extractor.release();
        } catch (Exception e) {
            Logu.e(TAG, "MediaExtractor 释放失败：" + e.getMessage());
        }
    }
}
