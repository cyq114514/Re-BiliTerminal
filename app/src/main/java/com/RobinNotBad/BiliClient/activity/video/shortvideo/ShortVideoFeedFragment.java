package com.RobinNotBad.BiliClient.activity.video.shortvideo;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.SurfaceTexture;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.ListChooseActivity;
import com.RobinNotBad.BiliClient.api.LikeCoinFavApi;
import com.RobinNotBad.BiliClient.api.PlayerApi;
import com.RobinNotBad.BiliClient.api.ReplyApi;
import com.RobinNotBad.BiliClient.api.ShortVideoFeedApi;
import com.RobinNotBad.BiliClient.api.WatchLaterApi;
import com.RobinNotBad.BiliClient.model.ShortVideoItem;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/**
 * 竖屏短视频信息流（外壳横向 pager 的第 0 页）。
 *
 * <p>与参考实现（Re：哔哩终端）的差异：那边是"每页一个播放器 + 预加载 3 条"，这里在手表/点读笔上
 * 扛不住 3 个硬解实例，改成<b>整个信息流共用一个 IjkMediaPlayer</b>：
 * 播放画面是一层盖在 RecyclerView 之下的 TextureView，每页默认被自己的封面盖住，
 * 只有当前页在首帧渲染完成后才隐藏封面。翻页时换数据源即可，不需要重建解码器。
 *
 * <p>为什么用 TextureView 而不是 SurfaceView：SurfaceView 的画面在独立的 surface 层上，
 * 和窗口内容、窗口背景之间的层级关系很容易踩坑（第一版就因为 Fragment 根布局的不透明底
 * 把画面整块盖住而"全黑"）；TextureView 就是一个普通 View，画在窗口里，层级关系一目了然。
 * 代价是多一次 GPU 拷贝，短视频这种尺寸可以接受。
 *
 * <p>手势（与 Husk 的需求对齐，和普通播放页不一样）：
 * <ul>
 *   <li>单击：暂停/继续</li>
 *   <li>双击：显示/隐藏操作栏</li>
 *   <li>长按：按住 3 倍速，松手还原</li>
 *   <li>上下滑：换视频（RecyclerView + PagerSnapHelper）</li>
 *   <li>左右滑：交给外壳 ViewPager（本页的 RecyclerView 不支持横向滚动，事件自然被外壳接管）</li>
 * </ul>
 */
public class ShortVideoFeedFragment extends Fragment implements ShortVideoPagerAdapter.Callback {

    /** 操作栏自动隐藏间隔 */
    private static final long AUTO_HIDE_MS = 3500;
    /** 长按倍速（与播放页一致：按住 2 倍速，松手还原） */
    private static final float BOOST_SPEED = 2f;
    /** 剩余几条时预取下一批 */
    private static final int LOAD_MORE_THRESHOLD = 3;
    /** 起播超时：冷连接（DNS+TLS+moov+解码器）慢一点也要给足，v1 的 8 秒对弱网太紧 */
    private static final long PREPARE_TIMEOUT_MS = 15000;
    /** prepare 完成到首帧的超时：解码器就绪了却一直不出画，多半是硬解/渲染问题 */
    private static final long FIRST_FRAME_TIMEOUT_MS = 10000;
    /** 失败后换线路 / 换解码器的间隔，给 native 一点收尾时间 */
    private static final long RETRY_DELAY_MS = 400;
    private static final String TAG = "short-video";

    private RecyclerView recyclerView;
    private ShortVideoPagerAdapter adapter;
    private PagerSnapHelper snapHelper;
    private TextureView textureView;
    /** TextureView 的 Surface（IjkMediaPlayer.setSurface 用），surface 销毁时必须显式释放 */
    private Surface videoSurface;
    /** 当前 Surface 对应的 SurfaceTexture：用来判断"只是尺寸变了"还是真的换了一块 */
    private SurfaceTexture attachedTexture;
    private View loadingLayout, errorLayout, btnBack;
    private TextView loadingText, errorText, textStatus, textPlayHint;

    private final List<ShortVideoItem> items = new ArrayList<>();
    private final ShortVideoFeedApi.StoryFeedPager pager = new ShortVideoFeedApi.StoryFeedPager();

    private IjkMediaPlayer player;
    private int currentIndex = -1;
    private boolean prepared = false;
    private boolean surfaceReady = false;
    private boolean userPaused = false;
    private boolean loadingFeed = false;
    private boolean feedEnded = false;
    private boolean relatedLoading = false;
    private boolean destroyed = false;
    private boolean shellActive = true;
    /** 换源代次：换视频/换页后，上一代迟到回调（onPrepared/onCompletion/onError）一律丢弃 */
    private int playToken = 0;
    /** TextureView 就绪前的重试次数（封顶，避免死循环 post） */
    private int surfaceRetry = 0;
    /** 顶部提示的代次：新的提示一出现，旧的"4 秒后自动收起"就作废 */
    private int hintToken = 0;
    /** 当前这条正在试第几个播放地址（对应 item.videoUrlList 的下标） */
    private int candidateIndex = 0;
    /** 这一条有没有已经试过"硬解失败降级软解" */
    private boolean softDecodeTried = false;
    /** 本次起播强制软解（上一轮硬解失败后的降级） */
    private boolean forceSoftDecode = false;
    /** 首帧是否已经真的画出来了（MEDIA_INFO_VIDEO_RENDERING_START） */
    private boolean firstFrameShown = false;

    private GestureDetector gestureDetector;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean controlsVisible = true;
    private float normalSpeed = 1f;

    /** 收藏夹选择页的回传码与 AddFavoriteActivity 一致：1=已添加，-1=取消收藏（0 表示没动） */
    private static final int RESULT_FAV_ADDED = 1;
    private static final int RESULT_FAV_DELETED = -1;

