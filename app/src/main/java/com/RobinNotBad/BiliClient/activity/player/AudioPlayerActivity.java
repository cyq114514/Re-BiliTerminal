package com.RobinNotBad.BiliClient.activity.player;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.ui.theme.ThemeManager;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.model.AudioQueue;
import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.service.AudioPlayerService;
import com.RobinNotBad.BiliClient.util.AdaptiveDialog;
import com.RobinNotBad.BiliClient.util.GlideUtil;
import com.RobinNotBad.BiliClient.util.LyricProvider;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import java.util.List;
import java.util.Locale;

//收藏夹音频播放：全屏页
//2026-11-05
public class AudioPlayerActivity extends BaseActivity implements AudioPlayerService.Listener {

    private ImageView cover;
    private TextView title, upName, tvPosition, tvDuration, tvQueueInfo;
    private SeekBar seekBar;
    private ImageButton btnPlay;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean dragging = false;
    private String lastCover = "";

    //歌词：B站 CC 字幕优先，取不到再走网易云兜底
    private TextView lyricLine;
    private LyricProvider.Lyrics lyrics;
    private long lyricCid = 0;
    private int lastLyricIndex = -1;
    private int lyricWaitTicks = 0;

    /**
     * 封面按屏幕比例算边长，不再用布局里写死的 220dp。
     *
     * <p>「界面大小」的实现是把 densityDpi 乘倍数（BiliTerminal.java:302），
     * 屏幕可用宽度（dp）随之变小：手表或大字号下 220dp 约等于整屏宽，
     * 又因为是正方形，高度把标题、进度条、按钮全挤出视口——用户看到的就是"封面铺满整屏"。
     *
     * <p>取屏宽 58% 与屏高 34% 的较小值，并夹在「96dp（且不超过半屏宽）」到 240dp 之间，
     * 保证任何界面缩放下四周都有留白、控制区可见。底部仍由 ScrollView 兜底。
     */
    private void applyResponsiveLayout() {
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        int widthPx = metrics.widthPixels, heightPx = metrics.heightPixels;
        float density = metrics.density;

        // 封面：取屏宽 56% 与屏高 30% 的较小值，夹在「96dp（且不超半屏宽）」~240dp
        int coverSide = (int) Math.min(widthPx * 0.56f, heightPx * 0.30f);
        int minSide = Math.min((int) (96 * density + 0.5f), (int) (widthPx * 0.4f));
        int maxSide = (int) (240 * density + 0.5f);
        coverSide = Math.max(minSide, Math.min(maxSide, coverSide));
        applySize(findViewById(R.id.coverCard), coverSide, coverSide);

        // 控制行：原先固定 52 + 68 + 52dp、间距 28dp×2，合计 228dp。
        // 窄屏或「界面大小」放大后总宽超过屏幕，右侧"下一首"会被截掉（用户截图可见）。
        // 改为按屏宽比例分配：两侧按钮各 20%、播放键 26%、间距各 5%，合计 76%，永远留白。
        int gap = (int) (widthPx * 0.05f);
        int minTouch = (int) (36 * density + 0.5f);      // 手表触控下限
        int sideBtn = Math.max((int) (widthPx * 0.20f), minTouch);
        int playBtn = Math.max((int) (widthPx * 0.26f), minTouch);
        applySize(findViewById(R.id.btnPrev), sideBtn, sideBtn);
        applySize(findViewById(R.id.btnNext), sideBtn, sideBtn);
        applyPlayButtonSize(playBtn, gap);
    }

    private void applySize(android.view.View view, int width, int height) {
        if (view == null) return;
        android.view.ViewGroup.LayoutParams lp = view.getLayoutParams();
        lp.width = width;
        lp.height = height;
        view.setLayoutParams(lp);
    }

