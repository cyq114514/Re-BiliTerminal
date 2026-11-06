package com.RobinNotBad.BiliClient.source;

import com.RobinNotBad.BiliClient.source.animeko.AnimekoSelectorSource;
import com.RobinNotBad.BiliClient.source.model.SourceInfo;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 第三方源的总入口：实例构造、并发调度、健康度、异常隔离。
 *
 * <p>三条硬约束：
 * <ol>
 *   <li><b>每次操作新建源实例</b>，不做实例缓存。源对象持有「取消信号」与「上次请求时间」，
 *       缓存会让两个页面的取消/节流互相污染；构造只是解析一份几 KB 的 JSON，不值得为它冒这个险。</li>
 *   <li><b>单源失败绝不向上抛</b>：失败只折算成健康度，搜索/详情照常展示其它源。</li>
 *   <li><b>并发上限 3</b>：手表与低端机同时开 20 个请求会把网络与内存一起拖死。</li>
 * </ol>
 */
public final class SourceManager {

    private static final SourceManager INSTANCE = new SourceManager();

    /**
     * 单次搜索最多同时进行的源数量。
     *
     * <p>从 3 提到 6：绝大多数源是「一条 HTTP + 一次选择器解析」，瓶颈在等网络；
     * 60 个源串行 3 条并发时，只要有 10 来个 8s 超时就够把整页拖到一分钟。
     */
    public static final int MAX_CONCURRENT = 6;

    /** 搜索缓存 TTL：同一关键词重复搜索（含 B 站混排重复触发）直接命中，不再打外站 */
    private static final long SEARCH_CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final int SEARCH_CACHE_MAX = 120;

    private final Map<String, int[]> health = new HashMap<>();
    /**
     * 连续失败的源临时跳过多久。
     *
     * <p>一次搜索 60 个源，只要有十几个站点挂了/被墙，每次搜索都要重新等它们超时；
     * 10 分钟内直接跳过，能把第二次搜索的耗时砍掉一大截。用户仍可在管理页手动恢复
     * （停用再启用会清掉惩罚）。
     */
    private static final long FAIL_BLOCK_MS = 10 * 60 * 1000L;
    private final Map<String, Long> blockedUntil = new HashMap<>();
    private volatile long lastPersistAt = 0;
    private ExecutorService pool;

