package com.RobinNotBad.BiliClient.activity.reply;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.RobinNotBad.BiliClient.activity.base.RefreshListFragment;
import com.RobinNotBad.BiliClient.adapter.ReplyAdapter;
import com.RobinNotBad.BiliClient.api.ReplyApi;
import com.RobinNotBad.BiliClient.event.ReplyEvent;
import com.RobinNotBad.BiliClient.model.Reply;
import com.RobinNotBad.BiliClient.model.UserInfo;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

//视频下评论页面，评论详情见ReplyInfoActivity
//部分通用代码在VideoReplyAdapter内
//2023-07-22

public class ReplyFragment extends RefreshListFragment {

    private boolean dontload;
    protected long aid, mid;
    protected int sort = 3;
    protected int type;
    protected int count;
    protected ArrayList<Reply> replyList;
    protected ReplyAdapter replyAdapter;
    public int replyType = ReplyApi.REPLY_TYPE_VIDEO;
    private long seek;
    private String pagination = "";
    private boolean isManager = false;
    //游标分页的到底标志：与父类 bottom 配合，到底后阻止继续请求（pagination 会被置空，避免误用空游标重拉第一页导致重复）
    //volatile：主线程写（世代守卫内）、后台加载循环读
    private volatile boolean isEnd = false;
    //已加载评论的 rpid 集合，作为游标分页的兜底去重，防止 B站 API 在游标边界返回重复评论
    //后台解析线程 add 与主线程 refresh clear/add 并发，需同步包装
    private final Set<Long> loadedRpids = java.util.Collections.synchronizedSet(new HashSet<>());
    //本地刚发出、服务端列表可能还没返回的根评论（最多留 20 条）。
    //B站评论列表有延迟：刚发完立刻重新拉取常常还看不到自己那条，空评论区尤其明显。
    //每次 refresh 成功后会把这些补回列表顶部，保证"发出去就能看到"
    private final java.util.List<Reply> localPostedReplies = new java.util.ArrayList<>();
    //补回的时间窗口：只在发出去的 5 分钟内兜着。窗口外的由服务端列表说话，
    //否则"发完又删掉"的那条会在之后每次刷新时被幽灵般补回来
    private static final long LOCAL_REPLY_MERGE_WINDOW_MS = 5 * 60 * 1000L;
    private final java.util.Map<Long, Long> localPostedAt = new java.util.HashMap<>();
    //刷新/翻页世代号：refresh 时自增，旧任务的所有回调按世代丢弃，防止旧数据写进新列表造成通知错位
    private int loadGeneration = 0;
    //连续加载空页的总时长上限：弱网+重试瀑布下防止"翻页无反应"无限拖延，超时静默停在当前游标，下次下滑可续传
    private static final long LOAD_DEADLINE_MS = 30_000L;

    public static ReplyFragment newInstance(long aid, int type) {
        ReplyFragment fragment = new ReplyFragment();
        Bundle args = new Bundle();
        args.putLong("aid", aid);
        args.putInt("type", type);
        fragment.setArguments(args);
        return fragment;
    }

    public static ReplyFragment newInstance(long aid, int type, boolean dontload) {
        ReplyFragment fragment = new ReplyFragment();
        Bundle args = new Bundle();
        args.putLong("aid", aid);
        args.putInt("type", type);
        args.putBoolean("dontload", dontload);
        fragment.setArguments(args);
        return fragment;
    }


    public static ReplyFragment newInstance(long aid, int type, long seek_rpid) {
        ReplyFragment fragment = new ReplyFragment();
        Bundle args = new Bundle();
        args.putLong("aid", aid);
        args.putInt("type", type);
        args.putLong("seek", seek_rpid);
        fragment.setArguments(args);
        return fragment;
    }

    public static ReplyFragment newInstance(long aid, int type, boolean dontload, long seek_rpid) {
        ReplyFragment fragment = new ReplyFragment();
        Bundle args = new Bundle();
        args.putLong("aid", aid);
        args.putInt("type", type);
        args.putBoolean("dontload", dontload);
        args.putLong("seek", seek_rpid);
        fragment.setArguments(args);
        return fragment;
    }

