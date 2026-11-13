package com.RobinNotBad.BiliClient.util;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.ui.theme.ImageSeeder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 自定义背景底图：管理「选图 → 压缩存入私有目录 → 各页面铺底 → 暗度调节」。
 *
 * <p>设计取舍：
 * <ul>
 *   <li><b>复制到私有目录</b>而不是记住相册 Uri。minSdk 19 用不了 ACTION_OPEN_DOCUMENT 的持久化授权，
 *       ACTION_GET_CONTENT 给的 Uri 在重启后随时可能失效；复制一份是唯一在老设备上稳定的做法。</li>
 *   <li><b>采样压缩后落盘</b>：背景图只需要屏幕分辨率级别，原图直接解码在老设备上极易 OOM，
 *       同时按屏幕长边采样后再存 JPEG，减少每次页面创建的解码开销。</li>
 *   <li><b>暗度用 ColorFilter</b>：比额外叠一层遮罩 View 更省一个 View 层级，
 *       也不会出现遮罩盖住内容层的问题。</li>
 * </ul>
 */
public class BackgroundManager {

    /** 开关：是否启用自定义背景底图 */
    public static final String KEY_ENABLE = "bg_image_enable";
    /** 私有目录内的背景图文件名 */
    public static final String KEY_PATH = "bg_image_path";
    /** 暗度：0-100，越大越暗 */
    public static final String KEY_DIM = "bg_image_dim";
    /**
     * 背景图主色（主题色种子）：导入时算一次落盘，冷启动零解码开销。
     * <p>0/缺省 = 没有可用颜色；是否已经算过看 {@link #KEY_SEED_DONE}。
     */
    public static final String KEY_SEED = "bg_image_seed";

    /**
     * 是否已经尝试过取色。
     *
     * <p>为什么不用「seed == 某个哨兵值」表示"算过但没取到"：种子是不透明 ARGB
     * （{@code 0xFFRRGGBB}），在 Java 里是负数，任何 int 哨兵都可能是一张图真实的主色
     * （例如纯白 {@code 0xFFFFFFFF} 恰好等于 -1）。用独立的布尔键才不会互相污染。
     */
    public static final String KEY_SEED_DONE = "bg_image_seed_done";

    /**
     * 已保存主色对应的取色算法版本。
     *
     * <p>算法改动（如 2026-11-14 从「量化簇心」换成「色相直方图」）后，旧值不再代表
     * 当前算法的主色，必须重算一次才能看到新结果。版本号对不上时，{@link #getSeed()}
     * 返回 0（先用默认配色），解码图片时后台重算，下一次启动生效。
     */
    public static final String KEY_SEED_VERSION = "bg_image_seed_ver";

    /** 当前取色算法版本；改动 {@code ImageSeeder} 的选色逻辑时 +1 */
    private static final int SEED_VERSION = 3;

    private static final String FILE_NAME = "custom_background.jpg";
    /** 背景图最长边上限（像素）：超过屏幕分辨率没有意义，只会白占内存与磁盘 */
    private static final int MAX_EDGE = 1600;
    private static final Object TAG = new Object();

    /** 进程内缓存：同一次运行内多个页面共用一张解码结果，避免每次 onCreate 都读盘解码 */
    private static Bitmap cached;
    private static String cachedPath;

    private BackgroundManager() {
    }

    public static boolean isEnabled() {
        return SharedPreferencesUtil.getBoolean(KEY_ENABLE, false);
    }

    /** 当前暗度（0-100，越界会被夹到范围内） */
    public static int getDim() {
        int dim = SharedPreferencesUtil.getInt(KEY_DIM, 30);
        return Math.max(0, Math.min(100, dim));
    }

