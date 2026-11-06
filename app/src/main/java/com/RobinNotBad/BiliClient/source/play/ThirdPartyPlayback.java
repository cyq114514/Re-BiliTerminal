package com.RobinNotBad.BiliClient.source.play;

import android.app.Activity;
import android.content.Intent;

import com.RobinNotBad.BiliClient.api.PlayerApi;
import com.RobinNotBad.BiliClient.model.PlayerData;
import com.RobinNotBad.BiliClient.source.SourceChannelHealth;
import com.RobinNotBad.BiliClient.source.SourceManager;
import com.RobinNotBad.BiliClient.source.SourcePreferenceStore;
import com.RobinNotBad.BiliClient.source.ThirdPartySource;
import com.RobinNotBad.BiliClient.source.model.SourceChannel;
import com.RobinNotBad.BiliClient.source.model.SourceEpisode;
import com.RobinNotBad.BiliClient.source.model.SourceInfo;
import com.RobinNotBad.BiliClient.source.model.SourceMedia;
import com.RobinNotBad.BiliClient.source.model.SourceSubject;
import com.RobinNotBad.BiliClient.source.resolve.MediaResolver;
import com.RobinNotBad.BiliClient.source.resolve.MediaRule;
import com.RobinNotBad.BiliClient.source.resolve.WebViewSniffer;
import com.RobinNotBad.BiliClient.source.rule.RuleLimits;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.Logu;

import java.util.ArrayList;
import java.util.List;

/**
 * 第三方源播放入口：解析 → 交给现有播放器。
 *
 * <p>提速的三个手段：
 * <ol>
 *   <li><b>两条线路并行解析</b>：串行时一条慢站点就能把「点一集」拖到 10s+；并行后首条成功即起播；</li>
 *   <li><b>播放地址短缓存</b>（3 分钟）：失败重试、切回来再看都不再重新抓播放页；</li>
 *   <li><b>网页嗅探只做一次、15s 上限</b>：原来每条线路各嗅一次、每次 25s，最坏 100s。</li>
 * </ol>
 */
public final class ThirdPartyPlayback {

    /** 先只解析 1 条线路；这条 2s 还没结果，再补第 2 条（坏线路已被屏蔽，不必再等 4s） */
    private static final int PARALLEL_CHANNELS = 2;
    private static final long ESCALATE_DELAY_MS = 2_000L;

    public interface Callback {
        /** 过程文案（主线程），如「正在解析线路 2/5」 */
        void onStatus(String text);

        /** 已经交给播放器（主线程） */
        void onStarted();

        /** 全部线路都失败（主线程），reason 可直接上屏 */
        void onFailed(String reason);
    }

    private ThirdPartyPlayback() {
    }

    public static void start(final Activity activity, final SourceInfo info, final SourceSubject subject,
                             final List<SourceChannel> channels, final int preferredChannelIndex,
                             final int episodeIndex, final String scope, final Callback callback) {
        if (activity == null || info == null || subject == null) {
            fail(callback, "参数不完整");
            return;
        }
        if (channels == null || channels.isEmpty()) {
            fail(callback, "该作品没有可用线路");
            return;
        }
        //再挡一层：选集页已过滤，但线路可能在页面打开期间刚被播放器判失败。
        //坏线路每条都要等一次 8~10s 超时，跳掉它们是加载提速的主要来源。
        final SourceChannelHealth health = SourceChannelHealth.get();
        String preferredId = preferredChannelIndex >= 0 && preferredChannelIndex < channels.size()
                ? channels.get(preferredChannelIndex).id : "";
        final List<SourceChannel> usable = new ArrayList<>();
        for (SourceChannel channel : channels) {
            if (!health.isBlocked(channel.id)) usable.add(channel);
        }
        if (usable.isEmpty()) {
            fail(callback, "线路均已被屏蔽，可在选集页点「恢复」重试");
            return;
        }
        int mappedPreferred = 0;
        for (int i = 0; i < usable.size(); i++) {
            if (usable.get(i).id.equals(preferredId)) {
                mappedPreferred = i;
                break;
            }
        }
        final ThirdPartySource source = SourceManager.get().newSource(info);
        if (source == null) {
            fail(callback, SourceManager.get().unsupportedReason(info));
            return;
        }
        Session session = new Session(activity, info, subject, usable, source,
                mappedPreferred, episodeIndex, scope, callback);
        status(callback, "开始解析…");
        session.schedule();
        session.armEscalation();
    }

    private static final class Session {
        private final Activity activity;
        private final SourceInfo info;
        private final SourceSubject subject;
        private final List<SourceChannel> channels;
        private final ThirdPartySource source;
        private final int preferredChannelIndex;
        private final int episodeIndex;
        private final String scope;
        private final Callback callback;
        private final boolean allowSniff;
        private final List<String> reasons = new ArrayList<>();
        private final int attempts;

