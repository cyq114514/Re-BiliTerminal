package com.RobinNotBad.BiliClient.activity.message;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.ui.theme.ThemeApplier;
import com.RobinNotBad.BiliClient.activity.settings.login.AppAuthActivity;
import com.RobinNotBad.BiliClient.activity.user.FollowUsersActivity;
import com.RobinNotBad.BiliClient.adapter.message.ShareUserAdapter;
import com.RobinNotBad.BiliClient.api.ImGrpcApi;
import com.RobinNotBad.BiliClient.api.PrivateMsgApi;
import com.RobinNotBad.BiliClient.model.PrivateMsgSession;
import com.RobinNotBad.BiliClient.model.UserInfo;
import com.RobinNotBad.BiliClient.util.AppAuthStore;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 「分享至消息」底部面板（对齐 PiliPlus SharePanel）。
 *
 * <p>最近会话横向多选 + 「更多」从关注列表选人 + 100 字附言 + 发送。
 * 发送时先尝试 App gRPC 的 msg_type=7 视频卡片；失败（当前最常见的是缺少 App
 * access_key，服务端回 -101）自动降级为 Cookie 通道的文本链接，保证功能可用。
 *
 * <p>发送成功后 dismiss，不跳聊天页（用户要求留在转发界面）。
 */
public class ShareToMsgDialog extends BottomSheetDialogFragment {

    private static final String ARG_AID = "share_aid";
    private static final String ARG_BVID = "share_bvid";
    private static final String ARG_TITLE = "share_title";
    private static final String ARG_COVER = "share_cover";
    private static final String ARG_AUTHOR = "share_author";
    private static final String ARG_AUTHOR_MID = "share_author_mid";

    private final List<ShareUserAdapter.Target> targets = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ShareUserAdapter adapter;
    private EditText input;
    private MaterialButton sendButton;
    private TextView loadingTip;
    private TextView authButton;
    private ActivityResultLauncher<Intent> pickerLauncher;

    private long aid;
    private String bvid = "";
    private String title = "";
    private String cover = "";
    private String author = "";
    private long authorMid;
    private boolean sending;
    private boolean recentLoaded;

    public static ShareToMsgDialog newInstance(long aid, String bvid, String title,
                                               String cover, String author, long authorMid) {
        ShareToMsgDialog fragment = new ShareToMsgDialog();
        Bundle args = new Bundle();
        args.putLong(ARG_AID, aid);
        args.putString(ARG_BVID, bvid);
        args.putString(ARG_TITLE, title);
        args.putString(ARG_COVER, cover);
        args.putString(ARG_AUTHOR, author);
        args.putLong(ARG_AUTHOR_MID, authorMid);
        fragment.setArguments(args);
        return fragment;
    }