    /** 源 + 关键词 → 搜索结果（带时间戳），访问顺序 LRU */
    private final java.util.LinkedHashMap<String, CachedSearch> searchCache =
            new java.util.LinkedHashMap<String, CachedSearch>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedSearch> eldest) {
                    return size() > SEARCH_CACHE_MAX;
                }
            };

    private static final class CachedSearch {
        final List<com.RobinNotBad.BiliClient.source.model.SourceSubject> subjects;
        final long at;

        CachedSearch(List<com.RobinNotBad.BiliClient.source.model.SourceSubject> subjects) {
            this.subjects = subjects;
            this.at = System.currentTimeMillis();
        }
    }

    private SourceManager() {
    }

    public static SourceManager get() {
        return INSTANCE;
    }

    public List<SourceInfo> all() {
        return SourceStore.load();
    }

    /**
     * 参与搜索的源：启用的且不在临时跳过期，按「健康度 → tier → 用户顺序」排。
     *
     * <p>与 {@link #enabled()} 的区别只在于过滤掉「刚连续失败」的源。
     */
    public List<SourceInfo> searchable() {
        List<SourceInfo> all = enabled();
        List<SourceInfo> result = new ArrayList<>(all.size());
        for (SourceInfo info : all) {
            if (!isTemporarilyBlocked(info.id)) result.add(info);
        }
        return result;
    }

    public boolean isTemporarilyBlocked(String sourceId) {
        if (sourceId == null) return false;
        synchronized (blockedUntil) {
            Long until = blockedUntil.get(sourceId);
            if (until == null) return false;
            if (System.currentTimeMillis() > until) {
                blockedUntil.remove(sourceId);
                return false;
            }
            return true;
        }
    }

    /** 管理页「解除临时跳过」用 */
    public void clearBlock(String sourceId) {
        if (sourceId == null) return;
        synchronized (blockedUntil) {
            blockedUntil.remove(sourceId);
        }
        synchronized (health) {
            health.remove(sourceId);
        }
    }

    /** 参与搜索的源：启用的，按「健康度 → tier → 用户顺序」排 */
    public List<SourceInfo> enabled() {
        final List<SourceInfo> ordered = SourceStore.load();
        List<SourceInfo> list = new ArrayList<>();
        for (SourceInfo info : ordered) {
            if (info.enabled) list.add(info);
        }
        //ordered 与 list 里是同一批对象，indexOf 用引用判等即可拿到用户排的顺序
        Collections.sort(list, (left, right) -> {
            int byHealth = Integer.compare(penalty(left.id), penalty(right.id));
            if (byHealth != 0) return byHealth;
            int byTier = Integer.compare(left.tier, right.tier);
            if (byTier != 0) return byTier;
            return Integer.compare(ordered.indexOf(left), ordered.indexOf(right));
        });
        return list;
    }

    /**
     * 构造一个可用的源实例。
     *
     * @return null 表示该类型暂不支持或配置坏了，原因用 {@link #unsupportedReason} 取
     */
    public ThirdPartySource newSource(SourceInfo info) {
        if (info == null) return null;
        if (info.type != SourceType.ANIMEKO_SELECTOR) return null;
        try {
            ThirdPartySource source = new AnimekoSelectorSource(info);
            //构造时没有抛异常只说明结构可读；真正的可用性由第一次请求决定
            return source;
        } catch (Throwable t) {
            noteFailure(info.id, t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : "：" + t.getMessage()));
            return null;
        }
    }

    public String unsupportedReason(SourceInfo info) {
        if (info == null) return "数据源为空";
        switch (info.type) {
            case ANIMEKO_JSON_API:
                return "Animeko JSON API 类型暂未支持";
            case KAZUMI_XPATH:
            case KAZUMI_API:
                return "Kazumi 规则类型暂未支持";
            default:
                return "数据源配置不可用";
        }
    }

    /** 健康度惩罚：连续失败越多排得越后，但不删除（用户可能只是想等站点恢复） */
    public int penalty(String sourceId) {
        if (sourceId == null) return 0;
        synchronized (health) {
            int[] counts = health.get(sourceId);
            if (counts == null) return 0;
            int fails = counts[1];
            if (fails >= 3) return 2;
            if (fails >= 1) return 1;
            return 0;
        }
    }

    public void noteSuccess(String sourceId) {
        updateHealth(sourceId, true, "");
    }

    public void noteFailure(String sourceId, String message) {
        updateHealth(sourceId, false, message);
    }

    private void updateHealth(String sourceId, boolean success, String message) {
        if (sourceId == null || sourceId.isEmpty()) return;
        int fails;
        synchronized (health) {
            int[] counts = health.get(sourceId);
            if (counts == null) {
                counts = new int[]{0, 0};
                health.put(sourceId, counts);
            }
            if (success) {
                counts[0]++;
                counts[1] = 0;
            } else {
                counts[1]++;
            }
            fails = counts[1];
        }
        synchronized (blockedUntil) {
            if (success) {
                blockedUntil.remove(sourceId);
            } else if (fails >= 3) {
                //连续 3 次失败：10 分钟内不再参与搜索，避免每次搜索都为挂掉的站点等超时
                blockedUntil.put(sourceId, System.currentTimeMillis() + FAIL_BLOCK_MS);
            }
        }
        persistHealth(sourceId, success, message);
    }

    /**
     * 把最近状态写回配置（节流 15s）。
     *
     * <p>一次批量搜索会连续产生几十个结果；每次结果都整表重写 170KB 的 SP 会让
     * 搜索明显变慢（解析 + 序列化都在调用的那个线程上）。节流到 15s 后，
     * 一次搜索最多写一到两次。
     */
    private void persistHealth(String sourceId, boolean success, String message) {
        long now = System.currentTimeMillis();
        if (now - lastPersistAt < 15_000) return;
        lastPersistAt = now;
        try {
            List<SourceInfo> list = SourceStore.load();
            boolean changed = false;
            for (SourceInfo info : list) {
                if (!info.id.equals(sourceId)) continue;
                info.lastStatus = success ? 1 : -1;
                info.lastError = success ? "" : (message == null ? "" : message);
                info.lastUsedAt = now;
                changed = true;
                break;
            }
            if (changed) SourceStore.save(list);
        } catch (Throwable ignored) {
            //健康度写盘失败不影响功能，下一次操作会再试
        }
    }

    /**
     * @return 命中的缓存结果（可直接读，不要再改这个 List）；未命中或已过期返回 null
     */
    public List<com.RobinNotBad.BiliClient.source.model.SourceSubject> cachedSearch(
            String sourceId, String keyword) {
        String key = cacheKey(sourceId, keyword);
        if (key == null) return null;
        synchronized (searchCache) {
            CachedSearch cached = searchCache.get(key);
            if (cached == null) return null;
            if (System.currentTimeMillis() - cached.at > SEARCH_CACHE_TTL_MS) {
                searchCache.remove(key);
                return null;
            }
            return cached.subjects;
        }
    }

    public void putSearch(String sourceId, String keyword,
                          List<com.RobinNotBad.BiliClient.source.model.SourceSubject> subjects) {
        String key = cacheKey(sourceId, keyword);
        if (key == null || subjects == null || subjects.isEmpty()) return;
        synchronized (searchCache) {
            searchCache.put(key, new CachedSearch(new ArrayList<>(subjects)));
        }
    }

    /** 线路/剧集缓存：同一作品反复进出选集页时不再重抓详情页 */
    private final java.util.LinkedHashMap<String, CachedChannels> channelCache =
            new java.util.LinkedHashMap<String, CachedChannels>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedChannels> eldest) {
                    return size() > 40;
                }
            };

    private static final class CachedChannels {
        final List<com.RobinNotBad.BiliClient.source.model.SourceChannel> channels;
        final long at;

        CachedChannels(List<com.RobinNotBad.BiliClient.source.model.SourceChannel> channels) {
            this.channels = channels;
            this.at = System.currentTimeMillis();
        }
    }

    public List<com.RobinNotBad.BiliClient.source.model.SourceChannel> cachedChannels(
            String sourceId, String subjectKey) {
        String key = cacheKey(sourceId, subjectKey);
        if (key == null) return null;
        synchronized (channelCache) {
            CachedChannels cached = channelCache.get(key);
            if (cached == null) return null;
            if (System.currentTimeMillis() - cached.at > SEARCH_CACHE_TTL_MS) {
                channelCache.remove(key);
                return null;
            }
            return cached.channels;
        }
    }

    public void putChannels(String sourceId, String subjectKey,
                            List<com.RobinNotBad.BiliClient.source.model.SourceChannel> channels) {
        String key = cacheKey(sourceId, subjectKey);
        if (key == null || channels == null || channels.isEmpty()) return;
        synchronized (channelCache) {
            channelCache.put(key, new CachedChannels(new ArrayList<>(channels)));
        }
    }

    /** 已解析出的播放地址缓存：同一集重复点播（失败重试、切回来再看）不再重新抓播放页 */
    private final java.util.LinkedHashMap<String, CachedMedia> mediaCache =
            new java.util.LinkedHashMap<String, CachedMedia>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedMedia> eldest) {
                    return size() > 40;
                }
            };

    private static final class CachedMedia {
        final com.RobinNotBad.BiliClient.source.model.SourceMedia media;
        final long at;

        CachedMedia(com.RobinNotBad.BiliClient.source.model.SourceMedia media) {
            this.media = media;
            this.at = System.currentTimeMillis();
        }
    }

    public com.RobinNotBad.BiliClient.source.model.SourceMedia cachedMedia(
            String sourceId, String episodeKey) {
        String key = cacheKey(sourceId, episodeKey);
        if (key == null) return null;
        synchronized (mediaCache) {
            CachedMedia cached = mediaCache.get(key);
            if (cached == null) return null;
            //播放地址常带时效，缓存只保 3 分钟
            if (System.currentTimeMillis() - cached.at > 3 * 60 * 1000L) {
                mediaCache.remove(key);
                return null;
            }
            return cached.media;
        }
    }

    public void putMedia(String sourceId, String episodeKey,
                         com.RobinNotBad.BiliClient.source.model.SourceMedia media) {
        String key = cacheKey(sourceId, episodeKey);
        if (key == null || media == null) return;
        synchronized (mediaCache) {
            mediaCache.put(key, new CachedMedia(media));
        }
    }

    /** 删除/停用源后调用：避免继续命中旧结果（搜索、线路、播放地址一起清） */
    public void clearSearchCache() {
        synchronized (searchCache) {
            searchCache.clear();
        }
        synchronized (channelCache) {
            channelCache.clear();
        }
        synchronized (mediaCache) {
            mediaCache.clear();
        }
    }

    private String cacheKey(String sourceId, String keyword) {
        if (sourceId == null || sourceId.isEmpty()) return null;
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) return null;
        return sourceId + '\u0001' + kw;
    }

    public ExecutorService pool() {
        if (pool != null) return pool;
        synchronized (this) {
            if (pool == null) {
                pool = Executors.newFixedThreadPool(MAX_CONCURRENT, new ThreadFactory() {
                    private final AtomicInteger counter = new AtomicInteger();

                    @Override
                    public Thread newThread(Runnable runnable) {
                        Thread thread = new Thread(runnable, "third-source-" + counter.incrementAndGet());
                        //后台线程：不抢 UI 优先级，也不阻止进程退出
                        thread.setDaemon(true);
                        return thread;
                    }
                });
            }
            return pool;
        }
    }

    /** 记录「网页嗅探」开关（默认关，见方案 D1） */
    public boolean sniffEnabled() {
        return SharedPreferencesUtil.getBoolean(SourcePreferenceStore.KEY_SNIFF, false);
    }

    public void setSniffEnabled(boolean enabled) {
        SharedPreferencesUtil.putBoolean(SourcePreferenceStore.KEY_SNIFF, enabled);
    }
}
