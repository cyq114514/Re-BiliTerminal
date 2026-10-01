package com.RobinNotBad.BiliClient.activity.message;

import android.content.Intent;
import android.os.Bundle;
import android.util.Pair;

import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity;
import com.RobinNotBad.BiliClient.adapter.message.NoticeAdapter;
import com.RobinNotBad.BiliClient.api.MessageApi;
import com.RobinNotBad.BiliClient.model.MessageCard;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;

import java.util.ArrayList;
import java.util.List;

public class NoticeActivity extends RefreshListActivity {
    private List<MessageCard> messageList;
    private NoticeAdapter noticeAdapter;
    private MessageCard.Cursor cursor;
    private String pageType;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setPageName("详情");

        Intent intent = getIntent();
        pageType = intent.getStringExtra("type");
        messageList = new ArrayList<>();

        if (pageType == null) {
            //没有类型参数就无法发起任何请求；原来 switch(null) 直接 NPE 被线程池吞掉，转圈永远不停
            finish();
            return;
        }

        CenterThreadPool.run(() -> {
            try {
                Pair<MessageCard.Cursor, List<MessageCard>> pair = null;
                List<MessageCard> systemMsg = null;
                switch (pageType) {
                    case "like":
                        pair = MessageApi.getLikeMsg(0, 0);
                        break;
                    case "reply":
                        pair = MessageApi.getReplyMsg(0, 0);
                        break;
                    case "at":
                        pair = MessageApi.getAtMsg(0, 0);
                        break;
                    case "system":
                        systemMsg = MessageApi.getSystemMsg();
                        break;
                }

                //列表只在主线程初始化和变更，避免 RecyclerView 在 bind 时读到被后台线程并发修改的数据
                final List<MessageCard> pairList = pair != null ? pair.second : null;
                final MessageCard.Cursor newCursor = pair != null ? pair.first : null;
                final List<MessageCard> systemList = systemMsg;
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    if (pairList != null) {
                        cursor = newCursor;
                        messageList.addAll(pairList);
                    } else if (systemList != null) {
                        messageList.addAll(systemList);
                    }
                    noticeAdapter = new NoticeAdapter(this, messageList);
                    setAdapter(noticeAdapter);
                    setRefreshing(false);
                    setOnLoadMoreListener(this::continueLoading);
                });
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    MsgUtil.showMsgLong("加载失败");
                    setRefreshing(false);
                    finish();
                });
            }
        });
    }

    private void continueLoading(int i) {
        CenterThreadPool.run(() -> {
            try {
                MessageCard.Cursor cur = cursor;
                //系统消息接口没有游标分页，一次拉全量，直接标记到底
                if ("system".equals(pageType) || cur == null) {
                    runOnUiThread(() -> bottom = true);
                    setRefreshing(false);
                    return;
                }
                Pair<MessageCard.Cursor, List<MessageCard>> pair;
                switch (pageType) {
                    case "like":
                        pair = MessageApi.getLikeMsg(cur.id, cur.time);
                        break;
                    case "reply":
                        pair = MessageApi.getReplyMsg(cur.id, cur.time);
                        break;
                    case "at":
                        pair = MessageApi.getAtMsg(cur.id, cur.time);
                        break;
                    default:
                        runOnUiThread(() -> bottom = true);
                        setRefreshing(false);
                        return;
                }
                MessageCard.Cursor newCursor = pair.first;
                List<MessageCard> more = pair.second;
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    cursor = newCursor;
                    int lastSize = messageList.size();
                    messageList.addAll(more);
                    noticeAdapter.notifyItemRangeInserted(lastSize, messageList.size() - lastSize);
                    bottom = newCursor != null && newCursor.is_end;
                });
                setRefreshing(false);
            } catch (Exception e) {
                e.printStackTrace();
                page--;
                runOnUiThread(() -> bottom = false);
                setRefreshing(false);
            }
        });
    }
}
