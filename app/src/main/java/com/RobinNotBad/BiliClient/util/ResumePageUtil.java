package com.RobinNotBad.BiliClient.util;

import android.app.Activity;
import android.content.Intent;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.RobinNotBad.BiliClient.BiliTerminal;

//冷启动页面恢复：
//应用切后台后进程/任务被系统回收（手表和省电激进的 ROM 上几乎必现），再次打开就是全新冷启动，
//Splash 只会进默认首页，用户感知即"切后台再回来像大退重进"。
//这里把用户最后停留的可恢复页面持久化，冷启动时由 SplashActivity 取出并直接跳过去。
//写入点在 BaseActivity.onResume（仅 isRestorablePage() 的页面）；
//清除点：所有 Activity 销毁（正常退出任务，见 BiliTerminal 生命周期回调）、菜单"退出"按钮、崩溃（ErrorCatch）；
//读取是一次性消费：恢复页若再崩溃，下次启动走默认流程，避免崩溃循环。
public class ResumePageUtil {

    private static final String KEY_RESUME_INTENT = "resume_page_intent";

    public static void save(Activity activity) {
        if (activity.isFinishing()) return;
        try {
            Intent intent = activity.getIntent();
            if (intent == null || intent.getComponent() == null) return;
            SharedPreferencesUtil.putString(KEY_RESUME_INTENT, intent.toUri(Intent.URI_INTENT_SCHEME));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void clear() {
        try {
            SharedPreferencesUtil.putString(KEY_RESUME_INTENT, "");
        } catch (Exception ignored) {
        }
    }

    //取出并清除恢复点；只接受本应用的组件，任何解析失败都返回 null 走默认流程
    @Nullable
    public static Intent takeRestoreIntent() {
        String uri = SharedPreferencesUtil.getString(KEY_RESUME_INTENT, "");
        if (TextUtils.isEmpty(uri)) return null;
        clear(); //先消费再跳转
        try {
            Intent intent = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
            if (intent.getComponent() == null) return null;
            if (!intent.getComponent().getPackageName().equals(BiliTerminal.context.getPackageName())) return null;
            //记录可能来自旧版本：目标页面在升级后可能被改名/删除，
            //不校验的话 startActivity 直接 ActivityNotFoundException 闪退
            BiliTerminal.context.getPackageManager().getActivityInfo(intent.getComponent(), 0);
            intent.setFlags(0); //不信任序列化进来的 flag，从 Splash 所在任务正常入栈即可
            return intent;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
