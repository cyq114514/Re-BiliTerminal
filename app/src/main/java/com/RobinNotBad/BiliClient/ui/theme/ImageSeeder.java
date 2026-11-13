package com.RobinNotBad.BiliClient.ui.theme;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.android.material.color.utilities.Hct;
import com.google.android.material.color.utilities.QuantizerCelebi;
import com.google.android.material.color.utilities.Score;

import java.util.List;
import java.util.Map;

/**
 * 从图片里取一个"能当强调色用"的主色。
 *
 * <p>用途：用户启用自定义背景图后，把图的主色作为主题色种子（见
 * {@link com.RobinNotBad.BiliClient.util.BackgroundManager} 与 {@link ThemeManager}），
 * 让开屏光晕/强调色跟页面底图一致，而不是永远粉色。
 *
 * <p>算法演进（2026-11-14，用户两轮反馈"色系匹配度不够高"）：
 * <ol>
 *   <li><b>先用色相直方图取主色系</b>（当前主路径）：跳过近灰/近黑像素，按饱和度加权统计
 *       36 段色相，环形平滑后取峰值桶，再只在该桶 ±1（约 ±10°）范围内统计代表色。</li>
 *   <li><b>代表色在 HCT 空间统一解算</b>（第三、四轮）：色相取 sin/cos 圆平均、彩度不低于
 *       {@link #MIN_SEED_CHROMA}、明度取主色系内的第 {@link #SEED_TONE_PERCENTILE} 百分位。
 *       实测同一张暖调插画：RGB 直接平均是 {@code #A47D72}（H35 C17 T56，棕褐），
 *       换成这套之后是 {@code #C68A77}（H36 C26 T63，暖桃），与画面亮部一致。</li>
 *   <li><b>色相必须统一在 HCT 空间</b>：中途踩过一次坑——用 HSV 的圆平均色相值回填
 *       {@code Hct.from(hue,...)}，结果绿色 146° 被搬成 120°、天蓝 242° 变成 207°。
 *       HSV 与 HCT 的色相刻度不同，混合使用会让整张图的色系跑偏。</li>
 *   <li><b>量化器只作兜底</b>：直方图取不到时，才退回 {@code QuantizerCelebi + Score}。</li>
 * </ol>
 *
 * <p>为什么不再把量化器当主路径：它把整张图聚成 16 个色簇，<b>簇心是跨色相的加权平均</b>。
 * 图里有两个面积不小的不同色系时（绿+红、天蓝+草绿），簇心会落到色相环中间 —— 实测
 * 70% 绿 + 30% 红会得到橄榄色 {@code #7B733E}（色相差 43°），60% 天蓝 + 40% 草绿会得到
 * 青灰 {@code #769791}（色相差 57°）；而色相直方图在这两个用例上色相偏差都是 0°。
 *
 * <p>其余取舍：
 * <ul>
 *   <li><b>不引新依赖</b>：量化兜底复用 {@code material:1.9.0} 内置类。已用 javap 校验
 *       {@code QuantizerCelebi}/{@code Score}/{@code Hct} 及其依赖链的字节码均不引用
 *       {@code java.util.function}/{@code java.util.stream}，minSdk 19 可用。</li>
 *   <li><b>先降采样再统计</b>：1600px 底图直接遍历太贵；降到 112px 仍能表达主色分布。</li>
 *   <li><b>灰阶图回退默认粉</b>：有彩像素（饱和度 ≥ 0.18）占比低于 20% 时直接返回 0，
 *       不再"从灰底里抠一个彩色小点"当主题色（用户确认过这个行为）。</li>
 * </ul>
 */
public final class ImageSeeder {

    /** 参与统计的采样边长：够表达主色分布，又不至于让遍历变慢 */
    private static final int SAMPLE_EDGE = 112;

    /** 兜底量化保留的颜色数（Material You 默认 128，这里只要主色） */
    private static final int MAX_COLORS = 16;

    /** 色相直方图分段数：36 段 = 每段 10°，±1 段约 ±10° */
    private static final int HUE_BINS = 36;

    /** 低于该饱和度视为"近灰"，不参与色相统计 */
    private static final float MIN_SATURATION = 0.18f;

    /** 低于该明度视为"近黑"，不参与统计（黑色没有可用色相） */
    private static final float MIN_VALUE = 0.12f;

    /** 有彩像素占"非近黑像素"的最低比例：低于它判定为灰阶图，回退默认粉 */
    private static final float MIN_COLORFUL_RATIO = 0.20f;

