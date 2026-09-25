package com.RobinNotBad.BiliClient.activity.dynamic;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.activity.base.RefreshMainActivity;
import com.RobinNotBad.BiliClient.adapter.dynamic.DynamicAdapter;
import com.RobinNotBad.BiliClient.adapter.dynamic.DynamicHolder;
import com.RobinNotBad.BiliClient.api.DynamicApi;
import com.RobinNotBad.BiliClient.api.EmoteApi;
import com.RobinNotBad.BiliClient.helper.TutorialHelper;
import com.RobinNotBad.BiliClient.model.Dynamic;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

//动态页面
//2023-09-17

public class DynamicActivity extends RefreshMainActivity {

    private ArrayList<Dynamic> dynamicList;
    private DynamicAdapter dynamicAdapter;
    private List<DynamicApi.UpInfo> recentUpList;
    private long offset = 0;
    private boolean firstRefresh = true;
    private String type = "all";
    private static final Map<String, String> typeNameMap = Map.of(
            "全部", "all",
            "视频投稿", "video",
            "追番", "pgc",
            "专栏", "article"
    );

    /**话题页（TopicDynamicActivity）复用本页框架时开启：隐藏发动态入口与UP列表*/
    public boolean isTopicMode() {
        return false;
    }

    /**列表前的占位头部数量（发动态入口+UP列表），Adapter与插入/删除偏移都依赖它*/
    public int headerCount() {
        return isTopicMode() ? 0 : (showRecentUp() ? 2 : 1);
    }