    private void applyPlayButtonSize(int size, int gap) {
        android.view.View play = findViewById(R.id.btnPlay);
        if (play == null) return;
        android.view.ViewGroup.LayoutParams lp = play.getLayoutParams();
        lp.width = size;
        lp.height = size;
        if (lp instanceof android.view.ViewGroup.MarginLayoutParams) {
            android.view.ViewGroup.MarginLayoutParams margins = (android.view.ViewGroup.MarginLayoutParams) lp;
            margins.leftMargin = gap;
            margins.rightMargin = gap;
            margins.setMarginStart(gap);
            margins.setMarginEnd(gap);
        }
        play.setLayoutParams(lp);
    }

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshUi();
            handler.postDelayed(this, 1000);
        }
    };

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!AudioPlayerService.isRunning() && AudioQueue.get().current() == null) {
            MsgUtil.showMsg("没有正在播放的音频");
            finish();
            return;
        }
        setContentView(R.layout.activity_audio_player);

        cover = findViewById(R.id.cover);
        title = findViewById(R.id.title);
        upName = findViewById(R.id.upName);
        tvPosition = findViewById(R.id.tvPosition);
        tvDuration = findViewById(R.id.tvDuration);
        tvQueueInfo = findViewById(R.id.tvQueueInfo);
        lyricLine = findViewById(R.id.lyricLine);
        seekBar = findViewById(R.id.seekBar);
        btnPlay = findViewById(R.id.btnPlay);
        applyResponsiveLayout();

        //进度条强调色跟随主题：colorControlActivated 是主题属性，自定义主题下 XML 默认色换不了。
        //progressTintList 是 API 21+ 接口，以下保持系统默认渲染
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            android.content.res.ColorStateList primary =
                    android.content.res.ColorStateList.valueOf(ThemeManager.getPrimary());
            seekBar.setProgressTintList(primary);
            seekBar.setThumbTintList(primary);
        }
        //顶栏「队列/停止」胶囊：图标跟主题 accent 走，和进度条强调色一致（新版美学）
        ((ImageView) findViewById(R.id.queueIcon)).setColorFilter(ThemeManager.getPrimary());
        ((ImageView) findViewById(R.id.stopIcon)).setColorFilter(ThemeManager.getPrimary());

        findViewById(R.id.top).setOnClickListener(v -> finish());
        findViewById(R.id.queueBtn).setOnClickListener(v ->
                startActivity(new Intent(this, AudioQueueActivity.class)));
        //停止并退出：停掉播放服务（顶部悬浮胶囊随之消失），不只是退出本页
        findViewById(R.id.stopBtn).setOnClickListener(v -> {
            AudioPlayerService.stopService(this);
            finish();
        });
        findViewById(R.id.btnPrev).setOnClickListener(v -> AudioPlayerService.previous(this));
        findViewById(R.id.btnNext).setOnClickListener(v -> AudioPlayerService.next(this));
        btnPlay.setOnClickListener(v -> AudioPlayerService.toggle(this));

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) tvPosition.setText(formatTime(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                AudioPlayerService.seekTo(AudioPlayerActivity.this, bar.getProgress());
            }
        });

        AudioPlayerService.addListener(this);
        refreshUi();
        handler.postDelayed(refreshRunnable, 1000);
        maybeShowFastLoadTip();
    }

    /**
     * 首次进入听歌模式时提示「快速加载会占用本地存储」。
     *
     * <p>只在开关处于开启、且没提示过时弹一次：用户可以直接在弹窗里关掉，
     * 读写的与设置页是同一个 key（{@link SharedPreferencesUtil#AUDIO_FAST_LOAD}），
     * 关掉后下一次起播就退回直连，不需要重启应用。
     */
    private void maybeShowFastLoadTip() {
        if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.AUDIO_FAST_LOAD_ASKED, false)) return;
        SharedPreferencesUtil.putBoolean(SharedPreferencesUtil.AUDIO_FAST_LOAD_ASKED, true);
        if (!SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.AUDIO_FAST_LOAD, true)) return;

        new AdaptiveDialog.Builder(this)
                .setTitle("听歌快速加载已开启")
                .setMessage("为了让你切歌、拖动进度条更快，会把听过的音频缓存到本地。\n\n"
                        + "最多占用约 80MB 存储空间，超出后自动清理最久没听的；"
                        + "可以随时在「设置 → 内置播放器设置 → 听歌快速加载」关闭。")
                .setPositiveButton("知道了", null)
                .setNegativeButton("关闭快速加载", (dialog, which) -> {
                    SharedPreferencesUtil.putBoolean(SharedPreferencesUtil.AUDIO_FAST_LOAD, false);
                    MsgUtil.showMsg("已关闭，可在设置里重新开启");
                })
                .show();
    }

    @Override
    public void onAudioStateChanged() {
        handler.post(this::refreshUi);
    }

    private void refreshUi() {
        if (isFinishing() || isDestroyed()) return;
        //只在服务彻底不存在时退出；解析阶段（isRunning 但未 prepared）继续显示加载态
        if (!AudioPlayerService.isRunning()) {
            finish();
            return;
        }
        VideoCard item = AudioQueue.get().current();
        if (item == null) {
            finish();
            return;
        }

        title.setText(item.title);
        upName.setText(item.upName != null ? item.upName : "");
        if (item.cover != null && !item.cover.equals(lastCover)) {
            lastCover = item.cover;
            GlideUtil.requestCover(cover, item.cover, R.mipmap.placeholder);
        }

        int duration = Math.max(AudioPlayerService.getDurationMs(), 0);
        int position = Math.max(AudioPlayerService.getPositionMs(), 0);
        seekBar.setMax(Math.max(duration, 1));
        if (!dragging) {
            seekBar.setProgress(Math.min(position, Math.max(duration, 1)));
            tvPosition.setText(formatTime(position));
        }
        tvDuration.setText(formatTime(duration));
        btnPlay.setImageResource(AudioPlayerService.isPlaying()
                ? R.drawable.btn_player_pause : R.drawable.btn_player_play);

        String folder = AudioQueue.get().getFolderName();
        String info = AudioQueue.get().positionText()
                + (folder == null || folder.isEmpty() ? "" : " · " + folder);
        if (AudioPlayerService.isResolving()) info = "正在解析音源…  " + info;
        tvQueueInfo.setText(info);

        ensureLyrics(item);
        updateLyric(position);
    }

    /**
     * 歌词懒加载：切到新歌（cid 变化）时异步取一次。
     *
     * <p>起播头一两秒 duration 还是 0，此时去网易云匹配只能取搜索结果第一条
     * （很容易搜到翻唱/Live 版），所以最多等 5 个刷新周期拿到时长再匹配。
     * B 站字幕本身不依赖时长，但也一并等——歌词不是关键路径，晚一两秒无所谓。
     */
    private void ensureLyrics(VideoCard item) {
        if (lyricLine == null || item == null || item.cid <= 0) return;
        if (item.cid == lyricCid) return;

        //缓存命中（预取过/播过）就不进「加载中」：切歌时歌词直接出现，不闪提示
        LyricProvider.Lyrics cached = LyricProvider.readCache(this, item.cid);
        if (cached != null && !cached.isEmpty()) {
            lyricWaitTicks = 0;
            lyricCid = item.cid;
            applyLyrics(cached);
            return;
        }

        int duration = AudioPlayerService.getDurationMs();
        if (duration <= 0 && lyricWaitTicks < 1) {
            //只等一个刷新周期拿到时长：完全不等会让网易云只能取搜索结果第一条，容易搜到翻唱
            lyricWaitTicks++;
            return;
        }
        lyricWaitTicks = 0;
        lyricCid = item.cid;
        lyrics = null;
        lastLyricIndex = -1;
        showLyricLoading();

        final long cid = item.cid;
        final VideoCard target = item;
        new Thread(() -> {
            LyricProvider.Lyrics loaded = LyricProvider.load(this, target, duration);
            handler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (cid != lyricCid) return;
                applyLyrics(loaded);
            });
        }).start();
    }

    /** 加载中：淡入一行灰字，避免歌词区突然出现/一片空白 */
    private void showLyricLoading() {
        if (lyricLine == null) return;
        lyricLine.animate().cancel();
        lyricLine.setText("歌词加载中…");
        lyricLine.setAlpha(0f);
        lyricLine.setVisibility(View.VISIBLE);
        lyricLine.animate().alpha(0.7f).setDuration(200).start();
    }

    /** 歌词到达：有内容淡入，没有就淡出收起；两种都带过渡，不硬切 */
    private void applyLyrics(LyricProvider.Lyrics loaded) {
        if (lyricLine == null) return;
        lyrics = loaded;
        lastLyricIndex = -1;
        lyricLine.animate().cancel();
        if (loaded == null || loaded.isEmpty()) {
            lyricLine.animate().alpha(0f).setDuration(160).withEndAction(() -> {
                if (lyricLine != null) lyricLine.setVisibility(View.GONE);
            }).start();
            return;
        }
        lyricLine.setAlpha(0f);
        lyricLine.setVisibility(View.VISIBLE);
        updateLyric(AudioPlayerService.getPositionMs());
        lyricLine.animate().alpha(1f).setDuration(240).start();
    }

    /** 显示「上一行 / 当前行（高亮）/ 下一行」，当前行不变时不重复 setText */
    private void updateLyric(int positionMs) {
        if (lyricLine == null || lyrics == null || lyrics.isEmpty()) return;
        List<LyricProvider.Line> lines = lyrics.lines;
        int index = findLyricLine(lines, positionMs);
        //还没到第一行就显示第一行：整块消失会让歌词区忽隐忽现
        if (index < 0) index = 0;
        if (index == lastLyricIndex) return;
        lastLyricIndex = index;

        int start = Math.max(0, index - 1);
        int end = Math.min(lines.size() - 1, index + 1);
        StringBuilder sb = new StringBuilder();
        int highlightStart = -1, highlightEnd = -1;
        for (int i = start; i <= end; i++) {
            if (i > start) sb.append('\n');
            if (i == index) {
                highlightStart = sb.length();
                sb.append(lines.get(i).text);
                highlightEnd = sb.length();
            } else {
                sb.append(lines.get(i).text);
            }
        }
        SpannableString span = new SpannableString(sb.toString());
        if (highlightStart >= 0) {
            span.setSpan(new ForegroundColorSpan(ThemeManager.getPrimary()),
                    highlightStart, highlightEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span.setSpan(new StyleSpan(android.graphics.Typeface.BOLD),
                    highlightStart, highlightEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        lyricLine.setText(span);
        lyricLine.setVisibility(View.VISIBLE);
        //换行时从下方轻微滑入：每行都硬切会很生硬；动画必须短，
        //否则歌词密集（一句两秒）时会糊成一片，反而看不清
        lyricLine.setTranslationY(dpToPx(6));
        lyricLine.animate().translationY(0f).setDuration(160).start();
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 二分找最后一个 timeMs <= position 的行；没有就是还没到第一行 */
    private static int findLyricLine(List<LyricProvider.Line> lines, int positionMs) {
        int low = 0, high = lines.size() - 1, result = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (lines.get(mid).timeMs <= positionMs) {
                result = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return result;
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(refreshRunnable);
        AudioPlayerService.removeListener(this);
        super.onDestroy();
    }

    private static String formatTime(int ms) {
        int totalSec = Math.max(ms, 0) / 1000;
        return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60);
    }
}