    public static ReplyFragment newInstance(long aid, int type, int count, long seek_rpid, long up_mid) {
        ReplyFragment fragment = new ReplyFragment();
        Bundle args = new Bundle();
        args.putLong("aid", aid);
        args.putInt("count", count);
        args.putInt("type", type);
        args.putLong("seek", seek_rpid);
        args.putLong("mid", up_mid);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            aid = getArguments().getLong("aid", 0);
            count = getArguments().getInt("count", 0);
            type = getArguments().getInt("type", 0);
            replyType = type;
            dontload = getArguments().getBoolean("dontload", false);
            seek = getArguments().getLong("seek", -1);
            mid = getArguments().getLong("mid", -1);
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        setForceSingleColumn();
        super.onViewCreated(view, savedInstanceState);

        if (SharedPreferencesUtil.getBoolean("ui_landscape", false)) {
            WindowManager windowManager = (WindowManager) view.getContext().getSystemService(Context.WINDOW_SERVICE);
            Display display = windowManager.getDefaultDisplay();
            DisplayMetrics metrics = new DisplayMetrics();
            if (Build.VERSION.SDK_INT >= 17) display.getRealMetrics(metrics);
            else display.getMetrics(metrics);
            int paddings = metrics.widthPixels / 6;
            recyclerView.setPadding(paddings, 0, paddings, 0);
        }

        setOnRefreshListener(() -> refresh(aid));
        setOnLoadMoreListener(this::continueLoading);

        Log.e("debug-av号", String.valueOf(aid));

        replyList = new ArrayList<>();

        if (!dontload) refresh(aid);
    }