    /**分页数据拉取钩子，话题页覆写为按话题名拉流*/
    protected long fetchPage(List<Dynamic> out, long offset, String type) throws Exception {
        return DynamicApi.getDynamicList(out, offset, 0, type);
    }
    public final ActivityResultLauncher<Intent> selectTypeLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), (result) -> {
        int code = result.getResultCode();
        Intent data = result.getData();
        if (code == RESULT_OK && data != null && data.getStringExtra("item") != null) {
            String type = typeNameMap.get(data.getStringExtra("item"));
            if (type != null) {
                if (isRefreshing) {
                    MsgUtil.showMsg("还在加载中OvO");
                } else {
                    this.type = type;
                    setRefreshing(true);
                    refreshDynamic();
                }
            }
        }
    });

    public ActivityResultLauncher<Intent> writeDynamicLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), (result) -> {
        int code = result.getResultCode();
        Intent data = result.getData();
        if (code == RESULT_OK && data != null) {
            String text = data.getStringExtra("text");
            String picsJson = data.getStringExtra("pics");
            String optionsJson = data.getStringExtra("options");
            CenterThreadPool.run(() -> {
                try {
                    long dynId;
                    Map<String, Long> atUids = new HashMap<>();
                    Pattern pattern = Pattern.compile("@(\\S+)\\s");
                    Matcher matcher = pattern.matcher(text);
                    while (matcher.find()) {
                        String matchedString = matcher.group(1);
                        long uid;
                        if ((uid = DynamicApi.mentionAtFindUser(matchedString)) != -1) {
                            atUids.put(matchedString, uid);
                        }
                    }
                    JSONArray pics = null;
                    if (picsJson != null && !picsJson.isEmpty()) {
                        pics = new JSONArray(picsJson);
                    }
                    JSONObject option = null;
                    if (optionsJson != null && !optionsJson.isEmpty()) {
                        option = new JSONObject(optionsJson);
                    }
                    Set<String> emoteTexts = EmoteApi.getEmoteTexts(EmoteApi.BUSINESS_DYNAMIC);
                    boolean hasPics = pics != null && pics.length() > 0;
                    if (hasPics) {
                        dynId = DynamicApi.publishImageContent(text, atUids, pics, option, emoteTexts);
                    } else if (atUids.isEmpty() && option == null) {
                        dynId = DynamicApi.publishTextContent(text);
                    } else {
                        dynId = DynamicApi.publishTextContent(text, atUids, option, emoteTexts);
                    }
                    if (!(dynId == -1)) {
                        runOnUiThread(() -> MsgUtil.showMsg("发送成功~"));
                        CenterThreadPool.run(() -> {
                            try {
                                Dynamic dynamic = DynamicApi.getDynamic(dynId);
                                dynamicList.add(0, dynamic);
                                runOnUiThread(() -> {
                                    if (type.equals("all")) {
                                        dynamicAdapter.notifyItemInserted(0);
                                        dynamicAdapter.notifyItemRangeChanged(0, dynamicList.size());
                                    }
                                });
                            } catch (Exception e) {
                                MsgUtil.err(e);
                            }
                        });
                    } else {
                        runOnUiThread(() -> MsgUtil.showMsg("发送失败"));
                    }
                } catch (Exception e) {
                    runOnUiThread(() -> MsgUtil.err(e));
                }
            });
        }
    });

    /**
     * 该方法务必在Activity的onStart生命周期之前调用,否则系统底层会抛异常!!!
     *
     * @param activity
     * @return
     */
    public static ActivityResultLauncher<Intent> getRelayDynamicLauncher(BaseActivity activity) {
        return activity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), (result) -> {
            int code = result.getResultCode();
            Intent data = result.getData();
            if (code == RESULT_OK && data != null) {
                String text = data.getStringExtra("text");
                if (TextUtils.isEmpty(text)) text = "转发动态";
                long dynamicId = data.getLongExtra("dynamicId", -1);
                //转发自动引用：SendDynamicActivity会原样回传启动时的extras
                String authorName = data.getStringExtra("forwardAuthorName");
                long authorMid = data.getLongExtra("forwardAuthorMid", 0);
                String authorContent = data.getStringExtra("forwardContentText");
                String finalText = text;
                CenterThreadPool.run(() -> {
                    try {
                        long dynId;
                        Map<String, Long> atUids = new HashMap<>();
                        Pattern pattern = Pattern.compile("@(\\S+)\\s");
                        Matcher matcher = pattern.matcher(finalText);
                        while (matcher.find()) {
                            String matchedString = matcher.group(1);
                            long uid;
                            if ((uid = DynamicApi.mentionAtFindUser(matchedString)) != -1) {
                                atUids.put(matchedString, uid);
                            }
                        }
                        Set<String> emoteTexts = EmoteApi.getEmoteTexts(EmoteApi.BUSINESS_DYNAMIC);
                        dynId = DynamicApi.relayDynamic(finalText, (atUids.isEmpty() ? null : atUids), dynamicId,
                                authorName, authorMid, authorContent, emoteTexts);
                        if (!(dynId == -1)) {
                            activity.runOnUiThread(() -> MsgUtil.showMsg("转发成功~"));
                        } else {
                            activity.runOnUiThread(() -> MsgUtil.showMsg("转发失败"));
                        }
                    } catch (Exception e) {
                        activity.runOnUiThread(() -> MsgUtil.err(e));
                    }
                });
            }
        });
    }

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setMenuClick();
        Log.e("debug", "进入动态页");

        setOnRefreshListener(this::refreshDynamic);
        setOnLoadMoreListener(page -> addDynamic(type));

        if (isTopicMode()) {
            String topicName = getIntent().getStringExtra("name");
            setPageName(topicName == null ? "话题" : "#" + topicName + "#");
            refreshDynamic();
            return;
        }

        setPageName("动态");

        TutorialHelper.showTutorialList(this, R.array.tutorial_dynamic, 6);

        loadRecentUpList();
        refreshDynamic();
    }

    @SuppressLint("NotifyDataSetChanged")
    private void refreshDynamic() {
        Log.e("debug", "刷新");
        if (firstRefresh) {
            dynamicList = new ArrayList<>();
        } else {
            offset = 0;
            bottom = false;
            dynamicList.clear();
            dynamicAdapter.notifyDataSetChanged();
        }

        loadRecentUpList();
        addDynamic(type, true);
    }

    private void addDynamic(String type) {
        addDynamic(type, false);
    }

    @SuppressLint("NotifyDataSetChanged")
    private void addDynamic(String type, boolean refresh) {
        Log.e("debug", "加载下一页");
        CenterThreadPool.run(() -> {
            try {
                List<Dynamic> list = new ArrayList<>();
                offset = fetchPage(list, offset, type);
                bottom = (offset == -1);
                setRefreshing(false);

                runOnUiThread(() -> {
                    dynamicList.addAll(list);
                    if (firstRefresh) {
                        firstRefresh = false;
                        dynamicAdapter = new DynamicAdapter(this, dynamicList, recyclerView, recentUpList);
                        setAdapter(dynamicAdapter);
                    } else {
                        if (refresh) {
                            dynamicAdapter.notifyDataSetChanged();
                        } else {
                            dynamicAdapter.notifyItemRangeInserted(dynamicList.size() - list.size() + headerCount(), list.size());
                        }
                    }
                    if (refresh) {
                        SharedPreferencesUtil.putInt(SharedPreferencesUtil.DYNAMIC_UPDATE_NUM, 0);
                    }
                });

            } catch (Exception e) {
                loadFail(e);
            }
        });
    }

    private void loadRecentUpList() {
        CenterThreadPool.run(() -> {
            try {
                recentUpList = DynamicApi.getRecentUpList();
                runOnUiThread(() -> {
                    if (dynamicAdapter != null) {
                        dynamicAdapter.recentUpList = recentUpList;
                        boolean shouldShow = showRecentUp();
                        int currentItemCount = dynamicAdapter.getItemCount();
                        int newItemCount = (dynamicList != null ? dynamicList.size() + 1 : 1) + (shouldShow ? 1 : 0);
                        if (currentItemCount != newItemCount) {
                            if (shouldShow) {
                                dynamicAdapter.notifyItemInserted(1);
                            } else {
                                dynamicAdapter.notifyItemRemoved(1);
                            }
                        } else if (shouldShow) {
                            dynamicAdapter.notifyItemChanged(1);
                        }
                    }
                });
            } catch (Exception e) {
                recentUpList = null;
            }
        });
    }

    private boolean showRecentUp() {
        return SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.RECENT_UP_DISPLAY_ENABLE, true)
                && recentUpList != null && !recentUpList.isEmpty();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        //动态操作菜单（置顶/可见范围/编辑）的选择结果
        DynamicHolder.onDynamicOpResult(requestCode, resultCode, data, this);
        if (requestCode == DynamicHolder.GO_TO_INFO_REQUEST && resultCode == RESULT_OK) {
            try {
                if (data != null && !isRefreshing) {
                    int adapterPosition = data.getIntExtra("position", 0);
                    int realPosition = adapterPosition - headerCount();
                    if (realPosition >= 0 && realPosition < dynamicList.size()) {
                        DynamicHolder.removeDynamicFromList(dynamicList, realPosition, dynamicAdapter, headerCount());
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }
}