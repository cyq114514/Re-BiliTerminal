package com.RobinNotBad.BiliClient.util;

import com.RobinNotBad.BiliClient.BuildConfig;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 崩溃上报的客户端过滤：只把「重要且可行动」的崩溃传到收集端。
 *
 * <p>背景：后台 50 条 1.2.2 记录里，约 90% 是系统内存回收（lmk/freezer）、实验室主动触发的
 * 测试崩溃、播放哨兵的后台回收误报，以及没有一帧本仓库代码的系统异常——真正的代码缺陷
 * 反而被淹没了。这里在<b>入队之前</b>做一次统一裁决：
 *
 * <ol>
 *   <li>系统压力类（lmk / freezer / resource）完全不上传；</li>
 *   <li>实验室「主动触发崩溃」不上传；</li>
 *   <li>播放哨兵只在「连续异常 ≥2 次」或「最后阶段属于 native 高危区」时才传；</li>
 *   <li>Java 崩溃的堆栈里没有本仓库代码帧（含随仓库编译的 ijk / DFM / brotli），
 *       视为系统或环境异常，丢弃；</li>
 *   <li>24 小时内同一指纹只上传一条，期间重复发生只累加计数（写进 message 后缀，
 *       不新增数据库字段——服务端表有约束，加列会被 4xx 拒收）。</li>
 * </ol>
 *
 * <p><b>本地留档不受影响</b>：调用方在过滤之前就已经 {@code saveLocal}，用户随时能导出；
 * 这里只决定「上不上传」。
 */
public final class CrashFilter {

    /** 裁决结果：是否上传 + 原因（原因只进日志与本地统计，不随请求发送）。 */
    public static final class Decision {
        public final boolean upload;
        public final String reason;

        Decision(boolean upload, String reason) {
            this.upload = upload;
            this.reason = reason;
        }
    }

    /** 播放哨兵合成的异常类名：它不是真异常，判定规则和其它 Java 崩溃不同 */
    public static final String SENTINEL_CLASS =
            "com.RobinNotBad.BiliClient.util.PlayerSentinel$AbnormalExit";

    /** 本仓库代码的包前缀（子模块 dart 编译进 APK 也算「我们的代码」） */
    private static final String[] OUR_PACKAGES = {
            "com.RobinNotBad.BiliClient",
            "tv.danmaku.ijk.media",
            "master.flame.danmaku",
            "com.netease.hearttouch.brotlij",
    };

    /**
     * native 崩溃高危阶段：只保留"播放还没真正开始"的那几步。
     *
     * <p>不能把「首帧就绪」算进去——后台看几小时被系统回收的设备，最后阶段也都是首帧就绪，
     * 实测 20 条哨兵里 17 条是这个，正是噪音主体。
     */
    private static final String[] NATIVE_RISK_STAGES = {
            "最后阶段=加载ijk库",
            "最后阶段=配置显示",
            "最后阶段=设置数据源",
            "最后阶段=已提交准备",
    };

    /** 短会话阈值：不到两分钟就异常收尾的播放，比"看了 7 小时被回收"更可能是真崩 */
    private static final long SHORT_SESSION_SECONDS = 120;

    /** 指纹表：fingerprint -> {"t": 上次上传时间, "n": 累计发生次数} */
    private static final String KEY_INDEX = "crash_fp_index";
    private static final long DEDUP_WINDOW_MS = 24L * 60 * 60 * 1000;
    private static final int MAX_INDEX_ENTRIES = 40;

    private CrashFilter() {
    }