        /** 下面三个字段都只在 synchronized(this) 里读写 */
        private int pointer = 0;
        private int inFlight = 0;
        private boolean finished = false;
        /** 网页嗅探只做一次 */
        private int sniffAttempts = 0;
        /** 起步只跑 1 条线路；4s 没结果再放宽到 2 条 */
        private int maxParallel = 1;
        private final android.os.Handler handler =
                new android.os.Handler(android.os.Looper.getMainLooper());
        private final Runnable escalate = () -> {
            synchronized (Session.this) {
                maxParallel = PARALLEL_CHANNELS;
            }
            schedule();
        };

        Session(Activity activity, SourceInfo info, SourceSubject subject, List<SourceChannel> channels,
                ThirdPartySource source, int preferredChannelIndex, int episodeIndex, String scope,
                Callback callback) {
            this.activity = activity;
            this.info = info;
            this.subject = subject;
            this.channels = channels;
            this.source = source;
            this.preferredChannelIndex = Math.max(0, preferredChannelIndex);
            this.episodeIndex = episodeIndex;
            this.scope = scope;
            this.callback = callback;
            this.allowSniff = SourceManager.get().sniffEnabled();
            this.attempts = Math.min(channels.size(), RuleLimits.MAX_FAILOVER_ATTEMPTS);
        }

        /** 保持最多 {@link #PARALLEL_CHANNELS} 条线路在解析，首条成功即结束 */
        synchronized void schedule() {
            if (finished) return;
            if (activity.isFinishing() || activity.isDestroyed()) {
                finished = true;
                return;
            }
            while (inFlight < maxParallel && pointer < attempts) {
                final int order = pointer++;
                final SourceChannel channel = channels.get((preferredChannelIndex + order) % channels.size());
                final SourceEpisode episode = episodeOf(channel, episodeIndex);
                if (episode == null) {
                    reasons.add(channel.name + "：没有这一集");
                    continue;
                }
                inFlight++;
                statusOnUi("正在解析线路 " + channel.name + "（" + (order + 1) + "/" + attempts + "）");
                SourceManager.get().pool().execute(() -> resolveOne(channel, episode));
            }
            if (inFlight == 0 && pointer >= attempts) {
                finished = true;
                handler.removeCallbacks(escalate);
                fail(callback, buildFailure());
            }
        }

        /** 起播 4s 后仍未成功就放开第 2 条线路；由 start() 在首次 schedule 之后调用 */
        void armEscalation() {
            handler.postDelayed(escalate, ESCALATE_DELAY_MS);
        }

        private void resolveOne(SourceChannel channel, SourceEpisode episode) {
            //已解析过的同一集（3 分钟内）直接用，不再抓播放页
            SourceMedia cached = SourceManager.get().cachedMedia(info.id, episode.pageUrl);
            if (cached != null) {
                succeed(channel, episode, cached);
                return;
            }
            final MediaResolver.Outcome outcome = MediaResolver.resolve(info, source, subject, channel, episode);
            if (outcome.media != null) {
                succeed(channel, episode, outcome.media);
                return;
            }
            boolean useSniff;
            synchronized (this) {
                useSniff = outcome.needWebView && allowSniff && sniffAttempts == 0 && !finished;
                if (useSniff) sniffAttempts++;
            }
            if (useSniff) {
                String target = outcome.nestedUrls.isEmpty() ? episode.pageUrl : outcome.nestedUrls.get(0);
                statusOnUi("正在网页解析线路 " + channel.name);
                CenterThreadPool.runOnUiThread(() -> sniff(channel, episode, target, outcome.reason));
                return;
            }
            onOneFailed(channel, outcome.reason);
        }

        private void sniff(SourceChannel channel, SourceEpisode episode, String pageUrl, String httpReason) {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            if (!WebViewSniffer.available()) {
                onOneFailed(channel, "本设备没有 WebView，无法网页解析");
                return;
            }
            final MediaRule rule = source.mediaRule();
            WebViewSniffer.sniff(activity, pageUrl, rule,
                    rule == null ? "" : rule.playPageUserAgent(),
                    rule == null ? "" : rule.playPageCookies(),
                    RuleLimits.SNIFF_TIMEOUT_MS,
                    new WebViewSniffer.Callback() {
                        @Override
                        public void onFound(String url) {
                            if (activity.isFinishing() || activity.isDestroyed()) return;
                            SourceMedia media = MediaResolver.fromUrl(info, subject, episode, url, rule);
                            if (media == null) {
                                onOneFailed(channel, "嗅探到的地址未通过校验");
                                return;
                            }
                            succeed(channel, episode, media);
                        }

                        @Override
                        public void onFail(String reason) {
                            onOneFailed(channel, reason == null ? httpReason : reason);
                        }
                    });
        }

