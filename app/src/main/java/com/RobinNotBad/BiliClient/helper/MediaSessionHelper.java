package com.RobinNotBad.BiliClient.helper;

import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;

/**
 * 播放通知栏 / 锁屏控制（MediaSession）的隔离层。
 *
 * <p><b>为什么必须单独成类：</b>{@code android.media.session.*} 是 API 21 才有的系统类。
 * 这套代码原本直接写在 {@code PlayerActivity} 里，D8 为兼容 API&lt;24 的 nest 机制会生成
 * 返回类型为 {@code MediaSession} 的合成访问器（{@code -$$Nest$fgetmediaSession}）；
 * 而 {@code BaseActivity.onStart} 里的 EventBus 注册要对 PlayerActivity 做
 * {@code getMethods()} 反射，解析到该返回类型时在 Android 4.4/5.0 上直接抛
 * {@code NoClassDefFoundError: android/media/session/MediaSession}，整个播放页起不来
 * （用户侧表现：点播放就闪退，崩溃页显示 "Could not inspect methods of PlayerActivity"）。
 *
 * <p>把类型收进本类、由调用方先判断 SDK 再 {@code new}，PlayerActivity 的方法签名里
 * 就彻底没有 MediaSession 了，反射链路也就安全了。本类只应在 API ≥ 21 时被实例化。
 */
public class MediaSessionHelper {

    /** 回调全部在主线程之外触发，实现方需要自行切换到主线程。 */
    public interface Callback {
        void onPlay();

        void onPause();

        void onSeekTo(long positionMs);

        void onSkipToNext();

        void onSkipToPrevious();
    }

    private final MediaSession session;

    public MediaSessionHelper(Context context, String tag, final Callback callback) {
        session = new MediaSession(context, tag);
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                super.onPlay();
                callback.onPlay();
            }

            @Override
            public void onPause() {
                super.onPause();
                callback.onPause();
            }

            @Override
            public void onSeekTo(long pos) {
                super.onSeekTo(pos);
                callback.onSeekTo(pos);
            }

            @Override
            public void onSkipToNext() {
                super.onSkipToNext();
                callback.onSkipToNext();
            }

            @Override
            public void onSkipToPrevious() {
                super.onSkipToPrevious();
                callback.onSkipToPrevious();
            }
        });
        session.setActive(true);
    }

    /** 更新标题/时长；任何异常都不该影响播放。 */
    public void setMetadata(String title, long durationMs) {
        try {
            MediaMetadata.Builder builder = new MediaMetadata.Builder();
            if (title != null) builder.putString(MediaMetadata.METADATA_KEY_TITLE, title);
            if (durationMs > 0) builder.putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs);
            session.setMetadata(builder.build());
        } catch (Throwable ignored) {
        }
    }

    /**
     * 更新播放状态。
     *
     * @param canSkipNext     是否有下一 P（决定通知栏是否显示"下一集"）
     * @param canSkipPrevious 是否有上一 P
     */
    public void setPlaybackState(boolean playing, long positionMs,
                                 boolean canSkipNext, boolean canSkipPrevious) {
        try {
            int state = playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
            long actions = PlaybackState.ACTION_PLAY
                    | PlaybackState.ACTION_PAUSE
                    | PlaybackState.ACTION_SEEK_TO
                    | PlaybackState.ACTION_SKIP_TO_NEXT
                    | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
            if (!canSkipNext) actions &= ~PlaybackState.ACTION_SKIP_TO_NEXT;
            if (!canSkipPrevious) actions &= ~PlaybackState.ACTION_SKIP_TO_PREVIOUS;
            PlaybackState.Builder builder = new PlaybackState.Builder()
                    .setState(state, Math.max(positionMs, 0), 1.0f)
                    .setActions(actions);
            session.setPlaybackState(builder.build());
        } catch (Throwable ignored) {
        }
    }

    public void release() {
        try {
            session.setActive(false);
            session.release();
        } catch (Throwable ignored) {
        }
    }
}