    public void setManager(Object source) {
        if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) == 0) return;

        try {
            if (source != null) {
                if (source instanceof List<?>) {
                    List<UserInfo> staffs = (List<UserInfo>) source;
                    for (UserInfo userInfo : staffs) {
                        if (userInfo.mid == SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0)) {
                            isManager = true;
                            break;
                        }
                    }
                } else if (source instanceof UserInfo) {
                    isManager = ((UserInfo) source).mid == SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0);
                }
            }
        } catch (Exception e) {
            MsgUtil.err(e);
        }
    }

    private ReplyAdapter createReplyAdapter() {
        return new ReplyAdapter(requireContext(), replyList, aid, 0, type, sort, mid);
    }

    @SuppressLint("NotifyDataSetChanged")
    private void continueLoading(int page) {
        //到底拦截：已到末页时不再发起请求，避免用空游标重拉第一页导致重复评论
        if (isEnd || bottom) {
            setRefreshing(false);
            return;
        }
        final int generation = loadGeneration;   //期间发生刷新/新翻页则本轮整体作废
        final long deadline = System.currentTimeMillis() + LOAD_DEADLINE_MS;
        CenterThreadPool.run(() -> {
            try {
                //B站对某些游标值可能只返回 cursor 不返回 replies，这里自动连续加载直到拿到评论或到底，避免用户看到"翻页无反应"
                int emptyPages = 0;
                boolean endReached = false;
                String cursor = pagination;   //游标先在局部推进，提交回字段统一走主线程，避免与 refresh 并发写
                while (!isEnd && !bottom && !endReached && emptyPages < 5 && System.currentTimeMillis() < deadline) {
                    if (generation != loadGeneration) return;   //已被刷新作废
                    List<Reply> list = new ArrayList<>();
                    ReplyApi.ReplyResult result = ReplyApi.getRepliesLazyWithRetry(aid, 0, cursor, type, sort, list);
                    if (generation != loadGeneration) return;
                    if (!result.isSuccess()) {
                        //失败时回退 page，使父类页码与游标状态一致；失败不更新游标，允许按原游标重新触发
                        runOnUiThread(() -> {
                            if (generation != loadGeneration) return;
                            this.page--;
                            setRefreshing(false);
                            showLoadErrorMsg(result);
                        });
                        return;
                    }
                    //仅当本次请求成功时才推进游标
                    cursor = result.nextOffset;
                    //兜底去重：过滤掉 rpid 已存在的评论
                    ReplyApi.filterDuplicateReplies(list, loadedRpids);
                    if (result.isEnd()) endReached = true;
                    if (!list.isEmpty()) {
                        final List<Reply> unique = list;
                        final String next = cursor;
                        final boolean finalEnd = endReached;
                        runOnUiThread(() -> {
                            if (generation != loadGeneration) return;
                            this.pagination = next;
                            replyList.addAll(unique);
                            if (replyAdapter != null)
                                replyAdapter.notifyItemRangeInserted(replyList.size() - unique.size() + 1, unique.size());
                            //isEnd/bottom 的写入收敛到主线程的世代守卫内：
                            //后台直接写会在新一代 refresh 重置之后到达，把新列表污染成"已到底"
                            if (finalEnd) {
                                isEnd = true;
                                bottom = true;
                            }
                            setRefreshing(false);
                        });
                        return;   //拿到评论，退出等用户继续下滑
                    }
                    //本页无新评论：若未到底则继续加载下一页，连续空页达上限则提示
                    emptyPages++;
                    Logu.d("ReplyFragment", "本页无新评论，自动加载下一页 (empty=" + emptyPages + ")");
                }
                //空页耗尽或超时：把已推进的游标落回字段，下次下滑从断点续传
                final String finalCursor = cursor;
                final boolean finalEnd = endReached;
                runOnUiThread(() -> {
                    if (generation != loadGeneration) return;
                    this.pagination = finalCursor;
                    if (finalEnd) {
                        isEnd = true;
                        bottom = true;
                    }
                    setRefreshing(false);
                });
                if (generation == loadGeneration && emptyPages >= 5 && !endReached) {
                    runOnUiThread(() -> MsgUtil.showMsgLong("暂时没有更多评论了"));
                }
            } catch (Exception e) {
                if (generation == loadGeneration) {
                    runOnUiThread(() -> {
                        if (generation != loadGeneration) return;
                        this.page--;
                        report(e);
                        setRefreshing(false);
                    });
                } else {
                    setRefreshing(false);
                }
            }
        });
    }

    /**根据 ReplyResult 的状态/API错误码给出用户友好的提示。网络错误时附带详情便于定位。*/
    private void showLoadErrorMsg(ReplyApi.ReplyResult result) {
        String shortMsg = null;
        if (result.status == ReplyApi.ReplyResult.STATUS_NET_ERROR) {
            //网络/解析失败：WBI 与无签名降级均失败，展示详情便于定位根因
            shortMsg = "评论加载失败：" + result.message;
        } else if (result.status == ReplyApi.ReplyResult.STATUS_API_ERROR) {
            String tip;
            switch (result.apiCode) {
                case -101: tip = "请先登录后再查看评论"; break;
                case -404: tip = "评论区不存在"; break;
                case 12002: tip = "评论区已关闭"; break;
                case 12014: tip = "评论已被风控，请稍后再试"; break;
                default: tip = "评论加载失败（" + result.apiCode + "）" + (result.message != null && !result.message.isEmpty() ? "：" + result.message : ""); break;
            }
            shortMsg = tip;
        }
        if (shortMsg == null) return;
        //「显示详细接口错误」打开时，把接口名、参数、返回码与原始返回片段一并给出（可复制）
        String detail = "评论区加载失败\n\n"
                + "type=" + type + "  oid=" + aid + "\n"
                + "seek=" + seek + "  sort=" + sort + "  pagination=\"" + pagination + "\"\n"
                + "status=" + result.status + "  apiCode=" + result.apiCode + "\n"
                + "message=" + result.message + "\n\n"
                + result.detail;
        Logu.e("ReplyFragment", detail.replace("\n", " | "));
        MsgUtil.apiErrorDetail(shortMsg, detail);
    }

    //由 EventBus MAIN 线程回调（VideoInfoActivity/DynamicInfoActivity 订阅），所有数据修改必须在主线程，
    //否则与 RecyclerView 的布局/滚动并发会造成通知与数据错位，列表直接卡死
    public void notifyReplyInserted(ReplyEvent replyEvent) {
        if (replyEvent.getOid() != aid) return;
        Reply reply = replyEvent.getMessage();
        if (reply == null) return;
        //先记下本地发出的根评论：列表还没就绪、或者随后一次 refresh 把列表整体换掉时都能补回来
        if (reply.root == 0) rememberLocalReply(reply);
        if (replyAdapter == null || replyList == null || recyclerView == null || recyclerView.getLayoutManager() == null)
            return;
        if (reply.root == 0) {
            LinearLayoutManager layoutManager = (LinearLayoutManager) recyclerView.getLayoutManager();
            //findFirstCompletelyVisibleItemPosition 返回 adapter 位（0 是头部发送按钮），换算数据位要减 1
            int firstVisible = layoutManager.findFirstCompletelyVisibleItemPosition();
            int dataIndex = Math.max(firstVisible - 1, 0);
            if (dataIndex > replyList.size()) dataIndex = replyList.size();
            boolean wasEmpty = replyList.isEmpty();
            replyList.add(dataIndex, reply);
            loadedRpids.add(reply.rpid);   //手工插入的评论也要进去重集，否则翻页拉到同一条会重复显示
            int adapterPos = dataIndex + 1;   //头部占一格
            if (wasEmpty) {
                //空评论区插第一条：直接整表刷新。原来单独 notifyItemInserted 在"列表只有头部"
                //这种状态下偶尔不会真正触发重新布局，表现就是"评论发出去了但列表里看不到"，
                //再发一条才被顺带刷出来。这里只有 0→1 这一种情况，整表刷新的代价可以忽略
                replyAdapter.notifyDataSetChanged();
                layoutManager.scrollToPosition(0);
            } else {
                replyAdapter.notifyItemInserted(adapterPos);
                layoutManager.scrollToPositionWithOffset(adapterPos, 0);
            }
        } else {
            //楼中楼回复：按根评论 rpid 定位。事件的 pos 只对发起回复的那个页面有效，
            //视频页/动态页/评论详情页可能同时收到同一事件，用 pos 会改错行甚至越界
            for (int i = 0; i < replyList.size(); i++) {
                Reply root = replyList.get(i);
                if (root != null && root.rpid == reply.root) {
                    root.childMsgList.add(reply);
                    root.childCount++;
                    replyAdapter.notifyItemChanged(i + 1);
                    break;
                }
            }
        }
    }

    /** 记住本地刚发出的根评论（最多 20 条），并顺手进重集 */
    private void rememberLocalReply(Reply reply) {
        loadedRpids.add(reply.rpid);
        localPostedAt.put(reply.rpid, System.currentTimeMillis());
        for (int i = 0; i < localPostedReplies.size(); i++) {
            if (localPostedReplies.get(i).rpid == reply.rpid) {
                localPostedReplies.set(i, reply);
                return;
            }
        }
        localPostedReplies.add(reply);
        while (localPostedReplies.size() > 20) localPostedReplies.remove(0);
    }

    /**
     * 把本地刚发出、但服务端列表还没返回的评论补到最前面。
     *
     * <p>为什么需要：B站的评论列表有延迟，刚发完立刻拉取常常还看不到自己那条；
     * 空评论区更明显——列表直接是 0 条，看起来就像"评论发出去了但没显示"，
     * 再发第二条时服务端才把两条一起返回，于是表现为"第二条发出后两条才出现"。
     */
    private void mergeLocalReplies() {
        if (replyList == null || localPostedReplies.isEmpty()) return;
        int added = 0;
        long now = System.currentTimeMillis();
        for (int i = localPostedReplies.size() - 1; i >= 0; i--) {   //倒着插到 0，最终保持发送顺序
            Reply local = localPostedReplies.get(i);
            Long postedAt = localPostedAt.get(local.rpid);
            if (postedAt == null || now - postedAt > LOCAL_REPLY_MERGE_WINDOW_MS) {
                localPostedReplies.remove(i);   //超出兜底窗口：交给服务端列表
                localPostedAt.remove(local.rpid);
                continue;
            }
            boolean exists = false;
            for (int j = 0; j < replyList.size(); j++) {
                Reply r = replyList.get(j);
                if (r != null && r.rpid == local.rpid) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                replyList.add(0, local);
                loadedRpids.add(local.rpid);   //refresh 开头清过重集，这里补回去，防止翻页拉到同一条重复显示
                added++;
            }
        }
        if (added > 0) Logu.d("ReplyFragment", "补回本地刚发出的评论 " + added + " 条");
    }

    @SuppressLint("NotifyDataSetChanged")
    public void refresh(long aid) {
        //切换/下拉刷新时重置全部分页状态，确保不残留旧数据与旧游标
        pagination = "";
        isEnd = false;
        bottom = false;
        loadedRpids.clear();
        page = 1;
        this.aid = aid;
        final int generation = ++loadGeneration;   //使在途的旧翻页/旧刷新回调全部作废
        setRefreshing(true);
        CenterThreadPool.run(() -> {
            try {
                List<Reply> list = new ArrayList<>();
                ReplyApi.ReplyResult result = ReplyApi.getRepliesLazyWithRetry(aid, seek, pagination, type, sort, list);
                if (generation != loadGeneration) return;   //已被更新的刷新作废
                //热度排序（mode=3）对"只有一两条新评论"的评论区经常返回空列表，而头部显示的
                //"共 N 条评论"是另一个接口给的数字——于是表现成"读得到数量但加载不出来"。
                //这里在"接口成功、列表为空、但确实说有 N 条"时，换时间排序再拉一次；
                //拉到了就把排序状态一起切过去，保证后续翻页/下拉刷新的口径一致
                String altDetail = null;   //换排序重拉的结果，只有"详细接口错误"会用到
                if (result.isSuccess() && list.isEmpty() && count > 0 && seek <= 0 && sort != 2) {
                    List<Reply> alt = new ArrayList<>();
                    ReplyApi.ReplyResult altResult = ReplyApi.getRepliesLazyWithRetry(aid, seek, "", type, 2, alt);
                    if (generation != loadGeneration) return;
                    altDetail = "\n\n【改用时间排序重拉】\n"
                            + "成功=" + altResult.isSuccess() + " 条数=" + alt.size() + "\n"
                            + altResult.detail;
                    if (altResult.isSuccess() && !alt.isEmpty()) {
                        Logu.d("ReplyFragment", "热度排序返回空但共 " + count + " 条，改用时间排序拉到 " + alt.size() + " 条");
                        list = alt;
                        result = altResult;
                        sort = 2;
                    }
                }
                final String finalAltDetail = altDetail;
                if (result.isSuccess()) {
                    ReplyApi.filterDuplicateReplies(list, loadedRpids);
                    final List<Reply> unique = list;
                    //上面可能把 result 换成"时间排序"那次的结果，lambda 里只能用 final 别名
                    final ReplyApi.ReplyResult finalResult = result;
                    //分页状态（pagination/isEnd/bottom）统一在主线程的世代守卫内落库：
                    //后台线程直写会在新一代 refresh 重置之后到达，把新列表污染成"已到底/错游标"
                    runOnUiThread(() -> {
                        if (!isAdded()) return;
                        if (generation != loadGeneration) return;
                        this.pagination = finalResult.nextOffset;
                        if (finalResult.isEnd()) {
                            Logu.d("ReplyFragment", "评论到底 type=" + type + " oid=" + aid);
                            isEnd = true;
                            bottom = true;
                        }
                        if (replyList != null) replyList.clear();
                        else replyList = new ArrayList<>();
                        replyList.addAll(unique);
                        //服务端还没返回的那几条本地评论补回顶部（见 mergeLocalReplies 注释）
                        mergeLocalReplies();
                        //仍然是空的、但头部说有 N 条：把这次请求的接口与返回原样抛给用户（开关打开时）
                        if (replyList.isEmpty() && count > 0) {
                            String detail = "评论区一条都没拉到，但接口说共有 " + count + " 条。\n\n"
                                    + "type=" + type + "  oid=" + aid + "\n"
                                    + "seek=" + seek + "  sort=" + sort + "  pagination=\"" + pagination + "\"\n\n"
                                    + finalResult.detail
                                    + (finalAltDetail != null ? finalAltDetail : "");
                            Logu.e("ReplyFragment", detail.replace("\n", " | "));
                            if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.API_ERROR_DETAIL, false)) {
                                MsgUtil.apiErrorDetail("评论区加载不出来（0 条，但显示共 " + count + " 条）", detail);
                            }
                        }
                        if (replyAdapter == null) {
                            replyAdapter = createReplyAdapter();
                            replyAdapter.count = count;
                            replyAdapter.sort = sort;   //换了排序口径时，适配器里的排序状态也要跟上
                            replyAdapter.isManager = isManager;
                            setOnSortSwitch();
                            setAdapter(replyAdapter);
                        } else {
                            replyAdapter.sort = sort;   //排序口径可能刚被兜底切成时间排序，同步给适配器（按钮文案/行为）
                            replyAdapter.notifyDataSetChanged();
                        }
                        setRefreshing(false);
                    });
                } else if (isAdded()) {
                    final ReplyApi.ReplyResult errorResult = result;
                    runOnUiThread(() -> {
                        if (generation != loadGeneration) return;
                        setRefreshing(false);
                        showLoadErrorMsg(errorResult);
                    });
                }
            } catch (Exception e) {
                if (generation == loadGeneration) {
                    runOnUiThread(() -> {
                        if (generation != loadGeneration) return;
                        this.page--;
                        report(e);
                        setRefreshing(false);
                    });
                } else {
                    setRefreshing(false);
                }
            }
        });
    }

    private void setOnSortSwitch() {
        replyAdapter.setOnSortSwitchListener(position -> {
            sort = (sort == 2 ? 3 : 2);
            replyAdapter.sort = this.sort;
            refresh(aid);
        });
    }
}