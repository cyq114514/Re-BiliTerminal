package com.RobinNotBad.BiliClient.api;

import android.util.Base64;

import com.RobinNotBad.BiliClient.util.AppAuthStore;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.UUID;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * B 站 App 端 IM gRPC 的最小实现（只做 SendMsg）。
 *
 * <p>背景：网页端 {@code web_im/v1/web_im/send_msg} 只支持 msg_type 1/2/5，
 * 视频分享卡片（msg_type=7）只能走 App 的 gRPC 接口
 * {@code /bilibili.im.interface.v1.ImInterface/SendMsg}。
 *
 * <p>协议：HTTP POST + content-type application/grpc，body 是 5 字节 gRPC 帧
 * （压缩位 + 4 字节大端长度）+ protobuf。这里手写 protobuf 编码，避免为几个字段引入
 * protobuf 运行时（minSdk 19 也不想再加 dex 体积）。
 *
 * <p>鉴权：优先使用 App 登录得到的 access_key（authorization: identify_v1 &lt;key&gt;）；
 * 同时把网页 Cookie 一并带上，便于在未接入 App 登录时验证 Cookie 是否也能被接受。
 * 两者都没有时服务端会返回 -101。
 */
public final class ImGrpcApi {

    private static final String URL =
            "https://app.bilibili.com/bilibili.im.interface.v1.ImInterface/SendMsg";
    private static final String USER_AGENT =
            "Mozilla/5.0 BiliDroid/2.0.1 (bbcallen@gmail.com) os/android model/android_hd "
                    + "mobi_app/android_hd build/2001100 channel/master innerVer/2001100 "
                    + "osVer/15 network/2";
    private static final MediaType GRPC = MediaType.parse("application/grpc");
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private ImGrpcApi() {
    }

    public static final class Result {
        public boolean success;
        /** bili-status-code 或 gRPC message；成功时为 0 */
        public int code;
        public String message = "";
    }

    /**
     * 发送一条 msg_type=7 的视频分享卡片。
     *
     * @param content 卡片 JSON：id/title/headline/source=5/thumb/author/author_id/bvid
     */
    public static Result sendShareCard(long senderUid, long receiverUid, JSONObject content) {
        Result result = new Result();
        try {
            byte[] msg = buildMsg(senderUid, receiverUid, 7, content.toString());
            Proto req = new Proto();
            req.message(1, msg);
            req.string(5, UUID.randomUUID().toString());
            byte[] frame = grpcFrame(req.toByteArray());

            Request.Builder builder = new Request.Builder()
                    .url(URL)
                    .post(RequestBody.create(GRPC, frame))
                    .header("Content-Type", "application/grpc")
                    .header("TE", "trailers")
                    .header("grpc-encoding", "identity")
                    .header("grpc-accept-encoding", "gzip,identity")
                    .header("user-agent", USER_AGENT)
                    .header("bili-http-engine", "cronet")
                    .header("x-bili-trace-id", UUID.randomUUID().toString().replace("-", ""));

            String cookies = SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "");
            if (!cookies.isEmpty()) builder.header("Cookie", cookies);

            String accessKey = AppAuthStore.getAccessKey(senderUid);
            String buvid = NetWorkUtil.getInfoFromCookie("buvid3", cookies);
            if (buvid == null || buvid.isEmpty())
                buvid = SharedPreferencesUtil.getString("buvid3", "");
            if (buvid == null) buvid = "";
            if (!buvid.isEmpty()) builder.header("buvid", buvid);
            if (accessKey != null && !accessKey.isEmpty())
                builder.header("authorization", "identify_v1 " + accessKey);

            builder.header("x-bili-aurora-zone", "")
                    .header("x-bili-gaia-vtoken", "")
                    .header("x-bili-exps-bin", "")
                    .header("x-bili-network-bin", b64(network()))
                    .header("x-bili-locale-bin", b64(locale()))
                    .header("x-bili-fawkes-req-bin", b64(fawkes()))
                    .header("x-bili-device-bin", b64(device(buvid)))
                    .header("x-bili-metadata-bin", b64(metadata(buvid, accessKey)));

            Response response = NetWorkUtil.getOkHttpInstance().newCall(builder.build()).execute();
            try {
                String grpcStatus = response.header("grpc-status");
                String biliCode = response.header("bili-status-code");
                String grpcMessage = response.header("grpc-message");
                result.success = "0".equals(grpcStatus);
                result.code = parseInt(biliCode, parseInt(grpcMessage, -1));
                if (!result.success) result.message = describe(result.code, grpcMessage);
            } finally {
                response.close();
            }
        } catch (Throwable e) {
            result.success = false;
            result.code = -1;
            result.message = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        return result;
    }

