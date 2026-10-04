package com.RobinNotBad.BiliClient.activity.player;

import android.graphics.Color;

import androidx.annotation.NonNull;

import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.netease.hearttouch.brotlij.Brotli;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.ref.WeakReference;
import java.nio.charset.Charset;
import java.util.Timer;
import java.util.TimerTask;

import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class PlayerDanmuClientListener extends WebSocketListener {
    public long mid = 0;
    public long roomid = 0;
    public String key = "";
    private int seq = 1;
    private final MessageData messageData = new MessageData();

    private Timer heartTimer = null;

    //弱引用持有 Activity：onDestroy 里的 ws.close 不保证触发 onClosed（如进程级销毁），
    //强引用会让心跳线程连着整个 Activity + View 树 + 已释放的 IjkPlayer 一起泄漏
    private WeakReference<PlayerActivity> playerActivityRef;

    public void setPlayerActivity(PlayerActivity activity) {
        playerActivityRef = new WeakReference<>(activity);
    }

    /**Activity onDestroy 时显式调用：无条件停掉心跳，不等 onClosed/onFailure 回调。*/
    public void destroy() {
        cancelHeartTimer();
        if (playerActivityRef != null) playerActivityRef.clear();
    }

    private void cancelHeartTimer() {
        if (heartTimer != null) {
            heartTimer.cancel();
            heartTimer = null;
        }
    }

    //弹幕与人数更新必须回到主线程：本类回调跑在 OkHttp 的 WS reader 线程上，
    //直接操作 DanmakuView 会与 DFM 绘制线程并发改弹幕队列（错乱/CME）
    private void postToUi(PlayerActivity activity, Runnable action) {
        if (activity == null || activity.isDestroyed() || activity.isFinishing()) return;
        activity.runOnUiThread(action);
    }

    private PlayerActivity activity() {
        return playerActivityRef != null ? playerActivityRef.get() : null;
    }


    @Override
    public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
        super.onOpen(webSocket, response);
        Logu.v("live-ws", "WebSocket已连接");

        cancelHeartTimer();

        //发送认证包
        try {
            JSONObject object = new JSONObject();
            if (SharedPreferencesUtil.getBoolean("live_by_guest", false)) object.put("uid", 0);
            else object.put("uid", mid);
            object.put("roomid", roomid);
            object.put("protover", 3);
            object.put("platform", "web");
            object.put("buvid", NetWorkUtil.getCookies().getOrDefault("buvid3", ""));
            object.put("type", 2);
            object.put("key", key);

            webSocket.send(messageData.getData(3, 7, object.toString().getBytes(Charset.forName("UTF-8"))));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private class MessageData {

        private byte[] getPacket(int protocolVersion, int actionCode, byte... data) {
            int headerSize = 16;
            int totalSize = headerSize + data.length;
            byte[] packet = new byte[totalSize];

            packet[0] = (byte) (totalSize >> 24);
            packet[1] = (byte) (totalSize >> 16);
            packet[2] = (byte) (totalSize >> 8);
            packet[3] = (byte) (totalSize);

            packet[4] = (byte) (0);
            packet[5] = (byte) (headerSize);

            packet[6] = (byte) 0;
            packet[7] = (byte) protocolVersion;

            packet[8] = (byte) 0;
            packet[9] = (byte) 0;
            packet[10] = (byte) 0;
            packet[11] = (byte) actionCode;

            packet[12] = (byte) (seq >> 24);
            packet[13] = (byte) (seq >> 16);
            packet[14] = (byte) (seq >> 8);
            packet[15] = (byte) (seq);
            seq++;

            System.arraycopy(data, 0, packet, headerSize, data.length);
            //不打印包内容：认证包（action=7）含直播间 token/key，且原生 Log 不受日志开关控制
            return packet;
        }

        public ByteString getData(int protocolVersion, int actionCode, byte... data) {
            return ByteString.of(getPacket(protocolVersion, actionCode, data));
        }

        public ByteString getBrotliData(int protocolVersion, int actionCode, byte... data) {
            byte[] encodedData = Brotli.compress(getPacket(protocolVersion, actionCode, data));
            return ByteString.of(getPacket(3, actionCode, encodedData));
        }

    }

    @Override
    public void onMessage(@NonNull WebSocket webSocket, @NonNull ByteString bytes) {
        super.onMessage(webSocket, bytes);

        int actionCode = bytes.getByte(11);
        switch (actionCode) {
            case 8:
                Logu.v("live-ws", "弹幕流认证成功");
                //重连会再次收到认证包：新建前必须取消旧心跳，否则旧 Timer 线程累积泄漏
                cancelHeartTimer();
                TimerTask heartTimerTask = new TimerTask() {
                    @Override
                    public void run() {
                        Logu.v("live-ws", "发送心跳包");
                        try {
                            webSocket.send(messageData.getData(1, 2, "".getBytes(Charset.forName("UTF-8"))));
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                };
                heartTimer = new Timer();
                heartTimer.schedule(heartTimerTask, 3000, 32000);
                break;

            case 5:
                plainPackage(bytes);
                break;

            default:
                break;
        }
    }

    @Override
    public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
        super.onClosed(webSocket, code, reason);
        Logu.v("live-ws", "WebSocket连接关闭：" + reason + "(" + code + ")");

        cancelHeartTimer();
    }

    @Override
    public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, Response response) {
        super.onFailure(webSocket, t, response);

        Writer writer = new StringWriter();
        PrintWriter printWriter = new PrintWriter(writer);
        t.printStackTrace(printWriter);

        Logu.v("live-ws", "WebSocket连接失败：" + writer);

        cancelHeartTimer();
    }

    //处理普通包
    private void plainPackage(ByteString bytes) {
        try {
            JSONObject result;

            //有些包不会压缩，要判断一下，虽然方式有点（
            ByteString bytes2 = bytes.substring(bytes.getByte(5));
            if (Brotli.decompress(bytes2.toByteArray()).length > 5) {
                ByteString bytes3 = ByteString.of(Brotli.decompress(bytes2.toByteArray()));
                result = new JSONObject(bytes3.substring(bytes3.getByte(5)).utf8()); //问就是懒得用别的方式sub
            } else if (bytes2.utf8().contains("{"))
                result = new JSONObject(bytes2.utf8().substring(bytes2.utf8().indexOf("{")));
            else return;

            JSONObject data;
            PlayerActivity activity = activity();
            switch (result.getString("cmd")) {

                //聊天弹幕
                case "DANMU_MSG": {
                    JSONArray info = result.getJSONArray("info");
                    String nickname = info.getJSONArray(0).getJSONObject(15).getJSONObject("user").getJSONObject("base").getString("name");
                    String content = info.getString(1);
                    final String text = SharedPreferencesUtil.getBoolean("player_danmaku_showsender", true)
                            ? nickname + "：" + content : content;
                    postToUi(activity, () -> activity.addDanmaku(text, Color.WHITE));
                    break;
                }

                //看过的人数
                case "WATCHED_CHANGE": {
                    data = result.getJSONObject("data");
                    final PlayerActivity act = activity;
                    if (act != null) {
                        final String watched = data.getString("text_large");
                        //online_number 由 UI 线程的 onlineTimer 读取，跨线程写也收口到主线程
                        postToUi(act, () -> act.online_number = watched);
                    }
                    break;
                }

                case "INTERACT_WORD": {
                    data = result.getJSONObject("data");

                    //进入直播间
                    if (data.getInt("msg_type") == 1) {
                        final String uname = data.getString("uname");
                        postToUi(activity, () -> activity.addDanmaku(uname + " 进入了直播间", Color.CYAN, 12, 4, 0));
                    }

                    break;
                }

                //送礼弹幕
                case "SEND_GIFT": {
                    data = result.getJSONObject("data");
                    final String content2 = data.getString("uname") + " " + data.getString("action") + data.getInt("num") + "个" + data.getString("giftName");
                    postToUi(activity, () -> activity.addDanmaku(content2, Color.WHITE, 25, 1, Color.argb(160, 255, 80, 80)));
                    break;
                }

                //特殊入场
                case "ENTRY_EFFECT": {
                    data = result.getJSONObject("data");
                    final String content3 = data.getString("copy_writing").replace("<%", "").replace("%>", "");
                    postToUi(activity, () -> activity.addDanmaku(content3, Color.WHITE, 25, 1, Color.argb(160, 80, 80, 255)));
                    break;
                }

                //通知消息
                case "NOTICE_MSG": {
                    final String msgCommon = result.getString("msg_common");
                    postToUi(activity, () -> activity.addDanmaku(msgCommon, Color.RED, 25, 1, Color.argb(60, 255, 255, 255)));
                    break;
                }

                //直播间消息修改
                case "ROOM_CHANGE": {
                    data = result.getJSONObject("data");
                    final PlayerActivity act = activity;
                    if (act != null) {
                        final String title = data.getString("title");
                        postToUi(act, () -> {
                            try {
                                act.text_title.setText(title);
                            } catch (Exception ignore) {
                            }
                        });
                    }
                    break;
                }

                default:
                    break;
            }

        } catch (Exception e) {
            Writer writer = new StringWriter();
            PrintWriter printWriter = new PrintWriter(writer);
            e.printStackTrace(printWriter);

            Logu.v("live-ws", "解析普通包时错误：" + writer);
        }
    }
}