    /** 兜底量化时的可接受彩度/明度（主路径不卡明度，明度由 ThemeManager 统一提亮） */
    private static final double MIN_CHROMA = 12d;
    private static final double MIN_TONE = 25d;
    private static final double MAX_TONE = 85d;

    /**
     * 主色落在"低饱和"区间时的去脏下限。
     *
     * <p>实测一张暖调插画（1280x1379）：峰值色相桶内 RGB 直接平均得到 `#A47D72`
     * （H35 C17 T56）——肉眼看是棕褐，和画面里的粉/米色系明显不符。原因有三：
     * RGB 平均把色相从桶内的 33° 拉到 35°（HSV 空间还会再偏到 13°）、彩度从 p90 的 24 压到 17、
     * 明度被大片阴影拉到 56（亮部的实际明度在 62 以上）。
     * 所以主色改成「HCT 圆平均色相 + 彩度下限 + 明度取亮部一档」。
     */
    private static final double MIN_SEED_CHROMA = 26d;

    /** 彩度上限：避免把一张高饱和图片放大成刺眼的强调色（默认品牌粉的 HCT 彩度约 57） */
    private static final double MAX_SEED_CHROMA = 60d;

    /**
     * 明度取主色系内的第 75 百分位，而不是平均值。
     *
     * <p>照片/插画里大片阴影会把平均明度拉低，取出来的颜色比画面观感暗一档（棕 vs 米）。
     * 取偏亮的一档更接近"人眼记住的那个颜色"，也保证深色底上的强调色足够可见。
     * 低于 {@code ThemeManager} 的提亮线时仍会被统一提亮，不会过暗。
     */
    private static final double SEED_TONE_PERCENTILE = 0.75d;

    /** 明度直方图的分段数（0~100，HCT tone 的取值范围） */
    private static final int TONE_BINS = 101;

    private ImageSeeder() {
    }

    /**
     * 取图片主色。
     *
     * <p>必须在后台线程调用（有一次缩放 + 统计，几十毫秒级）。
     *
     * @param source 已解码的位图，方法内不会 recycle 它
     * @return 不透明 ARGB；取不到可用颜色时返回 0
     */
    public static int extractSeed(Bitmap source) {
        if (source == null || source.isRecycled()) return 0;
        Bitmap sample = null;
        try {
            sample = scaleForSample(source);
            final int width = sample.getWidth();
            final int height = sample.getHeight();
            if (width <= 0 || height <= 0) return 0;

            int[] pixels = new int[width * height];
            sample.getPixels(pixels, 0, width, 0, 0, width, height);
            return seedFromPixels(pixels);
        } catch (Throwable t) {
            //取色失败只是没有主题色可跟，绝不能连累导入背景图本身
            return 0;
        } finally {
            //createScaledBitmap 在尺寸没变时可能返回入参本身，不能误回收调用方的位图
            if (sample != null && sample != source && !sample.isRecycled()) {
                sample.recycle();
            }
        }
    }

