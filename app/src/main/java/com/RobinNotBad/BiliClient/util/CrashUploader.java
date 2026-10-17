package com.RobinNotBad.BiliClient.util;

import android.content.Context;
import android.os.Build;

import com.RobinNotBad.BiliClient.BuildConfig;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 崩溃上报：把崩溃现场写到本地文件，并尽力发送到自建收集服务（Supabase）。
 *
 * <p>设计前提（实测得出）：部分网络环境下该域名会被重置（WiFi 下截断、手机流量下正常），
 * 所以**上报失败是常态而不是异常**。因此每次崩溃都先落本地文件，再尝试上传；
 * 上传失败进入待发队列，下次冷启动补发，最多保留 {@link #MAX_PENDING} 条。
 *
 * <p>采集范围刻意收窄：机型、系统版本、应用版本、随机安装标识、堆栈、线程名、崩溃页面。
 * <b>不采集</b> IMEI / OAID / AndroidID / 账号信息（mid、Cookie）。
 */
public final class CrashUploader {

    /** 自建收集服务（Supabase 表 crash_reports）。publishable key 只能插入、读不到数据。 */
    private static final String ENDPOINT =
            "https://yiplvonquoxpfrcayozg.supabase.co/rest/v1/crash_reports";
    private static final String API_KEY = "sb_publishable_c4ZISmj36Nn0stegNoAszA_SlWp-3Xr";

    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    /** 单次上报超时：崩溃路径上不能等太久 */
    private static final long TIMEOUT_SECONDS = 8;

    /** 待发队列上限：超过就丢最旧的，避免长期失败把存储吃满 */
    private static final int MAX_PENDING = 5;

    /** 本地崩溃记录文件保留份数 */
    private static final int MAX_LOCAL_FILES = 20;

    /**
     * 单条待发记录的最大重试次数。
     * 队列里如果有一条被服务端永久拒绝（表约束不匹配、字段超长等 4xx），
     * 旧实现"首条失败就 break"会让后面所有记录永远发不出去，所以改成
     * 「跳过继续 + 计次 + 到上限丢弃并留痕」。
     */
    private static final int MAX_RETRY = 3;

    /** 安装标识键：随机 UUID，卸载重装才会变，不含任何账号信息 */
    private static final String KEY_DEVICE_ID = "crash_report_device_id";

    private static final int MAX_MESSAGE = 500;
    private static final int MAX_STACK = 32000;

    /** 崩溃时所在的页面（类名），由 Application 的 Activity 回调维护 */
    private static volatile String currentPage = "";

    /** 最近一次上传的 HTTP 状态码：-1 表示没连上（断网/DNS/被重置），由崩溃页展示便于定性 */
    private static volatile int lastHttpCode = -1;

    private static OkHttpClient client;

    private CrashUploader() {
    }

    public static void setCurrentPage(String page) {
        currentPage = page == null ? "" : page;
    }

    /** 组装上报体。所有字段与 crash_reports 表的列一一对应。 */
    public static JSONObject buildReport(Context context, Throwable throwable,
                                         String threadName, String stackText) {
        JSONObject report = new JSONObject();
        try {
            report.put("install_id", deviceId(context));
            report.put("app_version", BuildConfig.VERSION_NAME);
            report.put("android_version", Build.VERSION.RELEASE == null ? "" : Build.VERSION.RELEASE);
            report.put("sdk_int", Build.VERSION.SDK_INT);
            report.put("device_model", Build.MODEL == null ? "" : Build.MODEL);
            report.put("crash_type", "java");
            report.put("exception_class", throwable.getClass().getName());
            report.put("message", cut(throwable.getMessage(), MAX_MESSAGE));
            report.put("stacktrace", cut(stackText, MAX_STACK));
            report.put("thread_name", threadName == null ? "" : threadName);
            report.put("page", currentPage);
        } catch (Throwable ignored) {
        }
        return report;
    }

    /**
     * 把报告写成本地文本文件，返回路径；落点不可用时返回空串。
     * 与调试日志同目录，用户用文件管理器即可取出。
     */
    public static String saveLocal(JSONObject report) {
        File dir = localDir();
        if (dir == null) return "";
        try {
            if (!dir.exists() && !dir.mkdirs()) return "";
            String name = "crash-"
                    + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(new Date())
                    + ".txt";
            File file = new File(dir, name);
            writeFile(file, report.toString(2));
            cleanupLocal(dir);
            return file.getAbsolutePath();
        } catch (Throwable t) {
            CrashTrace.log("上报: 本地文件写入失败 " + t);
            return "";
        }
    }

    /** 只保留最近 20 份崩溃记录，避免长期使用把目录堆满 */
    private static void cleanupLocal(File dir) {
        try {
            File[] files = dir.listFiles((d, name) -> name.startsWith("crash-") && name.endsWith(".txt"));
            if (files == null || files.length <= MAX_LOCAL_FILES) return;
            sortByTime(files);
            for (int i = 0; i < files.length - MAX_LOCAL_FILES; i++) {
                //noinspection ResultOfMethodCallIgnored
                files[i].delete();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 先入队再上传：即使进程在请求途中被杀，记录也已经落在待发队列里，下次启动补发。
     *
     * @return 待发队列里的那个文件；入队失败返回 null
     */
    public static File enqueue(Context context, JSONObject report) {
        return savePending(context, report);
    }

    /**
     * 尝试上传（调用方必须是后台线程）。成功则删掉对应的待发文件。
     */
    public static boolean tryUpload(JSONObject report, File pending) {
        if (upload(report)) {
            CrashTrace.log("上报: 成功");
            //同时打一行 logcat：CrashTrace 只写偏好，连电脑排查时看 logcat 更快
            Logu.e("crash", "上报成功");
            if (pending != null && pending.exists()) {
                //noinspection ResultOfMethodCallIgnored
                pending.delete();
            }
            return true;
        }
        CrashTrace.log("上报: 失败，保留在待发队列");
        Logu.e("crash", "上报失败，已入待发队列");
        return false;
    }

    /** 补发历史失败记录（后台线程调用）。 */
    public static void flushPending(Context context) {
        File dir = pendingDir(context);
        if (dir == null) return;
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null || files.length == 0) return;
        sortByTime(files);
        for (File file : files) {
            JSONObject report = readJson(file);
            if (report == null) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                continue;
            }
            if (upload(report)) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                clearFail(file);
                CrashTrace.log("上报: 补发成功 " + file.getName());
                Logu.e("crash", "补发成功 " + file.getName());
                continue;
            }
            //旧实现"首条失败就 break"：一旦队首那条被服务端永久拒绝，后面所有记录永远发不出去。
            //现在改为逐条计次，连续失败到 MAX_RETRY 次就丢弃并留痕，保证队列能继续往前走
            int fails = bumpFail(file);
            if (fails >= MAX_RETRY) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                clearFail(file);
                CrashTrace.log("上报: 放弃 " + file.getName() + "（连续失败 " + fails + " 次，HTTP " + lastHttpCode + "）");
                Logu.e("crash", "放弃一条记录，HTTP " + lastHttpCode);
            } else {
                CrashTrace.log("上报: 补发失败 " + file.getName() + "（第 " + fails + " 次，HTTP " + lastHttpCode + "）");
                Logu.e("crash", "补发失败，第 " + fails + " 次");
            }
        }
    }

    /** 最近一次上传的 HTTP 状态码；-1 表示请求根本没连上（断网 / DNS / 被重置） */
    public static int lastHttpCode() {
        return lastHttpCode;
    }

    /** 待发队列里还有多少条（崩溃页展示与「重新上报」用） */
    public static int pendingCount(Context context) {
        File dir = pendingDir(context);
        if (dir == null) return 0;
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        return files == null ? 0 : files.length;
    }

    /**
     * 组装非 Java 崩溃类的事件（原生崩溃 / ANR / 被系统杀 / 活跃统计）。
     * 字段与 crash_reports 表一一对应，{@code exception_class} 用来放细分原因。
     */
    public static JSONObject buildEvent(Context context, String crashType, String exceptionClass,
                                        String message, String threadName, String page, String detail) {
        JSONObject report = new JSONObject();
        try {
            report.put("install_id", deviceId(context));
            report.put("app_version", BuildConfig.VERSION_NAME);
            report.put("android_version", Build.VERSION.RELEASE == null ? "" : Build.VERSION.RELEASE);
            report.put("sdk_int", Build.VERSION.SDK_INT);
            report.put("device_model", Build.MODEL == null ? "" : Build.MODEL);
            report.put("crash_type", crashType == null ? "" : crashType);
            report.put("exception_class", cut(exceptionClass, MAX_MESSAGE));
            report.put("message", cut(message, MAX_MESSAGE));
            report.put("stacktrace", cut(detail, MAX_STACK));
            report.put("thread_name", threadName == null ? "" : threadName);
            report.put("page", page == null ? "" : page);
        } catch (Throwable ignored) {
        }
        return report;
    }

    /**
     * 通用投递：入队 → 尽力上传（调用方必须是后台线程）。
     * 与 Java 崩溃的区别只是没有崩溃页要弹，所以不需要 join 等 1.5 秒。
     */
    public static boolean submit(Context context, JSONObject report) {
        File pending = enqueue(context, report);
        if (upload(report)) {
            if (pending != null && pending.exists()) {
                //noinspection ResultOfMethodCallIgnored
                pending.delete();
            }
            return true;
        }
        return false;
    }

    /**
     * 启动最早期（Application.onCreate 之前、ErrorCatch 尚未安装）的崩溃：
     * 那一刻读不到任何配置，只能把原始堆栈落到 files 下，下次冷启动再转成正式上报。
     * 这条路径<b>不受上报开关约束</b>——写本地永远允许，传不传由下一次启动时的开关决定。
     */
    public static void saveEarly(Context context, String stack) {
        try {
            File dir = new File(context.getFilesDir(), "crash_early");
            if (!dir.exists() && !dir.mkdirs()) return;
            File target = new File(dir, System.currentTimeMillis() + ".txt");
            writeFile(target, stack == null ? "" : stack);
        } catch (Throwable ignored) {
        }
    }

    /** 把上次启动的早期崩溃文件转成正式上报（冷启动后台线程调用）。 */
    public static void flushEarly(Context context) {
        File dir = new File(context.getFilesDir(), "crash_early");
        File[] files = dir.listFiles((d, name) -> name.endsWith(".txt"));
        if (files == null || files.length == 0) return;
        sortByTime(files);
        for (File file : files) {
            String stack = readText(file);
            JSONObject report = buildEvent(context, "java-early", "EarlyStartupCrash",
                    "Application.onCreate 之前崩溃（ErrorCatch 尚未安装）", "main", "", stack);
            //本地留档 + （授权时）上报，最后才删源文件：宁可重复也不要丢
            saveLocal(report);
            if (CrashReportUtil.isEnabled()) submit(context, report);
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            CrashTrace.log("上报: 早期崩溃已转报 " + file.getName());
        }
    }

    // ---------------------------------------------------------------- 内部实现

    private static boolean upload(JSONObject report) {
        try {
            lastHttpCode = -1;
            Request request = new Request.Builder()
                    .url(ENDPOINT)
                    .addHeader("apikey", API_KEY)
                    .addHeader("Authorization", "Bearer " + API_KEY)
                    .addHeader("Prefer", "return=minimal")
                    .post(RequestBody.create(JSON_TYPE, report.toString()))
                    .build();
            try (Response response = client().newCall(request).execute()) {
                lastHttpCode = response.code();
                if (!response.isSuccessful()) {
                    //以前只知道"失败"，分不清断网和有响应被拒：4xx 是服务端拒绝（字段/表约束），
                    //重试再多次也没用；5xx/超时才是网络问题。状态码必须留痕
                    CrashTrace.log("上报: HTTP " + response.code() + " " + cut(response.message(), 60));
                    Logu.e("crash", "上报失败 HTTP " + response.code());
                }
                return response.isSuccessful();
            }
        } catch (Throwable t) {
            CrashTrace.log("上报: 请求异常 " + t.getClass().getSimpleName());
            return false;
        }
    }

    /** 失败计数的旁挂文件：与待发 json 同目录、同名加 .fail 后缀（不会被 .json 过滤器扫到） */
    private static File failFile(File pending) {
        return new File(pending.getParentFile(), pending.getName() + ".fail");
    }

    private static int bumpFail(File pending) {
        int fails = 1;
        try {
            File f = failFile(pending);
            if (f.exists()) {
                String text = readText(f);
                int stored = 0;
                try {
                    stored = text == null ? 0 : Integer.parseInt(text.trim());
                } catch (NumberFormatException ignored) {
                }
                fails = stored + 1;
            }
            writeFile(f, String.valueOf(fails));
        } catch (Throwable ignored) {
        }
        return fails;
    }

    private static void clearFail(File pending) {
        try {
            File f = failFile(pending);
            if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private static synchronized OkHttpClient client() {
        if (client == null) {
            client = new OkHttpClient.Builder()
                    .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    //失败就是失败，不在崩溃路径上自动重试
                    .retryOnConnectionFailure(false)
                    .build();
        }
        return client;
    }

    /** 与调试日志同目录：Download/ReBiliDiag → Android/media/<包名> → Android/data/<包名>/files */
    private static File localDir() {
        try {
            String diag = ProgressDiag.path();
            if (diag == null || diag.isEmpty()) return null;
            //ProgressDiag.path() 返回的是文件路径，不是目录
            return new File(diag).getParentFile();
        } catch (Throwable t) {
            return null;
        }
    }

    private static File pendingDir(Context context) {
        try {
            File dir = new File(context.getFilesDir(), "crash_pending");
            if (!dir.exists() && !dir.mkdirs()) return null;
            return dir;
        } catch (Throwable t) {
            return null;
        }
    }

    private static File savePending(Context context, JSONObject report) {
        File dir = pendingDir(context);
        if (dir == null) return null;
        try {
            File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
            if (files != null && files.length >= MAX_PENDING) {
                sortByTime(files);
                List<File> extras = new ArrayList<>(Arrays.asList(files));
                for (int i = 0; i <= files.length - MAX_PENDING; i++) {
                    //noinspection ResultOfMethodCallIgnored
                    extras.get(i).delete();
                }
            }
            File target = new File(dir, System.currentTimeMillis() + ".json");
            writeFile(target, report.toString());
            return target;
        } catch (Throwable t) {
            CrashTrace.log("上报: 入队失败 " + t.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 按修改时间升序。
     * 不用 {@code Comparator.comparingLong}：它是 API 24 才有的库 API，
     * 本项目 minSdk 19 且未开启 core library desugaring，低版本设备上会 NoSuchMethodError。
     */
    private static void sortByTime(File[] files) {
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
    }

    private static JSONObject readJson(File file) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) builder.append(line);
            return new JSONObject(builder.toString());
        } catch (Throwable t) {
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 读纯文本（早期崩溃堆栈、失败计数旁挂文件用）。失败返回 null。 */
    private static String readText(File file) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) builder.append(line).append('\n');
            return builder.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void writeFile(File file, String content) throws Exception {
        FileOutputStream stream = new FileOutputStream(file);
        Writer writer = new OutputStreamWriter(stream, StandardCharsets.UTF_8);
        try {
            writer.write(content);
            writer.flush();
        } finally {
            writer.close();
        }
    }

    private static String cut(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** 首次调用生成并保存安装标识，之后稳定复用。 */
    private static String deviceId(Context context) {
        String cached = SharedPreferencesUtil.getString(KEY_DEVICE_ID, null);
        if (cached != null && !cached.isEmpty()) return cached;
        String generated = java.util.UUID.randomUUID().toString().replace("-", "");
        SharedPreferencesUtil.putString(KEY_DEVICE_ID, generated);
        return generated;
    }
}
