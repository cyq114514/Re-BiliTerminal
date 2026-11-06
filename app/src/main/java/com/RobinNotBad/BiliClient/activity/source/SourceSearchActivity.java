package com.RobinNotBad.BiliClient.activity.source;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity;
import com.RobinNotBad.BiliClient.adapter.BgmGridAdapter;
import com.RobinNotBad.BiliClient.api.BgmApi;
import com.RobinNotBad.BiliClient.model.bgm.BgmSubject;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 第三方源入口的搜索页：**先用 Bangumi 展示结果卡片，点卡片再去各站点嗅探片源**。
 *
 * <p>为什么不再直接列站点结果：站点搜索没有封面、标题脏（「第X季 1080P」「高清版」），
 * 展示出来既分不清是不是同一部，也没法判断匹配。Bangumi 有封面 + 规范标题 + 别名，
 * 作为「元数据层」展示，站点只是「资源层」——这与 RE 的做法一致。
 *
 * <p>流程：关键词 → Bangumi 搜索（20 条/页）→ 卡片 → {@link SourceMatchActivity}（跨源选条目）
 * → {@link SourceDetailActivity}（线路/剧集）→ 解析播放。
 */
public class SourceSearchActivity extends InstanceActivity {

    public static final String EXTRA_KEYWORD = "keyword";

    private EditText keywordInput;
    private TextView statusText;
    private TextView emptyTip;
    private RecyclerView recycler;
    private View listContainer;

    private BgmGridAdapter adapter;
    private final List<BgmSubject> results = new ArrayList<>();
    private int generation = 0;
    private int cellWidthPx = 240;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_source_search);
        //子页面：点标题栏返回上一级，而不是像顶级页那样弹菜单
        setTopbarExit();
        setPageName(getString(R.string.source_search_title));

        keywordInput = findViewById(R.id.keywordInput);
        statusText = findViewById(R.id.statusText);
        emptyTip = findViewById(R.id.emptyTip);
        recycler = findViewById(R.id.recycler);
        listContainer = findViewById(R.id.listContainer);

        listContainer.setFocusableInTouchMode(true);
        listContainer.requestFocus();

        int span = spanCount();
        cellWidthPx = cellWidthPx(span);
        recycler.setLayoutManager(new GridLayoutManager(this, span));
        adapter = new BgmGridAdapter(this, results, span, cellWidthPx);
        adapter.setOnItemClickListener(this::openMatch);
        recycler.setAdapter(adapter);

        findViewById(R.id.btnManage).setOnClickListener(view ->
                startActivity(new Intent(this, SourceListActivity.class)));

        //搜索按钮已去掉：点输入框右端的放大镜就搜（图标是 drawableEnd，本身不可点，
        //所以在输入框上判断触点是否落在图标区域内）
        keywordInput.setOnTouchListener((view, event) -> {
            if (event.getAction() != android.view.MotionEvent.ACTION_UP) return false;
            android.graphics.drawable.Drawable icon = endIcon();
            if (icon == null) return false;
            int iconWidth = icon.getBounds().width();
            if (iconWidth <= 0) return false;
            int rightEdge = keywordInput.getWidth() - keywordInput.getPaddingEnd();
            if (event.getX() >= rightEdge - iconWidth) {
                doSearch();
                return true;
            }
            return false;
        });
        keywordInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch();
                return true;
            }
            return false;
        });

        String keyword = getIntent().getStringExtra(EXTRA_KEYWORD);
        if (keyword != null && !keyword.trim().isEmpty()) {
            keywordInput.setText(keyword.trim());
            keywordInput.setSelection(keywordInput.getText().length());
            doSearch();
        } else {
            statusText.setText(R.string.source_search_tip);
        }
    }

    private int spanCount() {
        int widthDp = getResources().getConfiguration().screenWidthDp;
        int span = (int) Math.floor(widthDp / 120f);
        return Math.max(2, Math.min(6, span));
    }

    private int cellWidthPx(int span) {
        int widthPx = getResources().getDisplayMetrics().widthPixels;
        return Math.max(120, (widthPx - dp(16)) / Math.max(1, span));
    }

    private void doSearch() {
        final String keyword = keywordInput.getText() == null
                ? "" : keywordInput.getText().toString().trim();
        if (keyword.isEmpty()) {
            MsgUtil.showMsg("请输入关键词");
            return;
        }
        hideKeyboard();
        final int myGeneration = ++generation;
        results.clear();
        adapter.notifyDataSetChanged();
        emptyTip.setVisibility(View.GONE);
        recycler.setVisibility(View.VISIBLE);
        statusText.setText(R.string.source_search_bgm_running);

        CenterThreadPool.run(() -> {
            List<BgmSubject> list = null;
            Exception error = null;
            try {
                list = BgmApi.searchSubjects(keyword, 0, true);
            } catch (Exception e) {
                error = e;
            }
            final List<BgmSubject> loaded = list;
            final Exception failure = error;
            runOnUiThread(() -> {
                if (isFinishing() || myGeneration != generation) return;
                if (loaded == null) {
                    showEmpty("番剧库搜索失败：" + (failure == null ? "未知错误" : failure.getMessage()));
                    return;
                }
                if (loaded.isEmpty()) {
                    showEmpty(getString(R.string.source_search_none));
                    return;
                }
                results.addAll(loaded);
                adapter.notifyDataSetChanged();
                statusText.setText(getString(R.string.source_search_bgm_count, results.size())
                        + (results.size() >= 20 ? getString(R.string.source_search_bgm_more) : ""));
            });
        });
    }

    private void showEmpty(String message) {
        emptyTip.setText(message);
        emptyTip.setVisibility(View.VISIBLE);
        recycler.setVisibility(View.GONE);
        statusText.setText("");
    }

    private void openMatch(BgmSubject subject) {
        Intent intent = new Intent(this, SourceMatchActivity.class);
        intent.putExtra(SourceMatchActivity.EXTRA_BGM_ID, subject.id);
        intent.putExtra(SourceMatchActivity.EXTRA_TITLE, subject.title());
        intent.putExtra(SourceMatchActivity.EXTRA_ORIGINAL, subject.originalTitle());
        //走代理并按小图宽度取：直连 lain.bgm.tv 在移动网络下拿不到图
        intent.putExtra(SourceMatchActivity.EXTRA_COVER,
                BgmApi.imageFor(subject.coverCommon == null || subject.coverCommon.isEmpty()
                        ? subject.coverLarge : subject.coverCommon, 200));
        startActivity(intent);
    }

    /** 输入框右端（drawableEnd）的图标；取不到返回 null */
    private android.graphics.drawable.Drawable endIcon() {
        android.graphics.drawable.Drawable[] relative = keywordInput.getCompoundDrawablesRelative();
        if (relative != null && relative.length > 2 && relative[2] != null) return relative[2];
        android.graphics.drawable.Drawable[] absolute = keywordInput.getCompoundDrawables();
        return absolute != null && absolute.length > 2 ? absolute[2] : null;
    }

    private void hideKeyboard() {
        try {
            InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (manager != null) manager.hideSoftInputFromWindow(keywordInput.getWindowToken(), 0);
            keywordInput.clearFocus();
            listContainer.requestFocus();
        } catch (Throwable ignored) {
            //键盘收不起来不影响搜索
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
