package com.RobinNotBad.BiliClient.activity.message;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity;
import com.RobinNotBad.BiliClient.adapter.message.PrivateMsgSessionsAdapter;
import com.RobinNotBad.BiliClient.api.MessageApi;
import com.RobinNotBad.BiliClient.api.PrivateMsgApi;
import com.RobinNotBad.BiliClient.helper.TutorialHelper;
import com.RobinNotBad.BiliClient.model.PrivateMsgSession;
import com.RobinNotBad.BiliClient.model.UserInfo;
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

public class MessageActivity extends InstanceActivity {
    private RecyclerView sessionsView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private PrivateMsgSessionsAdapter sessionsAdapter;
    private boolean viewReady;
    private boolean firstResume = true;
    private boolean loadingSessions;
    private boolean loadingUnread;

    @SuppressLint({"SetTextI18n", "InflateParams"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        asyncInflate(R.layout.activity_message, (layoutView, resId) -> {
            SwipeRefreshLayout swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
            this.swipeRefreshLayout = swipeRefreshLayout;
            //此前禁用下拉刷新，会话/未读只能靠重进页面更新；打开后返回消息页也能手动刷
            swipeRefreshLayout.setEnabled(true);
            swipeRefreshLayout.setOnRefreshListener(this::refreshAll);
            swipeRefreshLayout.setRefreshing(true);

            View settingBtn = findViewById(R.id.setting_btn);
            settingBtn.setOnClickListener(view -> {
                Intent intent = new Intent(this, MessageSettingsActivity.class);
                startActivity(intent);
            });

            View reply = findViewById(R.id.reply);
            reply.setOnClickListener(view -> {
                Intent intent = new Intent();
                intent.setClass(this, NoticeActivity.class);
                intent.putExtra("type", "reply");
                startActivity(intent);
                ((TextView) findViewById(R.id.reply_text)).setText("回复我的");
            });

            View like = findViewById(R.id.like);
            like.setOnClickListener(view -> {
                Intent intent = new Intent();
                intent.setClass(this, NoticeActivity.class);
                intent.putExtra("type", "like");
                startActivity(intent);
                ((TextView) findViewById(R.id.like_text)).setText("收到的赞");
            });

            View at = findViewById(R.id.at);
            at.setOnClickListener(view -> {
                Intent intent = new Intent();
                intent.setClass(this, NoticeActivity.class);
                intent.putExtra("type", "at");
                startActivity(intent);
                ((TextView) findViewById(R.id.at_text)).setText("@我");
            });

            View system = findViewById(R.id.system);
            system.setOnClickListener(view -> {
                Intent intent = new Intent();
                intent.setClass(this, NoticeActivity.class);
                intent.putExtra("type", "system");
                startActivity(intent);
            });

            sessionsView = findViewById(R.id.sessions_list);
            sessionsView.setNestedScrollingEnabled(false);

            viewReady = true;
            loadUnread();
            loadSessions();

            View scrollView = findViewById(R.id.scrollView);
            scrollView.setFocusable(true);
            scrollView.setFocusableInTouchMode(true);
            scrollView.requestFocus();

            TutorialHelper.showTutorialList(this, R.array.tutorial_message, 5);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        //首次 onResume 由 onCreate 的 loadUnread/loadSessions 负责；
        //从私信页返回时再拉一次，会话未读徽章立即消失。
        if (firstResume) {
            firstResume = false;
            return;
        }
        if (viewReady) refreshAll();
    }

    private void refreshAll() {
        if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(true);
        loadUnread();
        loadSessions();
    }

    /** 顶部三个未读数；与会话列表分开请求，任何一个失败不影响另一个。 */
    private void loadUnread() {
        if (loadingUnread) return;
        loadingUnread = true;
        CenterThreadPool.run(() -> {
            JSONObject stats = null;
            Exception error = null;
            try {
                stats = MessageApi.getUnread();
            } catch (Exception e) {
                error = e;
            }
            final JSONObject finalStats = stats;
            final Exception finalError = error;
            runOnUiThread(() -> {
                loadingUnread = false;
                if (!viewReady || isFinishing() || isDestroyed()) return;
                if (finalError != null) {
                    MsgUtil.err(finalError);
                    return;
                }
                if (finalStats == null) return;
                try {
                    //2×2 卡片每格只有半屏宽，未读数用紧凑的 (n)，不再写「n未读」
                    ((TextView) findViewById(R.id.reply_text)).setText(
                            "回复我的" + unreadSuffix(finalStats.optInt("reply", 0)));
                    ((TextView) findViewById(R.id.like_text)).setText(
                            "收到的赞" + unreadSuffix(finalStats.optInt("like", 0)));
                    ((TextView) findViewById(R.id.at_text)).setText(
                            "@我" + unreadSuffix(finalStats.optInt("at", 0)));
                } catch (Exception e) {
                    MsgUtil.err(e);
                }
            });
        });
    }

    private static String unreadSuffix(int count) {
        return count > 0 ? ("(" + count + ")") : "";
    }

    /**
     * 拉取会话列表并刷新界面。置顶/删除会话后由适配器回调再次调用（服务端才是唯一真相）。
     * <p>排序：置顶优先（置顶时间晚的靠前）→ 未读优先 → 维持服务端原序。原先只按「未读优先」排，
     * 置顶但没未读的会话会被挤到未读会话后面，表现为「点了置顶、提示已置顶，位置却没变」。
     * Collections.sort 是稳定排序，同档内不打乱服务端给出的时间顺序。
     *
     * <p>刷新时复用已有 adapter（原地 submit），不重建 RecyclerView：从私信页返回时
     * 会话列表不会跳回顶部。并发请求由 loadingSessions 挡掉，避免 onCreate 与 onResume 双发。
     */
    private void loadSessions() {
        if (loadingSessions) return;
        loadingSessions = true;
        CenterThreadPool.run(() -> {
            try {
                ArrayList<PrivateMsgSession> sessionsList = PrivateMsgApi.getNewSessionsList(0, 20, 0, "web");
                //如果新接口返回为空，回退到旧接口
                if (sessionsList.isEmpty()) {
                    sessionsList = PrivateMsgApi.getSessionsList(20);
                }
                Collections.sort(sessionsList, (o1, o2) -> {
                    if (o1.isTop() != o2.isTop()) return o1.isTop() ? -1 : 1;
                    if (o1.isTop() && o1.topTs != o2.topTs) return o1.topTs > o2.topTs ? -1 : 1;
                    boolean o1Unread = o1.unread > 0;
                    boolean o2Unread = o2.unread > 0;
                    if (o1Unread != o2Unread) return o1Unread ? -1 : 1;
                    return 0;
                });
                //使用 session 自带的信息 + 批量用户查询兜底
                ArrayList<Long> uidList = new ArrayList<>();
                for (PrivateMsgSession item : sessionsList) {
                    uidList.add(item.talkerUid);
                }
                HashMap<Long, UserInfo> userMap = new HashMap<>();
                try {
                    if (!uidList.isEmpty()) {
                        userMap = PrivateMsgApi.getUsersInfo(uidList);
                    }
                } catch (Exception ignored) {}

                final ArrayList<PrivateMsgSession> finalSessions = sessionsList;
                final HashMap<Long, UserInfo> finalUsers = userMap;
                runOnUiThread(() -> {
                    loadingSessions = false;
                    if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
                    if (!viewReady || isFinishing() || isDestroyed()) return;
                    if (sessionsAdapter == null) {
                        sessionsAdapter = new PrivateMsgSessionsAdapter(this, finalSessions, finalUsers);
                        sessionsAdapter.setOnSessionsChanged(this::loadSessions);
                        sessionsView.setLayoutManager(new CustomLinearManager(this));
                        sessionsView.setAdapter(sessionsAdapter);
                    } else {
                        sessionsAdapter.submit(finalSessions, finalUsers);
                    }
                    SharedPreferencesUtil.putInt(SharedPreferencesUtil.MESSAGE_UPDATE_NUM, 0);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    loadingSessions = false;
                    if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
                    if (viewReady && !isFinishing() && !isDestroyed()) MsgUtil.err(e);
                });
            }
        });
    }
}
