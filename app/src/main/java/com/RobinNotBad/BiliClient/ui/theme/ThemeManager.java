package com.RobinNotBad.BiliClient.ui.theme;

import android.graphics.Color;

import com.RobinNotBad.BiliClient.util.BackgroundManager;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.google.android.material.color.utilities.Hct;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 主题配色中心：新版美学（{@code NEW_UI_DESIGN}）强调色的唯一数据源。
 *
 * <p>设计原则见 {@code docs/decisions-theme-color.md}，要点：
 * <ul>
 *   <li><b>只接管强调色</b>：黑色背景、卡片灰底、普通描边、正文/次级文字、B 站品牌蓝、状态色
 *       一律不经过这里，组件继续用原来的资源。</li>
 *   <li><b>默认主题走硬编码快路径</b>：种子是 {@link #DEFAULT_SEED} 时直接返回改造前的原始常量，
 *       不跑任何算法，保证默认主题下逐像素与原版一致（{@code #1AE75688} 并不是 {@code #FE679A}
 *       的 10% alpha，任何公式生成都会让高亮卡片底变样）。</li>
 *   <li><b>算法只在自定义色时启用</b>：只调用 {@code Hct}（{@code material:1.9.0} 已内含），
 *       不用 {@code Scheme}/{@code DynamicColor}——它们引用 {@code java.util.function}（API 24+），
 *       minSdk 19 下会崩。</li>
 *   <li><b>不做每帧计算</b>：派生结果在种子变化时算一次，之后只读字段。</li>
 *   <li><b>种子有两个来源</b>：用户手选（持久化在 {@link #KEY_SEED}）优先；从没手选过、
 *       且启用了自定义背景图时，用背景图主色（{@code BackgroundManager.getSeed()}）。
 *       两者都在 {@link #resolveEffectiveSeed()} 里收敛成一个"生效种子"。</li>
 * </ul>
 *
 * <p>线程模型：只在主线程读写（设置页点击 / View 创建期），派生开销在微秒级，不加锁。
 * 唯一要求调用方注意主线程的是 {@link #refreshSeed()}（会同步通知自绘组件重绘）。
 */
public final class ThemeManager {

    /** 持久化键：只存种子色，不存衍生色 */
    public static final String KEY_SEED = "theme_seed_color";

    /** 默认种子：B 站粉（新版美学的原始强调色） */
    public static final int DEFAULT_SEED = 0xFFFE679A;

    // ---- 默认主题的原始派生值：改造前的真实常量，必须逐字保持不变 ----
    private static final int DEFAULT_PRIMARY = 0xFFFE679A;
    /** 高亮卡片底：原 {@code @color/card_bg_highlight}（E75688 @10%），注意不是 FE679A @10% */
    private static final int DEFAULT_PRIMARY_SOFT = 0x1AE75688;
    /** 高亮卡片描边：原 {@code @color/bili_pink} */
    private static final int DEFAULT_PRIMARY_BORDER = 0xFFFE679A;
    /** 氛围光晕本色：原 {@code AmbientBackground.COLOR_PINK}，绘制时再套 0x59 alpha */
    private static final int DEFAULT_AMBIENT = 0xFFFE679A;

    /** 高亮卡片底的透明度，与原 {@code card_bg_highlight} 一致 */
    private static final int SOFT_ALPHA = 0x1A;

    /**
     * 黑底上的可读性下限：HCT tone 低于该值的种子会被提亮到 {@link #MIN_TONE}。
     * 依据见 decisions-theme-color.md「低明度种子自动提亮」。
     */
    private static final double MIN_TONE = 50d;
    private static final double MIN_TONE_LIFTED = 55d;

    private static boolean loaded = false;
    /** 用户手选的种子（持久化值）；等于 {@link #DEFAULT_SEED} 时可能是"没选过"，也可能是"手点了 B 站粉" */
    private static int seed = DEFAULT_SEED;
    /** 是否手选过配色：决定背景图主色能不能接管（手选优先） */
    private static boolean seedExplicit = false;
    /** 实际生效的种子：手选过用手选，否则在启用背景图且取到主色时用图主色 */
    private static int effectiveSeed = DEFAULT_SEED;
    private static int primary = DEFAULT_PRIMARY;
    private static int primarySoft = DEFAULT_PRIMARY_SOFT;
    private static int primaryBorder = DEFAULT_PRIMARY_BORDER;
    private static int ambient = DEFAULT_AMBIENT;

    /** 已创建组件的刷新回调；用弱引用，Activity 销毁后自动失效 */
    private static final List<WeakReference<OnThemeChangedListener>> LISTENERS = new ArrayList<>();

    private ThemeManager() {
    }

    /** 主题色变化回调：实现方在回调里重新读色并重绘（不要在这里做布局变更） */
    public interface OnThemeChangedListener {
        void onThemeChanged();
    }

    // ------------------------------------------------------------------ 读色

    /** 主强调色：强调文字、图标、按钮底、开关激活、徽章、下拉刷新圈 */
    public static int getPrimary() {
        ensureLoaded();
        return themeActive() ? primary : DEFAULT_PRIMARY;
    }

    /** 半透明高亮底：高亮卡片背景（10% alpha） */
    public static int getPrimarySoft() {
        ensureLoaded();
        return themeActive() ? primarySoft : DEFAULT_PRIMARY_SOFT;
    }

    /** 高亮描边：高亮卡片描边（不透明） */
    public static int getPrimaryBorder() {
        ensureLoaded();
        return themeActive() ? primaryBorder : DEFAULT_PRIMARY_BORDER;
    }

    /** 氛围背景本色（不透明），绘制时由调用方套 0x59 alpha，与改造前一致 */
    public static int getAmbient() {
        ensureLoaded();
        return themeActive() ? ambient : DEFAULT_AMBIENT;
    }

    /** 用户选择的种子色（不管新版美学是否开启，供设置页展示） */
    public static int getSeed() {
        ensureLoaded();
        return seed;
    }

    /**
     * 实际生效的种子色：用户手选优先；没手选过且启用了自定义背景图时，取背景图主色。
     * 与 {@link #getSeed()} 的差别只在这一种情况，其余完全相等。
     */
    public static int getEffectiveSeed() {
        ensureLoaded();
        return effectiveSeed;
    }

    /** 当前配色是否来自背景图主色（用于设置页文案与色块选中态） */
    public static boolean isSeedFromBackground() {
        ensureLoaded();
        return !seedExplicit && effectiveSeed != DEFAULT_SEED;
    }

    /**
     * 当前是否需要走动态配色：只有「新版美学设计」开启且种子不是默认色时才生效。
     *
     * <p>关掉新版美学（经典样式）后必须完全回到改造前的观感——经典样式本来就不属于
     * 新版强调色体系，此时所有取色接口一律返回原版常量。
     */
    public static boolean isDefault() {
        ensureLoaded();
        return !themeActive() || effectiveSeed == DEFAULT_SEED;
    }

    /** 新版美学总开关；关掉时一切强调色都保持原版（开关变更会重启进程，无需缓存失效） */
    private static boolean themeActive() {
        return SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.NEW_UI_DESIGN, true);
    }

    // ------------------------------------------------------------------ 写色

    /**
     * 设置种子色并立即派生生色。
     *
     * @param argb 完整 ARGB；alpha 会被强制为不透明（种子只描述色相/彩度/明度）
     * @return 是否被接受（非法值原样返回 false，不写盘）
     */
    public static boolean setSeed(int argb) {
        if (Color.alpha(argb) == 0) return false;   // 全透明视作非法，避免"设置后什么都没有"
        int opaque = 0xFF000000 | (argb & 0x00FFFFFF);
        seed = opaque;
        seedExplicit = true;
        resolveEffectiveSeed();
        derive();
        SharedPreferencesUtil.putInt(KEY_SEED, opaque);
        notifyListeners();
        return true;
    }

    /**
     * 恢复默认主题：删掉配置而不是写默认值，缺配置时与首次安装走同一条路径。
     *
     * <p>注意「默认」的含义：没手选配色的用户如果启用了自定义背景图，默认色就是背景图主色
     * （见 {@link #resolveEffectiveSeed()}）。想强制回到 B 站粉，需要点预设里的「B站粉」
     * ——那是显式手选，会写入配置挡住图色。
     */
    public static void resetToDefault() {
        seed = DEFAULT_SEED;
        seedExplicit = false;
        resolveEffectiveSeed();
        derive();
        SharedPreferencesUtil.removeValue(KEY_SEED);
        notifyListeners();
    }

    /**
     * 背景图主色或「背景图」开关变化后重新解析一次种子。
     *
     * <p><b>必须在主线程调用</b>：会同步通知已创建的自绘组件重建渐变与缓存图。
     * 手选过配色时本方法不产生任何变化（手选优先）。
     */
    public static void refreshSeed() {
        ensureLoaded();
        int old = effectiveSeed;
        resolveEffectiveSeed();
        if (old == effectiveSeed) return;
        derive();
        notifyListeners();
    }

    // ------------------------------------------------------------------ 派生

    /** 懒加载：SharedPreferences 在 Application.onCreate 前可能为 null，读不到就用默认值 */
    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        int stored = SharedPreferencesUtil.getInt(KEY_SEED, 0);
        // 缺配置（0）/ 全透明 / 越界都安全回退默认色
        if (stored == 0 || Color.alpha(stored) == 0) {
            seed = DEFAULT_SEED;
            seedExplicit = false;
        } else {
            seed = 0xFF000000 | (stored & 0x00FFFFFF);
            seedExplicit = true;
        }
        resolveEffectiveSeed();
        derive();
    }

    /**
     * 解析实际生效的种子：手选优先；没手选过（无配置）且背景图启用时，用背景图主色。
     *
     * <p>为什么手选优先：用户明确点过色块/输入过 HEX，就是"我要这个颜色"，
     * 此时被一张底图悄悄改掉是更坏的行为（且换图后整站变色，用户很难理解）。
     * 反过来没手选过的用户，默认值本来就是"没有偏好"，用底图主色更协调。
     */
    private static void resolveEffectiveSeed() {
        effectiveSeed = seed;
        if (seedExplicit) return;
        int fromImage = BackgroundManager.getSeed();
        if (fromImage != 0) effectiveSeed = fromImage;
    }

    /** 按种子算一套强调色。默认种子直接命中常量表，不跑算法 */
    private static void derive() {
        if (effectiveSeed == DEFAULT_SEED) {
            primary = DEFAULT_PRIMARY;
            primarySoft = DEFAULT_PRIMARY_SOFT;
            primaryBorder = DEFAULT_PRIMARY_BORDER;
            ambient = DEFAULT_AMBIENT;
            return;
        }
        double[] hct = toHct(effectiveSeed);
        double hue = hct[0];
        double chroma = hct[1];
        double tone = hct[2];
        if (tone < MIN_TONE) tone = MIN_TONE_LIFTED;

        primary = fromHct(hue, chroma, tone, effectiveSeed);
        primaryBorder = primary;
        primarySoft = (primary & 0x00FFFFFF) | (SOFT_ALPHA << 24);
        // 氛围光晕比强调色略亮一点：它铺满整屏还要压到 0x59 alpha，太暗会看不见；
        // 彩度略微收一点，避免大面积泛色过艳
        double ambientTone = Math.min(70d, tone + 2d);
        ambient = fromHct(hue, Math.max(chroma * 0.95d, 8d), ambientTone, primary);
    }

    /**
     * 取种子的 HCT 三分量。HCT 不可用时退回 HSV 近似（老设备兜底，绝不让配色把界面搞崩）。
     *
     * @return {hue, chroma, tone}
     */
    private static double[] toHct(int color) {
        try {
            Hct hct = Hct.fromInt(color);
            return new double[]{hct.getHue(), hct.getChroma(), hct.getTone()};
        } catch (Throwable t) {
            Logu.e("theme", "HCT 不可用，退回 HSV：" + t);
            float[] hsv = new float[3];
            Color.colorToHSV(color, hsv);
            // HSV 的 V/S 与 HCT 的 tone/chroma 量纲不同，这里只求"观感别跑偏"
            return new double[]{hsv[0], hsv[1] * 100d, hsv[2] * 100d};
        }
    }

    private static int fromHct(double hue, double chroma, double tone, int fallback) {
        try {
            return Hct.from(hue, chroma, tone).toInt() | 0xFF000000;
        } catch (Throwable t) {
            return fallback & 0xFFFFFF | 0xFF000000;
        }
    }

    // ------------------------------------------------------------------ 监听

    public static void addListener(OnThemeChangedListener listener) {
        if (listener == null) return;
        synchronized (LISTENERS) {
            LISTENERS.add(new WeakReference<>(listener));
        }
    }

    public static void removeListener(OnThemeChangedListener listener) {
        if (listener == null) return;
        synchronized (LISTENERS) {
            Iterator<WeakReference<OnThemeChangedListener>> it = LISTENERS.iterator();
            while (it.hasNext()) {
                OnThemeChangedListener l = it.next().get();
                if (l == null || l == listener) it.remove();
            }
        }
    }

    /**
     * 通知所有存活组件重绘。只在用户改色时触发（低频），不需要节流。
     * 单个监听抛异常不影响其他组件刷新。
     */
    private static void notifyListeners() {
        List<OnThemeChangedListener> alive = new ArrayList<>();
        synchronized (LISTENERS) {
            Iterator<WeakReference<OnThemeChangedListener>> it = LISTENERS.iterator();
            while (it.hasNext()) {
                OnThemeChangedListener l = it.next().get();
                if (l == null) it.remove();
                else alive.add(l);
            }
        }
        for (OnThemeChangedListener l : alive) {
            try {
                l.onThemeChanged();
            } catch (Throwable t) {
                Logu.e("theme", "刷新主题色失败：" + t);
            }
        }
    }
}
