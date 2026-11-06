package com.RobinNotBad.BiliClient.activity.user.info;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.RefreshListFragment;
import com.RobinNotBad.BiliClient.adapter.video.UserVideoAdapter;
import com.RobinNotBad.BiliClient.api.UserInfoApi;
import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.ui.theme.ThemeManager;
import com.RobinNotBad.BiliClient.util.AdaptiveDialog;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;

import java.util.ArrayList;
import java.util.List;

//用户视频
//2023-09-30
//2024-05-03
//2026-11-06 加空间内搜索与排序

/**
 * UP 主主页「TA 的投稿」。
 *
 * <p>列表走 {@link RefreshListFragment} 那套刷新/翻页/空态，本类只多两件事：
 * <b>空间内搜索</b>（关键词直传 space/wbi/arc/search，服务端过滤）和<b>排序</b>
 * （order=pubdate 最新发布 / click 最多播放）。
 *
 * <p>搜索与排序都会重拉第一页，所以加载结果必须按「代数」丢弃过期响应：连点搜索/排序时
 * 先发的请求可能后回来，不拦的话旧关键词的结果会把新结果覆盖掉。
 */
public class UserVideoFragment extends RefreshListFragment {

    /** 排序档位：{显示名, order 参数值}，与 web 端空间投稿的排序一致 */
    private static final String[][] SORT_OPTIONS = {
            {"最新发布", UserInfoApi.ORDER_PUBDATE},
            {"最多播放", UserInfoApi.ORDER_CLICK}
    };

    private long mid;
    private ArrayList<VideoCard> videoList;
    private UserVideoAdapter adapter;

    private EditText keywordInput;
    private AppCompatImageButton sortButton;
    /** 当前关键词（已 trim）：空串 = 不过滤，等于「全部投稿」 */
    private String keyword = "";
    /** volatile：档位在主线程写、加载线程读，避免后台读到旧档位拉回旧排序 */
    private volatile String sortOrder = UserInfoApi.ORDER_PUBDATE;
    /** 每次重拉第一页自增，迟到的旧响应直接丢弃 */
    private int loadGeneration = 0;

    public UserVideoFragment() {

    }