    /**
     * 判定一条报告要不要上传。
     *
     * <p>可上报且是同指纹的重复发生（窗口已过）时，会往 {@code message} 末尾追加
     * 「本机累计第 N 次」，便于在收集端看出频次。
     */
    public static synchronized Decision decide(JSONObject report) {
        if (report == null) return new Decision(false, "null-report");

        final String type = report.optString("crash_type", "");
        final String cls = report.optString("exception_class", "");
        final String message = report.optString("message", "");
        final String stack = report.optString("stacktrace", "");
        final String page = report.optString("page", "");

        // 1) 系统内存压力：不是代码缺陷，完全不上传（真 OOM 会以 java.lang.OutOfMemoryError 走 P0）
        if ("lmk".equals(type) || "freezer".equals(type) || "resource".equals(type)) {
            return new Decision(false, "system-pressure:" + type);
        }

        // 2) 实验室里的「主动触发崩溃」：测试噪音
        if (message.contains("用户主动触发的测试崩溃") || message.startsWith("调试开关")) {
            return new Decision(false, "test-crash");
        }

        // 3) 播放哨兵：只看强信号（连续异常 / native 高危阶段）
        if (SENTINEL_CLASS.equals(cls)) {
            if (!sentinelWorthReporting(message)) {
                return new Decision(false, "sentinel-weak");
            }
        } else if ("java".equals(type) && !hasOurFrame(stack)) {
            // 4) Java 崩溃堆栈里没有任何本仓库代码帧 → 系统/环境异常（DeadSystem、BadParcelable、
            // 系统组件内部崩溃…），默认丢弃。java-early 不适用：它本来就发生在框架初始化阶段
            return new Decision(false, "no-our-frame");
        }

        // 5) 24 小时同指纹去重
        int count = dedup(fingerprint(type, cls, page, stack));
        if (count <= 0) return new Decision(false, "dedup-24h");
        if (count > 1) appendCount(report, count);
        return new Decision(true, "upload");
    }

    // ---------------------------------------------------------------- 判定细节

    /** 堆栈里是否有本仓库的代码帧（静态循环，避免每次编译 Pattern） */
    static boolean hasOurFrame(String stack) {
        if (stack == null || stack.isEmpty()) return false;
        for (String pkg : OUR_PACKAGES) {
            if (stack.contains(pkg)) return true;
        }
        return false;
    }

