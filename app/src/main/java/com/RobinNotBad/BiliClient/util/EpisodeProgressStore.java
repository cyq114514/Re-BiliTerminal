package com.RobinNotBad.BiliClient.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.RobinNotBad.BiliClient.BiliTerminal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 番剧分集续播位置的**本机存档**。
 *
 * 为什么必须自己存一份：服务端对本季的观看进度只维护"最近观看的那一集"这一条
 * —— 实测 season 1564 的第 8/9 话**共用同一个 avid(135433)**，看完第 9 话之后第 8 话的位置
 * 就被覆盖掉了；这不是终端的问题，**官方客户端里同样只有最新观看的那一集能续播，
 * 其余集都会从头开始**（用户在真机上确认过）。
 *
 * 因此"每集各自记住看到哪里"只能由终端自己兜住：
 *  · 键用 (mid, epid)：epid 是集身份，天然按集隔离，不可能出现跨集串进度；
 *    epid 缺失（服务端少数分区条目）时退回 (mid, cid)，cid 同样是每集唯一的流标识。
 *  · 值存 位置(毫秒) + 写入时间戳(毫秒)：时间戳用于诊断，以及条目超量时按时间淘汰。
 *  · 读取顺序见 {@link com.RobinNotBad.BiliClient.api.PlayerApi#getEpisodeProgressMs}：
 *    只有"能证明属于本集"的服务端数据（季级 last_ep_id==epid、观看记录全身份命中）才优先，
 *    服务端已经没有这一集的记录时（正是被覆盖掉的那些集）用本机存档续播。
 *  · 写入点见 {@link com.RobinNotBad.BiliClient.api.HistoryApi#reportHistoryPgc}：
 *    每次上报（周期/退出/看完）都把这一集的位置落到本机，看完则清掉。
 *
 * 只存番剧(PGC)，不影响投稿视频/多P（那条链路服务端自己按 last_play_cid 记得住）。
 */
public class EpisodeProgressStore {

    private static final String PREF_NAME = "episode_progress";
    /** 最多保留多少集：超出后按写入时间淘汰最旧的（一季几十集，正常用不到这么多） */
    private static final int MAX_ENTRIES = 800;
    /** 存档有效期：一年。太老的条目既没用又占地方，读取时直接忽略 */
    private static final long MAX_AGE_MS = 365L * 24 * 3600 * 1000;

    private static SharedPreferences prefs;

    private static SharedPreferences p() {
        if (prefs == null) {
            Context ctx = BiliTerminal.context;
            if (ctx == null) return null;
            prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        }
        return prefs;
    }

    /** 存档键：(mid, epid) 优先，epid 缺失时用 (mid, cid)。两者都缺失时不存（无法与本集一一对应） */
    private static String key(long mid, long epid, long cid) {
        if (epid != 0) return mid + ":e" + epid;
        if (cid != 0) return mid + ":c" + cid;
        return null;
    }

    /** 记录某一集的续播位置（毫秒，<=0 视为"不需要续播"，等价于清除）。 */
    public static void save(long mid, long epid, long cid, long progressMs) {
        if (progressMs <= 0) {
            clear(mid, epid, cid);
            return;
        }
        try {
            SharedPreferences sp = p();
            if (sp == null) return;
            String k = key(mid, epid, cid);
            if (k == null) return;
            SharedPreferences.Editor editor = sp.edit().putString(k, progressMs + "," + System.currentTimeMillis());
            if (sp.getAll().size() > MAX_ENTRIES) prune(sp);
            editor.apply();
        } catch (Throwable ignored) {
        }
    }

    /** 清除某一集的存档（看完、或明确要从头看时）。 */
    public static void clear(long mid, long epid, long cid) {
        try {
            SharedPreferences sp = p();
            if (sp == null) return;
            String k = key(mid, epid, cid);
            if (k == null) return;
            sp.edit().remove(k).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 取某一集的本机存档位置（毫秒）；没有、过期或数据损坏时返回 0。 */
    public static long load(long mid, long epid, long cid) {
        try {
            SharedPreferences sp = p();
            if (sp == null) return 0;
            String k = key(mid, epid, cid);
            if (k == null) return 0;
            String v = sp.getString(k, null);
            if (v == null) return 0;
            int comma = v.indexOf(',');
            long ms = Long.parseLong(comma >= 0 ? v.substring(0, comma) : v);
            long at = comma >= 0 ? Long.parseLong(v.substring(comma + 1)) : 0;
            if (ms <= 0) return 0;
            if (at > 0 && System.currentTimeMillis() - at > MAX_AGE_MS) return 0;
            return ms;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 诊断用：形如 {@code 本机存档=186000ms(写入于 1234s 前)} / {@code 本机存档=无} */
    public static String describe(long mid, long epid, long cid) {
        try {
            SharedPreferences sp = p();
            String k = sp == null ? null : key(mid, epid, cid);
            String v = k == null ? null : sp.getString(k, null);
            if (v == null) return "本机存档=无";
            int comma = v.indexOf(',');
            long ms = Long.parseLong(comma >= 0 ? v.substring(0, comma) : v);
            long at = comma >= 0 ? Long.parseLong(v.substring(comma + 1)) : 0;
            long ageSec = at > 0 ? (System.currentTimeMillis() - at) / 1000 : -1;
            return "本机存档=" + ms + "ms" + (ageSec >= 0 ? "(" + ageSec + "s 前写入)" : "");
        } catch (Throwable t) {
            return "本机存档=读取失败";
        }
    }

    /** 当前账号下共有多少条存档（诊断用） */
    public static int count(long mid) {
        try {
            SharedPreferences sp = p();
            if (sp == null) return 0;
            int n = 0;
            for (String k : sp.getAll().keySet()) {
                if (k.startsWith(mid + ":")) n++;
            }
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 超量淘汰：按写入时间从旧到新删，删到 MAX_ENTRIES 以内 */
    private static void prune(SharedPreferences sp) {
        try {
            Map<String, ?> all = sp.getAll();
            List<String[]> items = new ArrayList<>();   //{key, timestamp}
            for (Map.Entry<String, ?> e : all.entrySet()) {
                Object v = e.getValue();
                if (!(v instanceof String)) continue;
                int comma = ((String) v).indexOf(',');
                long at = 0;
                try {
                    at = comma >= 0 ? Long.parseLong(((String) v).substring(comma + 1)) : 0;
                } catch (Throwable ignored) {
                }
                items.add(new String[]{e.getKey(), String.valueOf(at)});
            }
            if (items.size() <= MAX_ENTRIES) return;
            Collections.sort(items, (a, b) -> Long.compare(Long.parseLong(a[1]), Long.parseLong(b[1])));
            SharedPreferences.Editor editor = sp.edit();
            int remove = items.size() - MAX_ENTRIES;
            for (int i = 0; i < remove; i++) editor.remove(items.get(i)[0]);
            editor.apply();
        } catch (Throwable ignored) {
        }
    }
}
