package com.RobinNotBad.BiliClient.activity;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.lifecycle.Lifecycle;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.BuildConfig;
import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity;
import com.RobinNotBad.BiliClient.activity.dynamic.DynamicActivity;
import com.RobinNotBad.BiliClient.activity.live.RecommendLiveActivity;
import com.RobinNotBad.BiliClient.activity.message.MessageActivity;
import com.RobinNotBad.BiliClient.activity.search.SearchActivity;
import com.RobinNotBad.BiliClient.activity.settings.SettingMainActivity;
import com.RobinNotBad.BiliClient.activity.settings.UpdateLogActivity;
import com.RobinNotBad.BiliClient.activity.settings.login.LoginActivity;
import com.RobinNotBad.BiliClient.activity.user.MySpaceActivity;
import com.RobinNotBad.BiliClient.activity.video.PopularActivity;
import com.RobinNotBad.BiliClient.activity.video.PreciousActivity;
import com.RobinNotBad.BiliClient.activity.video.HotSearchActivity;
import com.RobinNotBad.BiliClient.activity.video.RankingActivity;
import com.RobinNotBad.BiliClient.activity.video.RecommendActivity;
import com.RobinNotBad.BiliClient.activity.video.TimelineActivity;
import com.RobinNotBad.BiliClient.activity.video.local.LocalListActivity;
import com.RobinNotBad.BiliClient.adapter.MenuGridAdapter;
import com.RobinNotBad.BiliClient.ui.widget.RotaryRecyclerView;
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomGridManager;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.ResumePageUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.RobinNotBad.BiliClient.util.UpdateManager;
import com.RobinNotBad.BiliClient.util.UpdateLog;
import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

//菜单页面
//2023-07-14

public class MenuActivity extends BaseActivity {

    private String from;
    private MaterialButton dynamicButton;
    private MaterialButton messageButton;
    private MenuGridAdapter menuGridAdapter;

    /**
     * 在排序设置和Splash中使用到的，
     * 需要使用排序，故用了LinkedHashMap
     * 请不要让它的顺序被打乱（
     */
    public static final Map<String, Pair<String, Class<? extends InstanceActivity>>> btnNames = new LinkedHashMap<>() {{
        put("recommend", new Pair<>("推荐", RecommendActivity.class));
        put("popular", new Pair<>("热门", PopularActivity.class));
        put("hotsearch", new Pair<>("热搜", HotSearchActivity.class));
        put("precious", new Pair<>("入站必刷", PreciousActivity.class));
        put("ranking", new Pair<>("全站排行榜", RankingActivity.class));
        put("live", new Pair<>("直播", RecommendLiveActivity.class));
        put("timeline", new Pair<>("时间线", TimelineActivity.class));
        put("search", new Pair<>("搜索", SearchActivity.class));
        put("dynamic", new Pair<>("动态", DynamicActivity.class));
        put("myspace", new Pair<>("我的", MySpaceActivity.class));
        put("message", new Pair<>("消息", MessageActivity.class));
        put("local", new Pair<>("缓存", LocalListActivity.class));
        put("settings", new Pair<>("设置", SettingMainActivity.class));
    }};

    long time;