    public static File backgroundFile(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    public static boolean hasImage(Context context) {
        return backgroundFile(context).isFile();
    }

    /**
     * 把用户选中的图片复制并压缩到私有目录。
     * <p>必须在后台线程调用（有 IO 与解码）。
     *
     * @return 是否成功
     */
    public static boolean importImage(Context context, Uri uri) {
        if (context == null || uri == null) return false;
        InputStream in = null;
        FileOutputStream out = null;
        try {
            //第一遍只读尺寸，用于算采样率
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            in = context.getContentResolver().openInputStream(uri);
            BitmapFactory.decodeStream(in, null, bounds);
            closeQuietly(in);
            in = null;
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = calcSampleSize(bounds.outWidth, bounds.outHeight, MAX_EDGE);
            opts.inPreferredConfig = Bitmap.Config.RGB_565;   //背景不做透明，RGB_565 省一半内存
            in = context.getContentResolver().openInputStream(uri);
            Bitmap bitmap = BitmapFactory.decodeStream(in, null, opts);
            if (bitmap == null) return false;

            File target = backgroundFile(context);
            out = new FileOutputStream(target);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out);
            out.flush();

            //主色提取顺手在这里做掉：位图已经在内存里，且本方法本来就在后台线程；
            //落盘后开屏与全站强调色都能零成本读到（见 ThemeManager 的有效种子解析）
            int seed = ImageSeeder.extractSeed(bitmap);
            bitmap.recycle();

            SharedPreferencesUtil.putString(KEY_PATH, target.getAbsolutePath());
            SharedPreferencesUtil.putInt(KEY_SEED, seed);
            SharedPreferencesUtil.putBoolean(KEY_SEED_DONE, true);
            SharedPreferencesUtil.putInt(KEY_SEED_VERSION, SEED_VERSION);
            //换了图，缓存必须失效
            resetCache();
            return true;
        } catch (Throwable e) {
            Logu.e("background", "导入背景图失败：" + e);
            return false;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    /** 删除已保存的背景图并关闭开关 */
    public static void clear(Context context) {
        File f = backgroundFile(context);
        if (f.isFile() && !f.delete()) Logu.e("background", "删除背景图失败");
        SharedPreferencesUtil.putString(KEY_PATH, "");
        SharedPreferencesUtil.putBoolean(KEY_ENABLE, false);
        //主色跟着图一起失效，否则删图后还会残留一个"来自背景图"的主题色
        SharedPreferencesUtil.removeValue(KEY_SEED);
        SharedPreferencesUtil.removeValue(KEY_SEED_DONE);
        SharedPreferencesUtil.removeValue(KEY_SEED_VERSION);
        resetCache();
    }

    /**
     * 背景图主色，供主题色当种子用。
     *
     * <p>只有「启用背景图 + 有图 + 取到了可用颜色」三者同时成立才返回非 0；
     * 其余情况调用方应回退默认主题色。所有读取都是 SharedPreferences（内存缓存），
     * 可以在主线程调用——开屏页的 AmbientBackground 每帧都可能问一次。
     *
     * @return 不透明 ARGB；0 表示不可用
     */
    public static int getSeed() {
        if (!isEnabled()) return 0;
        //算法升级后旧主色作废：先回退默认配色，等解码时重算，下一次启动换成新颜色
        if (SharedPreferencesUtil.getInt(KEY_SEED_VERSION, 0) != SEED_VERSION) return 0;
        int seed = SharedPreferencesUtil.getInt(KEY_SEED, 0);
        //注意不能写 seed > 0：不透明 ARGB 是 0xFFRRGGBB，在 Java 里恒为负数
        if (seed == 0) return 0;
        return 0xFF000000 | (seed & 0x00FFFFFF);
    }

    private static void resetCache() {
        synchronized (BackgroundManager.class) {
            cached = null;
            cachedPath = null;
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }

    /** 计算 inSampleSize：结果保证解码后的最长边 >= maxEdge 的最小 2 的幂 */
    private static int calcSampleSize(int width, int height, int maxEdge) {
        int sample = 1;
        int longest = Math.max(width, height);
        while (longest / sample > maxEdge) sample *= 2;
        return Math.max(1, sample);
    }

    /** 读取（并缓存）背景图；不可用或解码失败返回 null */
    private static Bitmap getBitmap(Context context) {
        File file = backgroundFile(context);
        if (!file.isFile()) return null;
        String path = file.getAbsolutePath();
        synchronized (BackgroundManager.class) {
            if (cached != null && !cached.isRecycled() && path.equals(cachedPath)) return cached;
            try {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = Bitmap.Config.RGB_565;
                cached = BitmapFactory.decodeFile(path, opts);
                cachedPath = path;
                backfillSeedIfNeeded(cached);
                return cached;
            } catch (Throwable e) {
                Logu.e("background", "解码背景图失败：" + e);
                cached = null;
                cachedPath = null;
                return null;
            }
        }
    }

    /**
     * 老版本导入的图没有主色记录：解码本来就在后台线程，顺手补算一次。
     *
     * <p>补算结果要下次启动才生效（本次进程里主题色已经渲染完，中途换色只会让
     * 光晕和文字色对不上，见 {@code ThemeManager.refreshSeed} 的说明）。
     * 失败也写哨兵值，避免每次解码都重算一遍注定失败的量化。
     */
    private static void backfillSeedIfNeeded(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return;
        //算过、且是当前算法算的，才跳过；旧版本存的颜色（哪怕当时算出来了）也要重算
        if (SharedPreferencesUtil.getBoolean(KEY_SEED_DONE, false)
                && SharedPreferencesUtil.getInt(KEY_SEED_VERSION, 0) == SEED_VERSION) return;
        int seed = ImageSeeder.extractSeed(bitmap);
        SharedPreferencesUtil.putInt(KEY_SEED, seed);
        SharedPreferencesUtil.putBoolean(KEY_SEED_DONE, true);
        SharedPreferencesUtil.putInt(KEY_SEED_VERSION, SEED_VERSION);
        Logu.d("background", "背景图主色计算完成：" + Integer.toHexString(seed));
    }

    /**
     * 装饰层第 0 层是否已经是本类铺的底图。
     *
     * <p>纯 tag 判断（无 IO、无 IPC）：给 {@code BaseActivity.installAmbient} 的快路径用——
     * 那一层每次 onStart 都要检查一次，不能再走 {@link #install} 里的 SP/文件/IPC。
     */
    public static boolean isInstalledOn(ViewGroup decor) {
        return decor != null && decor.getChildCount() > 0 && decor.getChildAt(0).getTag() == TAG;
    }

    /**
     * 在页面上铺底图。成功时返回 true，调用方应据此跳过自己的氛围背景层。
     *
     * @param decor Activity 的 DecorView；加到 index 0 才能垫在内容层之下
     */
    public static boolean install(Activity activity, ViewGroup decor) {
        if (activity == null || decor == null) return false;
        if (!isEnabled()) return false;
        //播放器/看图等全屏页保持干净：它们由 NoSwipe 主题标识，与氛围背景的排除口径一致
        if (isFullscreenPage(activity)) return false;
        //文件不存在就不铺，省掉一次注定失败的解码探测
        if (!hasImage(activity)) return false;

        //同一页面重复调用时不要叠多张（配置变更/切页都可能再走一次 onCreate）
        if (decor.getChildCount() > 0 && decor.getChildAt(0).getTag() == TAG) {
            ImageView existing = (ImageView) decor.getChildAt(0);
            applyDim(existing);
            if (existing.getDrawable() == null) loadInto(existing, activity);
            return true;
        }

        ImageView image = new ImageView(activity);
        image.setTag(TAG);
        //CENTER_CROP：圆屏与异形屏下用居中裁切，不拉伸变形
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        applyDim(image);
        decor.addView(image, 0, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        loadInto(image, activity);
        return true;
    }

    /**
     * 把背景图填进 ImageView。
     *
     * <p>解码放到后台线程：1600px 级别的 JPEG 解码在低配机/手表上有肉眼可见的卡顿，
     * 而 {@link #install} 是在 Activity.onCreate（主线程）里被调用的。
     * 代价是首次进入页面时背景会稍晚一点出现；命中进程内缓存时同步填，没有这个延迟。
     *
     * <p>用主线程 Handler 而不是 {@code View.post}：后者在视图尚未 attach 时会把任务压进
     * 一个待执行队列，若这个 View 始终没被 attach 就永远不会执行。
     */
    private static void loadInto(final ImageView image, Context context) {
        final Context appContext = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        Bitmap cachedBitmap = peekCache(appContext);
        if (cachedBitmap != null) {
            image.setImageBitmap(cachedBitmap);
            return;
        }
        CenterThreadPool.run(() -> {
            final Bitmap bitmap = getBitmap(appContext);
            if (bitmap == null) return;
            new Handler(Looper.getMainLooper()).post(() -> image.setImageBitmap(bitmap));
        });
    }

    /** 只看进程内缓存、不触发解码：用于判断能否同步填图 */
    private static Bitmap peekCache(Context context) {
        File file = backgroundFile(context);
        synchronized (BackgroundManager.class) {
            if (cached != null && !cached.isRecycled()
                    && file.getAbsolutePath().equals(cachedPath)) return cached;
        }
        return null;
    }

    /** 把暗度换算成黑色叠加强度。0 时清掉滤镜，让原图明亮显示 */
    private static void applyDim(ImageView image) {
        int dim = getDim();
        if (dim <= 0) {
            image.setColorFilter(null);
            return;
        }
        int alpha = dim * 255 / 100;
        image.setColorFilter(new PorterDuffColorFilter(Color.argb(alpha, 0, 0, 0),
                PorterDuff.Mode.SRC_OVER));
    }

    private static boolean isFullscreenPage(Activity activity) {
        try {
            android.content.pm.ActivityInfo info =
                    activity.getPackageManager().getActivityInfo(activity.getComponentName(), 0);
            return info.theme == R.style.Theme_NoSwipe || info.theme == R.style.Theme_NoSwipe_AppCompat;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 供设置页展示当前状态用 */
    public static String describeState(Context context) {
        if (!isEnabled()) return "未启用";
        return hasImage(context) ? "已设置底图，暗度 " + getDim() + "%" : "已启用但还没有选择图片";
    }
}