    /**
     * 用深色底部面板主题。默认的 Theme.Design.Light.BottomSheetDialog 会让面板变白，
     * 与全应用深色主题冲突。
     */
    @Override
    public int getTheme() {
        return R.style.Theme_BiliClient_BottomSheet;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        if (args != null) {
            aid = args.getLong(ARG_AID, 0);
            bvid = args.getString(ARG_BVID, "");
            title = args.getString(ARG_TITLE, "");
            cover = args.getString(ARG_COVER, "");
            author = args.getString(ARG_AUTHOR, "");
            authorMid = args.getLong(ARG_AUTHOR_MID, 0);
        }
        //回调时 View 可能还没创建；result 里只做数据合并，视图操作都走 post。
        pickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != Activity.RESULT_OK) return;
                    Intent data = result.getData();
                    if (data == null) return;
                    long mid = data.getLongExtra("mid", 0);
                    if (mid <= 0) return;
                    addOrSelect(mid, data.getStringExtra("name"), data.getStringExtra("avatar"));
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.dialog_share_to_msg, container, false);
        //对话框不在 Activity 的 setContentView 路径上，需要单独应用一次主题色
        ThemeApplier.apply(view);
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        RecyclerView userList = view.findViewById(R.id.share_user_list);
        userList.setLayoutManager(new LinearLayoutManager(requireContext(),
                LinearLayoutManager.HORIZONTAL, false));
        adapter = new ShareUserAdapter(targets, this::updateSendState);
        userList.setAdapter(adapter);

        input = view.findViewById(R.id.share_input);
        sendButton = view.findViewById(R.id.share_send);
        loadingTip = view.findViewById(R.id.share_loading);
        authButton = view.findViewById(R.id.btn_app_auth);
        authButton.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), AppAuthActivity.class)));

        view.findViewById(R.id.btn_close).setOnClickListener(v -> dismiss());
        view.findViewById(R.id.btn_more).setOnClickListener(v -> openPicker());
        sendButton.setOnClickListener(v -> send());

        updateSendState();
        updateAuthButton();
        loadRecent();
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacksAndMessages(null);
        adapter = null;
        input = null;
        sendButton = null;
        loadingTip = null;
        authButton = null;
        super.onDestroyView();
    }

    @Override
    public void onResume() {
        super.onResume();
        //从 AppAuthActivity 返回后刷新入口：授权成功则隐藏「卡片授权」
        updateAuthButton();
    }

    private void loadRecent() {
        if (recentLoaded) return;
        recentLoaded = true;
        final long selfMid = SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0);
        CenterThreadPool.run(() -> {
            ArrayList<ShareUserAdapter.Target> loaded = new ArrayList<>();
            String error = null;
            try {
                ArrayList<PrivateMsgSession> sessions = PrivateMsgApi.getSessionsList(5);
                if (sessions != null) {
                    //get_sessions 的 account_info 经常缺失，只剩"用户_123"；补一次用户名片，
                    //否则分享面板里全是默认头像 + 用户_xxx，跟新版 UI 完全不搭
                    ArrayList<Long> needInfo = new ArrayList<>();
                    for (PrivateMsgSession session : sessions) {
                        if (session == null || session.talkerUid <= 0) continue;
                        if (session.talkerUid == selfMid) continue;
                        if (contains(session.talkerUid)) continue;
                        if (TextUtils.isEmpty(session.talkerName) || TextUtils.isEmpty(session.talkerFace))
                            needInfo.add(session.talkerUid);
                    }
                    HashMap<Long, UserInfo> userMap = null;
                    if (!needInfo.isEmpty()) {
                        try {
                            userMap = PrivateMsgApi.getUsersInfo(needInfo);
                        } catch (Throwable ignored) {
                            //名片接口失败不影响分享：退回"用户_uid"+默认头像
                        }
                    }
                    for (PrivateMsgSession session : sessions) {
                        if (session == null || session.talkerUid <= 0) continue;
                        if (session.talkerUid == selfMid) continue;
                        if (contains(session.talkerUid)) continue;
                        String name = session.talkerName;
                        String face = session.talkerFace;
                        UserInfo user = userMap == null ? null : userMap.get(session.talkerUid);
                        if (user != null) {
                            if (TextUtils.isEmpty(name)) name = user.name;
                            if (TextUtils.isEmpty(face)) face = user.avatar;
                        }
                        if (TextUtils.isEmpty(name)) name = "用户_" + session.talkerUid;
                        loaded.add(new ShareUserAdapter.Target(session.talkerUid, name, face));
                    }
                }
            } catch (Throwable e) {
                error = e.getMessage();
            }
            final ArrayList<ShareUserAdapter.Target> result = loaded;
            final String fail = error;
            handler.post(() -> {
                if (!isAdded()) return;
                targets.addAll(result);
                if (adapter != null) adapter.notifyDataSetChanged();
                if (loadingTip != null) {
                    if (!result.isEmpty()) {
                        loadingTip.setVisibility(View.GONE);
                    } else {
                        loadingTip.setVisibility(View.VISIBLE);
                        loadingTip.setText(fail == null
                                ? "暂无最近会话，点「更多」选择用户"
                                : "最近会话加载失败，点「更多」选择用户");
                    }
                }
                updateSendState();
            });
        });
    }

    private boolean contains(long mid) {
        for (ShareUserAdapter.Target target : targets) if (target.mid == mid) return true;
        return false;
    }

    private void openPicker() {
        long selfMid = SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0);
        if (selfMid == 0) {
            MsgUtil.showMsg("请先登录");
            return;
        }
        Intent intent = new Intent(requireContext(), FollowUsersActivity.class);
        intent.putExtra("mid", selfMid);
        intent.putExtra("mode", 0);
        intent.putExtra("pick_mode", true);
        pickerLauncher.launch(intent);
    }

    private void addOrSelect(long mid, String name, String avatar) {
        if (!isAdded()) return;
        for (ShareUserAdapter.Target target : targets) {
            if (target.mid == mid) {
                target.selected = true;
                if (adapter != null) adapter.notifyDataSetChanged();
                updateSendState();
                return;
            }
        }
        ShareUserAdapter.Target target = new ShareUserAdapter.Target(mid,
                TextUtils.isEmpty(name) ? ("用户_" + mid) : name, avatar);
        target.selected = true;
        targets.add(0, target);
        if (adapter != null) adapter.notifyDataSetChanged();
        if (loadingTip != null) loadingTip.setVisibility(View.GONE);
        updateSendState();
    }

    private void updateAuthButton() {
        if (authButton == null) return;
        long selfMid = SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0);
        authButton.setVisibility(selfMid > 0 && !AppAuthStore.hasAccessKey(selfMid)
                ? View.VISIBLE : View.GONE);
    }

    private void updateSendState() {
        if (sendButton == null) return;
        boolean enabled = !sending && adapter != null && adapter.hasSelection();
        sendButton.setEnabled(enabled);
        sendButton.setText(sending ? "发送中…" : "发送");
    }

    private void send() {
        if (sending) return;
        if (adapter == null) return;
        ArrayList<ShareUserAdapter.Target> selected = adapter.getSelected();
        if (selected.isEmpty()) {
            MsgUtil.showMsg("请选择分享的用户");
            return;
        }
        long selfMid = SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0);
        if (selfMid == 0) {
            MsgUtil.showMsg("请先登录");
            return;
        }

        final JSONObject card = buildCardContent();
        final String text = buildMessageText();
        final String textContent;
        try {
            textContent = new JSONObject().put("content", text).toString();
        } catch (Exception e) {
            MsgUtil.showMsg("生成分享内容失败");
            return;
        }

        sending = true;
        updateSendState();
        final long timestamp = System.currentTimeMillis() / 1000;
        CenterThreadPool.run(() -> {
            int cardOk = 0;
            int linkOk = 0;
            int failed = 0;
            String firstCardError = null;
            String firstTextError = null;

            for (ShareUserAdapter.Target target : selected) {
                boolean sent = false;
                //1) 先试 App gRPC 的 msg_type=7 卡片（无 access_key 时会回 -101）
                if (card != null) {
                    ImGrpcApi.Result result = ImGrpcApi.sendShareCard(selfMid, target.mid, card);
                    if (result.success) {
                        cardOk++;
                        sent = true;
                    } else if (firstCardError == null) {
                        firstCardError = result.message;
                    }
                }
                //2) 卡片不可用：降级为现有 Cookie 通道的文本链接
                if (!sent) {
                    try {
                        JSONObject result = PrivateMsgApi.sendMsg(selfMid, target.mid,
                                PrivateMsgApi.MSG_TYPE_TEXT, timestamp, textContent);
                        int code = result != null ? result.optInt("code", -1) : -1;
                        if (code == 0) {
                            linkOk++;
                        } else {
                            failed++;
                            if (firstTextError == null) firstTextError = describeError(result, code);
                        }
                    } catch (Throwable e) {
                        failed++;
                        if (firstTextError == null) firstTextError = e.getMessage();
                    }
                }
            }

            final int cardCount = cardOk;
            final int linkCount = linkOk;
            final int failCount = failed;
            final String cardError = firstCardError;
            final String textError = firstTextError;
            handler.post(() -> {
                if (!isAdded()) return;
                sending = false;
                updateSendState();
                int total = selected.size();
                if (failCount == 0) {
                    if (cardCount == total) {
                        MsgUtil.showMsg("已发送视频卡片");
                    } else if (cardCount > 0) {
                        MsgUtil.showMsg("已发送（" + cardCount + " 张卡片，" + linkCount + " 条链接）");
                    } else {
                        MsgUtil.showMsg(cardFallbackHint(cardError));
                    }
                    dismissAllowingStateLoss();
                } else if (cardCount + linkCount > 0) {
                    MsgUtil.showMsg("部分发送成功：卡片 " + cardCount + "，链接 " + linkCount
                            + "，失败 " + failCount);
                } else {
                    MsgUtil.showMsg("分享失败：" + (TextUtils.isEmpty(textError) ? "未知错误" : textError));
                }
            });
        });
    }

    private JSONObject buildCardContent() {
        if (aid <= 0) return null;
        try {
            JSONObject card = new JSONObject();
            //PiliPlus 发的是字符串 aid；服务端/接收端都能接受，这里保持一致
            card.put("id", String.valueOf(aid));
            card.put("title", title == null ? "" : title);
            card.put("headline", title == null ? "" : title);
            card.put("source", 5);
            if (!TextUtils.isEmpty(cover)) card.put("thumb", cover);
            if (!TextUtils.isEmpty(author)) card.put("author", author);
            if (authorMid > 0) card.put("author_id", String.valueOf(authorMid));
            if (!TextUtils.isEmpty(bvid)) card.put("bvid", bvid);
            return card;
        } catch (Exception e) {
            return null;
        }
    }

    private static String cardFallbackHint(String error) {
        if (error == null) return "已发送链接";
        if (error.contains("-101") || error.contains("access_key") || error.contains("未登录"))
            return "已发送链接（卡片需 App 登录，当前账号未授权）";
        return "已发送链接（卡片失败：" + error + "）";
    }

    private String buildMessageText() {
        String link = TextUtils.isEmpty(bvid)
                ? "https://www.bilibili.com/video/av" + aid
                : "https://www.bilibili.com/video/" + bvid.trim();
        String safeTitle = title == null ? "" : title.trim();
        String base = safeTitle.isEmpty() ? link : safeTitle + "  " + link;
        String comment = input == null ? "" : input.getText().toString().trim();
        return comment.isEmpty() ? base : comment + "\n" + base;
    }

    private static String describeError(JSONObject result, int code) {
        String message = result == null ? "" : result.optString("message", "");
        switch (code) {
            case -101:
                return "账号未登录";
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
                return TextUtils.isEmpty(message) ? ("错误码 " + code) : message;
        }
    }
}
