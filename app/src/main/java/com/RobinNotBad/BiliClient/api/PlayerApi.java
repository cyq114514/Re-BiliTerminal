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
import com.RobinNotBad.BiliClient.model.ClipInfo;
import com.RobinNotBad.BiliClient.model.DashAudioStream;
import com.RobinNotBad.BiliClient.model.DashData;
import com.RobinNotBad.BiliClient.model.DashVideoStream;
import com.RobinNotBad.BiliClient.model.HighEnergyData;
import com.RobinNotBad.BiliClient.model.PlayerData;
import com.RobinNotBad.BiliClient.model.Subtitle;
import com.RobinNotBad.BiliClient.model.SubtitleLink;
import com.RobinNotBad.BiliClient.model.VideoInfo;
import com.RobinNotBad.BiliClient.service.DownloadService;
import com.RobinNotBad.BiliClient.util.CookieGenerator;
import com.RobinNotBad.BiliClient.util.FileUtil;
import com.RobinNotBad.BiliClient.util.GsonUtil;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.PlayerSentinel;
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

    //番剧取流：v2 才有 play_view_business_info（观看进度），v1 只做兜底
    private static final String PGC_PLAYURL_V2 = "https://api.bilibili.com/pgc/player/web/v2/playurl";
    private static final String PGC_PLAYURL_V1 = "https://api.bilibili.com/pgc/player/web/playurl";

    public static class PlayUrlData {
        @SerializedName("durl") public List<DurlItem> durl;
        @SerializedName("dash") public DashData dash;
        @SerializedName("last_play_cid") public long last_play_cid;
        @SerializedName("last_play_time") public int last_play_time;
        @SerializedName("timelength") public long timelength;
        @SerializedName("accept_description") public List<String> accept_description;
        @SerializedName("accept_quality") public List<Integer> accept_quality;
        //官方下发的片头/片尾片段（实测路径 result.video_info.clip_info_list）。
        //v1/v2 的流信息都是这个模型，因此两条路径都能拿到，不需要额外请求
        @SerializedName("clip_info_list") public List<ClipInfo> clip_info_list;
    }

    public static class DurlItem {
        @SerializedName("url") public String url;
        /** 备用地址：B 站对 1080P 的 mp4 首地址常常给 PCDN，官方 upos CDN 只出现在这里 */
        @SerializedName("backup_url") public List<String> backup_url;
    }

    public static class PlayUrlResult {
        @SerializedName("result") public PlayUrlData result;
    }

    /** pgc/player/web/v2/playurl：流信息在 result.video_info，观看进度在 result.play_view_business_info。 */
    public static class PgcPlayUrlResult {
        @SerializedName("result") public PgcPlayUrlData result;
    }

    public static class PgcPlayUrlData {
        @SerializedName("video_info") public PlayUrlData video_info;
        @SerializedName("play_view_business_info") public PgcBusinessInfo play_view_business_info;
    }

    public static class PgcBusinessInfo {
        @SerializedName("episode_info") public PgcEpisodeInfo episode_info;
        @SerializedName("user_status") public PgcUserStatus user_status;
    }

    /** 服务端认定的"这次请求的是哪一集/哪条流"：拿它给进度做身份校验，不能只信调用方传参。 */
    public static class PgcEpisodeInfo {
        @SerializedName("ep_id") public long ep_id;
        @SerializedName("cid") public long cid;
    }

    public static class PgcUserStatus {
        @SerializedName("watch_progress") public WatchProgress watch_progress;
    }

    /**
     * 服务端保存的番剧观看进度（只有 pgc/player/web/v2/playurl 会返回）。
     *
     * <p>**单位：这里三个字段全部是毫秒**（诊断实测：本集位置 421 秒时
     * {@code current_watch_progress=421000}；同一位置在季级状态接口
     * {@code pgc/view/web/season/user/status} 里是 {@code last_time=421}（秒）——
     * 两个接口的同名字段单位不同，不能互相套用）。
     *
     * <p><b>{@code current_watch_progress} 的"集级"语义只在"每集有独立 aid"的番剧上成立。</b>
     * 早期注释曾据 season 1178（CLANNAD，每集 aid 各不相同）断言"服务端按集保存、不需要本地记录"，
     * 该结论对 season 1564（攻壳 SAC，**整季共享 aid=135433**）不成立——真机日志场景：
     * 打开 ep28960 返回 {@code current=687000}；打开 ep28961 后返回 {@code current=0, last_ep_id=28960}；
     * 再打开 ep28960 时变成 {@code current=0, last_ep_id=28961}
     * ——即这类番剧的 {@code current_watch_progress} 实际是"本季最近一集"的位置，切集后先前那集会被清零。
     *
     * <p>因此判断"本集有没有记录"不能只看 {@code watchProgress != null}，
     * 必须配合 {@code last_ep_id == 本集 epid} 才成立（见 {@link #getEpisodeProgressMs}）。
     * 对这类番剧，唯一可靠的按集数据源是观看记录 {@code x/web-interface/history/cursor}，
     * 其条目带 epid/cid，能精确区分同季的不同集。
     */
    public static class WatchProgress {
        @SerializedName("current_watch_progress") public long current_watch_progress;
        @SerializedName("last_ep_id") public long last_ep_id;
        @SerializedName("last_time") public long last_time;
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
        //播放哨兵起点：放在"用户点播放"这一层，连跳转页都没起来就闪退的情况也能留下线索
        PlayerSentinel.begin("点击播放");
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

    /**
     * DASH 双流下载（1080P 高码率/4K 等 durl 拿不到的清晰度）：
     * 下载服务会分别拉视频与音频流并用 MediaMerger 合成单个 MP4。
     * audioUrl 只作入库兜底，真正下载时服务端会重新取新链接（旧链接几小时就过期）。
     */
    public static void startDownloadingDash(VideoInfo videoInfo, int page, int qn, String audioUrl) {
        if (videoInfo.cids.size() == 1) DownloadService.startDownload(videoInfo.title, videoInfo.aid, videoInfo.cids.get(0), videoInfo.cover, qn, "video_dash", audioUrl);
        else DownloadService.startDownload(videoInfo.title, videoInfo.pagenames.get(page), videoInfo.aid, videoInfo.cids.get(page), videoInfo.cover, qn, "video_dash", audioUrl);
    }

    /**
     * 把 playurl 下发的地址整理成「下载候选列表」：去重 + 官方 CDN 优先（PiliPlus 的 getCdnUrl 同思路）。
     *
     * <p>实测 B 站现在给 1080P 的 mp4（format=mp4720）与 DASH 流，首地址大量是 PCDN：
     * {@code *.edge.mountaintoys.cn:4483}、{@code *.mcdn.bilivideo.cn:8082}；而 backup_url
     * 里始终带着 {@code upos-sz-*.bilivideo.com}。下载器按本列表顺序尝试，
     * 官方线路优先（实测快 5 倍），PCDN 作兜底（能下，只是慢）。
     */
    public static ArrayList<String> buildMediaCandidates(String primary, List<String> backups) {
        ArrayList<String> all = new ArrayList<>();
        if (primary != null && !primary.isEmpty()) all.add(primary);
        if (backups != null) {
            for (String b : backups) {
                if (b != null && !b.isEmpty() && !all.contains(b)) all.add(b);
            }
        }
        ArrayList<String> trusted = new ArrayList<>();
        ArrayList<String> others = new ArrayList<>();
        for (String u : all) {
            if (isTrustedMediaHost(u)) {
                trusted.add(u);
            } else if (isHttpsUrl(u)) {
                //第三方 PCDN 只保留 https：这类地址本就不该拿到登录 Cookie（见 mediaHeadersFor），
                //再走明文等于把播放内容也一并交给中间人，直接从候选里剔除
                others.add(u);
            }
        }
        ArrayList<String> ordered = new ArrayList<>(trusted.size() + others.size());
        ordered.addAll(trusted);
        ordered.addAll(others);
        return ordered;
    }

    /** 该地址是否属于 B 站自己的域名（可用于携带登录 Cookie 的媒体请求）。 */
    public static boolean isTrustedMediaHost(String url) {
        try {
            okhttp3.HttpUrl parsed = okhttp3.HttpUrl.parse(url);
            return parsed != null && NetWorkUtil.isBilibiliHost(parsed.host());
        } catch (Exception e) {
            return false;
        }
    }

    /** 是否是 https 地址（无法解析的一律按不安全处理）。 */
    private static boolean isHttpsUrl(String url) {
        try {
            okhttp3.HttpUrl parsed = okhttp3.HttpUrl.parse(url);
            return parsed != null && parsed.isHttps();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 媒体（视频/音频流）请求头的唯一出口。
     *
     * <p>playurl 下发的候选地址里混着第三方 PCDN（如 {@code *.edge.mountaintoys.cn:4483}），
     * 把带着 SESSDATA/bili_jct 的完整 Cookie 发给它，等于把登录态交给第三方 CDN 运营方。
     * 下载链路一直按域剥离 Cookie（见 {@code DownloadService} 的 isBilibiliHost 判定），
     * 内置播放器与短视频此前漏了这一层，这里收口成一处，避免再各写一套。
     */
    public static Map<String, String> mediaHeadersFor(String url) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://www.bilibili.com/");
        if (isTrustedMediaHost(url)) {
            headers.put("Cookie", CookieGenerator.getCookieString(true));
        }
        return headers;
    }

    public static void getVideoDash(PlayerData playerData) throws IOException, JSONException {
        getVideoDash(playerData, 16);
    }

    /**
     * DASH 取流。fnval 是位掩码：16=基础 DASH，4048=DASH|HDR|4K|杜比|8K|AV1 全开——
     * 4K(qn=120) 及以上不带对应位时服务端不会返回该清晰度的流。
     */
    public static void getVideoDash(PlayerData playerData, int fnval) throws IOException, JSONException {
        playerData.danmakuUrl = "https://comment.bilibili.com/" + playerData.cid + ".xml";
        String url = "https://api.bilibili.com/x/player/wbi/playurl?avid=" + playerData.aid + "&cid=" + playerData.cid + "&qn=" + playerData.qn + "&fnval=" + fnval + "&fnver=0&platform=pc&voice_balance=1&gaia_source=pre-load&isGaiaAvoided=true";
        String json = NetWorkUtil.getJson(ConfInfoApi.signWBI(url), NetWorkUtil.webHeaders).toString();
        ApiResponse<PlayUrlData> resp = GsonUtil.fromJson(json, new com.google.gson.reflect.TypeToken<ApiResponse<PlayUrlData>>(){}.getType());
        if (resp == null || !resp.isSuccess() || resp.data == null) throw new JSONException("获取播放地址失败");
        PlayUrlData data = resp.data;

        if (data.dash != null) {
            playerData.dashData = data.dash;
            DashVideoStream vs = playerData.dashData.getVideoStream(playerData.qn);
            if (vs != null) {
                playerData.videoUrls = buildMediaCandidates(vs.baseUrl, vs.backupUrl);
                playerData.videoUrl = playerData.videoUrls.isEmpty() ? vs.baseUrl : playerData.videoUrls.get(0);
            }
            DashAudioStream as = playerData.dashData.getBestAudioStream();
            if (as != null) {
                playerData.audioUrls = buildMediaCandidates(as.baseUrl, as.backupUrl);
                playerData.audioUrl = playerData.audioUrls.isEmpty() ? as.baseUrl : playerData.audioUrls.get(0);
            }
            //解析层曾经"静默成功"：dash 字段在、但流全空（Gson 字段名对不上），
            //调用方拿到 null 只会提示"没有可用的音频流"，把解析 bug 伪装成内容问题
            if (playerData.videoUrls.isEmpty() && playerData.audioUrls.isEmpty())
                throw new JSONException("DASH 解析结果为空：音视频流都未取到");
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
        playerData.videoUrls = buildMediaCandidates(data.durl.get(0).url, data.durl.get(0).backup_url);
        playerData.videoUrl = playerData.videoUrls.isEmpty() ? data.durl.get(0).url : playerData.videoUrls.get(0);
        playerData.cidHistory = data.last_play_cid; playerData.progress = adoptLastPlayTime(data.last_play_cid, playerData.cid, data.last_play_time);
        if (playerData.cidHistory == 0) { playerData.cidHistory = playerData.cid; playerData.progress = 0; }
        if (data.accept_description != null && data.accept_quality != null) {
            playerData.qnStrList = data.accept_description.toArray(new String[0]);
            int[] qnValueList = new int[data.accept_quality.size()];
            for (int i = 0; i < qnValueList.length; i++) qnValueList[i] = data.accept_quality.get(i);
            playerData.qnValueList = qnValueList;
        }
    }

    /**
     * 番剧取流 + 续播位置。
     *
     * <p>取流走 {@code pgc/player/web/v2/playurl}（PiliPlus 同款）：只有 v2 的响应里带
     * {@code play_view_business_info.user_status.watch_progress}，那是"这一集自己的观看进度"；
     * 老 v1 接口的 result 里完全没有这些字段，只能靠季级状态兜底。
     * v2 取不到 durl（服务端返回了业务错误/接口形态变化）时回退 v1：能播，但本轮拿不到集级进度；
     * v2 在网络层失败则直接快速失败——v1 也走同一套网络栈，再试一轮只会把等待翻倍。
     */
    public static void getBangumi(PlayerData playerData) throws IOException, JSONException {
        String session = ToolsUtil.md5(String.valueOf(System.currentTimeMillis() - SystemClock.currentThreadTimeMillis()));
        //season_type 用调用方带来的真实值：硬编码 1 在「国创/电视剧/综艺」等分区会让服务端
        //按错误的剧集类型取流，可能干脆不返回 play_view_business_info.user_status.watch_progress，
        //集级续播的第 1 层会直接失效。没有真实值时仍兜底为 1（番剧），与修复前行为一致
        int realSeasonType = playerData.seasonType != 0 ? playerData.seasonType : 1;
        NetWorkUtil.FormData params = new NetWorkUtil.FormData().setUrlParam(true)
                .put("aid", playerData.aid).put("cid", playerData.cid).put("fnval", 1).put("fnver", 0)
                .put("qn", playerData.qn).put("season_type", realSeasonType)
                .put("session", session).put("platform", "pc");
        //番剧进度是按集(ep_id)保存的，请求必须带上目标集；season_id 用于兜底查季级状态
        if (playerData.epid != 0) params.put("ep_id", playerData.epid);
        if (playerData.seasonId != 0) params.put("season_id", playerData.seasonId);

        PlayUrlData stream = null;
        WatchProgress watchProgress = null;
        //服务端自己认定的集身份：epid 缺失（从历史/搜索进来只有 cid）时也拿得到，用于给进度做身份校验
        long confirmedEpid = 0;
        try {
            PgcPlayUrlResult v2 = GsonUtil.fromJson(
                    NetWorkUtil.getJson(PGC_PLAYURL_V2 + params).toString(), PgcPlayUrlResult.class);
            if (v2 != null && v2.result != null) {
                stream = v2.result.video_info;
                PgcBusinessInfo business = v2.result.play_view_business_info;
                if (business != null) {
                    if (business.episode_info != null) {
                        confirmedEpid = business.episode_info.ep_id;
                        //响应的流与请求的流必须是同一条，否则说明服务端按别的 cid 回的，
                        //这时它的进度字段不属于本集，直接丢弃（身份与位置必须来自同一条数据）
                        if (business.episode_info.cid != 0 && business.episode_info.cid != playerData.cid) {
                            ProgressDiag.log("服务端进度", "响应 cid=" + business.episode_info.cid
                                    + " 与请求 cid=" + playerData.cid + " 不一致，丢弃进度字段");
                            confirmedEpid = 0;
                        } else if (business.user_status != null) {
                            watchProgress = business.user_status.watch_progress;
                        }
                    } else if (business.user_status != null) {
                        watchProgress = business.user_status.watch_progress;
                    }
                }
            }
        } catch (IOException e) {
            //网络层失败（超时/断网）时不再回退 v1：getJson 内部自带重试（api_retry_max_times，默认 5 次），
            //v2 已经把重试额度用完，再对 v1 重复一轮只会把"起播失败"的等待翻倍，而结果必然相同
            ProgressDiag.log("番剧取流", "v2 网络异常，快速失败不回退 v1: " + e);
            throw e;
        } catch (Exception e) {
            //响应解析失败（服务端返回了非预期结构）才回退 v1：接口形态可能变化，老接口还能撑一阵
            ProgressDiag.log("番剧取流", "v2 响应异常，回退 v1: " + e);
        }
        if (stream == null || stream.durl == null || stream.durl.isEmpty()) {
            ProgressDiag.log("番剧取流", "v2 未取到 durl，回退 v1（本轮无集级进度字段）epid=" + playerData.epid);
            PlayUrlResult v1 = GsonUtil.fromJson(
                    NetWorkUtil.getJson(PGC_PLAYURL_V1 + params).toString(), PlayUrlResult.class);
            if (v1 == null || v1.result == null || v1.result.durl == null || v1.result.durl.isEmpty())
                throw new JSONException("获取番剧播放地址失败");
            stream = v1.result;
            watchProgress = null;
            confirmedEpid = 0;
        }
        //续播解析用的集身份：优先调用方给的 epid，缺失时用服务端回显的 ep_id。
        //顺便补齐 playerData.epid——历史/搜索入口只有 cid，缺了它播放器的心跳上报会整条跳过
        long identityEpid = playerData.epid != 0 ? playerData.epid : confirmedEpid;
        if (playerData.epid == 0 && identityEpid != 0) playerData.epid = identityEpid;

        playerData.videoUrls = buildMediaCandidates(stream.durl.get(0).url, stream.durl.get(0).backup_url);
        playerData.videoUrl = playerData.videoUrls.isEmpty() ? stream.durl.get(0).url : playerData.videoUrls.get(0);
        playerData.danmakuUrl = "https://comment.bilibili.com/" + playerData.cid + ".xml";
        playerData.cidHistory = playerData.cid;
        //片头/片尾片段：随取流响应一起下发，直接交给播放器做跳过判定（派生的展示数据，取不到就是 null）
        playerData.clipInfoList = stream.clip_info_list;
        ProgressDiag.log("服务端进度", "epid=" + playerData.epid + "/服务端=" + confirmedEpid
                + " v2 watch_progress=" + describeWatchProgress(watchProgress)
                + " timelength=" + stream.timelength);
        long lastProgress = getEpisodeProgressMs(playerData.aid, playerData.cid, identityEpid,
                playerData.seasonId, watchProgress, stream.timelength);
        if (lastProgress > 0)
            Logu.d("history-last", "番剧续播命中 epid=" + playerData.epid + " " + lastProgress + "ms");
        else
            Logu.w("history-last", "番剧未取到本集续播进度，从头播放 epid=" + playerData.epid
                    + " aid=" + playerData.aid + " cid=" + playerData.cid);
        playerData.progress = normalizeProgress(lastProgress, stream.timelength);
        ProgressDiag.log("开播续播", "「" + playerData.title + "」epid=" + playerData.epid
                + " aid=" + playerData.aid + " cid=" + playerData.cid + " bvid=" + playerData.bvid
                + " sid=" + playerData.seasonId + " subType=" + playerData.seasonType
                + " timelength=" + stream.timelength
                + " 原始=" + lastProgress + "ms → 最终=" + playerData.progress + "ms");
        if (stream.accept_description != null && stream.accept_quality != null) {
            playerData.qnStrList = stream.accept_description.toArray(new String[0]);
            int[] qnValueList = new int[stream.accept_quality.size()];
            for (int i = 0; i < qnValueList.length; i++) qnValueList[i] = stream.accept_quality.get(i);
            playerData.qnValueList = qnValueList;
        }
    }

    public static String describeWatchProgress(WatchProgress wp) {
        if (wp == null) return "无（v1 回退或字段缺失）";
        return "{current=" + wp.current_watch_progress + ",last_ep_id=" + wp.last_ep_id + ",last_time=" + wp.last_time + "}";
    }

    /** 只回读 v2 取流接口的 watch_progress（诊断用：确认本集进度有没有真的落库，不参与播放决策）。 */
    public static WatchProgress queryPgcWatchProgress(long aid, long cid, long epid, long seasonId) {
        return queryPgcWatchProgress(aid, cid, epid, seasonId, 0);
    }

    /**
     * 同上，可指定真实 season_type。
     * 回读必须与取流用同一组参数，否则在非番剧分区（国创/电视剧/综艺）上会出现
     * "取流拿得到进度、自检却说没落库"的假阴性。
     */
    public static WatchProgress queryPgcWatchProgress(long aid, long cid, long epid, long seasonId, int seasonType) {
        try {
            String session = ToolsUtil.md5(String.valueOf(System.currentTimeMillis() - SystemClock.currentThreadTimeMillis()));
            NetWorkUtil.FormData params = new NetWorkUtil.FormData().setUrlParam(true)
                    .put("aid", aid).put("cid", cid).put("fnval", 1).put("fnver", 0).put("qn", 32)
                    .put("season_type", seasonType != 0 ? seasonType : 1)
                    .put("session", session).put("platform", "pc");
            if (epid != 0) params.put("ep_id", epid);
            if (seasonId != 0) params.put("season_id", seasonId);
            PgcPlayUrlResult v2 = GsonUtil.fromJson(
                    NetWorkUtil.getJson(PGC_PLAYURL_V2 + params).toString(), PgcPlayUrlResult.class);
            if (v2 == null || v2.result == null || v2.result.play_view_business_info == null
                    || v2.result.play_view_business_info.user_status == null) return null;
            return v2.result.play_view_business_info.user_status.watch_progress;
        } catch (Exception e) {
            ProgressDiag.log("服务端进度", "回读 v2 watch_progress 失败: " + e);
            return null;
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
     * <p>口径与 PiliPlus 一致：**不在本地保存任何进度**，读取顺序全部来自服务端的同一条数据
     * （身份与位置写在一起），因此天然不会串集：
     *
     * <ol>
     *   <li>{@code pgc/player/web/v2/playurl} 的 {@code watch_progress.current_watch_progress}——
     *       本次请求这一集自己的续播位置（**毫秒**），直接采用（PiliPlus 就是这么取 {@code lastPlayTime} 的）；</li>
     *   <li>同一个响应里的 {@code last_ep_id}/{@code last_time}：只有 {@code last_ep_id == 本集 epid}
     *       时才拿 {@code last_time}（**毫秒**，注意季级状态接口的同名字段是秒）当本集位置；</li>
     *   <li>季级状态接口 {@code pgc/view/web/season/user/status}（v1 回退或字段缺失时才查）；</li>
     *   <li>观看记录里**这一集自己的**条目（business=pgc 且 cid/epid/oid 全身份命中）。</li>
     * </ol>
     *
     * <p>**番剧绝不回退 {@code x/player/wbi/v2} 的 last_play_time**（v3 之前这么做过，是"跨集串进度"的根因）：
     * 该接口对 PGC 的 last_play_time 是按**季**保存的"本季最近观看位置"，而 last_play_cid 并不可靠
     * ——它不是"真正最后观看的那一集的 cid"，经常就是"最近一次被请求过的 cid"。
     * 于是 `last_play_cid == cid` 的严格配对并不能证明"这个位置属于本集"：
     * 打开一集**从没看过**的新番，cid 配对会通过，而 last_play_time 却是**上一次看的那一集**的位置。
     *
     * <p>四层都证明不了 → 从头播（该集确实没有任何观看记录）。
     *
     * <p>**单位这件事踩过坑，记在这里**：v6 曾按"两个字段相差 1000 倍即换算"做自校准，
     * 结果实测里 {@code current} 与 {@code last_time} 本来就是同一个毫秒值（都是 421000），
     * 被误判成秒再 ×1000，越界丢弃后**每一次续播都变成 0**——表现就是"完全读不到官方客户端已记录的进度"。
     * 现已确认：这两个字段恒为毫秒，不做任何单位猜测（见 {@link WatchProgress}）。
     */
    public static long getEpisodeProgressMs(long aid, long cid, long epid, long seasonId,
                                            WatchProgress watchProgress, long durationMs) {
        ProgressDiag.log("续播查询", "aid=" + aid + " cid=" + cid + " epid=" + epid + " seasonId=" + seasonId
                + " 服务端进度=" + describeWatchProgress(watchProgress));

        //1. 服务端按集保存的本集进度（PiliPlus 口径，毫秒）
        long adopted = 0;
        String source = null;
        if (watchProgress != null && watchProgress.current_watch_progress > 0) {
            adopted = watchProgress.current_watch_progress;
            source = "服务端按集进度 current_watch_progress";
            if (watchProgress.last_ep_id != 0 && watchProgress.last_ep_id != epid
                    && adopted == watchProgress.last_time)
                //仅提示：两者数值相同只是巧合（本集自己看到的位置恰好等于季级那条记录），按集级采用
                ProgressDiag.log("续播结果", "注：本集进度与季级位置数值相同（本集 epid=" + epid
                        + "，季级 last_ep_id=" + watchProgress.last_ep_id + "），仍按集级采用");
            if (durationMs > 0 && adopted > durationMs) {
                //位置不可能超过总时长：只可能是异常数据，宁可从头播也不要跳到一个离谱的位置
                ProgressDiag.log("续播结果", "服务端按集进度 " + adopted + "ms 超过总时长 " + durationMs + "ms，按从头播放处理");
                adopted = 0;
                source = null;
            }
        }

        //2. 本集就是"本季最近观看"那一集，但集级字段为空：同响应的 last_time（毫秒）就是本集位置
        if (adopted <= 0 && watchProgress != null && epid != 0
                && watchProgress.last_ep_id == epid && watchProgress.last_time > 0) {
            adopted = watchProgress.last_time;
            source = "季级(同响应 last_time)";
        }

        //3. v1 回退或字段缺失时才查季级状态接口（多一次请求）
        BangumiApi.SeasonProgress sp = null;
        if (adopted <= 0 && seasonId != 0) {
            sp = BangumiApi.getSeasonProgress(seasonId);
            if (sp.known && sp.lastEpid != 0 && sp.lastEpid == epid && sp.lastProgressMs > 0) {
                adopted = sp.lastProgressMs;
                source = "季级状态接口";
            }
        }
        if (adopted > 0) {
            ProgressDiag.log("续播结果", "采用" + source + ": " + adopted + "ms（epid=" + epid + "）");
            return adopted;
        }

        //4. 观看记录里这一集自己的条目（全身份命中）。
        //
        //   关键修复：不能用「v2 返回了 watch_progress 对象」当作「本集已有记录」。
        //   真机诊断日志（season 1564 整季共享 aid=135433）证实：服务端对这类番剧的
        //   current_watch_progress 语义是「本季最近观看的那一集」，切到别的集后先前那集会被清零——
        //   此时 watch_progress 对象仍然存在（只是 current=0、last_ep_id 指向别的集），
        //   若据此跳过扫描，就把唯一按 epid/cid 分集的观看记录源丢掉了，
        //   表现为「只有最近一集能续播、其它集一律从头播」。
        //
        //   真正的「本集无记录」只在「本集就是本季最近一集、服务端仍说没有位置」时成立。
        //   注意第 1 层因位置越界被丢弃（adopted 归零）时 current 仍 > 0，这里同样不该跳过扫描。
        boolean serverConfirmsNoRecordForThisEp = watchProgress != null
                && watchProgress.current_watch_progress <= 0
                && epid != 0
                && watchProgress.last_ep_id == epid;
        if (serverConfirmsNoRecordForThisEp) {
            ProgressDiag.log("续播结果", "服务端确认本集无记录（本集即最近一集且 current=0），跳过观看记录扫描");
        } else {
            //扫描只在第 1/2/3 层全部未命中时才会走到，页数上限沿用 LOCATE_MAX_PAGES；
            //命中的记录通常就在第一页（刚上报的那条一定在最前），深度扫描只在"很久没看这一集"时发生
            long historyMs = HistoryApi.findEpisodeProgressMs(cid, aid, epid);
            if (historyMs > 0) {
                ProgressDiag.log("续播结果", "采用观看记录: " + historyMs + "ms");
                return historyMs;
            }
        }

        //5. 只有投稿视频才回退 wbi/v2：那种场景 last_play_cid 与 last_play_time 是真正的 aid 级配对数据。
        //   判"是番剧"用 epid 或 seasonId 任一非 0：少数分区条目(花絮/PV)的 epid 可能缺失，
        //   但详情页一定会带上 seasonId，不能因为 epid 缺失就退回会串集的投稿视频口径。
        boolean isPgc = epid != 0 || seasonId != 0;
        ProgressDiag.log("续播结果", "本集无可用续播位置，从头播放（季级 lastEpid="
                + (sp != null ? sp.lastEpid : -1) + " 本集epid=" + epid + " seasonId=" + seasonId + "）"
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
                //片头/片尾片段随取流结果一起带进播放器。
                //只对内置播放器传：外部播放器有自己的跳过能力，也无从读取本应用的参数
                if (playerData.clipInfoList != null && !playerData.clipInfoList.isEmpty())
                    intent.putParcelableArrayListExtra("clipInfoList",
                            new ArrayList<>(playerData.clipInfoList));
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

    /**
     * 高能进度条数据（新版）：取自 x/player/wbi/v2 响应的 view_points 字段。
     *
     * <p>旧数据源 {@code bvc.bilivideo.com/pbp/data} 已失效：2026-10-31 实测对 Rick Astley MV、
     * 番剧 ep（cid=22742297）、12.8 万弹幕的 av2 全部返回 404 HTML 页，带不带
     * Referer/buvid3 无差别，App 内表现为"开始加载数据→7 秒重试→未获取到有效数据"。
     * 该端点连同重试一起弃用。
     *
     * <p>view_points 匿名请求实测恒为空数组（含 buvid3 也是），需要登录 cookie 才有数据；
     * 本方法与同接口其余调用方（续播进度/字幕）一样走全局 webHeaders（带 cookie）+ WBI 签名，
     * 口径一致，且播放时本来就要请求该接口，无额外端点。
     *
     * <p>view_points 元素为 {from,to,score}（单位秒，score 为该时间段的弹幕热度），
     * 这里映射为固定 10s 步长的密度数组，供 HighEnergyProgressBar 绘制波形。
     *
     * @return 无数据/接口异常时返回 null，调用方按"本次无高能数据"处理
     */
    public static HighEnergyData getHighEnergyViewPoints(long aid, long cid) {
        try {
            JSONObject response = NetWorkUtil.getJson(ConfInfoApi.signWBI(
                    "https://api.bilibili.com/x/player/wbi/v2?aid=" + aid + "&cid=" + cid));
            JSONObject data = response.optJSONObject("data");
            if (data == null) {
                Logu.d("高能进度条", "wbi/v2 响应无 data（未登录？） aid=" + aid + " cid=" + cid);
                return null;
            }
            JSONArray points = data.optJSONArray("view_points");
            if (points == null || points.length() == 0) {
                Logu.d("高能进度条", "view_points 为空 aid=" + aid + " cid=" + cid
                        + "（若登录态下仍为空，则该功能当前无可用数据源）");
                return null;
            }
            //登录态验证诊断：首条原始数据直接进日志，复测时看这里就知道字段格式是否如预期
            Logu.d("高能进度条", "view_points 点数=" + points.length()
                    + " 首条=" + points.optJSONObject(0));

            HighEnergyData result = new HighEnergyData();
            result.stepSec = 10;
            result.tagStr = "view_points";
            //先扫出最大 to 定桶数（波形的横向覆盖范围）
            double maxTo = 0;
            for (int i = 0; i < points.length(); i++) {
                JSONObject p = points.optJSONObject(i);
                if (p != null) maxTo = Math.max(maxTo, p.optDouble("to", 0));
            }
            int buckets = (int) Math.ceil(maxTo / result.stepSec);
            if (buckets <= 0) return null;
            float[] events = new float[buckets];
            for (int i = 0; i < points.length(); i++) {
                JSONObject p = points.optJSONObject(i);
                if (p == null) continue;
                double from = p.optDouble("from", -1);
                double to = p.optDouble("to", -1);
                double score = p.optDouble("score", 0);
                if (to <= from || from < 0) continue;
                int begin = (int) Math.max(0, Math.floor(from / result.stepSec));
                int end = (int) Math.min(buckets - 1, Math.ceil(to / result.stepSec) - 1);
                for (int b = begin; b <= end; b++) events[b] = (float) Math.max(events[b], score);
            }
            result.events = events;
            return result;
        } catch (Exception e) {
            Logu.e("高能进度条", "view_points 加载失败: " + e.getMessage());
            return null;
        }
    }
}