    public static UserVideoFragment newInstance(long mid) {
        UserVideoFragment fragment = new UserVideoFragment();
        Bundle args = new Bundle();
        args.putLong("mid", mid);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            mid = getArguments().getLong("mid");
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        //比基类多一条搜索栏；列表仍是 fragment_simple_refresh（include 进去），
        //基类 onViewCreated 按 id 找 emptyTip/swipeRefreshLayout/recyclerView 不受影响
        return inflater.inflate(R.layout.fragment_user_video, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        videoList = new ArrayList<>();
        initSearchBar(view);
        setOnLoadMoreListener(this::continueLoading);
        //注册下拉刷新：基类默认禁用且此前未注册，失败后只能退出重进；空态/失败态同步补齐（X-26）
        setOnRefreshListener(this::loadVideos);
        loadVideos();
    }

    private void initSearchBar(View view) {
        keywordInput = view.findViewById(R.id.keywordInput);
        sortButton = view.findViewById(R.id.sortButton);

        if (keywordInput != null) {
            //软键盘右下角就是「搜索」：回车即搜，不用先点图标再收键盘
            keywordInput.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                    submitSearch(keywordInput.getText().toString().trim());
                    hideKeyboard();
                    return true;
                }
                return false;
            });
        }

        View searchButton = view.findViewById(R.id.searchButton);
        if (searchButton != null) searchButton.setOnClickListener(v -> {
            submitSearch(keywordInput != null ? keywordInput.getText().toString().trim() : "");
            hideKeyboard();
        });

        if (sortButton != null) {
            sortButton.setOnClickListener(v -> showSortDialog());
            updateSortButton();
        }
    }

    private void hideKeyboard() {
        if (keywordInput == null) return;
        keywordInput.clearFocus();
        Context context = getContext();
        if (context == null) return;
        InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(keywordInput.getWindowToken(), 0);
    }

    /** 同一个词再点一次不重复请求：关键词没变就什么也不做（列表还在那儿） */
    private void submitSearch(String newKeyword) {
        if (newKeyword == null) newKeyword = "";
        if (newKeyword.equals(keyword)) return;
        keyword = newKeyword;
        reloadFirstPage();
    }

    /** 关键词/排序变化后的重拉：作废在途请求、复位游标、回到第一页 */
    private void reloadFirstPage() {
        page = 1;
        bottom = false;
        setRefreshing(true);
        loadVideos();
    }

    private void updateSortButton() {
        if (sortButton == null) return;
        //非默认档位用主题强调色，一眼能看出当前不是「最新发布」
        boolean customOrder = !UserInfoApi.ORDER_PUBDATE.equals(sortOrder);
        Context context = getContext();
        if (context == null) return;
        int color = customOrder ? ThemeManager.getPrimary()
                : ContextCompat.getColor(context, R.color.textwhite);
        ImageViewCompat.setImageTintList(sortButton, ColorStateList.valueOf(color));
    }

    private void showSortDialog() {
        Context context = getContext();
        if (context == null) return;

        View content = LayoutInflater.from(context).inflate(R.layout.dialog_search_sort, null);
        LinearLayout container = content.findViewById(R.id.sort_options);
        int primary = ThemeManager.getPrimary();
        //弹窗不在 ThemeApplier 的作用范围（它只管 Activity 的 setContentView），
        //强调色必须在这里按用户主题色手动设，否则自定义主题下弹窗永远是粉色
        content.findViewById(R.id.sort_title_bar).setBackgroundColor(primary);

        final android.app.Dialog[] holder = new android.app.Dialog[1];
        for (String[] option : SORT_OPTIONS) {
            View row = LayoutInflater.from(context).inflate(R.layout.item_search_sort_option, container, false);
            ((TextView) row.findViewById(R.id.option_text)).setText(option[0]);

            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(primary);
            row.findViewById(R.id.option_dot).setBackground(dot);

            ImageView check = row.findViewById(R.id.option_check);
            if (option[1].equals(sortOrder)) {
                ImageViewCompat.setImageTintList(check, ColorStateList.valueOf(primary));
                check.setVisibility(View.VISIBLE);
            } else {
                check.setVisibility(View.GONE);
            }

            row.setOnClickListener(v -> {
                if (holder[0] != null) holder[0].dismiss();
                if (option[1].equals(sortOrder)) return;
                sortOrder = option[1];
                updateSortButton();
                reloadFirstPage();
            });
            container.addView(row);
        }

        holder[0] = new AdaptiveDialog.Builder(context).setView(content).show();
    }

    private void loadVideos() {
        //递增在后台上线程之前完成：调用方（搜索/排序/下拉）一进来就先作废所有在途请求
        final int generation = ++loadGeneration;
        final String requestKeyword = keyword;
        final String requestOrder = sortOrder;
        CenterThreadPool.run(() -> {
            try {
                List<VideoCard> list = new ArrayList<>();
                int result = UserInfoApi.getUserVideos(mid, 1, requestKeyword, requestOrder, list);
                runOnUiThread(() -> {
                    if (generation != loadGeneration || !isAdded() || getContext() == null) return;
                    setRefreshing(false);
                    page = 1;
                    videoList.clear();
                    videoList.addAll(list);
                    bottom = (result == 1);
                    adapter = new UserVideoAdapter(requireContext(), mid, videoList);
                    setAdapter(adapter);
                    //搜索/排序后要看到的一定是第一条结果，别停在上一批的滚动位置
                    if (recyclerView != null) recyclerView.scrollToPosition(0);
                    if (videoList.isEmpty()) showEmptyView(emptyMessage(result, requestKeyword));
                    else hideEmptyView();
                });
            } catch (Exception e) {
                if (generation != loadGeneration) return;   //旧请求的异常不要弹给用户
                runOnUiThread(() -> {
                    if (generation != loadGeneration || !isAdded()) return;
                    loadFail(e);
                    if (videoList.isEmpty()) showEmptyView("加载失败，请下拉重试");
                });
            }
        });
    }

    /** 空态/失败态文案：搜过和没搜过不是一回事，别让用户以为 TA 一条投稿都没有 */
    private String emptyMessage(int result, String requestKeyword) {
        if (result == -1) return "加载失败，请下拉重试";
        if (!requestKeyword.isEmpty()) return "没有搜到相关稿件~";
        return "TA 还没有投稿过视频~";
    }

    private void continueLoading(int requestPage) {
        //参数名不能叫 page：会遮蔽基类的 page 字段，而 lambda 里要改的是字段（失败回退页码）
        final int generation = loadGeneration;
        final String requestKeyword = keyword;
        final String requestOrder = sortOrder;
        CenterThreadPool.run(() -> {
            try {
                List<VideoCard> list = new ArrayList<>();
                int result = UserInfoApi.getUserVideos(mid, requestPage, requestKeyword, requestOrder, list);
                if (generation != loadGeneration) return;   //关键词/排序已变，这批是过期数据，丢掉
                if (result == -1) {
                    //翻页失败：与基类 loadFail 同口径回退页码，滑一下重试同一页，不会跳过这一页
                    runOnUiThread(() -> {
                        if (generation == loadGeneration) page--;
                        setRefreshing(false);
                    });
                    return;
                }
                if (list.isEmpty()) {
                    //这一页空 = 到底了。不置 bottom 的话滚到底会反复请求同一个空页
                    runOnUiThread(() -> {
                        if (generation == loadGeneration) bottom = true;
                        setRefreshing(false);
                    });
                    return;
                }
                runOnUiThread(() -> {
                    if (generation != loadGeneration || adapter == null || !isAdded()) return;
                    videoList.addAll(list);
                    //UserVideoAdapter 带 header（position-1 才是数据下标）：
                    //通知范围必须 +1 偏移，否则插入范围错位一格，出现重复/缺失 item
                    adapter.notifyItemRangeInserted(videoList.size() - list.size() + 1, list.size());
                    if (result == 1) bottom = true;
                    setRefreshing(false);
                });
            } catch (Exception e) {
                if (generation != loadGeneration) return;
                runOnUiThread(() -> loadFail(e));
            }
        });
    }
}