    /**
     * 播放哨兵是否值得上报。
     *
     * <p>哨兵只能看到"播放页没有正常收尾"，分不清 native 崩溃、被系统回收还是被强杀；
     * 因此只保留三类强信号：
     * <ul>
     *   <li>连续异常 ≥2 次（偶发回收不会连着来）；</li>
     *   <li>最后阶段停在 native 高危区（加载 ijk / 配置显示 / 设置数据源 / 已提交准备）——
     *       播放还没起来就没了，基本是播放器 so 的锅；</li>
     *   <li>短会话（≤120 秒）异常收尾：看几分钟就崩，比"看了一晚上被回收"更可疑。</li>
     * </ul>
     */
    static boolean sentinelWorthReporting(String message) {
        if (message == null) return false;
        int idx = message.indexOf("连续=");
        if (idx >= 0) {
            int end = message.indexOf('次', idx);
            if (end > idx + 3) {
                try {
                    int streak = Integer.parseInt(message.substring(idx + 3, end));
                    if (streak >= 2) return true;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        for (String stage : NATIVE_RISK_STAGES) {
            if (message.contains(stage)) return true;
        }
        //短会话：解析 "停留=123s"
        int stayIdx = message.indexOf("停留=");
        if (stayIdx >= 0) {
            int end = message.indexOf('s', stayIdx);
            if (end > stayIdx + 3) {
                try {
                    long seconds = Long.parseLong(message.substring(stayIdx + 3, end));
                    if (seconds >= 0 && seconds <= SHORT_SESSION_SECONDS) return true;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return false;
    }

    /**
     * 指纹：类型 + 异常类 + 页面 + 栈顶第一个本仓库帧 + 版本。
     *
     * <p>不含 message：同一崩溃的文案可能变（例如带参数），含页面是为了区分"同一异常在不同页"；
     * 含版本是为了升级后每个崩溃还能再报一次（否则老版本的一条记录会把新版本的同类崩溃压掉 24 小时）。
     */
    static String fingerprint(String type, String cls, String page, String stack) {
        return type + "|" + cls + "|" + page + "|" + topOurFrame(stack) + "|" + BuildConfig.VERSION_NAME;
    }

    /** 栈里第一个本仓库帧（去掉行号，避免不同构建行号不同导致指纹漂移） */
    private static String topOurFrame(String stack) {
        if (stack == null || stack.isEmpty()) return "";
        int from = 0;
        while (from < stack.length()) {
            int end = stack.indexOf('\n', from);
            if (end < 0) end = stack.length();
            String line = stack.substring(from, end).trim();
            for (String pkg : OUR_PACKAGES) {
                if (line.contains(pkg)) {
                    int paren = line.indexOf('(');
                    return cut(paren > 0 ? line.substring(0, paren) : line, 120);
                }
            }
            from = end + 1;
        }
        return "";
    }

    // ---------------------------------------------------------------- 去重索引

    /**
     * @return 0 表示 24 小时内已传过（抑制）；&gt;0 表示本次可传，值为该指纹的累计发生次数
     */
    private static int dedup(String fingerprint) {
        try {
            JSONObject index = readIndex();
            long now = System.currentTimeMillis();
            JSONObject entry = index.optJSONObject(fingerprint);
            if (entry != null) {
                long last = entry.optLong("t", 0L);
                int count = entry.optInt("n", 1) + 1;
                entry.put("n", count);
                if (now - last < DEDUP_WINDOW_MS) {
                    //窗口内：只累加计数，不刷新上传时间
                    writeIndex(index);
                    return 0;
                }
                entry.put("t", now);
                writeIndex(index);
                return count;
            }
            JSONObject fresh = new JSONObject();
            fresh.put("t", now);
            fresh.put("n", 1);
            index.put(fingerprint, fresh);
            trimIndex(index);
            writeIndex(index);
            return 1;
        } catch (Throwable t) {
            //索引损坏/写入失败时宁可不抑制，别把真崩溃弄丢
            return 1;
        }
    }

    private static JSONObject readIndex() {
        try {
            String raw = SharedPreferencesUtil.getString(KEY_INDEX, "");
            if (raw == null || raw.isEmpty()) return new JSONObject();
            return new JSONObject(raw);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    private static void writeIndex(JSONObject index) {
        try {
            SharedPreferencesUtil.putString(KEY_INDEX, index.toString());
        } catch (Throwable ignored) {
        }
    }

    /** 索引最多 40 条，超了按"上次上传时间"淘汰最旧的 */
    private static void trimIndex(JSONObject index) {
        try {
            if (index.length() <= MAX_INDEX_ENTRIES) return;
            List<String> keys = new ArrayList<>();
            Iterator<String> it = index.keys();
            while (it.hasNext()) keys.add(it.next());
            //按 t 升序（数量很小，选择排序足够）
            for (int i = 0; i < keys.size(); i++) {
                for (int j = i + 1; j < keys.size(); j++) {
                    if (index.optJSONObject(keys.get(j)).optLong("t", 0) < index.optJSONObject(keys.get(i)).optLong("t", 0)) {
                        String tmp = keys.get(i);
                        keys.set(i, keys.get(j));
                        keys.set(j, tmp);
                    }
                }
            }
            int drop = keys.size() - MAX_INDEX_ENTRIES;
            for (int i = 0; i < drop; i++) index.remove(keys.get(i));
        } catch (Throwable ignored) {
        }
    }

    /** 重复发生时把累计次数写进 message 末尾（不加数据库字段） */
    private static void appendCount(JSONObject report, int count) {
        try {
            String message = report.optString("message", "");
            report.put("message", cut(message, 400) + "（本机累计第 " + count + " 次）");
        } catch (Throwable ignored) {
        }
    }

    private static String cut(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max);
    }
}
