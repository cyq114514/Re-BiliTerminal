package com.RobinNotBad.BiliClient.util;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

/**
 * 音量按键处理：短按 = 系统音量 ±1 档并提示百分比，按住 600ms = 弹出无极调音面板。
 *
 * <p>用户反馈（2026-11-07）：听歌页与短视频只能拖滑条，手表上没有音量键之外的手段；
 * 播放页的按键只处理了 DPAD，物理音量键完全交给系统、没有任何反馈，也调不出无极滑条。
 * 三个页面统一走这里，行为一致：
 * <ul>
 *   <li>{@link KeyEvent#KEYCODE_VOLUME_UP} / {@link KeyEvent#KEYCODE_VOLUME_DOWN} 与
 *       {@link KeyEvent#KEYCODE_DPAD_UP} / {@link KeyEvent#KEYCODE_DPAD_DOWN} 等价；</li>
 *   <li>短按调的是<b>系统音量</b>（保持老习惯，戴耳机/外放都生效）；</li>
 *   <li>按住超过 {@link #LONG_PRESS_MS} 弹无极调音（软件增益 0~100%，1% 一档）。</li>
 * </ul>
 */
public final class VolumeKeyHelper {

    /** 按住多久算长按 */
    private static final long LONG_PRESS_MS = 600;

    public interface Host {
        /** 短按反馈：把百分比显示在屏幕上 */
        void showVolumePercent(int percent);

        /** 长按：打开无极调音面板 */
        void showVolumePanel();
    }

    private final Context context;
    private final Host host;
    private final AudioManager audioManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean panelOpening = false;
    private boolean panelOpened = false;

    private final Runnable longPress;

    public VolumeKeyHelper(Context context, Host host) {
        this.context = context;
        this.host = host;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        //必须在构造器里初始化：字段初始化式里读 final 字段会报"可能尚未初始化"
        this.longPress = () -> {
            if (!panelOpening) return;
            panelOpened = true;
            panelOpening = false;
            if (this.host != null) this.host.showVolumePanel();
        };
    }

    /** @return true = 本类已消费该事件 */
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int dir = direction(keyCode);
        if (dir == 0) return false;

        if (event.getRepeatCount() == 0) {
            panelOpening = true;
            panelOpened = false;
            handler.postDelayed(longPress, LONG_PRESS_MS);
        }
        //长按已经弹面板后不再继续加减，避免"手还按着，音量跟着跳"
        if (panelOpened) return true;

        step(dir);
        return true;
    }

    /** @return true = 本类已消费该事件 */
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (direction(keyCode) == 0) return false;
        cancel();
        return true;
    }

    /** 页面失焦/销毁时调用，避免计时器泄漏 */
    public void cancel() {
        panelOpening = false;
        panelOpened = false;
        handler.removeCallbacks(longPress);
    }

    private static int direction(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_DPAD_UP:
                return 1;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return -1;
            default:
                return 0;
        }
    }

    private void step(int dir) {
        if (audioManager == null) {
            if (host != null) host.showVolumePercent(0);
            return;
        }
        int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int now = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int next = Math.max(0, Math.min(max, now + dir));
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0);
        } catch (Throwable ignored) {
        }
        int percent = max > 0 ? Math.round(next * 100f / max) : 0;
        if (host != null) host.showVolumePercent(percent);
    }

    /**
     * 供界面上的音量按钮调用：调一档系统音量。
     *
     * @return 调整后的百分比；取不到 AudioManager 时返回 -1
     */
    public static int stepSystemVolume(Context context, int dir) {
        if (context == null) return -1;
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return -1;
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int now = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        int next = Math.max(0, Math.min(max, now + dir));
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0);
        } catch (Throwable ignored) {
        }
        return max > 0 ? Math.round(next * 100f / max) : 0;
    }

    /** 需要时把系统音量百分比读出来（面板标题等处用得到） */
    public int currentPercent() {
        if (audioManager == null) return 0;
        int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int now = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        return max > 0 ? Math.round(now * 100f / max) : 0;
    }
}
