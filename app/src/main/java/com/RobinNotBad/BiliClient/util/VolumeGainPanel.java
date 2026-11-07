package com.RobinNotBad.BiliClient.util;

import android.app.Activity;
import android.app.Dialog;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * 无极调音面板：0~100 的软件增益，1% 一档，与系统音量档位无关。
 *
 * <p>播放页原本就有一套同样读写 {@code player_volume_gain} 的增益，这里把它抽成共用实现，
 * 让听歌页、短视频也能"按键调 / 长按调出滑条"：同一份偏好、同一套手感，
 * 切页面不会突然变响或变轻。
 *
 * <p>为什么用软件增益而不是系统音量：手表上系统音量常常只有 3~15 档，调不出 35% 这种值；
 * ijk 与 MediaPlayer 的 {@code setVolume} 都是采样级线性乘算，可以做到 1% 一档。
 */
public final class VolumeGainPanel {

    /** 偏好键：与播放页历史版本保持一致，升级后用户原有音量不丢 */
    public static final String KEY = "player_volume_gain";

    public interface OnGainChanged {
        /**
         * @param gain 0~1，已限幅
         */
        void onGainChanged(float gain);
    }

    private VolumeGainPanel() {
    }

    public static float clamp(float gain) {
        if (gain < 0f) return 0f;
        if (gain > 1f) return 1f;
        return gain;
    }

    public static float currentGain() {
        return clamp(SharedPreferencesUtil.getFloat(KEY, 100f) / 100f);
    }

    public static void persist(float gain) {
        SharedPreferencesUtil.putFloat(KEY, clamp(gain) * 100f);
    }

    /**
     * 按步长调一档并落盘。
     *
     * @return 调整后的增益（0~1）
     */
    public static float step(float current, float delta) {
        float gain = clamp(current + delta);
        persist(gain);
        return gain;
    }

    /**
     * 弹出无极调音滑条。拖动即时回调，松手/关闭时落盘。
     *
     * @return 已显示的 Dialog（调用方可持有，页面销毁时 dismiss）
     */
    public static Dialog show(Activity activity, final OnGainChanged cb) {
        return show(activity, "音量", currentGain(), cb);
    }

    public static Dialog show(Activity activity, String title, float gain, final OnGainChanged cb) {
        if (activity == null || activity.isFinishing()) return null;
        final float start = clamp(gain);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = ToolsUtil.dp2px(16f);
        box.setPadding(pad, ToolsUtil.dp2px(2f), pad, 0);

        final TextView value = new TextView(activity);
        value.setGravity(Gravity.CENTER);
        value.setTextSize(20f);
        value.setText(Math.round(start * 100) + "%");
        box.addView(value, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        SeekBar bar = new SeekBar(activity);
        bar.setMax(100);
        bar.setProgress(Math.round(start * 100));
        bar.setPadding(0, ToolsUtil.dp2px(10f), 0, ToolsUtil.dp2px(10f));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value.setText(progress + "%");
                if (cb != null) cb.onGainChanged(progress / 100f);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                persist(seekBar.getProgress() / 100f);
            }
        });
        box.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Dialog dialog = new AdaptiveDialog.Builder(activity)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("完成", (d, which) -> {
                })
                .show();
        //没有点「完成」直接返回/取消也要把最后的位置记住
        if (dialog != null) {
            dialog.setOnDismissListener(d -> persist(bar.getProgress() / 100f));
        }
        return dialog;
    }
}
