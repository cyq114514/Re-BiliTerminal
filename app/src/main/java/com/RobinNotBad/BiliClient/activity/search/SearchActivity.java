package com.RobinNotBad.BiliClient.activity.search;

import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.ViewPager;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity;
import com.RobinNotBad.BiliClient.adapter.SearchHistoryAdapter;
import com.RobinNotBad.BiliClient.adapter.SearchSuggestionsAdapter;
import com.RobinNotBad.BiliClient.adapter.viewpager.ViewPagerFragmentAdapter;
import com.RobinNotBad.BiliClient.api.SearchApi;
import com.RobinNotBad.BiliClient.helper.TutorialHelper;
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager;
import com.RobinNotBad.BiliClient.util.JsonUtil;
import com.RobinNotBad.BiliClient.util.LinkUrlUtil;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.RobinNotBad.BiliClient.util.ToolsUtil;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class SearchActivity extends InstanceActivity {
    private String lastKeyword = "≠~`";
    private RecyclerView historyRecyclerview;
    private RecyclerView suggestionsRecyclerview;
    SearchHistoryAdapter searchHistoryAdapter;
    SearchSuggestionsAdapter searchSuggestionsAdapter;
    ViewPager viewPager;
    EditText keywordInput;
    private ConstraintLayout searchBar;
    private boolean searchBarVisible = true;
    private boolean refreshing = false;
    private long animate_last;
    Handler handler;
    ArrayList<String> searchHistory;
    ArrayList<String> searchSuggestions;
    private Runnable suggestionRunnable;
    private boolean suggestionsEnabled;
    //搜索建议请求代际：每次输入自增，响应到达时若代际已过期则丢弃（防慢响应覆盖新响应的乱序竞态）
    private int suggestionGeneration;
    private String defaultSearchContent;
    private boolean defaultSearchContentEnabled;

    boolean tutorial_show;
    String classname;
    ViewPagerFragmentAdapter vpfAdapter;

    String[] specialList = {"心理疾病", "自杀", "自尽", "自残", "抑郁", "双相", "安眠药"};

    @SuppressLint({"MissingInflatedId", "NotifyDataSetChanged", "InflateParams"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        classname = getClass().getSimpleName();
        tutorial_show = SharedPreferencesUtil.getBoolean("tutorial_pager_" + classname, true);

        asyncInflate(R.layout.activity_search, (layoutView, resId) -> {
            Log.e("debug", "进入搜索页");

            TutorialHelper.showTutorialList(this, R.array.tutorial_search, 4);

            handler = new Handler();

            suggestionsEnabled = SharedPreferencesUtil.getBoolean("search_suggestions_enable", true);
            defaultSearchContentEnabled = SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.SEARCH_DEFAULT_CONTENT_ENABLE, false);
            
            if (defaultSearchContentEnabled) {
                new Thread(() -> {
                    try {
                        defaultSearchContent = SearchApi.getDefaultSearchContent();
                        if (defaultSearchContent != null && !defaultSearchContent.isEmpty()) {
                            runOnUiThread(() -> keywordInput.setHint(defaultSearchContent));
                        }
                    } catch (Exception e) {
                        Log.e("SearchActivity", "获取默认搜索内容失败", e);
                    }
                }).start();
            }

            viewPager = findViewById(R.id.viewPager);

            View searchBtn = findViewById(R.id.search);
            keywordInput = findViewById(R.id.keywordInput);
            searchBar = findViewById(R.id.searchbar);
            historyRecyclerview = findViewById(R.id.history_recyclerview);
            suggestionsRecyclerview = findViewById(R.id.suggestions_recyclerview);
            viewPager.setOffscreenPageLimit(4);

            keywordInput.setOnFocusChangeListener((view, b) -> {
                if (b) {
                    // 获得焦点时，根据输入内容决定显示历史还是建议
                    String keyword = keywordInput.getText().toString();
                    if (keyword.isEmpty() || !suggestionsEnabled || searchSuggestions.isEmpty()) {
                        historyRecyclerview.setVisibility(View.VISIBLE);
                        suggestionsRecyclerview.setVisibility(View.GONE);
                    } else {
                        historyRecyclerview.setVisibility(View.GONE);
                        suggestionsRecyclerview.setVisibility(View.VISIBLE);
                    }
                } else {
                    // 失去焦点时隐藏所有列表
                    historyRecyclerview.setVisibility(View.GONE);
                    suggestionsRecyclerview.setVisibility(View.GONE);
                }
            });
            historyRecyclerview.setVisibility(View.VISIBLE);
            suggestionsRecyclerview.setVisibility(View.GONE);
            List<Fragment> fragmentList = new ArrayList<>();
            fragmentList.add(SearchVideoFragment.newInstance());
            fragmentList.add(SearchArticleFragment.newInstance());
            fragmentList.add(SearchUserFragment.newInstance());
            fragmentList.add(SearchLiveFragment.newInstance());
            vpfAdapter = new ViewPagerFragmentAdapter(getSupportFragmentManager(), fragmentList);
            viewPager.setAdapter(vpfAdapter);

            viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
                @Override
                public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
                    if (position != 0) {
                        onScrolled(256); // 让搜索框隐藏
                        if (tutorial_show) {
                            tutorial_show = false;
                            findViewById(R.id.text_tutorial_pager).setVisibility(View.GONE);
                            SharedPreferencesUtil.putBoolean("tutorial_pager_" + classname, false);
                        }
                    }
                }

                @Override
                public void onPageSelected(int position) {
                    SearchFragment fragment = (SearchFragment) vpfAdapter.getFragment(position);
                    if (fragment != null) {
                        fragment.refresh();
                    }
                }

                @Override
                public void onPageScrollStateChanged(int state) {
                }
            });

            searchBtn.setOnClickListener(view -> searchKeyword(keywordInput.getText().toString()));
            searchBtn.setOnLongClickListener(this::jumpToTargetId);
            //搜索执行后焦点会被结果列表抢占（requestFragmentFocus），部分ROM（一加13/ColorOS16实测）
            //上第一次点击输入框只恢复焦点、不拉起输入法，这里在点击时主动补一次
            keywordInput.setOnClickListener(v -> v.post(() -> {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
            }));
            keywordInput.setOnEditorActionListener((textView, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE || event != null
                        && KeyEvent.KEYCODE_ENTER == event.getKeyCode() && KeyEvent.ACTION_DOWN == event.getAction()) {
                    searchKeyword(keywordInput.getText().toString());
                }
                return false;
            });

            try {
                searchHistory = JsonUtil.jsonToArrayList(
                        new JSONArray(SharedPreferencesUtil.getString(SharedPreferencesUtil.search_history, "[]")),
                        false);
            } catch (JSONException e) {
                runOnUiThread(() -> MsgUtil.err(e));
                searchHistory = new ArrayList<>();
            }
            searchHistoryAdapter = new SearchHistoryAdapter(this, searchHistory);
            searchHistoryAdapter.setOnClickListener(position -> keywordInput.setText(searchHistory.get(position)));
            searchHistoryAdapter.setOnLongClickListener(position -> {
                MsgUtil.showMsg("删除成功");
                searchHistory.remove(position);
                searchHistoryAdapter.notifyItemRemoved(position);
                searchHistoryAdapter.notifyItemRangeChanged(position, searchHistory.size() - position);
                SharedPreferencesUtil.putString(SharedPreferencesUtil.search_history,
                        new JSONArray(searchHistory).toString());
            });
            historyRecyclerview.setLayoutManager(new CustomLinearManager(this));
            historyRecyclerview.setAdapter(searchHistoryAdapter);
            if (searchHistory.size() > 4) {
                historyRecyclerview.setFocusable(true);
                historyRecyclerview.setFocusableInTouchMode(true);
                historyRecyclerview.requestFocus();
            }

            // 初始化搜索建议
            searchSuggestions = new ArrayList<>();
            searchSuggestionsAdapter = new SearchSuggestionsAdapter(this, searchSuggestions);
            searchSuggestionsAdapter.setOnClickListener(position -> {
                String suggestion = searchSuggestions.get(position);
                //先清掉输入法未上屏的组词缓冲，防止其迟后commit再次触发建议请求导致卡片回弹
                keywordInput.clearComposingText();
                keywordInput.setText(suggestion);
                keywordInput.setSelection(suggestion.length());
                searchKeyword(suggestion);
            });
            suggestionsRecyclerview.setLayoutManager(new CustomLinearManager(this));
            suggestionsRecyclerview.setAdapter(searchSuggestionsAdapter);

            // 添加输入监听器获取搜索建议
            if (suggestionsEnabled) {
                keywordInput.addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                    }

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                    }

                    @Override
                    public void afterTextChanged(Editable s) {
                        //搜索执行期间忽略一切文本变化（包括部分ROM输入法收起/确认时的延迟commit，
                        //如一加13/ColorOS16），否则建议卡片会在结果加载完成后回弹
                        if (refreshing) return;
                        String keyword = s.toString();

                        // 移除之前的请求
                        if (suggestionRunnable != null) {
                            handler.removeCallbacks(suggestionRunnable);
                        }

                        if (keyword.isEmpty()) {
                            // 输入为空时显示历史记录
                            runOnUiThread(() -> {
                                if (keywordInput.hasFocus()) {
                                    historyRecyclerview.setVisibility(View.VISIBLE);
                                    suggestionsRecyclerview.setVisibility(View.GONE);
                                }
                            });
                        } else {
                            suggestionGeneration++;
                            final int gen = suggestionGeneration;
                            suggestionRunnable = () -> new Thread(() -> {
                                try {
                                    ArrayList<String> suggestions = SearchApi.getSearchSuggestions(keyword);
                                    runOnUiThread(() -> {
                                        //不能用"输入框此刻是否持有焦点"作为显示条件：部分ROM输入法
                                        //在组词/候选期间会让输入框短暂失焦（一加13/ColorOS16实测），
                                        //旧实现会因此把结果整包丢弃，表现为搜索建议永远不出现。
                                        //代际校验负责丢弃过期响应（防慢响应覆盖新响应的乱序竞态）
                                        if (gen != suggestionGeneration || refreshing || isFinishing() || isDestroyed())
                                            return;
                                        searchSuggestions.clear();
                                        searchSuggestions.addAll(suggestions);
                                        searchSuggestionsAdapter.notifyDataSetChanged();

                                        if (!suggestions.isEmpty()) {
                                            historyRecyclerview.setVisibility(View.GONE);
                                            suggestionsRecyclerview.setVisibility(View.VISIBLE);
                                        } else {
                                            historyRecyclerview.setVisibility(View.VISIBLE);
                                            suggestionsRecyclerview.setVisibility(View.GONE);
                                        }
                                    });
                                } catch (Exception e) {
                                    Log.e("SearchActivity", "获取搜索建议失败", e);
                                }
                            }).start();
                            handler.postDelayed(suggestionRunnable, 300);
                        }
                    }
                });
            }

            if (getIntent().getStringExtra("keyword") != null) {
                findViewById(R.id.top).setOnClickListener(view1 -> finish());
                keywordInput.setText(getIntent().getStringExtra("keyword"));
                MsgUtil.showMsg("可点击标题栏返回详情页");
            }
        });
    }

    public boolean jumpToTargetId(View view) {
        String text = keywordInput.getText().toString();
        LinkUrlUtil.handleId(this, text);
        return true;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void searchKeyword(String str) {
        if (str.contains("Robin") || str.contains("robin")) {
            if (str.contains("撅")) {
                MsgUtil.showText("特殊彩蛋", getString(R.string.egg_special));
                return;
            }
            if (str.contains("纳西妲")) {
                MsgUtil.showText("特殊彩蛋", getString(R.string.egg_robin_nahida));
                return;
            }
        }
        for (String s : specialList) {
            if (str.contains(s)) {
                MsgUtil.showText("特殊彩蛋", getString(R.string.egg_warmwords_warmworld));
                break;
            }
        }

        if (!refreshing) {
            InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            View curFocus;
            if ((curFocus = getCurrentFocus()) != null) {
                manager.hideSoftInputFromWindow(curFocus.getWindowToken(), InputMethodManager.HIDE_NOT_ALWAYS);
            }

            //点击联想词/历史词会经 setText 触发一次新的建议请求；搜索执行时作废它并收起建议面板，
            //否则该请求返回后会把建议卡片重新盖到搜索结果上
            suggestionGeneration++;
            if (suggestionRunnable != null) handler.removeCallbacks(suggestionRunnable);
            runOnUiThread(() -> suggestionsRecyclerview.setVisibility(View.GONE));

            if (str.isEmpty()) {
                if (defaultSearchContentEnabled && defaultSearchContent != null && !defaultSearchContent.isEmpty()) {
                    str = defaultSearchContent;
                } else {
                    runOnUiThread(() -> MsgUtil.showMsg("还没输入内容喵~"));
                    return;
                }
            }
            
            if (Objects.equals(lastKeyword, str)) {
                runOnUiThread(() -> {
                    keywordInput.clearFocus();
                    historyRecyclerview.setVisibility(View.GONE);
                });
            } else {
                refreshing = true;
                lastKeyword = str;

                // 搜索记录
                runOnUiThread(() -> {
                    historyRecyclerview.setVisibility(View.GONE);
                    keywordInput.clearFocus();
                });

                if (!searchHistory.contains(str)) {
                    try {
                        searchHistory.add(0, str);
                        SharedPreferencesUtil.putString(SharedPreferencesUtil.search_history,
                                new JSONArray(searchHistory).toString());
                        runOnUiThread(() -> {
                            searchHistoryAdapter.notifyItemInserted(0);
                            searchHistoryAdapter.notifyItemRangeChanged(0, searchHistory.size());
                            historyRecyclerview.scrollToPosition(0);
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> MsgUtil.err(e));
                    }
                } else {
                    try {
                        int pos = searchHistory.indexOf(str);
                        searchHistory.remove(str);
                        searchHistory.add(0, str);
                        SharedPreferencesUtil.putString(SharedPreferencesUtil.search_history,
                                new JSONArray(searchHistory).toString());
                        runOnUiThread(() -> {
                            searchHistoryAdapter.notifyItemMoved(pos, 0);
                            searchHistoryAdapter.notifyItemRangeChanged(0, searchHistory.size());
                            historyRecyclerview.scrollToPosition(0);
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> MsgUtil.err(e));
                    }
                }

                try {
                    for (int i = 0; i < 4; i++) {
                        SearchFragment fragment = (SearchFragment) vpfAdapter.getFragment(i);
                        if (fragment != null)
                            fragment.update(str);
                    }
                    SearchFragment fragmentCurr = (SearchFragment) vpfAdapter.getFragment(viewPager.getCurrentItem());
                    if (fragmentCurr != null) {
                        fragmentCurr.refresh();
                        requestFragmentFocus();
                    }
                } catch (Exception e) {
                    report(e);
                }
                refreshing = false;

                if (tutorial_show) {
                    runOnUiThread(() -> {
                        TextView textView = findViewById(R.id.text_tutorial_pager);
                        textView.setVisibility(View.VISIBLE);
                        textView.setText(getString(R.string.tutorial_pager, 4));
                    });
                }
            }
        }
    }

    public void onScrolled(int dy) {
        float height = searchBar.getHeight() + ToolsUtil.dp2px(2f);

        if (System.currentTimeMillis() - animate_last > 200) {
            if (dy > 0 && searchBarVisible) {
                animate_last = System.currentTimeMillis();
                this.searchBarVisible = false;
                @SuppressLint("ObjectAnimatorBinding")
                ObjectAnimator animator = ObjectAnimator.ofFloat(searchBar, "translationY", 0, -height);
                animator.start();
                handler.postDelayed(() -> searchBar.setVisibility(View.GONE), 200);
            }
            if (dy < -1 && !searchBarVisible) {
                animate_last = System.currentTimeMillis();
                this.searchBarVisible = true;
                searchBar.setVisibility(View.VISIBLE);
                @SuppressLint("ObjectAnimatorBinding")
                ObjectAnimator animator = ObjectAnimator.ofFloat(searchBar, "translationY", -height, 0);
                animator.start();
            }
        }

        requestFragmentFocus();
    }

    private void requestFragmentFocus(){
        //输入框持有焦点（用户正在输入或刚点击输入框）时不得把焦点抢给结果列表：
        //键盘弹出引发的布局变化会让列表产生滚动回调走到这里，抢占焦点会立刻把刚拉起的键盘顶掉
        //（一加13/ColorOS16实测：点击输入框后键盘短暂弹出随即被收回）
        if (keywordInput.hasFocus()) return;
        SearchFragment fragmentCurr = (SearchFragment) vpfAdapter.getFragment(viewPager.getCurrentItem());
        if (fragmentCurr != null) {
            fragmentCurr.refresh();
            if (fragmentCurr.getView() != null) {
                View recyclerView = fragmentCurr.getView().findViewById(R.id.recyclerView);
                recyclerView.setFocusable(true);
                recyclerView.setFocusableInTouchMode(true);
                recyclerView.requestFocus();
            }
        }
    }
}