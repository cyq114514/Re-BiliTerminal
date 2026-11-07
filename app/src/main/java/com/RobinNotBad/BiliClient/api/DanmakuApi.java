package com.RobinNotBad.BiliClient.api;

import com.RobinNotBad.BiliClient.model.DmSegMobileReply;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.ProtobufParser;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import okhttp3.Response;

//弹幕api

public class DanmakuApi {

    // 发送弹幕
    public static int sendVideoDanmakuByBvid(long cid, String msg, String bvid, long progress, int color, int mode)
            throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/v2/dm/post";
        String arg = "type=1&oid=" + cid + "&msg=" + NetWorkUtil.urlEncode(msg) + "&bvid=" + bvid + "&progress=" + progress + "&color=" + color
                + "&mode=" + mode + "&rnd=" + (System.currentTimeMillis() * 1000000) + "&csrf="
                + NetWorkUtil.getLoginCsrf();
        JSONObject result = new JSONObject(
                Objects.requireNonNull(NetWorkUtil.post(url, arg, NetWorkUtil.webHeaders).body()).string());
        Logu.i(result.toString());
        return result.getInt("code"); // https://socialsisteryi.github.io/bilibili-API-collect/docs/danmaku/action.html#%E5%8F%91%E9%80%81%E8%A7%86%E9%A2%91%E5%BC%B9%E5%B9%95
    }

    public static int sendVideoDanmakuByAid(long cid, String msg, long aid, long progress, int color, int mode)
            throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/v2/dm/post";
        String arg = "type=1&oid=" + cid + "&msg=" + NetWorkUtil.urlEncode(msg) + "&aid=" + aid + "&progress=" + progress + "&color=" + color
                + "&mode=" + mode + "&rnd=" + (System.currentTimeMillis() * 1000000) + "&csrf="
                + NetWorkUtil.getLoginCsrf();
        JSONObject result = new JSONObject(
                Objects.requireNonNull(NetWorkUtil.post(url, arg, NetWorkUtil.webHeaders).body()).string());
        Logu.i(result.toString());
        return result.getInt("code"); // https://socialsisteryi.github.io/bilibili-API-collect/docs/danmaku/action.html#%E5%8F%91%E9%80%81%E8%A7%86%E9%A2%91%E5%BC%B9%E5%B9%95
    }

    /**
     * 发送直播弹幕（1.3.3 用户许愿：直播发弹幕）。
     * POST https://api.live.bilibili.com/msg/send，需登录 cookie + csrf；
     * 表单字段对照 PiliPlus {@code LiveHttp.sendLiveMsg}（bubble/color/mode/room_type/
     * jumpfrom/statistics 等固定值，rnd 为秒级时间戳，roomid=直播间 id）。
     */
    public static int sendLiveDanmaku(long roomId, String msg) throws IOException, JSONException {
        String url = "https://api.live.bilibili.com/msg/send";
        String arg = "bubble=0&mode=1&msg=" + NetWorkUtil.urlEncode(msg)
                + "&color=16777215&fontsize=25&mode=1&room_type=0&jumpfrom=0"
                + "&statistics=" + NetWorkUtil.urlEncode("{\"appId\":100,\"platform\":5}")
                + "&rnd=" + (System.currentTimeMillis() / 1000)
                + "&roomid=" + roomId
                + "&csrf=" + NetWorkUtil.getLoginCsrf()
                + "&csrf_token=" + NetWorkUtil.getLoginCsrf();
        JSONObject result = new JSONObject(
                Objects.requireNonNull(NetWorkUtil.post(url, arg, NetWorkUtil.webHeaders).body()).string());
        Logu.i(result.toString());
        return result.getInt("code");
    }

    // 点赞弹幕
    public static int likeDanmaku(long dmid, long cid, int op) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/v2/dm/thumbup/add";
        String arg = "oid=" + cid + "&dmid=" + dmid + "&op=" + op + "&platform=web_player" + "&csrf="
                + NetWorkUtil.getLoginCsrf();
        JSONObject result = new JSONObject(
                Objects.requireNonNull(NetWorkUtil.post(url, arg, NetWorkUtil.webHeaders).body()).string());
        Logu.i(result.toString());
        return result.getInt("code");
    }

    // 撤回弹幕
    public static int recallDanmaku(long dmid, long cid) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/dm/recall";
        // 文档里就是cid，不是oid
        String arg = "cid=" + cid + "&dmid=" + dmid + "&csrf=" + NetWorkUtil.getLoginCsrf();
        JSONObject result = new JSONObject(
                Objects.requireNonNull(NetWorkUtil.post(url, arg, NetWorkUtil.webHeaders).body()).string());
        Logu.i(result.toString());
        return result.getInt("code");
    }

    /**
     * 获取视频弹幕（新版 API，返回 protobuf 格式）
     * 使用分段获取方式，每段 6 分钟
     *
     * @param aid          稿件 avid
     * @param cid          视频 cid
     * @param segmentIndex 分包索引（6min 一包，从 0 开始）
     * @return 弹幕分段响应
     * @throws IOException   IO异常
     * @throws JSONException JSON异常
     */
    public static DmSegMobileReply getVideoDanmakuSegment(long aid, long cid, int segmentIndex)
            throws IOException, JSONException {
        byte[] data = getVideoDanmakuSegmentBytes(aid, cid, segmentIndex);

        // 解析 protobuf 数据
        DmSegMobileReply reply = ProtobufParser.parseDmSegMobileReply(data);
        Logu.d("新版弹幕API", "分段 " + segmentIndex + " 获取到 " + reply.elems.size() + " 条弹幕");

        return reply;
    }

    /**
     * 无签名通道本次运行内已被证明不可用时置位。
     *
     * <p>弹幕是逐段串行拉的（1 小时视频约 11 段），如果不记住失败，每段都要先白试一次
     * 再回退，等于把请求数与等待时间翻倍。
     */
    private static volatile boolean plainSegUnavailable = false;

    /** 无签名端点（PiliPlus 的 webDanmaku 口径）。实测真机可用且稳定，作主通道 */
    private static final String SEG_URL_PLAIN = "https://api.bilibili.com/x/v2/dm/web/seg.so";
    /** WBI 签名端点，作回退通道 */
    private static final String SEG_URL_WBI = "https://api.bilibili.com/x/v2/dm/wbi/web/seg.so";

    /**
     * 获取单个分段的原始 protobuf 字节（不解析），供磁盘缓存直接落盘复用。
     *
     * <p>两条 protobuf 通道，返回的数据相同，只有前缀与签名方式不同：
     * <ol>
     *   <li>{@code /x/v2/dm/web/seg.so}（<b>不需要签名</b>）—— 主通道。这条就是 B站 文档与
     *       PiliPlus 的 {@code webDanmaku} 用的那个端点；真机日志实测稳定返回 protobuf。</li>
     *   <li>{@code /x/v2/dm/wbi/web/seg.so}（WBI 签名）—— 回退通道。签名键过期、风控或参数
     *       不被接受时它会整段失败。</li>
     * </ol>
     * 两条 URL 的前缀不同（{@code wbi} 段是签名端点的固有部分），早先的实现把两条写成了同一个
     * 地址，等于"回退通道"并不存在。
     */
    public static byte[] getVideoDanmakuSegmentBytes(long aid, long cid, int segmentIndex)
            throws IOException, JSONException {
        String args = "?type=1&oid=" + cid + "&pid=" + aid + "&segment_index=" + segmentIndex;
        if (!plainSegUnavailable) {
            try {
                return fetchSeg(SEG_URL_PLAIN + args, segmentIndex, "无签名");
            } catch (Exception e) {
                //只在第一次失败时打日志：后续分段直接走回退通道，不再逐段刷屏
                plainSegUnavailable = true;
                Logu.w("新版弹幕API", "无签名通道不可用（" + e.getMessage() + "），本次运行改用 WBI 通道");
            }
        }
        return fetchSeg(ConfInfoApi.signWBI(SEG_URL_WBI + args), segmentIndex, "WBI");
    }

    private static byte[] fetchSeg(String url, int segmentIndex, String channel) throws IOException {
        Response response = NetWorkUtil.get(url, NetWorkUtil.webHeaders);
        byte[] data = Objects.requireNonNull(response.body()).bytes();
        if (data.length == 0) {
            //空响应是"这个分段之后没有弹幕了"的正常表示，上层据此停止翻段。
            //这里若当成错误，会把正常结束变成异常，还会白试后面的每一个分段并把通道标记为不可用
            return data;
        }
        //真正的错误体是 JSON（以 '{'=0x7B 开头）。直接当 protobuf 解析只会得到空分段，
        //被上层误判成"这个视频没有弹幕"并静默回退，故障现象会变得非常难查——这里显式判掉
        if (data[0] == '{') {
            throw new IOException(channel + " 通道分段 " + segmentIndex + " 返回 JSON 而非 protobuf");
        }
        return data;
    }

    /**
     * 分段聚合结果：rawSegments 与 parsed 一一对应。
     * DmSegMobileReply 是手写解析对象无法反向序列化，需要磁盘复用时由调用方落盘 rawSegments。
     */
    public static class DanmakuSegments {
        public final List<byte[]> rawSegments = new ArrayList<>();
        public final List<DmSegMobileReply> parsed = new ArrayList<>();
    }

    /** 时长未知时最多探查的分段数（40 段 = 4 小时）：只作兜底上限，正常靠空段提前退出 */
    private static final int MAX_SEGMENTS_WHEN_DURATION_UNKNOWN = 40;
    /** 时长未知时，连续多少个空段才判定"到片尾" */
    private static final int EMPTY_STREAK_TO_STOP = 3;

    /**
     * 获取视频所有弹幕（新版 API），自动分段获取。
     * 额外保留每段原始字节供磁盘缓存复用。
     *
     * <p><b>分段数只由真实时长决定</b>（用户反馈 2026-11-07：长视频 66 分钟后弹幕消失）。
     * 66 分钟 = 11 段 × 6 分钟，正是"兜底 3600 秒"算出来的段数；此前还把"某段为空"当成
     * "到达片尾"，而服务端对没有弹幕的分段会回 HTTP 304 + 空体（实测 seg 11/12 就是 304），
     * 中间出现 6~12 分钟没人发弹幕的冷门长视频会被整段截断。
     *
     * <p>因此：<b>时长已知就按 ceil(时长/360) 一段不漏地拉满，中途空段跳过但不停</b>；
     * 只有时长未知（调用方也没拿到）时才退化成"连续 {@link #EMPTY_STREAK_TO_STOP} 段为空即停"，
     * 并封顶 {@link #MAX_SEGMENTS_WHEN_DURATION_UNKNOWN} 段。
     *
     * @param aid         稿件 avid
     * @param cid         视频 cid
     * @param maxDuration 视频真实时长（秒）；&lt;=0 表示未知
     */
    public static DanmakuSegments getAllVideoDanmakuWithRaw(long aid, long cid, int maxDuration)
            throws IOException, JSONException {
        DanmakuSegments result = new DanmakuSegments();

        final boolean durationKnown = maxDuration > 0;
        // 每段 6 分钟 = 360 秒，向上取整：3900 秒的视频要 11 段（0~66 分钟），不能只取 10 段
        int segmentCount = durationKnown
                ? (maxDuration + 359) / 360
                : MAX_SEGMENTS_WHEN_DURATION_UNKNOWN;

        Logu.d("新版弹幕API", "视频时长: " + maxDuration + "秒（" + (durationKnown ? "已知" : "未知")
                + "）, 需要获取 " + segmentCount + " 个分段");

        int consecutiveEmpty = 0;
        for (int i = 0; i < segmentCount; i++) {
            try {
                byte[] raw = getVideoDanmakuSegmentBytes(aid, cid, i + 1);
                DmSegMobileReply segment = ProtobufParser.parseDmSegMobileReply(raw);
                if (segment != null && !segment.elems.isEmpty()) {
                    result.rawSegments.add(raw);
                    result.parsed.add(segment);
                    consecutiveEmpty = 0;
                } else {
                    consecutiveEmpty++;
                    Logu.d("新版弹幕API", "分段 " + (i + 1) + " 为空（连续 " + consecutiveEmpty + "）");
                    // 时长已知：空段只说明这 6 分钟没人发弹幕，后面还有，继续拉
                    if (!durationKnown && consecutiveEmpty >= EMPTY_STREAK_TO_STOP) {
                        Logu.d("新版弹幕API", "时长未知且连续 " + EMPTY_STREAK_TO_STOP + " 段为空，判定到达末尾，停止获取");
                        break;
                    }
                }
            } catch (Exception e) {
                Logu.e("新版弹幕API", "获取分段 " + (i + 1) + " 失败: " + e.getMessage());
                // 单段失败继续下一段：弱网下整段丢失比整片没有好
            }
        }

        int totalDanmaku = 0;
        for (DmSegMobileReply segment : result.parsed) {
            totalDanmaku += segment.elems.size();
        }
        Logu.d("新版弹幕API", "共获取到 " + totalDanmaku + " 条弹幕");

        return result;
    }
}