    /** gRPC 帧：1 字节压缩位 + 4 字节大端长度 + protobuf */
    private static byte[] grpcFrame(byte[] body) {
        byte[] frame = new byte[body.length + 5];
        frame[0] = 0;
        frame[1] = (byte) ((body.length >>> 24) & 0xFF);
        frame[2] = (byte) ((body.length >>> 16) & 0xFF);
        frame[3] = (byte) ((body.length >>> 8) & 0xFF);
        frame[4] = (byte) (body.length & 0xFF);
        System.arraycopy(body, 0, frame, 5, body.length);
        return frame;
    }

    /** bilibili.im.type.Msg */
    private static byte[] buildMsg(long senderUid, long receiverUid, int msgType, String content) {
        Proto msg = new Proto();
        msg.int64(1, senderUid);          // senderUid
        msg.int32(2, 1);                  // receiverType = 用户
        msg.int64(3, receiverUid);        // receiverId
        msg.int32(5, msgType);            // msgType
        msg.string(6, content);           // content
        msg.int64(8, System.currentTimeMillis() / 1000); // timestamp
        msg.int32(16, 1);                 // newFaceVersion
        return msg.toByteArray();
    }

    /** bilibili.metadata.device.Device */
    private static byte[] device(String buvid) {
        Proto p = new Proto();
        p.int32(1, 5);                    // appId
        p.int32(2, 2001100);              // build
        p.string(3, buvid);               // buvid
        p.string(4, "android_hd");        // mobiApp
        p.string(5, "android");           // platform
        p.string(7, "master");            // channel
        p.string(8, "android");           // brand
        p.string(9, "android");           // model
        p.string(10, "15");               // osver
        p.string(13, "2.0.1");            // versionName
        return p.toByteArray();
    }

    /** bilibili.metadata.Metadata */
    private static byte[] metadata(String buvid, String accessKey) {
        Proto p = new Proto();
        p.string(1, accessKey);           // accessKey（可为空）
        p.string(2, "android_hd");        // mobiApp
        p.string(3, "android");           // device
        p.int32(4, 2001100);              // build
        p.string(5, "master");            // channel
        p.string(6, buvid);               // buvid
        p.string(7, "android");           // platform
        return p.toByteArray();
    }

    /** bilibili.metadata.network.Network（WIFI=1） */
    private static byte[] network() {
        Proto p = new Proto();
        p.int32(1, 1);
        return p.toByteArray();
    }

    /** bilibili.metadata.locale.Locale + LocaleIds */
    private static byte[] locale() {
        Proto ids = new Proto();
        ids.string(1, "zh");
        ids.string(2, "Hans");
        ids.string(3, "CN");

        Proto p = new Proto();
        p.message(1, ids.toByteArray());
        p.message(2, ids.toByteArray());
        p.string(4, "Asia/Shanghai");
        return p.toByteArray();
    }

    /** bilibili.metadata.fawkes.FawkesReq */
    private static byte[] fawkes() {
        Proto p = new Proto();
        p.string(1, "android_hd");
        p.string(2, "prod");
        p.string(3, UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        return p.toByteArray();
    }

    private static String b64(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isEmpty()) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String describe(int code, String grpcMessage) {
        switch (code) {
            case -101:
                return "账号未登录（缺少 App access_key）";
            case 21015:
                return "账号未绑定手机号，无法发送私信";
            case 21020:
                return "发送太快，请稍后再试";
            case 21035:
                return "该类消息暂不支持";
            case 21047:
                return "对方未关注/回复前只能发 1 条消息";
            case 25003:
                return "对方隐私设置无法接收私信";
            default:
                return grpcMessage == null || grpcMessage.isEmpty()
                        ? ("发送失败（" + code + "）") : grpcMessage;
        }
    }

    /**
     * 极简 protobuf 编码器。只支持本项目用到的 varint / length-delimited 两种 wire type。
     */
    private static final class Proto {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        void int32(int field, int value) {
            if (value == 0) return;
            tag(field, 0);
            varint(value & 0xFFFFFFFFL);
        }

        void int64(int field, long value) {
            if (value == 0) return;
            tag(field, 0);
            varint(value);
        }

        void string(int field, String value) {
            if (value == null || value.isEmpty()) return;
            byte[] bytes = value.getBytes(UTF8);
            tag(field, 2);
            varint(bytes.length);
            out.write(bytes, 0, bytes.length);
        }

        void message(int field, byte[] bytes) {
            if (bytes == null || bytes.length == 0) return;
            tag(field, 2);
            varint(bytes.length);
            out.write(bytes, 0, bytes.length);
        }

        private void tag(int field, int wireType) {
            varint(((long) field << 3) | wireType);
        }

        private void varint(long value) {
            while ((value & ~0x7FL) != 0) {
                out.write((int) ((value & 0x7F) | 0x80));
                value >>>= 7;
            }
            out.write((int) value);
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }
    }
}