    @SuppressLint({"MissingInflatedId", "InflateParams"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_menu);


        time = System.currentTimeMillis();
        Log.e("debug", "MenuActivity onCreate: " + time);

        Intent intent = getIntent();
        from = intent.getStringExtra("from");
        if (from != null) {
            Log.d("debug-menu", from);
            if (btnNames.containsKey(from))
                setPageName(Objects.requireNonNull(btnNames.get(from)).first);
        }

        findViewById(R.id.top).setOnClickListener(view -> finish());

        List<String> btnList;

        String sortConf = SharedPreferencesUtil.getString(SharedPreferencesUtil.MENU_SORT, "");
        Log.e("debug_sort", sortConf);

        if (!TextUtils.isEmpty(sortConf)) {
            String[] splitName = sortConf.split(";");
            if (splitName.length != btnNames.size()) {
                btnList = getDefaultSortList();
            } else {
                btnList = new ArrayList<>();
                for (String name : splitName) {
                    if (!btnNames.containsKey(name)) {
                        btnList = getDefaultSortList();
                        break;
                    } else {
                        btnList.add(name);
                    }
                }
            }
        } else {
            btnList = getDefaultSortList();
        }

        if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) == 0) {
            btnList.add(0, "login");
            btnList.remove("dynamic");
            btnList.remove("message");
            btnList.remove("myspace");
        }

        if (!SharedPreferencesUtil.getBoolean("menu_popular", true)) btnList.remove("popular");
        if (!SharedPreferencesUtil.getBoolean("menu_hotsearch", true)) btnList.remove("hotsearch");
        if (!SharedPreferencesUtil.getBoolean("menu_precious", false)) btnList.remove("precious");
        if (!SharedPreferencesUtil.getBoolean("menu_ranking", false)) btnList.remove("ranking");
        if (!SharedPreferencesUtil.getBoolean("menu_live", false)) btnList.remove("live");
        if (!SharedPreferencesUtil.getBoolean("menu_timeline", false)) btnList.remove("timeline");

        btnList.add("exit"); //如果你希望用户手动把退出按钮排到第一个（

        LinearLayout layout = findViewById(R.id.menu_layout);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        boolean newUi = SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.NEW_UI_DESIGN, true);
        int dynamicUpdateNum = SharedPreferencesUtil.getInt(SharedPreferencesUtil.DYNAMIC_UPDATE_NUM, 0);
        int messageUpdateNum = SharedPreferencesUtil.getInt(SharedPreferencesUtil.MESSAGE_UPDATE_NUM, 0);

        if (newUi) {
            //新版美学：圆形描边按钮网格（图标 + 标签 + 更新数粉色徽章）
            RotaryRecyclerView menuGrid = findViewById(R.id.menu_grid);
            menuGrid.setVisibility(View.VISIBLE);
            findViewById(R.id.menu_scroll).setVisibility(View.GONE);
            menuGrid.setLayoutManager(new CustomGridManager(this,
                    SharedPreferencesUtil.getBoolean("ui_landscape", false) ? 3 : 2));
            //菜单是静态内容：关掉默认 item 动画避免 notifyDataSetChanged 时整格交叉淡入，
            //setHasFixedSize 减少不必要的整表布局请求，低配手表上更顺
            menuGrid.setItemAnimator(null);
            menuGrid.setHasFixedSize(true);
            menuGridAdapter = new MenuGridAdapter(new MenuGridAdapter.OnMenuClickListener() {
                @Override
                public void onMenuClick(String key) {
                    killAndJump(key);
                }

                @Override
                public boolean onMenuLongClick(String key) {
                    return handleMenuLongClick(key);
                }
            });
            menuGrid.setAdapter(menuGridAdapter);

            List<MenuGridAdapter.MenuEntry> entries = new ArrayList<>();
            for (String btn : btnList) {
                int badge = 0;
                if (btn.equals("dynamic") && dynamicUpdateNum > 0) badge = dynamicUpdateNum;
                else if (btn.equals("message") && messageUpdateNum > 0) badge = messageUpdateNum;
                String label = btn.equals("exit") ? "退出"
                        : btn.equals("login") ? "登录"
                        : Objects.requireNonNull(btnNames.get(btn)).first;
                entries.add(new MenuGridAdapter.MenuEntry(btn, label, menuIconRes(btn), badge));
            }
            menuGridAdapter.setEntries(entries);
        } else {
            for (String btn : btnList) {
                MaterialButton materialButton = new MaterialButton(this);
                switch (btn) {
                    case "exit":
                        materialButton.setText("退出");
                        break;
                    case "login":
                        materialButton.setText("登录");
                        break;
                    case "dynamic":
                        String btnText = Objects.requireNonNull(btnNames.get(btn)).first;
                        dynamicButton = materialButton;
                        if (dynamicUpdateNum > 0) {
                            btnText = btnText + " (" + dynamicUpdateNum + ")";
                        }
                        materialButton.setText(btnText);
                        break;
                    case "message":
                        String messageBtnText = Objects.requireNonNull(btnNames.get(btn)).first;
                        messageButton = materialButton;
                        if (messageUpdateNum > 0) {
                            messageBtnText = messageBtnText + " (" + messageUpdateNum + ")";
                        }
                        materialButton.setText(messageBtnText);
                        break;
                    default:
                        materialButton.setText(Objects.requireNonNull(btnNames.get(btn)).first);
                        break;
                }
                materialButton.setOnClickListener(view -> killAndJump(btn));
                // 长按"推荐"刷新视频
                if (btn.equals("recommend")) {
                    materialButton.setOnLongClickListener(view -> handleMenuLongClick(btn));
                }
                layout.addView(materialButton, params);
            }
        }

        //首次安装/升级到新版本后，首次进入主菜单时自动打开当前版本的更新日志
        String lastVersion = SharedPreferencesUtil.getString(SharedPreferencesUtil.last_version, "");
        if (!BuildConfig.VERSION_NAME.equals(lastVersion)) {
            SharedPreferencesUtil.putString(SharedPreferencesUtil.last_version, BuildConfig.VERSION_NAME);
            int logIndex = UpdateLog.indexOf(BuildConfig.VERSION_NAME);
            if (logIndex >= 0) {
                Intent logIntent = new Intent(this, UpdateLogActivity.class);
                logIntent.putExtra("version_index", logIndex);
                startActivity(logIntent);
            }
        }

        //应用内自动检查更新：静默执行（进程内一次），发现新版本且未被忽略时弹一次性提示；
        //网络不通/接口异常时完全静默，不打扰使用
        UpdateManager.autoCheck(this);

        Log.e("debug", "MenuActivity onCreate in: " + (System.currentTimeMillis() - time));
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.e("debug", "MenuActivity onStart in: " + (System.currentTimeMillis() - time));
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.e("debug", "MenuActivity onResume in: " + (System.currentTimeMillis() - time));
        if (menuGridAdapter != null) {
            //新版菜单：更新数以徽章形式刷新
            menuGridAdapter.updateBadge("dynamic", SharedPreferencesUtil.getInt(SharedPreferencesUtil.DYNAMIC_UPDATE_NUM, 0));
            menuGridAdapter.updateBadge("message", SharedPreferencesUtil.getInt(SharedPreferencesUtil.MESSAGE_UPDATE_NUM, 0));
        }
        if (dynamicButton != null) {
            String btnText = Objects.requireNonNull(btnNames.get("dynamic")).first;
            int updateNum = SharedPreferencesUtil.getInt(SharedPreferencesUtil.DYNAMIC_UPDATE_NUM, 0);
            if (updateNum > 0) {
                btnText = btnText + " (" + updateNum + ")";
            }
            dynamicButton.setText(btnText);
        }
        if (messageButton != null) {
            String messageBtnText = Objects.requireNonNull(btnNames.get("message")).first;
            int messageUpdateNum = SharedPreferencesUtil.getInt(SharedPreferencesUtil.MESSAGE_UPDATE_NUM, 0);
            if (messageUpdateNum > 0) {
                messageBtnText = messageBtnText + " (" + messageUpdateNum + ")";
            }
            messageButton.setText(messageBtnText);
        }
    }

    //长按"推荐"刷新视频（新旧两版菜单共用）
    private boolean handleMenuLongClick(String key) {
        if (!"recommend".equals(key)) return false;
        if (key.equals(from)) {
            //当前已在推荐页，发送刷新广播
            //顶部不一定是推荐页（可能是搜索/动态等其他 InstanceActivity），盲转 ClassCastException；认准实例再刷新
            if (BiliTerminal.getInstanceActivityOnTop() instanceof RecommendActivity) {
                ((RecommendActivity) BiliTerminal.getInstanceActivityOnTop()).refreshRecommend();
            }
            finish();
        } else {
            killAndJump(key);
        }
        return true;
    }

    //新版菜单图标映射：优先复用项目现有矢量图标，缺失的（火焰/趋势/直播）为本次新增
    private int menuIconRes(String key) {
        switch (key) {
            case "recommend":
                return R.drawable.icon_home;
            case "popular":
                return R.drawable.icon_fire;
            case "hotsearch":
                return R.drawable.icon_trending;
            case "precious":
                return R.drawable.icon_bv;
            case "ranking":
                return R.drawable.icon_star;
            case "live":
                return R.drawable.icon_live;
            case "timeline":
                return R.drawable.icon_time;
            case "search":
                return R.drawable.icon_search;
            case "dynamic":
                return R.drawable.icon_followings;
            case "myspace":
            case "login":
                return R.drawable.icon_person;
            case "message":
                return R.drawable.icon_reply;
            case "local":
                return R.drawable.icon_download;
            case "settings":
                return R.drawable.icon_setting;
            case "exit":
                return R.drawable.icon_logout;
            default:
                return R.drawable.icon_menu;
        }
    }

    private void killAndJump(String name) {
        if (btnNames.containsKey(name) && !Objects.equals(name, from)) {
            InstanceActivity instance = BiliTerminal.getInstanceActivityOnTop();
            if (instance != null && instance.getLifecycle().getCurrentState() != Lifecycle.State.DESTROYED)
                instance.finish();

            Intent intent = new Intent();
            intent.setClass(MenuActivity.this, Objects.requireNonNull(btnNames.get(name)).second);
            intent.putExtra("from", name);
            startActivity(intent);
            Glide.get(BiliTerminal.context).clearMemory();
        } else {
            switch (name) {
                case "exit": //退出按钮
                    InstanceActivity instance = BiliTerminal.getInstanceActivityOnTop();
                    if (instance != null && !instance.isDestroyed()) instance.finish();
                    //主动退出属于正常退出任务，onDestroy 来不及执行（进程被杀），
                    //这里必须先清掉冷启动恢复记录，否则下次打开会回到退出前的页面
                    ResumePageUtil.clear();
                    Process.killProcess(Process.myPid());
                    break;
                case "login": //登录按钮
                    Intent intent = new Intent();
                    intent.setClass(MenuActivity.this, LoginActivity.class);
                    startActivity(intent);
                    break;
            }
        }
        finish();
    }

    private List<String> getDefaultSortList() {
        return new ArrayList<>() {{
            add("recommend");
            add("popular");
            add("hotsearch");
            add("precious");
            add("ranking");
            add("live");
            add("timeline");
            add("search");
            add("dynamic");
            add("myspace");
            add("message");
            add("local");
            add("settings");
        }};
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) finish();
        return super.onKeyDown(keyCode, event);
    }
}

