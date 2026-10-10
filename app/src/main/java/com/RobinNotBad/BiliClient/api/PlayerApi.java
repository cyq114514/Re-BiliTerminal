package com.RobinNotBad.BiliClient.api;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;

import androidx.core.content.FileProvider;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.player.PlayerActivity;
import com.RobinNotBad.BiliClient.activity.settings.SettingPlayerChooseActivity;
import com.RobinNotBad.BiliClient.activity.video.JumpToPlayerActivity;
import com.RobinNotBad.BiliClient.model.ApiResponse;
import com.RobinNotBad.BiliClient.model.DashAudioStream;
import com.RobinNotBad.BiliClient.model.DashData;
import com.RobinNotBad.BiliClient.model.DashVideoStream;
import com.RobinNotBad.BiliClient.model.HighEnergyData;
import com.RobinNotBad.BiliClient.model.PlayerData;
import com.RobinNotBad.BiliClient.model.Subtitle;
import com.RobinNotBad.BiliClient.model.SubtitleLink;
import com.RobinNotBad.BiliClient.model.VideoInfo;
import com.RobinNotBad.BiliClient.service.DownloadService;
import com.RobinNotBad.BiliClient.util.FileUtil;
import com.RobinNotBad.BiliClient.util.GsonUtil;
import com.RobinNotBad.BiliClient.util.EpisodeProgressStore;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.ProgressDiag;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.RobinNotBad.BiliClient.util.ToolsUtil;
import com.google.gson.annotations.SerializedName;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlayerApi {

    //续播位置的合理上限，用于兜底异常数据
    private static final long MAX_PROGRESS_MS = 24L * 60 * 60 * 1000;

    public static class PlayUrlData {
        @SerializedName("durl") public List<DurlItem> durl;
        @SerializedName("dash") public DashData dash;
        @SerializedName("last_play_cid") public long last_play_cid;
        @SerializedName("last_play_time") public int last_play_time;
        @SerializedName("timelength") public long timelength;
        @SerializedName("accept_description") public List<String> accept_description;
        @SerializedName("accept_quality") public List<Integer> accept_quality;
    }

    public static class DurlItem {
        @SerializedName("url") public String url;
    }

    public static class PlayUrlResult {
        @SerializedName("result") public PlayUrlData result;
    }

    public static class SubtitleLinkData {
        @SerializedName("data") public SubtitleDataInner data;
    }
    public static class SubtitleDataInner {
        @SerializedName("subtitle") public SubtitleInner subtitle;
        @SerializedName("interaction") public InteractionData interaction;
        @SerializedName("view_points") public List<ViewPointData> view_points;
        //x/player/wbi/v2 同时返回续播进度，与字幕是同一个响应体。
        //last_play_time 只属于 last_play_cid 那一集/那一P，必须成对使用（见 getLastPlayProgress）
        @SerializedName("last_play_time") public long last_play_time;
        @SerializedName("last_play_cid") public long last_play_cid;
    }
    public static class SubtitleInner {
        @SerializedName("subtitles") public List<SubtitleItem> subtitles;
    }
    public static class SubtitleItem {
        @SerializedName("id") public long id;
        @SerializedName("type") public int type;
        @SerializedName("lan_doc") public String lan_doc;
        @SerializedName("subtitle_url") public String subtitle_url;
    }
    public static class InteractionData {
        @SerializedName("graph_version") public long graph_version;
    }
    public static class ViewPointData {
        @SerializedName("content") public String content;
        @SerializedName("from") public int from;
        @SerializedName("to") public int to;
        @SerializedName("type") public int type;
        @SerializedName("imgUrl") public String imgUrl;
        @SerializedName("logoUrl") public String logoUrl;
    }

    public static class SubtitleBody {
        @SerializedName("body") public List<SubtitleEntry> body;
    }
    public static class SubtitleEntry {
        @SerializedName("content") public String content;
        @SerializedName("from") public double from;
        @SerializedName("to") public double to;
    }

    public static void startGettingUrl(PlayerData playerData) {
        Context context = BiliTerminal.context;
        context.startActivity(new Intent().setClass(context, JumpToPlayerActivity.class).putExtra("data", playerData).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    public static void startDownloading(VideoInfo videoInfo, int page, int qn) {
        if (SharedPreferencesUtil.getBoolean("dev_download_old", false)) {
            Context context = BiliTerminal.context;
            context.startActivity(new Intent(context, JumpToPlayerActivity.class).putExtra("data", videoInfo.toPlayerData(page)).putExtra("download", videoInfo.pagenames.size() == 1 ? 1 : 2).putExtra("cover", videoInfo.cover).putExtra("parent_title", videoInfo.title).putExtra("qn", qn).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        if (videoInfo.cids.size() == 1) DownloadService.startDownload(videoInfo.title, videoInfo.aid, videoInfo.cids.get(0), videoInfo.cover, qn, "video", "");
        else DownloadService.startDownload(videoInfo.title, videoInfo.pagenames.get(page), videoInfo.aid, videoInfo.cids.get(page), videoInfo.cover, qn, "video", "");
    }

    public static void startDownloadingAudioOnly(VideoInfo videoInfo, int page, int qn, String audioUrl) {
        if (videoInfo.cids.size() == 1) DownloadService.startDownload(videoInfo.title, videoInfo.aid, videoInfo.cids.get(0), videoInfo.cover, qn, "audio_only", audioUrl);
        else DownloadService.startDownload(videoInfo.title, videoInfo.pagenames.get(page), videoInfo.aid, videoInfo.cids.get(page), videoInfo.cover, qn, "audio_only", audioUrl);
    }

    public static void getVideoDash(PlayerData playerData) throws IOException, JSONException {
        playerData.danmakuUrl = "https://comment.bilibili.com/" + playerData.cid + ".xml";
        String url = "https://api.bilibili.com/x/player/wbi/playurl?avid=" + playerData.aid + "&cid=" + playerData.cid + "&qn=" + playerData.qn + "&fnval=16&fnver=0&platform=pc&voice_balance=1&gaia_source=pre-load&isGaiaAvoided=true";
        String json = NetWorkUtil.getJson(ConfInfoApi.signWBI(url), NetWorkUtil.webHeaders).toString();
        ApiResponse<PlayUrlData> resp = GsonUtil.fromJson(json, new com.google.gson.reflect.TypeToken<ApiResponse<PlayUrlData>>(){}.getType());
        if (resp == null || !resp.isSuccess() || resp.data == null) throw new JSONException("获取播放地址失败");
        PlayUrlData data = resp.data;

        if (data.dash != null) {
            playerData.dashData = data.dash;
            DashVideoStream vs = playerData.dashData.getVideoStream(playerData.qn);
            if (vs != null) playerData.videoUrl = vs.baseUrl;
            DashAudioStream as = playerData.dashData.getBestAudioStream();
            if (as != null) playerData.audioUrl = as.baseUrl;
        } else {
            getVideo(playerData, true);
            return;
        }

        //last_play_cid/last_play_time 是 aid 级"上次播放"数据：进度只属于 last_play_cid 那一P。
        //请求的 cid 与上次播放的 cid 不一致时（多P视频换P续播）不能把别人的进度套在本P上，应从头播
        playerData.cidHistory = data.last_play_cid;
        playerData.progress = adoptLastPlayTime(data.last_play_cid, playerData.cid, data.last_play_time);
        if (playerData.cidHistory == 0) { playerData.cidHistory = playerData.cid; playerData.progress = 0; }

        if (data.accept_description != null && data.accept_quality != null) {
            String[] qnStrList = data.accept_description.toArray(new String[0]);
            int[] qnValueList = new int[data.accept_quality.size()];
            for (int i = 0; i < qnValueList.length; i++) qnValueList[i] = data.accept_quality.get(i);
            playerData.qnStrList = qnStrList; playerData.qnValueList = qnValueList;
        }
    }

    public static void getVideo(PlayerData playerData, boolean download) throws IOException, JSONException {
        if (System.currentTimeMillis() - playerData.timeStamp < 600000) return;
        playerData.timeStamp = System.currentTimeMillis();
        playerData.danmakuUrl = "https://comment.bilibili.com/" + playerData.cid + ".xml";
        boolean html5 = !download && "mtvPlayer".equals(SharedPreferencesUtil.getString("player", ""));
        String url = "https://api.bilibili.com/x/player/wbi/playurl?avid=" + playerData.aid + "&cid=" + playerData.cid + (html5 ? "&high_quality=1" : "") + "&qn=" + playerData.qn + "&fnval=1&fnver=0&platform=" + (html5 ? "html5" : "pc") + "&voice_balance=1&gaia_source=pre-load&isGaiaAvoided=true";
        String json = NetWorkUtil.getJson(ConfInfoApi.signWBI(url), NetWorkUtil.webHeaders).toString();
        ApiResponse<PlayUrlData> resp = GsonUtil.fromJson(json, new com.google.gson.reflect.TypeToken<ApiResponse<PlayUrlData>>(){}.getType());
        if (resp == null || !resp.isSuccess() || resp.data == null) throw new JSONException("获取播放地址失败");
        PlayUrlData data = resp.data;
        if (data.durl == null || data.durl.isEmpty()) throw new JSONException("durl is empty");
        playerData.videoUrl = data.durl.get(0).url;
        playerData.cidHistory = data.last_play_cid; playerData.progress = adoptLastPlayTime(data.last_play_cid, playerData.cid, data.last_play_time);
        if (playerData.cidHistory == 0) { playerData.cidHistory = playerData.cid; playerData.progress = 0; }
        if (data.accept_description != null && data.accept_quality != null) {
            playerData.qnStrList = data.accept_description.toArray(new String[0]);
            int[] qnValueList = new int[data.accept_quality.size()];
            for (int i = 0; i < qnValueList.length; i++) qnValueList[i] = data.accept_quality.get(i);
            playerData.qnValueList = qnValueList;
        }
    }

    public static void getBangumi(PlayerData playerData) throws IOException, JSONException {
        String session = ToolsUtil.md5(String.valueOf(System.currentTimeMillis() - SystemClock.currentThreadTimeMillis()));
        String url = "https://api.bilibili.com/pgc/player/web/playurl" + new NetWorkUtil.FormData().setUrlParam(true)
                .put("aid", playerData.aid).put("cid", playerData.cid).put("fnval", 1).put("fnver", 0).put("qn", playerData.qn).put("season_type", 1).put("session", session).put("platform", "pc");
        String json = NetWorkUtil.getJson(url).toString();
        PlayUrlResult result = GsonUtil.fromJson(json, PlayUrlResult.class);
        if (result == null || result.result == null || result.result.durl == null || result.result.durl.isEmpty())
            throw new JSONException("获取番剧播放地址失败");
        playerData.videoUrl = result.result.durl.get(0).url;
        playerData.danmakuUrl = "https://comment.bilibili.com/" + playerData.cid + ".xml";
        //番剧取流接口(pgc/player/web/playurl)的 result 不返回 last_play_*，续播进度必须单独查询。
        //必须走集级校验：服务端"本季最近观看"是按季(kid=ssid)维护的，直接采用会把上次看的那一集
        //的位置塞给本次要播的这一集，表现就是"同一部番剧不同集互相串进度"（详见 getEpisodeProgressMs）
        playerData.cidHistory = playerData.cid;
        long lastProgress = getEpisodeProgressMs(playerData.aid, playerData.cid, playerData.epid, playerData.seasonId);
        if (lastProgress > 0)
            Logu.d("history-last", "番剧续播命中 epid=" + playerData.epid + " " + lastProgress + "ms");
        else
            Logu.w("history-last", "番剧未取到本集续播进度，从头播放 epid=" + playerData.epid
                    + " aid=" + playerData.aid + " cid=" + playerData.cid);
        playerData.progress = normalizeProgress(lastProgress, result.result.timelength);
        ProgressDiag.log("开播续播", "「" + playerData.title + "」epid=" + playerData.epid
                + " aid=" + playerData.aid + " cid=" + playerData.cid + " bvid=" + playerData.bvid
                + " sid=" + playerData.seasonId + " subType=" + playerData.seasonType
                + " timelength=" + result.result.timelength
                + " 原始=" + lastProgress + "ms → 最终=" + playerData.progress + "ms");
        if (result.result.accept_description != null && result.result.accept_quality != null) {
            playerData.qnStrList = result.result.accept_description.toArray(new String[0]);
            int[] qnValueList = new int[result.result.accept_quality.size()];
            for (int i = 0; i < qnValueList.length; i++) qnValueList[i] = result.result.accept_quality.get(i);
            playerData.qnValueList = qnValueList;
        }
    }

    /**
     * 查询稿件/剧集的"上次播放进度"（毫秒，取不到时为 0）。
     * 投稿视频可从 playurl 的 last_play_time 拿到，但番剧的 playurl 不返回该字段，
     * 因此统一走 x/player/wbi/v2（与 {@link #getSubtitleLinks(long, long)} 是同一个接口）。
     *
     * @param allowSeasonScoped true 表示调用方已经独立证明"这一集就是本季最后观看的那一集"，
     *                          此时即使 last_play_cid 对不上（番剧的该字段服务端基本不维护，
     *                          而 last_play_time 其实是按季保存的）也可以采纳——这是不丢续播能力的关键；
     *                          false 时严格配对，避免把别一集/别一分P的位置套上来。
     */
    public static long getLastPlayProgress(long aid, long cid, boolean allowSeasonScoped) {
        try {
            String json = NetWorkUtil.getJson(ConfInfoApi.signWBI("https://api.bilibili.com/x/player/wbi/v2?aid=" + aid + "&cid=" + cid)).toString();
            SubtitleLinkData data = GsonUtil.fromJson(json, SubtitleLinkData.class);
            if (data == null || data.data == null) {
                ProgressDiag.log("wbi/v2", "响应为空或未登录 aid=" + aid + " cid=" + cid);
                return 0;
            }
            long rawLastPlayTime = data.data.last_play_time;
            long lastPlayTime;
            String how;
            if (allowSeasonScoped) {
                //已由季级状态证明"本集就是本季最近观看的那一集"：last_play_time 此刻在语义上就等于本集位置
                lastPlayTime = rawLastPlayTime > 0 ? rawLastPlayTime : 0;
                how = "季级证明后采纳";
            } else {
                lastPlayTime = adoptLastPlayTime(data.data.last_play_cid, cid, rawLastPlayTime);
                how = lastPlayTime > 0 ? "cid配对采纳" : "cid不配对已丢弃";
            }
            ProgressDiag.log("wbi/v2", "aid=" + aid + " cid=" + cid + " last_play_cid=" + data.data.last_play_cid
                    + " last_play_time=" + rawLastPlayTime + " → " + how + "=" + lastPlayTime);
            if (lastPlayTime <= 0)
                Logu.w("history-last", "未取到本集上次播放进度 aid=" + aid + " cid=" + cid
                        + "（last_play_cid=" + data.data.last_play_cid + " last_play_time=" + rawLastPlayTime + "）");
            else Logu.d("history-last", "aid=" + aid + " cid=" + cid + " last_play_time=" + lastPlayTime);
            return lastPlayTime;
        } catch (Exception e) {
            ProgressDiag.log("wbi/v2", "查询失败: " + e);
            Logu.e("history-last", "获取上次播放进度失败: " + e.getMessage());
            return 0;
        }
    }

    /** 严格配对版本：只有 last_play_cid == cid 才采纳（普通视频/多P视频的既有口径）。 */
    public static long getLastPlayProgress(long aid, long cid) {
        return getLastPlayProgress(aid, cid, false);
    }

    /**
     * 集级续播进度（毫秒，取不到为 0）。番剧每个 episode 有独立 aid/cid，集身份用 epid(集) + cid(流) + aid 表达。
     *
     * **番剧只认"身份与位置在同一条数据里"的来源，只有两层**：
     *
     * 1. 观看记录里**这一集自己的**条目（business=pgc 且 cid/epid/oid 任一命中）——身份与位置写在同一条记录上，
     *    天然不会串，且不需要 WBI，是最可信的来源；
     * 2. 季级状态 (last_ep_id, last_time)：只有 last_ep_id == epid 时才把 last_time 当本集位置（服务端把两者严格配对）。
     *
     * **番剧绝不再回退 {@code x/player/wbi/v2} 的 last_play_time**（上一版这么做过，是"跨集串进度"的残余根因）：
     * 该接口对 PGC 的 last_play_time 是按**季**(kid=ssid)保存的"本季最近观看位置"，而 last_play_cid 并不可靠
     * ——它不是"真正最后观看的那一集的 cid"，经常就是"最近一次被请求过的 cid"（App 自己取流/查字幕时
     * 用目标集的 cid 请求过该接口，服务端就会把它当成 last_play_cid 回给下一次请求）。
     * 于是对它做 `last_play_cid == cid` 的严格配对并不能证明"这个位置属于本集"：
     * 打开一集**从没看过**的新番，cid 配对会通过，而 last_play_time 却是**上一次看的那一集**的位置
     * ——表现就是"看了 A 集，打开没看过的 B 集，却从 A 集的位置开始播"。
     *
     * 两层都证明不了 → 用**本机存档**；再没有才从头播。
     *
     * 为什么必须有本机存档：服务端对本季只维护"最近观看的那一集"这一条位置，
     * 看下一集会把上一集的位置覆盖掉（官方客户端里同样如此）——
     * 也就是说"服务端没有这一集的记录"并不代表"这一集没看过"，
     * 这时只有终端自己存的那份能给出正确的续播位置（见 {@link EpisodeProgressStore}）。
     */
    public static long getEpisodeProgressMs(long aid, long cid, long epid, long seasonId) {
        long mid = NetWorkUtil.getLoginMid();
        ProgressDiag.log("续播查询", "aid=" + aid + " cid=" + cid + " epid=" + epid + " seasonId=" + seasonId
                + " 登录中=" + NetWorkUtil.isLoggedIn() + " 实时mid=" + mid
                + " 本地mid=" + SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0)
                + " " + EpisodeProgressStore.describe(mid, epid, cid));

        //1. 季级配对：服务端对"本季最近观看的那一集"是权威且自洽的 (last_ep_id, last_time)
        BangumiApi.SeasonProgress sp = seasonId != 0 ? BangumiApi.getSeasonProgress(seasonId)
                : new BangumiApi.SeasonProgress();
        boolean isSeasonLastEpisode = sp.known && sp.lastEpid != 0 && sp.lastEpid == epid;
        if (isSeasonLastEpisode && sp.lastProgressMs > 0) {
            ProgressDiag.log("续播结果", "采用季级状态: " + sp.lastProgressMs + "ms（本集=本季最后观看的那一集）");
            EpisodeProgressStore.save(mid, epid, cid, sp.lastProgressMs);   //顺手同步本机存档
            return sp.lastProgressMs;
        }

        //2. 本机存档：服务端只会留"最近观看的那一集"，别的集早就被覆盖了，自己存的那份才是这一集的位置
        long localMs = EpisodeProgressStore.load(mid, epid, cid);
        if (localMs > 0) {
            ProgressDiag.log("续播结果", "采用本机存档: " + localMs + "ms（服务端已无本集记录：季级 lastEpid="
                    + sp.lastEpid + " 本集epid=" + epid + "）");
            return localMs;
        }

        //3. 观看记录里这一集自己的条目（全身份命中）：本机没存档时（比如在别的设备上看过）的兜底
        long historyMs = HistoryApi.findEpisodeProgressMs(cid, aid, epid);
        if (historyMs > 0) {
            ProgressDiag.log("续播结果", "采用观看记录: " + historyMs + "ms");
            EpisodeProgressStore.save(mid, epid, cid, historyMs);
            return historyMs;
        }

        //4. 只有投稿视频才回退 wbi/v2：那种场景 last_play_cid 与 last_play_time 是真正的 aid 级配对数据。
        //   番剧走到这里说明服务端与本机都没有这一集的位置 —— 从头播才对。
        //   判"是番剧"用 epid 或 seasonId 任一非 0：少数分区条目(花絮/PV)的 epid 可能缺失，
        //   但详情页一定会带上 seasonId，不能因为 epid 缺失就退回会串集的投稿视频口径。
        boolean isPgc = epid != 0 || seasonId != 0;
        ProgressDiag.log("续播结果", "本集无可用续播位置，从头播放（季级 known=" + sp.known
                + " lastEpid=" + sp.lastEpid + " 本集epid=" + epid + " seasonId=" + seasonId + "）"
                + (isPgc ? "；番剧不做 wbi/v2 回退（该接口对 PGC 按季维护，无法证明位置属于本集）" : ""));
        if (isPgc) return 0;

        long wbiMs = getLastPlayProgress(aid, cid, false);
        if (wbiMs > 0) {
            ProgressDiag.log("续播结果", "投稿视频回退 wbi/v2: " + wbiMs + "ms");
            return wbiMs;
        }
        return 0;
    }

    /**
     * last_play_time 是与 last_play_cid 配对的"上次播放"进度，只属于那一P。
     * 仅当上次播放的P就是本次请求的P时才作为续播进度采纳；换P播放一律从头开始，
     * 否则会出现"选了P3却从P2的位置开始播"的进度错乱。
     */
    private static int adoptLastPlayTime(long lastPlayCid, long requestCid, long lastPlayTime) {
        if (lastPlayCid != requestCid || lastPlayTime <= 0) return 0;
        return (int) lastPlayTime;
    }

    /**
     * last_play_time 官方文档标注为毫秒，但该字段在 x/player/wbi/v2 中未被文档确证。
     * 这里以毫秒为准，仅当毫秒值超出视频总时长、而换算成秒后落在时长内时按秒处理，避免把一个较大的毫秒值当成越界数据丢弃。
     */
    private static int normalizeProgress(long raw, long durationMs) {
        if (raw <= 0) return 0;
        long ms = raw;
        if (durationMs > 0 && ms > durationMs) {
            long sec = raw * 1000L;
            if (sec <= durationMs) {
                Logu.d("history-last", "last_play_time 疑似单位为秒：" + raw);
                ms = sec;
            } else {
                Logu.e("history-last", "last_play_time 越界已丢弃：" + raw + " / " + durationMs);
                ms = 0;
            }
        }
        //timelength 缺失时没有参照，24 小时兜底，避免异常数据经 int 截断后变成一个离谱的跳转位置
        if (ms > MAX_PROGRESS_MS) {
            Logu.e("history-last", "last_play_time 超出合理范围已丢弃：" + ms);
            ms = 0;
        }
        return (int) ms;
    }

    public static Intent jumpToPlayer(PlayerData playerData) {
        Context context = BiliTerminal.context;
        Intent intent = new Intent();
        switch (SharedPreferencesUtil.getString("player", "null")) {
            case "terminalPlayer":
                intent.setClass(context, PlayerActivity.class);
                intent.putExtra("url", playerData.videoUrl).putExtra("danmaku", playerData.danmakuUrl).putExtra("title", playerData.title).putExtra("aid", playerData.aid).putExtra("cid", playerData.cid).putExtra("mid", playerData.mid).putExtra("progress", playerData.progress).putExtra("live_mode", playerData.isLive());
                //番剧维度随播放器携带，播放中才能周期性走心跳接口上报进度（epid=0 即普通视频，无需传 type）
                intent.putExtra("epid", playerData.epid).putExtra("seasonId", playerData.seasonId).putExtra("seasonType", playerData.seasonType);
                if (playerData.bvid != null && !playerData.bvid.isEmpty())
                    intent.putExtra("bvid", playerData.bvid);
                if (playerData.qnStrList != null && playerData.qnValueList != null) { intent.putExtra("qnStrList", playerData.qnStrList).putExtra("qnValueList", playerData.qnValueList).putExtra("currentQuality", playerData.qn); }
                if (playerData.pagenames != null && playerData.cids != null && playerData.pagenames.size() > 1) {
                    intent.putStringArrayListExtra("pagenames", playerData.pagenames);
                    long[] cidArray = new long[playerData.cids.size()]; for (int i = 0; i < playerData.cids.size(); i++) cidArray[i] = playerData.cids.get(i);
                    intent.putExtra("cids", cidArray).putExtra("currentPageIndex", playerData.currentPageIndex);
                }
                break;
            case "mtvPlayer":
                //显式 setClassName 锁定用户在设置里选择的播放器包名，Intent 不会被第三方应用截获；
                //cookie 是 wearbiliPlayer 拉取清晰度/弹幕的既有协作契约（需要 SESSDATA 的只读会话），
                //但会剥离 bili_jct——那是写操作的 CSRF 令牌，播放器只做读操作，没有下发它的理由
                intent.setClassName(context.getString(R.string.player_package_mtv), "com.xinxiangshicheng.wearbiliplayer.cn.player.PlayerActivity");
                intent.setAction(Intent.ACTION_VIEW).putExtra("cookie", cookieForThirdPartyPlayer()).putExtra("mode", playerData.isLocal() ? "2" : "0").putExtra("url", playerData.videoUrl).putExtra("danmaku", playerData.danmakuUrl).putExtra("title", playerData.title).putExtra("live_mode", playerData.isLive());
                break;
            case "aliangPlayer":
                intent.setClassName(context.getString(R.string.player_package_aliang), "com.aliangmaker.media.PlayVideoActivity");
                intent.putExtra("name", playerData.title).putExtra("danmaku", playerData.danmakuUrl).putExtra("live_mode", playerData.isLive());
                if (playerData.isLocal()) {
                    //本地文件必须走 FileProvider 的 content:// 并授予只读：裸文件路径在 scoped storage 下
                    //对方进程读不了，老系统上也不该依赖对方持有存储权限
                    intent.setData(getVideoUri(context, playerData.videoUrl));
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } else {
                    intent.setData(Uri.parse(playerData.videoUrl));
                    Map<String, String> headers = new HashMap<>(); headers.put("Cookie", cookieForThirdPartyPlayer()); headers.put("Referer", "https://www.bilibili.com/");
                    intent.putExtra("cookie", (Serializable) headers).putExtra("agent", NetWorkUtil.USER_AGENT_WEB).putExtra("progress", playerData.progress * 1000L);
                }
                intent.setAction(Intent.ACTION_VIEW);
                break;
            default: intent.setClass(context, SettingPlayerChooseActivity.class); break;
        }
        return intent;
    }

    public static Uri getVideoUri(Context context, String path) {
        return FileProvider.getUriForFile(context, context.getPackageName() + ".FileProvider", new File(path));
    }

    /**
     * 交给第三方播放器的 Cookie：剥离 bili_jct（写操作 CSRF 令牌）后下发。
     * 播放器只调用读接口（playurl/弹幕/心跳），携 bili_jct 的完整 Cookie 等于把
     * "以用户身份执行任意写操作"的能力一并送出；剥离后即使所选播放器被恶意替换，
     * 泄露面也从"完整账号操作权"缩小为"只读会话"。
     */
    private static String cookieForThirdPartyPlayer() {
        String cookies = SharedPreferencesUtil.getString("cookies", "");
        if (cookies == null || cookies.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String pair : cookies.split(";")) {
            String item = pair.trim();
            if (item.isEmpty()) continue;
            int eq = item.indexOf('=');
            String key = eq == -1 ? item : item.substring(0, eq).trim();
            if (key.equalsIgnoreCase("bili_jct")) continue;   //写令牌绝不下发
            if (sb.length() > 0) sb.append("; ");
            sb.append(item);
        }
        return sb.toString();
    }

    public static SubtitleLink[] getSubtitleLinks(File folder) {
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".json"));
        SubtitleLink[] links = new SubtitleLink[files != null ? files.length + 1 : 1];
        if (files != null) for (int i = 0; i < files.length; i++) links[i] = new SubtitleLink(i, files[i].getName(), files[i].toString(), false);
        links[links.length - 1] = new SubtitleLink(-1, "不显示字幕", "null", false);
        return links;
    }

    public static SubtitleLink[] getSubtitleLinks(long aid, long cid) throws IOException, JSONException {
        String json = NetWorkUtil.getJson(ConfInfoApi.signWBI("https://api.bilibili.com/x/player/wbi/v2?aid=" + aid + "&cid=" + cid)).toString();
        SubtitleLinkData data = GsonUtil.fromJson(json, SubtitleLinkData.class);
        if (data == null || data.data == null || data.data.subtitle == null || data.data.subtitle.subtitles == null)
            return new SubtitleLink[]{new SubtitleLink(-1, "不显示字幕", "null", false)};
        List<SubtitleItem> subs = data.data.subtitle.subtitles;
        SubtitleLink[] links = new SubtitleLink[subs.size() + 1];
        for (int i = 0; i < subs.size(); i++) {
            SubtitleItem s = subs.get(i);
            links[i] = new SubtitleLink(s.id, s.lan_doc, "https:" + s.subtitle_url, s.type == 1);
        }
        links[subs.size()] = new SubtitleLink(-1, "不显示字幕", "null", false);
        return links;
    }

    public static java.util.List<com.RobinNotBad.BiliClient.model.ViewPoint> getViewPoints(long aid, long cid) throws IOException, JSONException {
        String json = NetWorkUtil.getJson(ConfInfoApi.signWBI("https://api.bilibili.com/x/player/wbi/v2?aid=" + aid + "&cid=" + cid)).toString();
        SubtitleLinkData data = GsonUtil.fromJson(json, SubtitleLinkData.class);
        java.util.List<com.RobinNotBad.BiliClient.model.ViewPoint> viewPoints = new ArrayList<>();
        if (data == null || data.data == null || data.data.view_points == null) return viewPoints;
        for (ViewPointData vp : data.data.view_points) {
            if (vp != null) viewPoints.add(new com.RobinNotBad.BiliClient.model.ViewPoint(vp.content, vp.from, vp.to, vp.type, vp.imgUrl, vp.logoUrl));
        }
        return viewPoints;
    }

    public static long getInteractionGraphVersion(long aid, long cid) throws IOException, JSONException {
        String json = NetWorkUtil.getJson(ConfInfoApi.signWBI("https://api.bilibili.com/x/player/wbi/v2?aid=" + aid + "&cid=" + cid)).toString();
        SubtitleLinkData data = GsonUtil.fromJson(json, SubtitleLinkData.class);
        return (data != null && data.data != null && data.data.interaction != null) ? data.data.interaction.graph_version : 0;
    }

    public static Subtitle[] getSubtitle(String url) throws IOException, JSONException {
        String json = NetWorkUtil.getJson(url).toString();
        SubtitleBody body = GsonUtil.fromJson(json, SubtitleBody.class);
        if (body == null || body.body == null) return new Subtitle[0];
        Subtitle[] subtitles = new Subtitle[body.body.size()];
        for (int i = 0; i < body.body.size(); i++) {
            SubtitleEntry e = body.body.get(i);
            subtitles[i] = e != null ? new Subtitle(e.content, e.from, e.to) : new Subtitle("", 0, 0);
        }
        return subtitles;
    }

    public static Subtitle[] getSubtitle(File file) {
        String str = FileUtil.readString(file);
        if (str == null) return null;
        SubtitleBody body = GsonUtil.fromJson(str, SubtitleBody.class);
        if (body == null || body.body == null) return new Subtitle[0];
        Subtitle[] subtitles = new Subtitle[body.body.size()];
        for (int i = 0; i < body.body.size(); i++) {
            SubtitleEntry e = body.body.get(i);
            subtitles[i] = e != null ? new Subtitle(e.content, e.from, e.to) : new Subtitle("", 0, 0);
        }
        return subtitles;
    }

    public static HighEnergyData getHighEnergyData(long cid, long aid) {
        try {
            String url = "https://bvc.bilivideo.com/pbp/data?cid=" + cid + (aid > 0 ? "&aid=" + aid : "");
            JSONObject response = NetWorkUtil.getJson(url, NetWorkUtil.webHeaders);
            if (response == null) return null;
            int code = response.optInt("code", -1);
            if (code != 0 && code != -1) return null;
            HighEnergyData data = new HighEnergyData();
            data.stepSec = response.optInt("step_sec", 10); data.tagStr = response.optString("tagstr", ""); data.debug = response.optString("debug", "");
            JSONObject events = response.optJSONObject("events");
            if (events != null) {
                JSONArray defaultArray = events.optJSONArray("default");
                if (defaultArray != null && defaultArray.length() > 0) {
                    float[] eventData = new float[defaultArray.length()];
                    for (int i = 0; i < defaultArray.length(); i++) eventData[i] = (float) defaultArray.optDouble(i, 0.0);
                    data.events = eventData;
                } else data.events = new float[0];
            } else data.events = new float[0];
            return data;
        } catch (Exception e) { return null; }
    }
}