        private void succeed(SourceChannel channel, SourceEpisode episode, SourceMedia media) {
            if (media == null || media.mediaUrl == null || media.mediaUrl.isEmpty()) {
                onOneFailed(channel, "解析结果为空");
                return;
            }
            synchronized (this) {
                if (finished) return;
                finished = true;
                inFlight = 0;
            }
            handler.removeCallbacks(escalate);
            //记下「这条线路能解析」：下次把它排到最前，起播不用先为坏线路等超时
            SourceChannelHealth.get().noteSuccess(channel.id);
            SourceManager.get().putMedia(info.id, episode.pageUrl, media);
            startPlayback(channel, episode, media);
        }

        private void onOneFailed(SourceChannel channel, String reason) {
            //连续 2 次解析不出地址就自动屏蔽（播放器报错则由播放页直接屏蔽，更严格）
            if (channel != null) SourceChannelHealth.get().noteResolveFailure(channel.id);
            synchronized (this) {
                reasons.add((channel == null ? "" : channel.name + "：") + reason);
                inFlight--;
            }
            schedule();
        }

        private void startPlayback(SourceChannel channel, SourceEpisode episode, SourceMedia media) {
            //HLS 代理要先抓一次原始 m3u8（网络请求）；网页嗅探回调在主线程，
            //整段放到后台线程再回主线程起播，避免 NetworkOnMainThreadException
            SourceManager.get().pool().execute(() -> {
            final PlayerData data = new PlayerData(PlayerData.TYPE_THIRD_PARTY);
            data.title = subject.title + " · " + episode.display();
            //加密 HLS（#EXT-X-KEY）经 loopback 代理改写，绕开 ijk 对
            //crypto+https://host:非443端口 的协议层限制；非 HLS / 代理起不来时原样直连
            String playUrl = LocalHlsProxy.get().rewrite(media.mediaUrl, media.headers);
            data.videoUrl = playUrl;
            data.extraHeaders = media.headersForIntent();
            data.progress = 0;
            //第三方弹幕只有「番剧名+集号」可用（没有 cid）；集号取解析出的数值集号，解析不出回退下标+1
            String danmakuTitle = subject.danmakuTitle == null || subject.danmakuTitle.isEmpty()
                    ? subject.title : subject.danmakuTitle;
            data.danmakuTitle = danmakuTitle == null ? "" : danmakuTitle;
            double sort = episode.sort;
            data.danmakuEpisode = sort > 0 ? (int) Math.round(sort) : episode.index + 1;
            //第三方源没有 B 站维度：aid/cid/epid 保持 0，进度上报与弹幕链路自动跳过
            SourcePreferenceStore.setChannelId(scope, channel.id);
            SourcePreferenceStore.setEpisodeIndex(scope, episode.index);
            //W 级：正式包关掉 V/D/I，只有 W/E 一定进 logcat。起播前把「到底交了什么地址」留下证据
            Logu.w("third-resolve", "起播 源=" + info.displayName() + " 线路=" + channel.name
                    + " url=" + com.RobinNotBad.BiliClient.source.resolve.MediaUrls.summarize(playUrl)
                    + " 代理=" + !playUrl.equals(media.mediaUrl)
                    + " 头=" + media.headers.keySet());
            CenterThreadPool.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                try {
                    Intent intent = PlayerApi.jumpToPlayer(data, true);
                    //播放页报错时据此把线路标记为不可用；不经过 PlayerData，Parcel 顺序不用动
                    intent.putExtra("third_channel_key", channel.id);
                    activity.startActivity(intent);
                    if (callback != null) callback.onStarted();
                } catch (Throwable t) {
                    fail(callback, "打开播放器失败：" + t.getClass().getSimpleName());
                }
            });
            });
        }

        private String buildFailure() {
            if (reasons.isEmpty()) return "所有线路都没解析出播放地址";
            StringBuilder builder = new StringBuilder("没有线路能播：\n");
            int start = Math.max(0, reasons.size() - 3);
            for (int i = start; i < reasons.size(); i++) {
                if (i > start) builder.append('\n');
                builder.append("· ").append(reasons.get(i));
            }
            return builder.toString();
        }

        private void statusOnUi(final String text) {
            CenterThreadPool.runOnUiThread(() -> status(callback, text));
        }
    }

    private static SourceEpisode episodeOf(SourceChannel channel, int episodeIndex) {
        if (channel == null || channel.episodes.isEmpty()) return null;
        if (episodeIndex >= 0 && episodeIndex < channel.episodes.size())
            return channel.episodes.get(episodeIndex);
        return channel.episodes.get(0);
    }

    private static void status(Callback callback, String text) {
        if (callback != null) callback.onStatus(text);
    }

    private static void fail(Callback callback, String reason) {
        if (callback != null) {
            CenterThreadPool.runOnUiThread(() -> callback.onFailed(reason));
        }
    }
}