    /**
     * 从 ARGB 像素数组里取主色。
     *
     * <p>单独抽出来是为了能脱离 {@code Bitmap}/Android 运行时直接验证（HSV/HCT 与量化都是纯 Java）。
     *
     * @return 不透明 ARGB；取不到可用颜色（含灰阶图）时返回 0
     */
    static int seedFromPixels(int[] pixels) {
        if (pixels == null || pixels.length == 0) return 0;
        try {
            HueStats stats = collectHueStats(pixels);
            //灰阶图：不让"灰底里的一小块彩色"决定整个主题色，直接回退默认粉
            if (stats.isGrayDominant()) return 0;

            int dominant = pickDominantHue(pixels, stats);
            if (dominant != 0) return opaque(dominant);

            //直方图没取到（极端情况）才走量化兜底
            return quantizedFallback(pixels);
        } catch (Throwable t) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ 主路径：色相直方图

    /** 色相统计结果：平滑前的直方图权重 + 用于判定灰阶图的两个计数 */
    private static final class HueStats {
        final double[] weight = new double[HUE_BINS];
        /** 非近黑像素数 */
        int considered;
        /** 其中饱和度达标（有彩）的像素数 */
        int colorful;

        boolean isGrayDominant() {
            return considered == 0 || colorful < considered * MIN_COLORFUL_RATIO;
        }
    }

    private static HueStats collectHueStats(int[] pixels) {
        HueStats stats = new HueStats();
        float[] hsv = new float[3];
        for (int color : pixels) {
            toHsv(color, hsv);
            if (hsv[2] < MIN_VALUE) continue;      // 近黑：没有可用色相
            stats.considered++;
            if (hsv[1] < MIN_SATURATION) continue; // 近灰
            stats.colorful++;
            int bin = hueBin(hsv[0]);
            stats.weight[bin] += hsv[1];
        }
        return stats;
    }

    /**
     * 取峰值色相桶，并在该桶 ±1 范围内解出一个"色系保真"的代表色。
     *
     * <p>两个要点：
     * <ol>
     *   <li><b>只在赢家桶内统计</b>：跨桶平均会把绿和红揉成橄榄色，是上一版色系跑偏的根因；</li>
     *   <li><b>色相用圆平均（sin/cos），不用 RGB 平均</b>：实测一张暖调插画，RGB 平均把真实
     *       色相 13° 拉到 35°（粉变成棕），彩度也从 p90 的 24 掉到 17。圆平均保住色相，
     *       彩度再套一个去脏下限，得到的就是画面里那个色系。</li>
     * </ol>
     */
    private static int pickDominantHue(int[] pixels, HueStats stats) {
        int peak = -1;
        double bestWeight = 0;
        for (int i = 0; i < HUE_BINS; i++) {
            //环形平滑：避免同一色系被桶边界切成两半、谁也过不了阈值
            double smooth = stats.weight[(i + HUE_BINS - 1) % HUE_BINS] * 0.5
                    + stats.weight[i]
                    + stats.weight[(i + 1) % HUE_BINS] * 0.5;
            if (smooth > bestWeight) {
                bestWeight = smooth;
                peak = i;
            }
        }
        if (peak < 0 || bestWeight <= 0) return 0;

        double sumR = 0, sumG = 0, sumB = 0, sumWeight = 0;
        double sinSum = 0, cosSum = 0, chromaSum = 0, hctWeight = 0;
        double[] toneHistogram = new double[TONE_BINS];
        float[] hsv = new float[3];
        for (int color : pixels) {
            toHsv(color, hsv);
            if (hsv[2] < MIN_VALUE || hsv[1] < MIN_SATURATION) continue;
            if (hueBinDistance(hueBin(hsv[0]), peak) > 1) continue;

            double weight = hsv[1];
            sumR += ((color >> 16) & 0xFF) * weight;
            sumG += ((color >> 8) & 0xFF) * weight;
            sumB += (color & 0xFF) * weight;
            sumWeight += weight;

            //色相/彩度/明度必须统一在 HCT 空间：HSV 的 120°（绿）不是 HCT 的 120°，混用会把整张图的色系搬走
            try {
                Hct hct = Hct.fromInt(opaque(color));
                double radian = Math.toRadians(hct.getHue());
                sinSum += Math.sin(radian) * weight;
                cosSum += Math.cos(radian) * weight;
                chromaSum += hct.getChroma() * weight;
                hctWeight += weight;

                int toneIndex = (int) Math.round(hct.getTone());
                if (toneIndex < 0) toneIndex = 0;
                if (toneIndex >= TONE_BINS) toneIndex = TONE_BINS - 1;
                toneHistogram[toneIndex] += weight;
            } catch (Throwable ignored) {
                //单像素转换失败不影响整体，最后按有没有样本决定走哪条路
            }
        }
        if (sumWeight <= 0) return 0;

        int r = clampChannel(Math.round(sumR / sumWeight));
        int g = clampChannel(Math.round(sumG / sumWeight));
        int b = clampChannel(Math.round(sumB / sumWeight));
        int averaged = (r << 16) | (g << 8) | b;
        if (hctWeight <= 0) return averaged;   //HCT 全不可用的老设备：退回 RGB 平均

        double hue = Math.toDegrees(Math.atan2(sinSum, cosSum));
        if (hue < 0) hue += 360;
        double chroma = Math.min(Math.max(chromaSum / hctWeight, MIN_SEED_CHROMA), MAX_SEED_CHROMA);
        double tone = tonePercentile(toneHistogram, hctWeight, SEED_TONE_PERCENTILE);
        try {
            return Hct.from(hue, chroma, tone).toInt() | 0xFF000000;
        } catch (Throwable t) {
            return averaged;
        }
    }

    /**
     * 按权重取明度分位（默认 p75）。
     *
     * <p>用分位而不是平均：一张图里大片阴影会把平均明度压低一档，取出来就是"比画面暗"的棕/灰。
     *
     * @param histogram 明度直方图（下标 = tone 0~100）
     * @param total     权重总和
     * @param percentile 0~1
     * @return 该分位对应的 tone
     */
    private static double tonePercentile(double[] histogram, double total, double percentile) {
        if (total <= 0) return 0;
        double target = total * percentile;
        double accumulated = 0;
        for (int tone = 0; tone < histogram.length; tone++) {
            accumulated += histogram[tone];
            if (accumulated >= target) return tone;
        }
        return histogram.length - 1;
    }

    private static int hueBin(float hue) {
        int bin = (int) (hue / 360f * HUE_BINS);
        return Math.max(0, Math.min(HUE_BINS - 1, bin));
    }

    /** 环形距离：色相环首尾相邻，0 段与 35 段只差 10° */
    private static int hueBinDistance(int a, int b) {
        int d = Math.abs(a - b);
        return Math.min(d, HUE_BINS - d);
    }

    // ------------------------------------------------------------------ 兜底：量化取色

    /**
     * 上一版的量化路径，仅在直方图取不到色时使用。
     *
     * <p>{@code Score} 在没有颜色通过筛选时会返回内置默认蓝 {@code #4285F4}，必须用
     * {@code quantized.containsKey} 过滤掉，否则灰阶图会拿到一个不属于图片的蓝色。
     */
    private static int quantizedFallback(int[] pixels) {
        Map<Integer, Integer> quantized = QuantizerCelebi.quantize(pixels, MAX_COLORS);
        if (quantized == null || quantized.isEmpty()) return 0;

        int picked = pickFromScore(Score.score(quantized), quantized);
        if (picked == 0) picked = pickByCount(quantized);
        return picked == 0 ? 0 : opaque(picked);
    }

    private static int pickFromScore(List<Integer> scored, Map<Integer, Integer> quantized) {
        if (scored == null) return 0;
        for (Integer color : scored) {
            if (color == null || !quantized.containsKey(color)) continue;
            if (isUsable(color)) return color;
        }
        return 0;
    }

    private static int pickByCount(Map<Integer, Integer> quantized) {
        int best = 0;
        int bestCount = -1;
        for (Map.Entry<Integer, Integer> entry : quantized.entrySet()) {
            Integer color = entry.getKey();
            Integer count = entry.getValue();
            if (color == null || count == null) continue;
            if (!isUsable(color)) continue;
            if (count > bestCount) {
                bestCount = count;
                best = color;
            }
        }
        return best;
    }

    /** 量化兜底的彩度/明度门槛；HCT 不可用时退回 HSV 近似 */
    private static boolean isUsable(int color) {
        int opaque = opaque(color);
        try {
            Hct hct = Hct.fromInt(opaque);
            double tone = hct.getTone();
            return hct.getChroma() >= MIN_CHROMA && tone >= MIN_TONE && tone <= MAX_TONE;
        } catch (Throwable t) {
            try {
                float[] hsv = new float[3];
                Color.colorToHSV(opaque, hsv);
                return hsv[1] * 100d >= MIN_CHROMA
                        && hsv[2] * 100d >= MIN_TONE
                        && hsv[2] * 100d <= MAX_TONE;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 缩到 {@link #SAMPLE_EDGE} 以内；原图本来就小就原样使用（避免无意义的放大） */
    private static Bitmap scaleForSample(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int longest = Math.max(width, height);
        if (longest <= SAMPLE_EDGE) return source;

        float ratio = SAMPLE_EDGE / (float) longest;
        int targetWidth = Math.max(1, Math.round(width * ratio));
        int targetHeight = Math.max(1, Math.round(height * ratio));
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true);
    }

    /** ARGB → HSV（H 0~360，S/V 0~1），不依赖 android.graphics 的静态方法，便于纯 JVM 验证 */
    private static void toHsv(int color, float[] out) {
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;

        float hue = 0;
        if (delta > 0) {
            if (max == r) hue = 60f * (((g - b) / delta) % 6f);
            else if (max == g) hue = 60f * (((b - r) / delta) + 2f);
            else hue = 60f * (((r - g) / delta) + 4f);
        }
        if (hue < 0) hue += 360f;

        out[0] = hue;
        out[1] = max <= 0 ? 0 : delta / max;
        out[2] = max;
    }

    private static int opaque(int color) {
        return 0xFF000000 | (color & 0x00FFFFFF);
    }

    private static int clampChannel(long value) {
        return (int) Math.max(0, Math.min(255, value));
    }
}
