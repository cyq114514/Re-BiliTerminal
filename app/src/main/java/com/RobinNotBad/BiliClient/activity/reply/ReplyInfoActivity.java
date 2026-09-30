package com.RobinNotBad.BiliClient.activity.reply;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.adapter.ReplyAdapter;
import com.RobinNotBad.BiliClient.api.ReplyApi;
import com.RobinNotBad.BiliClient.event.ReplyEvent;
import com.RobinNotBad.BiliClient.model.ContentType;
import com.RobinNotBad.BiliClient.model.Reply;
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.RobinNotBad.BiliClient.util.TerminalContext;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;

//评论详细信息
//2023-07-22

public class ReplyInfoActivity extends BaseActivity {

    private long oid, rpid, up_mid;
    private int sort = 0;
    private boolean isManager;
    private ContentType type;
    private RecyclerView recyclerView;
    private SwipeRefreshLayout refreshLayout;
    private ArrayList<Reply> replyList;
    private ReplyAdapter replyAdapter;
    private boolean bottom = false;
    private int page = 1;
    private boolean refreshing = false;
    //已加载子评论的 rpid 集合，作为分页兜底去重，避免重复评论
    private final Set<Long> loadedRpids = new HashSet<>();

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_simple_refresh);

        Intent intent = getIntent();
        rpid = intent.getLongExtra("rpid", 0);
        oid = intent.getLongExtra("oid", 0);
        try {
            type = ContentType.getContentType(intent.getIntExtra("type", 1));
        } catch (ContentType.TerminalIllegalTypeCodeException e) {
            throw new RuntimeException(e);
        }
        up_mid = intent.getLongExtra("up_mid", -1);
        isManager = intent.getBooleanExtra("is_manager", false);

        refreshLayout = findViewById(R.id.swipeRefreshLayout);
        recyclerView = findViewById(R.id.recyclerView);
        refreshLayout.setOnRefreshListener(this::refresh);

        setPageName("评论详情");

        if (SharedPreferencesUtil.getBoolean("ui_landscape", false)) {
            WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            Display display = windowManager.getDefaultDisplay();
            DisplayMetrics metrics = new DisplayMetrics();
            if (Build.VERSION.SDK_INT >= 17) display.getRealMetrics(metrics);
            else display.getMetrics(metrics);
            int paddings = metrics.widthPixels / 6;
            recyclerView.setPadding(paddings, 0, paddings, 0);
        }


        refreshLayout.setRefreshing(true);
        TerminalContext.getInstance().getReply(type, oid, rpid).observe(this, (rootReplyResult) -> {
            replyList = new ArrayList<>();
            rootReplyResult.onSuccess((rootReply) -> {
                Future<Integer> future = CenterThreadPool.supplyAsyncWithFuture(() -> ReplyApi.getReplies(oid, rpid, page, type, sort, replyList));
                CenterThreadPool.observe(future, (result) -> {
                    if (result != -1) {
                        loadedRpids.clear();
                        loadedRpids.add(rootReply.rpid);   //排除根评论本身，避免其重复出现在子评论列表
                        ReplyApi.filterDuplicateReplies(replyList, loadedRpids);
                        replyList.add(0, rootReply);
                        replyAdapter = new ReplyAdapter(this, replyList, oid, rpid, type.getTypeCode(), sort, up_mid);
                        replyAdapter.isManager = isManager;
                        replyAdapter.isDetail = true;
                        setOnSortSwitch();
                        recyclerView.setLayoutManager(new CustomLinearManager(this));
                        recyclerView.setAdapter(replyAdapter);
                        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
                            @Override
                            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                                super.onScrollStateChanged(recyclerView, newState);
                            }

                            @Override
                            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                                super.onScrolled(recyclerView, dx, dy);
                                LinearLayoutManager manager = (LinearLayoutManager) recyclerView.getLayoutManager();
                                assert manager != null;
                                int lastItemPosition = manager.findLastVisibleItemPosition();  //获取最后一个显示的itemPosition
                                int itemCount = manager.getItemCount();
                                if (lastItemPosition >= (itemCount - 3) && dy > 0 && !refreshing && !bottom) {// 滑动到倒数第三个就可以刷新了
                                    refreshing = true;
                                    CenterThreadPool.run(() -> continueLoading()); //加载第二页
                                }
                            }
                        });
                        refreshLayout.setRefreshing(false);
                        if (result == 1) {
                            Log.e("debug", "到底了");
                            bottom = true;
                        }
                    }
                }, (error) -> onPullDataFailed(new Exception(error)));
            }).onFailure((error) -> onPullDataFailed(new Exception(error)));
        });
    }

    private void onPullDataFailed(Exception e) {
        MsgUtil.err(e);
        refreshLayout.setRefreshing(false);
    }

    private void continueLoading() {
        if (bottom) return;   //到底拦截，避免无效请求
        runOnUiThread(() -> refreshLayout.setRefreshing(true));
        page++;
        try {
            List<Reply> list = new ArrayList<>();
            int result = ReplyApi.getReplies(oid, rpid, page, type, sort, list);
            if (result != -1) {
                Log.e("debug", "下一页");
                //兜底去重，避免分页边界返回重复子评论
                ReplyApi.filterDuplicateReplies(list, loadedRpids);
                runOnUiThread(() -> {
                    replyList.addAll(list);
                    //数据末位 i 对应 adapter 位 i+1（详情模式头部只有根评论+回复框一个额外格）
                    //旧代码写成 +2，插入位比实际多 1，第二批数据开始通知与数据错位导致列表卡死
                    replyAdapter.notifyItemRangeInserted(replyList.size() - list.size() + 1, list.size());
                    refreshLayout.setRefreshing(false);
                });
                if (result == 1) {
                    //runOnUiThread(()-> MsgUtil.showMsg("到底啦QwQ",this));
                    Log.e("debug", "到底了");
                    bottom = true;
                }
            } else {
                page--;   //翻页失败大概率是到底了（B站-404等），静默结束不弹窗
                bottom = true;
                runOnUiThread(() -> refreshLayout.setRefreshing(false));
            }
            refreshing = false;
        } catch (Exception e) {
            page--;
            runOnUiThread(() -> {
                MsgUtil.err(e);
                refreshLayout.setRefreshing(false);
            });
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private void refresh() {
        page = 1;
        bottom = false;
        loadedRpids.clear();
        refreshLayout.setRefreshing(true);

        TerminalContext.getInstance().getReply(type, oid, rpid).observe(this, (rootReplyResult) -> rootReplyResult.onSuccess((rootReply) -> {
            List<Reply> list = new ArrayList<>();
            Future<Integer> future = CenterThreadPool.supplyAsyncWithFuture(() -> ReplyApi.getReplies(oid, rpid, page, type, sort, list));
            CenterThreadPool.observe(future, (result) -> {
                if (result != -1) {
                    loadedRpids.add(rootReply.rpid);
                    ReplyApi.filterDuplicateReplies(list, loadedRpids);
                    runOnUiThread(() -> {
                        replyList.clear();
                        replyList.add(0, rootReply);
                        replyList.addAll(list);
                        if (replyAdapter == null) {
                            replyAdapter = new ReplyAdapter(this, replyList, oid, rpid, type.getTypeCode(), sort, up_mid);
                            replyAdapter.isDetail = true;
                            setOnSortSwitch();
                            recyclerView.setAdapter(replyAdapter);
                        } else {
                            replyAdapter.notifyDataSetChanged();
                        }
                        refreshLayout.setRefreshing(false);
                    });
                    if (result == 1) {
                        Log.e("debug", "到底了");
                        bottom = true;
                    } else bottom = false;
                }
            }, (error) -> {
                this.onPullDataFailed(new Exception(error));
            });
        }).onFailure((error) -> {
            this.onPullDataFailed(new Exception(error));
        }));
    }

    private void setOnSortSwitch() {
        replyAdapter.setOnSortSwitchListener(position -> {
            sort = (sort == 0 ? 1 : 0);
            refresh();
        });
    }

    @Override
    protected boolean eventBusEnabled() {
        return true;
    }

    //必须留在主线程：直接改 replyList 并通知 RecyclerView，后台线程回调会与布局并发冲突
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEvent(ReplyEvent event) {
        if (event.getOid() != oid) return;
        if (replyAdapter == null || replyList == null) return;
        Reply reply = event.getMessage();
        if (reply == null) return;
        //楼中楼详情页：新回复追加到子评论末尾（数据末位 i 对应 adapter 位 i+1，头部固定项是根评论+回复框）
        replyList.add(reply);
        replyAdapter.notifyItemInserted(replyList.size());
        LinearLayoutManager layoutManager = (LinearLayoutManager) recyclerView.getLayoutManager();
        if (layoutManager != null)
            layoutManager.scrollToPositionWithOffset(replyList.size(), 0);
    }
}
