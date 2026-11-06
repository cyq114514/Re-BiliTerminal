package com.RobinNotBad.BiliClient.source;

import com.RobinNotBad.BiliClient.source.model.SourceException;
import com.RobinNotBad.BiliClient.source.model.SourceInfo;
import com.RobinNotBad.BiliClient.source.model.SourceSubject;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.Logu;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 跨多个源做一次关键词搜索。
 *
 * <p>行为约定（UI 直接依赖这些语义）：
 * <ul>
 *   <li>每个源的结果是<b>独立回调</b>的：快源先出，慢源后补，一个源失败不影响已出的结果；</li>
 *   <li>回调一律在主线程；页面退出时 {@link #cancel()}，之后不再回调任何东西；</li>
 *   <li>并发上限由 {@link SourceManager#pool()} 保证（3 个），这里只负责提交与聚合。</li>
 * </ul>
 */
public final class SourceSearchTask {

    public interface Listener {
        /**
         * @param subjects 成功时的结果（可能为空列表）；失败时为 null
         * @param error    失败原因；成功时为 null
         * @param done     已完成的源数量（含失败）
         */
        void onSourceResult(SourceInfo info, List<SourceSubject> subjects, SourceException error,
                            long costMs, int done, int total);

        void onFinished(int success, int failed, boolean cancelled);
    }

    private final String keyword;
    private final List<SourceInfo> sources;
    private final Listener listener;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicInteger done = new AtomicInteger();
    private final AtomicInteger success = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();

    public SourceSearchTask(String keyword, List<SourceInfo> sources, Listener listener) {
        this.keyword = keyword == null ? "" : keyword.trim();
        this.sources = sources == null ? Collections.emptyList() : sources;
        this.listener = listener;
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void cancel() {
        cancelled.set(true);
    }

    public void start() {
        if (keyword.isEmpty() || sources.isEmpty()) {
            deliverFinished();
            return;
        }
        for (final SourceInfo info : sources) {
            SourceManager.get().pool().execute(() -> runOne(info));
        }
    }

    private void runOne(SourceInfo info) {
        if (cancelled.get()) return;
        long start = System.currentTimeMillis();
        List<SourceSubject> subjects;
        SourceException error = null;

        //5 分钟内的同一关键词直接命中缓存：B 站搜索混排与「源」页常常连着搜同一个词，
        //不缓存的话每次都要把 60 个外站重新打一遍
        List<SourceSubject> cached = SourceManager.get().cachedSearch(info.id, keyword);
        if (cached != null) {
            subjects = cached;
            Logu.d("third", info.displayName() + " 搜索 命中缓存 " + subjects.size() + " 条");
        } else {
            subjects = null;
            ThirdPartySource source = SourceManager.get().newSource(info);
            if (source == null) {
                error = SourceException.unsupported(info.displayName(),
                        SourceManager.get().unsupportedReason(info));
            } else {
                source.setCancelSignal(cancelled::get);
                try {
                    subjects = source.search(keyword);
                    if (subjects == null) subjects = Collections.emptyList();
                    SourceManager.get().noteSuccess(info.id);
                    SourceManager.get().putSearch(info.id, keyword, subjects);
                } catch (SourceException e) {
                    error = e;
                    SourceManager.get().noteFailure(info.id, e.userMessage());
                } catch (Throwable t) {
                    error = new SourceException(SourceException.Phase.SEARCH,
                            SourceException.Kind.PARSE, info.displayName(),
                            "搜索异常：" + t.getClass().getSimpleName(), t);
                    SourceManager.get().noteFailure(info.id, error.userMessage());
                }
            }
        }
        long cost = System.currentTimeMillis() - start;
        int finished = done.incrementAndGet();
        if (error == null) success.incrementAndGet();
        else failed.incrementAndGet();
        Logu.d("third", info.displayName() + " 搜索 " + (error == null ? "成功" : "失败")
                + " " + cost + "ms 结果=" + (subjects == null ? 0 : subjects.size())
                + (error == null ? "" : " 原因=" + error.userMessage()));

        final List<SourceSubject> deliveredSubjects = subjects;
        final SourceException deliveredError = error;
        final int doneCount = finished;
        if (cancelled.get()) return;
        CenterThreadPool.runOnUiThread(() -> {
            if (cancelled.get() || listener == null) return;
            listener.onSourceResult(info, deliveredSubjects, deliveredError, cost,
                    doneCount, sources.size());
        });
        if (finished >= sources.size()) deliverFinished();
    }

    private void deliverFinished() {
        if (cancelled.get()) return;
        final int ok = success.get();
        final int fail = failed.get();
        CenterThreadPool.runOnUiThread(() -> {
            if (listener == null) return;
            listener.onFinished(ok, fail, cancelled.get());
        });
    }
}