    /**
     * 收藏必须等回传再改图标。
     *
     * <p>AddFavoriteActivity 是"用户在收藏夹列表里操作完才 setResult"的，
     * 原来直接 startActivity 然后立刻写 faved=true/已收藏，取消选择也会变成已收藏。
     */
    private final ActivityResultLauncher<Intent> favLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultCallback<ActivityResult>() {
                @Override
                public void onActivityResult(ActivityResult result) {
                    int code = result.getResultCode();
                    if (code != RESULT_FAV_ADDED && code != RESULT_FAV_DELETED) return;
                    ShortVideoItem item = currentItem();
                    ShortVideoPagerAdapter.Holder holder = holderAt(currentIndex);
                    if (item == null || holder == null) return;
                    boolean faved = code == RESULT_FAV_ADDED;
                    //收藏数跟着状态变化加减（真实数字要等下次拉信息流，这里只做即时反馈）
                    if (item.favState != (faved ? 1 : 0)) {
                        item.favoriteCount += faved ? 1 : -1;
                        if (item.favoriteCount < 0) item.favoriteCount = 0;
                    }
                    item.favState = faved ? 1 : 0;
                    holder.faved = faved;
                    holder.iconFav.setImageResource(faved ? R.drawable.icon_fav_1 : R.drawable.icon_fav_0);
                    holder.textFav.setText(item.favoriteCount > 0
                            ? ShortVideoItem.formatCount(item.favoriteCount) : "收藏");
                    showActionHint(faved ? "已收藏" : "已取消收藏");
                }
            }
    );

    private final Runnable hideControlsRunnable = () -> setControlsVisible(false);
    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            handler.postDelayed(this, 500);
        }
    };

    public ShortVideoFeedFragment() {
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_short_video_feed, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        //onDestroyView 之后视图可能被重新创建：状态必须复位，否则 destroyed 会一直是 true
        destroyed = false;
        prepared = false;
        surfaceReady = false;
        recyclerView = view.findViewById(R.id.recyclerView);
        textureView = view.findViewById(R.id.videoTexture);
        textStatus = view.findViewById(R.id.textStatus);
        textPlayHint = view.findViewById(R.id.textPlayHint);
        if (textPlayHint != null) {
            //加载态/失败提示是可点的：失败时点一下就把当前这条重试一次
            textPlayHint.setOnClickListener(v -> {
                if (currentIndex >= 0) {
                    playToken++;
                    prepared = false;
                    playCurrent(currentIndex);
                }
            });
        }
        setStatus("进入短视频页");   //开关关闭时这里会自动隐藏
        loadingLayout = view.findViewById(R.id.loadingLayout);
        errorLayout = view.findViewById(R.id.errorLayout);
        loadingText = view.findViewById(R.id.loadingText);
        errorText = view.findViewById(R.id.errorText);
        btnBack = view.findViewById(R.id.btnBack);

        btnBack.setOnClickListener(v -> {
            if (getActivity() instanceof ShortVideoPlayerActivity) {
                ((ShortVideoPlayerActivity) getActivity()).handleBack();
            }
        });
        view.findViewById(R.id.retryButton).setOnClickListener(v -> loadFeed(true));

        LinearLayoutManager manager = new LinearLayoutManager(getContext(), RecyclerView.VERTICAL, false);
        recyclerView.setLayoutManager(manager);
        snapHelper = new PagerSnapHelper();
        snapHelper.attachToRecyclerView(recyclerView);
        adapter = new ShortVideoPagerAdapter(items, this);
        recyclerView.setAdapter(adapter);
        recyclerView.setItemAnimator(null);
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                View snap = snapHelper.findSnapView(rv.getLayoutManager());
                if (snap == null) return;
                int position = rv.getChildAdapterPosition(snap);
                if (position != RecyclerView.NO_POSITION) onSnapTo(position);
            }
        });

        setupSurface();
        setupGestures(view);
        ensurePlayer();
        loadFeed(true);
    }

    // ---------------------------------------------------------------- 播放窗

    /**
     * TextureView 的 Surface 生命周期。
     *
     * <p>TextureView 走普通 View 的绘制流程，所以"页面被回收/切后台"时 surface 可能被销毁；
     * 销毁时必须把 Surface 从播放器上摘下来并 release（内存泄漏 + native 崩溃点），
     * 重建后再挂回去，否则回来就是黑屏。
     */
    private void setupSurface() {
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
                surfaceReady = false;
                if (player != null) {
                    try {
                        player.setSurface(null);
                    } catch (Throwable ignored) {
                    }
                }
                if (videoSurface != null) {
                    videoSurface.release();
                    videoSurface = null;
                }
                attachedTexture = null;
                //返回 true：由我们负责释放，TextureView 不再自行处理
                return true;
            }

            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
                attachSurface(surfaceTexture);
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
                attachSurface(surfaceTexture);
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {
            }
        });
        //Fragment 的 view 可能比 surface 先就绪，attach 时再兜一次
        if (textureView.isAvailable() && textureView.getSurfaceTexture() != null) {
            attachSurface(textureView.getSurfaceTexture());
        }
    }

    private void attachSurface(SurfaceTexture surfaceTexture) {
        if (surfaceTexture == null) return;
        surfaceReady = true;
        if (videoSurface != null && attachedTexture == surfaceTexture) {
            //同一块 SurfaceTexture 只是尺寸变了（首次布局、旋屏）：Surface 不用重建。
            //重建会把旧 Surface release 掉，而 native 侧可能还持有它——既会闪一下也可能崩
            setStatus("TextureView 尺寸变化 " + textureView.getWidth() + "x" + textureView.getHeight());
            return;
        }
        if (videoSurface != null) videoSurface.release();
        attachedTexture = surfaceTexture;
        videoSurface = new Surface(surfaceTexture);
        surfaceReady = true;
        setStatus("TextureView 已就绪 " + textureView.getWidth() + "x" + textureView.getHeight());
        if (player != null) {
            try {
                player.setSurface(videoSurface);
            } catch (Throwable e) {
                setStatus("挂载 Surface 失败：" + e.getMessage());
            }
        }
        //surface 重建后（切后台回来）当前这条要重新起一次，否则画面停在黑屏
        if (prepared && currentIndex >= 0 && !userPaused && shellActive && player != null) {
            try {
                player.start();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 每次起播前都要重新下发一遍 ijk 的选项。
     *
     * <p>关键点：{@code IjkMediaPlayer.setOption} 是**下发到 native 播放器实例**上的，
     * 而 {@code reset()} 会把 native 实例整个重建——选项随之失效。播放页的做法是每次
     * {@code MPPrepare} 之前都重新调一次 {@code setDisplay()}（里面顺带下发全部选项），
     * 第一版短视频只在创建时下发了一次，{@code reset()} 之后就没选项了：
     * 其中就包括把 ijk 自带的 User-Agent 换成 B 站能接受的那个
     * （播放页注释原话："ijkplayer 自带一个 useragent，要把默认的改掉才能用"）。
     * 表现就是 prepare 迟迟不回调、画面永远不出来。
     */
    private void applyPlayerOptions(IjkMediaPlayer mp) {
        if (mp == null) return;
        //硬件解码跟随「播放器 → 硬件解码」设置；本条起不来时会自动降级成软解再试一次（见 onAttemptFailed）
        final int useMediaCodec = forceSoftDecode ? 0
                : (SharedPreferencesUtil.getBoolean("player_codec", true) ? 1 : 0);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", useMediaCodec);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 0);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 4);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "soundtouch", 1);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 100);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags", "flush_packets");
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1);
        //连接/读取超时（单位微秒）。ffmpeg 默认不超时：CDN 卡住时 prepareAsync 既不回调也不报错，
        //只能干等看门狗——这也是之前"卡在准备中、拿不到任何错误码"的根因。
        //设了超时才能把"卡死"变成明确的错误，交给重试链处理
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", PREPARE_TIMEOUT_MS * 1000);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rw_timeout", PREPARE_TIMEOUT_MS * 1000);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 1);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 48);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", 8 * 1024 * 1024);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user_agent", NetWorkUtil.USER_AGENT_WEB);
        Logu.d(TAG, "播放器选项 mediacodec=" + useMediaCodec);
    }

    private void ensurePlayer() {
        if (player != null) return;
        IjkMediaPlayer.loadLibrariesOnce(null);
        player = new IjkMediaPlayer();
        applyPlayerOptions(player);
    }

    /**
     * 扔掉当前播放器实例（不做 reset）。
     *
     * <p>prepare 卡死时 native 读线程可能还挂在 connect 上，{@code reset()} 会在 UI 线程等它，
     * 而且复用同一个实例会把"卡死"状态带进下一次——直接换新实例最干净。
     * {@code release()} 可能阻塞，扔到后台线程做，绝不卡 UI。
     */
    private void dropPlayer() {
        final IjkMediaPlayer old = player;
        player = null;
        if (old == null) return;
        try {
            old.setSurface(null);
        } catch (Throwable ignored) {
        }
        CenterThreadPool.run(() -> {
            try {
                old.release();
            } catch (Throwable ignored) {
            }
        });
    }

    /**
     * 每次 prepare 之前重新绑定一遍监听器。
     *
     * <p>闭包里锁住<b>实例</b>和<b>代次</b>：换条、换线路、换实例之后，旧实例迟到的
     * onPrepared / onError / onCompletion 一律丢弃。共享一个 IjkMediaPlayer 时这是必需的，
     * 否则上一条的迟到回调会把刚起播的新条目判成失败。
     */
    private void bindPlayerListeners(final IjkMediaPlayer mp, final int token) {
        mp.setOnPreparedListener(x -> {
            if (x != mp || token != playToken || destroyed) return;
            prepared = true;
            setStatus("已准备 dur=" + getDurationSafe() + "ms");
            showPlayHint("解码中…");
            if (!userPaused && shellActive) {
                try {
                    x.start();
                } catch (Throwable ignored) {
                }
            }
            ShortVideoPagerAdapter.Holder holder = holderAt(currentIndex);
            if (holder != null) holder.setBuffering(false);
            handler.removeCallbacks(progressRunnable);
            handler.post(progressRunnable);
            //prepare 完成只代表解码器就绪，画面还没出来：换一段更短的看门狗盯首帧
            armWatchdog(token, FIRST_FRAME_TIMEOUT_MS);
        });
        mp.setOnCompletionListener(x -> {
            if (x != mp || token != playToken || destroyed) return;
            onVideoCompleted();
        });
        mp.setOnErrorListener((x, what, extra) -> {
            if (x == mp && token == playToken && !destroyed) {
                onAttemptFailed(token, "播放器报错 what=" + what + " extra=" + extra);
            }
            return true;
        });
        mp.setOnInfoListener((x, what, extra) -> {
            if (x != mp || token != playToken) return false;
            if (what == IjkMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                revealCurrentPage();
            } else if (what == IjkMediaPlayer.MEDIA_INFO_BUFFERING_START) {
                ShortVideoPagerAdapter.Holder holder = holderAt(currentIndex);
                if (holder != null) holder.setBuffering(true);
            } else if (what == IjkMediaPlayer.MEDIA_INFO_BUFFERING_END) {
                ShortVideoPagerAdapter.Holder holder = holderAt(currentIndex);
                if (holder != null) holder.setBuffering(false);
            }
            return false;
        });
    }

    /**
     * 首帧真的画出来了再撤封面。
     *
     * <p>第一版是"起播 1.2 秒后无条件撤封面"，播放失败时用户只会看到一块没有任何信息的纯黑页；
     * 现在只在 {@code MEDIA_INFO_VIDEO_RENDERING_START}（真出画）时调用，失败由看门狗接管。
     */
    private void revealCurrentPage() {
        //注意：不能因为拿不到 holder 就提前 return——首帧回调可能早于列表布局完成，
        //那样封面就再也不会被撤掉（表现为"视频在播，但一直停在一张封面上"）。
        //状态与提示必须先按"已出画"处理，holder 相关的 UI 才做空判断。
        cancelWatchdog();
        firstFrameShown = true;
        hidePlayHint();
        setStatus("首帧已出 aid=" + (currentItem() == null ? 0 : currentItem().aid));
        ShortVideoPagerAdapter.Holder holder = holderAt(currentIndex);
        if (holder != null) {
            holder.showCover(false);
            holder.setBuffering(false);
        }
    }

    private long getDurationSafe() {
        try {
            return player == null ? 0 : player.getDuration();
        } catch (Throwable t) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- 取流

    private void loadFeed(boolean first) {
        if (destroyed || loadingFeed) return;
        loadingFeed = true;
        if (first) {
            loadingLayout.setVisibility(View.VISIBLE);
            errorLayout.setVisibility(View.GONE);
            loadingText.setText("加载短视频…");
        }
        final boolean firstBatch = first;
        CenterThreadPool.run(() -> {
            final List<ShortVideoItem> batch = pager.fetchPage();
            handler.post(() -> {
                if (destroyed) return;
                loadingFeed = false;
                if (batch.isEmpty()) {
                    if (firstBatch && items.isEmpty()) {
                        loadingLayout.setVisibility(View.GONE);
                        errorLayout.setVisibility(View.VISIBLE);
                        errorText.setText("获取视频列表失败");
                    } else {
                        feedEnded = true;
                        MsgUtil.showMsg("没有更多短视频了");
                    }
                    return;
                }
                int start = items.size();
                if (firstBatch) {
                    //重试/刷新：旧列表可能是半截失败数据，先清掉再加，避免出现重复页
                    items.clear();
                    start = 0;
                }
                items.addAll(batch);
                if (firstBatch) {
                    adapter.notifyDataSetChanged();
                    loadingLayout.setVisibility(View.GONE);
                    errorLayout.setVisibility(View.GONE);
                    recyclerView.scrollToPosition(0);
                    //scrollToPosition 不触发滚动状态回调，这里显式起播第一页
                    currentIndex = -1;
                    onSnapTo(0);
                } else {
                    adapter.notifyItemRangeInserted(start, batch.size());
                }
            });
        });
    }

    private void maybeLoadMore() {
        if (loadingFeed || feedEnded) return;
        if (items.size() - currentIndex <= LOAD_MORE_THRESHOLD) loadFeed(false);
    }

    private void loadRelated(long anchorAid) {
        if (relatedLoading || destroyed) return;
        relatedLoading = true;
        MsgUtil.showMsg("正在为你推荐相关视频…");
        CenterThreadPool.run(() -> {
            final List<ShortVideoItem> batch = pager.fetchRelated(anchorAid);
            handler.post(() -> {
                if (destroyed) return;
                relatedLoading = false;
                if (batch.isEmpty()) {
                    feedEnded = true;
                    MsgUtil.showMsg("没有更多短视频了");
                    return;
                }
                int start = items.size();
                items.addAll(batch);
                adapter.notifyItemRangeInserted(start, batch.size());
                scrollTo(start);
            });
        });
    }

    // ---------------------------------------------------------------- 播放切换

    private void onSnapTo(int position) {
        if (position < 0 || position >= items.size()) return;
        if (position == currentIndex) return;

        ShortVideoPagerAdapter.Holder previous = holderAt(currentIndex);
        if (previous != null) {
            previous.showCover(true);
            previous.setPlaying(false);
            previous.setBuffering(false);
        }
        currentIndex = position;
        userPaused = false;
        hidePlayHint();
        //每换一条：操作栏先亮一下，3.5 秒后自动收起（双击仍可随时叫出来）
        setControlsVisible(true);
        playCurrent(position);
        maybeLoadMore();
        if (getActivity() instanceof ShortVideoPlayerActivity) {
            ((ShortVideoPlayerActivity) getActivity()).onFeedAnchorChanged();
        }
    }

    private void playCurrent(int position) {
        ShortVideoItem item = items.get(position);
        //每一条都从「第一个候选地址 + 硬解」重新开始试
        candidateIndex = 0;
        softDecodeTried = false;
        forceSoftDecode = false;
        firstFrameShown = false;
        showPlayHint("正在加载…");
        if (item.videoUrlList.isEmpty() && TextUtils.isEmpty(item.videoUrl)) {
            ShortVideoPagerAdapter.Holder holder = holderAt(position);
            if (holder != null) holder.setBuffering(true);
            setStatus("取流中 aid=" + item.aid + " cid=" + item.cid);
            final int target = ++playToken;
            CenterThreadPool.run(() -> {
                final String error = ShortVideoFeedApi.fetchVideoUrl(item);
                handler.post(() -> {
                    if (destroyed || target != playToken || currentIndex != position) return;
                    if (error != null || (item.videoUrlList.isEmpty() && TextUtils.isEmpty(item.videoUrl))) {
                        ShortVideoPagerAdapter.Holder h = holderAt(position);
                        if (h != null) h.setBuffering(false);
                        //取流失败不再自动跳下一条：停在当前这条，点提示就能重试
                        setStatus("取流失败：" + error);
                        showPlayHint("取流失败：" + error + "，点击重试");
                        MsgUtil.showMsg("获取播放地址失败");
                        return;
                    }
                    startPlayback(item, position);
                });
            });
            return;
        }
        startPlayback(item, position);
    }

    /**
     * 按候选顺序取地址。
     *
     * <p>playurl 下发的首地址经常是 PCDN（{@code *.edge.mountaintoys.cn:4483} 这类），
     * 移动网络下不一定连得上；{@link com.RobinNotBad.BiliClient.api.PlayerApi#buildMediaCandidates}
     * 已经把列表排成「官方 CDN 优先、PCDN 兜底」，这里按下标逐个试。
     */
    private String resolveCandidate(ShortVideoItem item) {
        ArrayList<String> list = item.videoUrlList;
        if (list != null && !list.isEmpty()) {
            if (candidateIndex < 0 || candidateIndex >= list.size()) return null;
            return list.get(candidateIndex);
        }
        return candidateIndex == 0 ? item.videoUrl : null;
    }

    private void startPlayback(ShortVideoItem item, int position) {
        ensurePlayer();
        if (!surfaceReady) {
            //surface 还没建好（进页面第一帧）：等 surfaceCreated 再挂。重试要封顶，
            //否则页面被系统悄悄销毁时这里会一直 post 自己
            ShortVideoPagerAdapter.Holder holder = holderAt(position);
            if (holder != null) holder.setBuffering(true);
            if (surfaceRetry++ > 25) {
                surfaceRetry = 0;
                showPlayHint("播放器初始化超时，点击重试");
                return;
            }
            textureView.postDelayed(() -> {
                if (!destroyed && currentIndex == position) startPlayback(item, position);
            }, 200);
            return;
        }
        surfaceRetry = 0;

        final String playUrl = resolveCandidate(item);
        if (TextUtils.isEmpty(playUrl)) {
            setStatus("没有可用的播放地址 aid=" + item.aid);
            showPlayHint("没有可用的播放地址，点击重试");
            return;
        }
        final int token = ++playToken;
        prepared = false;
        firstFrameShown = false;
        userPaused = false;
        cancelWatchdog();
        try {
            player.reset();
            //reset() 会把 native 实例整个重建，选项与 surface 都得重新挂（见 applyPlayerOptions 注释）
            applyPlayerOptions(player);
            player.setSurface(videoSurface);
            bindPlayerListeners(player, token);
            //必须带 Referer：B 站媒体 CDN 对缺 Referer 的请求会 403，表现就是 prepare 永远不回调。
            //Cookie 由 mediaHeadersFor 按域决定——第三方 PCDN 地址不再携带登录态
            String mediaUrl = ShortVideoFeedApi.upgradeMediaUrl(playUrl);
            player.setDataSource(mediaUrl, PlayerApi.mediaHeadersFor(mediaUrl));
            player.setScreenOnWhilePlaying(true);
            player.prepareAsync();
        } catch (Throwable e) {
            onAttemptFailed(token, "起播失败：" + e.getClass().getSimpleName());
            return;
        }
        showPlayHint("准备中…");
        setStatus("准备中 aid=" + item.aid + " 候选#" + (candidateIndex + 1)
                + (forceSoftDecode ? " 软解" : " 硬解") + "\n" + abbreviate(playUrl));
        Logu.d(TAG, "prepare aid=" + item.aid + " cand=" + candidateIndex
                + " soft=" + forceSoftDecode + " url=" + abbreviate(playUrl));
        ShortVideoPagerAdapter.Holder holder = holderAt(position);
        if (holder != null) {
            holder.setBuffering(true);
            holder.setPlaying(true);
        }
        armWatchdog(token, PREPARE_TIMEOUT_MS);
    }

    /**
     * 这一次起播失败了。按「换线路 → 换软解 → 放弃」三级处理，<b>绝不自动跳下一条</b>。
     *
     * <p>v1 是"一失败就自动跳下一条"，而同一条在 prepare 卡死时 native 实例还挂在 connect 上，
     * 下一条 reset() 复用它就会跟着卡住，于是连着几条一起判死——用户看到的是"满屏报错"。
     * 现在同一条自己把能试的都试完，再不行就停在封面上，由用户决定重试还是上滑跳过。
     */
    private void onAttemptFailed(final int token, String reason) {
        if (destroyed || token != playToken) return;
        cancelWatchdog();
        prepared = false;
        ShortVideoPagerAdapter.Holder holder = holderAt(currentIndex);
        if (holder != null) holder.setBuffering(false);
        setStatus("失败：" + reason);
        Logu.e(TAG, "播放失败 aid=" + (currentItem() == null ? 0 : currentItem().aid) + "：" + reason);

        final ShortVideoItem item = currentItem();
        if (item == null) return;
        final int position = currentIndex;

        //不管是换线路还是换解码器，都换一个新实例：prepare 卡死的实例可能还挂在 connect 上，
        //reset() 复用它会在 UI 线程等它（见 dropPlayer 注释）
        dropPlayer();

        //1) 还有别的线路：换下一个候选地址再来
        if (item.videoUrlList.size() > candidateIndex + 1) {
            candidateIndex++;
            //先作废旧代次，别让这个实例迟到的回调把新一轮也判死
            final int retryToken = ++playToken;
            showPlayHint("这条线路没起来，换线路重试…");
            handler.postDelayed(() -> {
                if (destroyed || retryToken != playToken) return;
                startPlayback(item, position);
            }, RETRY_DELAY_MS);
            return;
        }
        //2) 线路都试过了：硬解换软解再来一次（部分机型 mediacodec 起不来却也不报错）
        if (!softDecodeTried && SharedPreferencesUtil.getBoolean("player_codec", true)) {
            softDecodeTried = true;
            forceSoftDecode = true;
            candidateIndex = 0;
            final int retryToken = ++playToken;
            showPlayHint("硬解失败，改用软解重试…");
            handler.postDelayed(() -> {
                if (destroyed || retryToken != playToken) return;
                startPlayback(item, position);
            }, RETRY_DELAY_MS);
            return;
        }
        //3) 都试完了：停在当前这条，等用户决定（点提示重试 / 上滑跳过）
        playToken++;   //已经换掉实例了，把它的迟到回调一并作废
        showPlayHint(reason + "，点击重试或上滑跳过");
        MsgUtil.showMsg("这条视频播不了，点提示可以重试");
    }

    /** 单个看门狗：换阶段时重新赋值，removeCallbacks 才能准确撤掉上一个 */
    private Runnable watchdog;

    private void cancelWatchdog() {
        if (watchdog != null) {
            handler.removeCallbacks(watchdog);
            watchdog = null;
        }
    }

    /**
     * 布一个看门狗。
     *
     * <p>两段计时：prepare 阶段用 {@link #PREPARE_TIMEOUT_MS}，prepare 完成到首帧用
     * {@link #FIRST_FRAME_TIMEOUT_MS}——原来只有一个 8 秒的粗暴计时，冷连接稍微慢一点就被判死，
     * 而且它不认代次，换条之后旧看门狗醒来会把新条目一起判死。
     */
    private void armWatchdog(final int token, long delayMs) {
        cancelWatchdog();
        watchdog = () -> {
            watchdog = null;
            if (destroyed || token != playToken) return;
            if (!shellActive || userPaused) {
                //用户翻到右侧页或手动暂停：画面本来就不该出，这不算失败。等回来再看
                armWatchdog(token, FIRST_FRAME_TIMEOUT_MS);
                return;
            }
            if (!prepared) {
                onAttemptFailed(token, "起播超时（prepared=false，surface=" + surfaceReady + "）");
            } else if (!firstFrameShown) {
                onAttemptFailed(token, "解码了但一直没出画（prepared=true）");
            }
        };
        handler.postDelayed(watchdog, delayMs);
    }

    private static String abbreviate(String url) {
        if (url == null) return "";
        return url.length() > 90 ? url.substring(0, 90) + "…" : url;
    }

    /**
     * 播放状态/失败提示。
     *
     * <p>与调试状态行分开：这一行是给普通用户看的加载态——「正在加载…」→「解码中…」→（出画后消失）；
     * 失败时显示原因并且可以点一下重试。之前失败就是一块没有任何信息的封面，
     * 既没法判断卡在哪一步，用户也不知道能不能重试。
     */
    private void showPlayHint(String text) {
        if (textPlayHint == null) return;
        hintToken++;
        textPlayHint.setText(text);
        textPlayHint.setVisibility(View.VISIBLE);
    }

    /**
     * 操作结果也走这一行：点赞/收藏/评论到底成没成，用户抬头就能看见。
     *
     * <p>为什么不用 Toast 就完事：Snackbar/Toast 位置不固定、容易错过，而这个页面之前
     * 已经证明"屏幕上那行字"是唯一能被用户准确复述的诊断信息（截图里就是它）。
     * 4 秒后自动收起，播放状态变化时会顶掉它（{@link #hintToken} 保证旧的收起不会误清新的提示）。
     */
    private void showActionHint(String text) {
        showPlayHint(text);
        final int token = hintToken;
        handler.postDelayed(() -> {
            if (token == hintToken) hidePlayHint();
        }, 4000);
    }

    private void hidePlayHint() {
        if (textPlayHint == null) return;
        hintToken++;
        textPlayHint.setVisibility(View.GONE);
    }

    /** 页面内状态行：只在「设置 → 实验室 → 调试 → 允许Logu.d」打开时显示 */
    private void setStatus(String text) {
        if (textStatus == null) return;
        if (!SharedPreferencesUtil.getBoolean("dev_logd", false)) {
            textStatus.setVisibility(View.GONE);
            return;
        }
        textStatus.setVisibility(View.VISIBLE);
        textStatus.setText(text);
    }

    private void onVideoCompleted() {
        int next = currentIndex + 1;
        if (next < items.size()) {
            scrollTo(next);
            return;
        }
        ShortVideoItem item = currentItem();
        if (item == null || feedEnded) {
            MsgUtil.showMsg("没有更多短视频了");
            return;
        }
        loadRelated(item.aid);
    }

    /** 切到某一页：smoothScroll 会走翻页动画并触发 IDLE 回调，由 onSnapTo 接管换源 */
    private void scrollTo(int position) {
        if (position < 0 || position >= items.size()) return;
        recyclerView.smoothScrollToPosition(position);
    }

    // ---------------------------------------------------------------- 控制栏

    private void setControlsVisible(boolean visible) {
        controlsVisible = visible;
        if (adapter != null) adapter.setControlsVisibleState(visible);
        applyControlsToCurrent();
        handler.removeCallbacks(hideControlsRunnable);
        if (visible) handler.postDelayed(hideControlsRunnable, AUTO_HIDE_MS);
    }

    /**
     * 把显隐状态落到当前这一页上。
     *
     * <p>v1 用 {@code animate().alpha(0)}：看不见但**仍然可以点**，用户会误触到"赞"，
     * 而且点了以后图标在看不见的地方变，看起来就是"点赞没反应"。现在改成 INVISIBLE +
     * 关闭子 View 的 enabled，收起时彻底不可交互。
     */
    private void applyControlsToCurrent() {
        ShortVideoPagerAdapter.Holder holder = holderAt(currentIndex);
        if (holder != null) holder.setControlsVisible(controlsVisible);
        if (btnBack != null) {
            btnBack.setVisibility(controlsVisible ? View.VISIBLE : View.INVISIBLE);
            btnBack.setEnabled(controlsVisible);
        }
    }

    @Override
    public void onControlsTouched() {
        handler.removeCallbacks(hideControlsRunnable);
        handler.postDelayed(hideControlsRunnable, AUTO_HIDE_MS);
    }

    private void togglePlay() {
        if (player == null || !prepared) return;
        if (player.isPlaying()) {
            player.pause();
            userPaused = true;
            ShortVideoPagerAdapter.Holder holder = holderAt(currentIndex);
            if (holder != null) holder.setPlaying(false);
        } else {
            userPaused = false;
            player.start();
            ShortVideoPagerAdapter.Holder holder = holderAt(currentIndex);
            if (holder != null) holder.setPlaying(true);
        }
        setControlsVisible(true);
    }

    // ---------------------------------------------------------------- 手势

    private void setupGestures(View root) {
        gestureDetector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                //点在操作栏/底栏/返回键/提示条上不算"点画面"，交给它们自己的点击事件
                if (isOnControls(e)) return false;
                //单击只负责操作栏显隐：收起时叫出来，显示时收回去
                setControlsVisible(!controlsVisible);
                return true;
            }

            @Override
            public boolean onDoubleTap(@NonNull MotionEvent e) {
                if (!controlsVisible) {
                    //控件收着的时候没有别的入口，双击先把它叫出来
                    setControlsVisible(true);
                    return true;
                }
                if (isOnControls(e)) return false;
                togglePlay();
                return true;
            }

            @Override
            public void onLongPress(@NonNull MotionEvent e) {
                if (controlsVisible && isOnControls(e)) return;
                startSpeedBoost();
            }
        });
        //必须挂在 dispatchTouchEvent 这一级（见 FeedGestureLayout 注释）：根布局的
        //OnTouchListener 收不到事件——下面的 RecyclerView 会对 ACTION_DOWN 无条件返回 true
        //把触摸消费掉；即使收到了，返回 false 也接不到 UP，手势检测器永远确认不了一次点击
        if (root instanceof FeedGestureLayout) {
            FeedGestureLayout gestureRoot = (FeedGestureLayout) root;
            gestureRoot.setGestureDetector(gestureDetector);
            gestureRoot.setOnTouchEnd(this::endSpeedBoost);
        }
    }

    /** 这一下点在控件上吗？（控件已收起时一定是 false：INVISIBLE 的 View 没有可见区域） */
    private boolean isOnControls(MotionEvent e) {
        int x = (int) e.getRawX();
        int y = (int) e.getRawY();
        Rect rect = new Rect();
        ShortVideoPagerAdapter.Holder holder = holderAt(currentIndex);
        if (holder != null) {
            if (holder.actionColumn.getGlobalVisibleRect(rect) && rect.contains(x, y)) return true;
            if (holder.bottomInfo.getGlobalVisibleRect(rect) && rect.contains(x, y)) return true;
        }
        //返回键和加载/失败提示条也是可点的，别被当成"点画面"顺手暂停
        if (btnBack != null && btnBack.getVisibility() == View.VISIBLE
                && btnBack.getGlobalVisibleRect(rect) && rect.contains(x, y)) return true;
        return textPlayHint != null && textPlayHint.getVisibility() == View.VISIBLE
                && textPlayHint.getGlobalVisibleRect(rect) && rect.contains(x, y);
    }

    private void startSpeedBoost() {
        if (player == null || !prepared || !player.isPlaying()) return;
        normalSpeed = 1f;
        try {
            player.setSpeed(BOOST_SPEED);
            MsgUtil.showMsg("2.0x 倍速播放中");
        } catch (Throwable ignored) {
        }
    }

    private void endSpeedBoost() {
        if (player == null || !prepared) return;
        try {
            player.setSpeed(normalSpeed);
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 进度

    private void updateProgress() {
        if (destroyed || player == null || !prepared || currentIndex < 0) return;
        ShortVideoPagerAdapter.Holder holder = holderAt(currentIndex);
        if (holder == null) return;
        try {
            holder.updateProgress((int) player.getCurrentPosition(), (int) player.getDuration());
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 操作栏回调

    @Override
    public void onLike(ShortVideoItem item, int position) {
        ShortVideoPagerAdapter.Holder holder = holderAt(position);
        if (holder == null) {
            showActionHint("点赞没生效：当前页状态已失效，重新滑一下再试");
            return;
        }
        if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) == 0) {
            showActionHint("还没有登录，先登录再点赞");
            MsgUtil.showMsg("还没有登录，先登录再点赞");
            return;
        }
        final boolean target = !holder.liked;
        showActionHint(target ? "正在点赞…" : "正在取消点赞…");
        CenterThreadPool.run(() -> {
            try {
                //接口是靠返回码表达失败的（-101 未登录 / -111 csrf 失效 / -403 风控 / 65006 重复点赞）。
                //v1 丢弃返回值，本地照样翻图标，用户以为点上了，其实服务端根本没记下来
                int code = LikeCoinFavApi.like(item.aid, target ? 1 : 0);
                if (code != 0) {
                    String reason = likeFailReason(code);
                    Logu.e("short-video", "点赞失败 aid=" + item.aid + " code=" + code);
                    handler.post(() -> {
                        showActionHint(reason);
                        MsgUtil.showMsg(reason);
                    });
                    return;
                }
                item.likeCount += target ? 1 : -1;
                if (item.likeCount < 0) item.likeCount = 0;
                item.likeState = target ? 1 : 0;
                final long finalCount = item.likeCount;
                handler.post(() -> {
                    holder.liked = target;
                    holder.iconLike.setImageResource(target ? R.drawable.icon_like_1 : R.drawable.icon_like_0);
                    holder.textLike.setText(finalCount > 0 ? ShortVideoItem.formatCount(finalCount) : "点赞");
                    showActionHint(target ? "已点赞" : "已取消点赞");
                });
            } catch (Exception e) {
                Logu.e("short-video", "点赞异常 aid=" + item.aid + "：" + e);
                handler.post(() -> {
                    String reason = (target ? "点赞失败：" : "取消点赞失败：")
                            + e.getClass().getSimpleName();
                    showActionHint(reason);
                    MsgUtil.showMsg(reason);
                });
            }
        });
    }

    /** 点赞接口返回码 → 用户看得懂的话 */
    private static String likeFailReason(int code) {
        switch (code) {
            case -101:
                return "还没有登录，先登录再点赞";
            case -111:
                return "登录状态已失效，请重新登录";
            case -403:
                return "操作过于频繁，被风控拦下了，稍后再试";
            case 65004:
                return "取消点赞失败，稍后再试";
            case 65006:
                return "已经点过赞了喵~";
            default:
                return "点赞失败，错误码：" + code;
        }
    }

    @Override
    public void onFav(ShortVideoItem item, int position) {
        if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) == 0) {
            //AddFavoriteActivity 自己也会判这句然后 finish，先在这里明说，别让用户以为"点了没反应"
            showActionHint("还没有登录，先登录再收藏");
            MsgUtil.showMsg("还没有登录，先登录再收藏");
            return;
        }
        Intent intent = new Intent(getContext(), com.RobinNotBad.BiliClient.activity.video.info.AddFavoriteActivity.class);
        intent.putExtra("aid", item.aid);
        //走 launcher 等回传：收藏夹选择页是"用户操作完才 setResult(1/-1)"的，
        //v1 直接 startActivity 后立刻写"已收藏"，连取消选择都会显示成功
        try {
            favLauncher.launch(intent);
            showActionHint("已打开收藏夹，选一个收藏夹即可");
        } catch (Throwable e) {
            Logu.e("short-video", "打开收藏夹失败 aid=" + item.aid + "：" + e);
            showActionHint("打开收藏夹失败：" + e.getClass().getSimpleName());
        }
    }

    @Override
    public void onComment(ShortVideoItem item, int position) {
        if (getActivity() instanceof ShortVideoPlayerActivity) {
            //必须把当前这条的 aid 一起带过去：右侧三页是按锚点创建的，
            //锚点没设好时 ShortVideoShellAdapter 返回的是空壳 Fragment，翻过去只有一张白页
            ((ShortVideoPlayerActivity) getActivity())
                    .openPageFor(ShortVideoPlayerActivity.PAGE_REPLY, item.aid, item.bvid);
            showActionHint("正在打开评论…");
        } else {
            showActionHint("打开评论失败：页面已失效");
        }
    }

    @Override
    public void onMore(ShortVideoItem item, int position) {
        Intent intent = new Intent(getContext(), ListChooseActivity.class);
        intent.putExtra("title", "更多操作");
        ArrayList<String> options = new ArrayList<>(Arrays.asList(
                "稍后再看", "投币 1 枚", "分享", "查看视频详情", "不感兴趣"));
        intent.putExtra("items", options);
        startActivityForResult(intent, REQ_MORE);
    }

    private static final int REQ_MORE = 0x51;

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_MORE || data == null) return;
        String choice = data.getStringExtra("item");
        ShortVideoItem item = currentItem();
        if (choice == null || item == null) return;
        switch (choice) {
            case "稍后再看":
                CenterThreadPool.run(() -> {
                    try {
                        int code = WatchLaterApi.add(item.aid);
                        handler.post(() -> MsgUtil.showMsg(code == 0
                                ? "已加入稍后再看" : "加入稍后再看失败，错误码：" + code));
                    } catch (Exception e) {
                        handler.post(() -> MsgUtil.showMsg("加入稍后再看失败"));
                    }
                });
                break;
            case "投币 1 枚":
                CenterThreadPool.run(() -> {
                    try {
                        //同点赞：coin 也是用返回码表达失败，v1 一律报"投币成功"
                        int code = LikeCoinFavApi.coin(item.aid, 1);
                        handler.post(() -> MsgUtil.showMsg(coinResultText(code)));
                    } catch (Exception e) {
                        handler.post(() -> MsgUtil.showMsg("投币失败"));
                    }
                });
                break;
            case "分享": {
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("text/plain");
                share.putExtra(Intent.EXTRA_TEXT,
                        item.title + "  https://www.bilibili.com/video/" + (TextUtils.isEmpty(item.bvid) ? ("av" + item.aid) : item.bvid));
                startActivity(Intent.createChooser(share, "分享视频"));
                break;
            }
            case "查看视频详情":
                if (getActivity() instanceof ShortVideoPlayerActivity) {
                    ((ShortVideoPlayerActivity) getActivity())
                            .openPageFor(ShortVideoPlayerActivity.PAGE_DETAIL, item.aid, item.bvid);
                }
                break;
            case "不感兴趣":
                removeCurrent();
                break;
            default:
                break;
        }
    }

    /** 投币接口返回码 → 用户看得懂的话 */
    private static String coinResultText(int code) {
        switch (code) {
            case 0:
                return "投币成功";
            case 34002:
                return "不能给自己投币哦";
            case 34005:
                return "这一条能投的币已经到上限了";
            case -101:
                return "还没有登录";
            case -111:
                return "登录状态已失效，请重新登录";
            case -403:
                return "投币被风控拦下了，稍后再试";
            default:
                return "投币失败，错误码：" + code;
        }
    }

    /** 「不感兴趣」：本地直接移掉当前这条，再落到它后面那条（不再参与本次会话） */
    private void removeCurrent() {
        if (currentIndex < 0 || currentIndex >= items.size()) return;
        int target = Math.min(currentIndex, items.size() - 2);
        items.remove(currentIndex);
        adapter.notifyItemRemoved(currentIndex);
        playToken++;
        prepared = false;
        currentIndex = -1;
        if (items.isEmpty()) {
            loadFeed(true);
            return;
        }
        if (target < 0) target = 0;
        final int finalTarget = target;
        recyclerView.post(() -> {
            recyclerView.scrollToPosition(finalTarget);
            onSnapTo(finalTarget);
        });
    }

    @Override
    public void onSeekStart() {
    }

    @Override
    public void onSeek(ShortVideoItem item, int position, int progressMs) {
        if (player == null || !prepared || position != currentIndex) return;
        try {
            player.seekTo(progressMs);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onSeekStop() {
    }

    // ---------------------------------------------------------------- 生命周期

    /** 外壳翻到右侧页 / 翻回来时调用：离开第 0 页就暂停，回来再续播 */
    public void setShellPageActive(boolean active) {
        shellActive = active;
        if (player == null) return;
        if (!active) {
            try {
                if (player.isPlaying()) player.pause();
            } catch (Throwable ignored) {
            }
        } else if (prepared && !userPaused) {
            try {
                player.start();
            } catch (Throwable ignored) {
            }
        }
    }

    public ShortVideoItem currentItem() {
        return currentIndex >= 0 && currentIndex < items.size() ? items.get(currentIndex) : null;
    }

    private ShortVideoPagerAdapter.Holder holderAt(int position) {
        if (recyclerView == null || position < 0) return null;
        RecyclerView.ViewHolder holder = recyclerView.findViewHolderForAdapterPosition(position);
        return holder instanceof ShortVideoPagerAdapter.Holder ? (ShortVideoPagerAdapter.Holder) holder : null;
    }

    @Override
    public void onDestroyView() {
        destroyed = true;
        cancelWatchdog();
        handler.removeCallbacksAndMessages(null);
        //先摘 Surface 再异步 release：release 可能被卡住的 native 读线程拖住，不能占着 UI 线程；
        //摘掉 Surface 之后 native 也不会再去碰这块已经被我们释放的 Surface
        dropPlayer();
        if (videoSurface != null) {
            videoSurface.release();
            videoSurface = null;
        }
        recyclerView = null;
        textureView = null;
        super.onDestroyView();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (prepared && !userPaused && shellActive) {
            try {
                if (player != null && !player.isPlaying()) player.start();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        handler.removeCallbacks(hideControlsRunnable);
        //进度刷新是每 500ms 自续期的 Runnable：不停掉的话退到后台仍在跑（读播放器位置 + 刷 UI）
        handler.removeCallbacks(progressRunnable);
        if (player != null) {
            try {
                if (player.isPlaying()) player.pause();
            } catch (Throwable ignored) {
            }
        }
    }
}
