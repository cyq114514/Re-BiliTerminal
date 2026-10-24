package com.RobinNotBad.BiliClient.activity.base;

import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.MenuActivity;

public class InstanceActivity extends BaseActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        BiliTerminal.setInstance(this);
        super.onCreate(savedInstanceState);
    }

    //浏览类页面（菜单入口的各个列表页）统一可恢复
    @Override
    public boolean isRestorablePage() {
        return true;
    }

    public void setMenuClick() {
        findViewById(R.id.top).setOnClickListener(view -> menuClick.run());
    }

    public Runnable menuClick = (() -> {
        Intent intent = new Intent();
        intent.setClass(this, MenuActivity.class);
        if (getIntent().hasExtra("from"))
            intent.putExtra("from", getIntent().getStringExtra("from"));
        startActivity(intent);
        //「关闭界面过渡动画」打开时不要再强行加转场（BaseActivity.startActivity 已经置成 0,0）
        if (!com.RobinNotBad.BiliClient.util.SharedPreferencesUtil.getBoolean(
                com.RobinNotBad.BiliClient.util.SharedPreferencesUtil.DISABLE_ANIMATION, false)) {
            overridePendingTransition(R.anim.anim_activity_in_down, 0);
        }
    });

    /**
     * 打开菜单，但<b>不</b>把本页的 from 带过去。
     *
     * <p>给"打开菜单后会把自身 finish 掉"的页面用（当前是短视频页）：这类页面已经不在返回栈里，
     * 菜单若还带着 from，点同一个入口会命中 {@code MenuActivity.killAndJump} 的
     * "回到旧页"分支——只关菜单、不启动页面，任务栈被清空，用户看到的就是
     * "点短视频按钮应用直接退出"。
     */
    public void openMenuWithoutFrom() {
        Intent intent = new Intent(this, MenuActivity.class);
        startActivity(intent);
        if (!com.RobinNotBad.BiliClient.util.SharedPreferencesUtil.getBoolean(
                com.RobinNotBad.BiliClient.util.SharedPreferencesUtil.DISABLE_ANIMATION, false)) {
            overridePendingTransition(R.anim.anim_activity_in_down, 0);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) menuClick.run();
        return super.onKeyDown(keyCode, event);
    }
}
