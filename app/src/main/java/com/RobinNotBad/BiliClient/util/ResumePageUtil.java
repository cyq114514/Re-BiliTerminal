package com.RobinNotBad.BiliClient.util;

import android.app.Activity;
import android.content.Intent;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.RobinNotBad.BiliClient.BiliTerminal;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

//冷启动页面栈恢复：
//应用切后台后进程/任务被系统回收（手表和省电激进的 ROM 上几乎必现），再次打开就是全新冷启动，
//Splash 只会进默认首页，用户感知即"切后台再回来像大退重进"。
//这里把用户最后停留的"可恢复页面链"持久化（栈底→栈顶），冷启动时由 SplashActivity 按顺序重建整个返回栈。
//只重建栈顶单个页面的旧实现有个问题：恢复出的页面下没有上级页面，点返回会直接退出应用。
//写入点：可恢复页面（isRestorablePage()，即 InstanceActivity 全体+五个深页面）onCreate 入栈、onDestroy 出栈；
//每条记录带一次性 token，出栈按 token 精确匹配——同类页叠放（视频→用户→视频）且新页 push 先于
//旧页 pop 执行时，旧的"从栈顶向下找同类"会删掉新页条目、留下幽灵页面；
//token 未命中（进程重建/旧版本数据）才退回类名匹配。
//清除点：所有 Activity 销毁（正常退出任务，见 BiliTerminal 生命周期回调）、菜单"退出"按钮、崩溃（ErrorCatch）；
//读取是一次性消费：恢复页若再崩溃，下次启动走默认流程，避免崩溃循环。
public class ResumePageUtil {

    private static final String KEY_RESUME_STACK = "resume_page_stack";
    /**
     * 冷启动恢复链重建页面时携带的标记 extra。
     * 部分页面（搜索）的可见状态不在 Intent 里，只能凭"这次是恢复"来决定是否重放自己的状态，
     * 普通新开页面收到这个 extra 也只会忽略，不影响行为。
     */
    public static final String EXTRA_RESUME_RESTORE = "_resume_restore";
    //栈深上限：深页面不会无限层叠（用户空间→视频详情→再点UP主→…），限制启动期 startActivity 次数
    private static final int MAX_DEPTH = 6;

    private static final AtomicLong TOKEN_COUNTER = new AtomicLong(0);

    private static final class Entry {
        final String uri;
        final long token;

        Entry(String uri, long token) {
            this.uri = uri;
            this.token = token;
        }
    }

    //新格式为 {"uri":..., "token":...}；旧格式是纯 URI 字符串（token=0，走类名匹配兜底）
    private static Entry parseEntry(String raw) {
        try {
            if (raw.startsWith("{")) {
                JSONObject json = new JSONObject(raw);
                return new Entry(json.optString("uri", ""), json.optLong("token", 0));
            }
            return new Entry(raw, 0);
        } catch (Exception e) {
            return null;
        }
    }

    /**可恢复页面 onCreate 时调用；返回本页面的出栈凭证（0 表示入栈失败，出栈走类名兜底）。*/
    public static long push(Activity activity) {
        if (activity.isFinishing()) return 0;
        try {
            Intent intent = activity.getIntent();
            if (intent == null || intent.getComponent() == null) return 0;
            long token = TOKEN_COUNTER.incrementAndGet();
            List<String> stack = load();
            JSONObject entry = new JSONObject();
            entry.put("uri", intent.toUri(Intent.URI_INTENT_SCHEME));
            entry.put("token", token);
            stack.add(entry.toString());
            while (stack.size() > MAX_DEPTH) stack.remove(0);
            persist(stack);
            return token;
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    /**可恢复页面 onDestroy 时调用，token 为 push 返回的凭证。*/
    public static void pop(Activity activity, long token) {
        try {
            List<String> stack = load();
            if (token != 0) {
                for (int i = stack.size() - 1; i >= 0; i--) {
                    Entry entry = parseEntry(stack.get(i));
                    if (entry != null && entry.token == token) {
                        stack.remove(i);
                        persist(stack);
                        return;
                    }
                }
            }
            //token 未命中：退回旧的类名匹配（旧版本遗留数据、入栈失败的页面）
            for (int i = stack.size() - 1; i >= 0; i--) {
                if (isSameClass(stack.get(i), activity)) {
                    stack.remove(i);
                    persist(stack);
                    return;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void clear() {
        try {
            SharedPreferencesUtil.putString(KEY_RESUME_STACK, "");
        } catch (Exception ignored) {
        }
    }

    //取出并清除整个恢复栈（栈底→栈顶）；逐条校验：只接受本应用且当前包内真实存在的组件，
    //记录可能来自旧版本，目标页面升级后可能被改名/删除，不校验的话 startActivity 直接闪退。
    //任何一条解析失败只跳过该条，不废掉整条链
    @Nullable
    public static List<Intent> takeRestoreIntents() {
        List<String> stack = load();
        clear(); //先消费再跳转
        List<Intent> intents = new ArrayList<>();
        for (String raw : stack) {
            try {
                Entry entry = parseEntry(raw);
                if (entry == null) continue;
                Intent intent = Intent.parseUri(entry.uri, Intent.URI_INTENT_SCHEME);
                if (intent.getComponent() == null) continue;
                if (!intent.getComponent().getPackageName().equals(BiliTerminal.context.getPackageName())) continue;
                BiliTerminal.context.getPackageManager().getActivityInfo(intent.getComponent(), 0);
                intent.setFlags(0); //不信任序列化进来的 flag，从 Splash 所在任务正常入栈即可
                //标记"这是恢复出来的页面"：搜索等页面据此重放自己的内存态（结果列表不持久化）
                intent.putExtra(EXTRA_RESUME_RESTORE, true);
                intents.add(intent);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return intents;
    }

    private static boolean isSameClass(String raw, Activity activity) {
        try {
            Entry entry = parseEntry(raw);
            if (entry == null) return false;
            Intent intent = Intent.parseUri(entry.uri, Intent.URI_INTENT_SCHEME);
            if (intent.getComponent() == null) return false;
            return intent.getComponent().getClassName().equals(activity.getComponentName().getClassName());
        } catch (Exception e) {
            return false;
        }
    }

    private static List<String> load() {
        List<String> stack = new ArrayList<>();
        try {
            String raw = SharedPreferencesUtil.getString(KEY_RESUME_STACK, "");
            if (!TextUtils.isEmpty(raw)) {
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); i++) stack.add(array.getString(i));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return stack;
    }

    private static void persist(List<String> stack) {
        JSONArray array = new JSONArray();
        for (String uri : stack) array.put(uri);
        SharedPreferencesUtil.putString(KEY_RESUME_STACK, array.toString());
    }
}
