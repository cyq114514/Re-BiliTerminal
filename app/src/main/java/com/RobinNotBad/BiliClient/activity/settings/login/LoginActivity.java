package com.RobinNotBad.BiliClient.activity.settings.login;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import androidx.fragment.app.Fragment;
import androidx.viewpager.widget.ViewPager;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.adapter.viewpager.ViewPagerFragmentAdapter;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import java.util.ArrayList;
import java.util.List;

//登录页面，参考了腕上哔哩和WearBili的代码

public class LoginActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_simple_viewpager);
        Log.e("debug", "进入登录页面");
        setPageName("登录");

        boolean from_setup = getIntent().getBooleanExtra("from_setup", false);

        /*
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            startActivity(new Intent(this, SpecialLoginActivity.class).putExtra("from_setup",from_setup));
            finish();
        }
         */

        ViewPager viewPager = findViewById(R.id.viewPager);
        List<Fragment> fragmentList = new ArrayList<>();
        fragmentList.add(QRLoginFragment.newInstance(from_setup));       //第0页：扫码登录
        fragmentList.add(PasswordLoginFragment.newInstance());           //第1页：密码登录
        fragmentList.add(SMSLoginFragment.newInstance());                //第2页：短信登录

        viewPager.setOffscreenPageLimit(fragmentList.size());
        ViewPagerFragmentAdapter vpfAdapter = new ViewPagerFragmentAdapter(getSupportFragmentManager(), fragmentList);
        viewPager.setAdapter(vpfAdapter);

        //三种登录方式的页点指示器（适配审计 20261107 P2-6）：此前三种方式只靠横滑切换 +
        //首次一条长 toast 提示，1.4 寸表上发现性差。顶栏下加 3 个圆点跟随翻页，
        //选中点用主题强调色（仅本页代码注入，activity_simple_viewpager 被 5 个页面共用，不动 XML）
        addPagerDots(viewPager, fragmentList.size());

        findViewById(R.id.loading).setVisibility(View.GONE);
        if (fragmentList.size() > 1 && SharedPreferencesUtil.getBoolean("first_" + LoginActivity.class.getSimpleName(), true)) {
            MsgUtil.showMsgLong("提示：本页面可以左右滑动");
            SharedPreferencesUtil.putBoolean("first_" + LoginActivity.class.getSimpleName(), false);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    /**
     * 页点指示器（适配审计 P2-6）：顶栏下方一排小圆点，跟随 ViewPager 翻页。
     * 未选中点用半透明灰，选中点用主题强调色；登录页与改色页不会同时存活
     * （导航是 killAndJump），不注册 OnThemeChangedListener。
     */
    private void addPagerDots(ViewPager viewPager, int count) {
        if (count < 2) return;
        float density = getResources().getDisplayMetrics().density;
        int dotSize = (int) (6 * density + 0.5f);
        int dotGap = (int) (6 * density + 0.5f);

        android.widget.LinearLayout dotsLayout = new android.widget.LinearLayout(this);
        dotsLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        dotsLayout.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.RelativeLayout.LayoutParams dotsParams = new android.widget.RelativeLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        dotsParams.addRule(android.widget.RelativeLayout.CENTER_HORIZONTAL);
        dotsParams.addRule(android.widget.RelativeLayout.BELOW, R.id.top);
        dotsParams.setMargins(0, (int) (6 * density + 0.5f), 0, 0);
        dotsLayout.setLayoutParams(dotsParams);

        ImageView[] dots = new ImageView[count];
        for (int i = 0; i < count; i++) {
            ImageView dot = new ImageView(this);
            android.widget.LinearLayout.LayoutParams dotParams = new android.widget.LinearLayout.LayoutParams(dotSize, dotSize);
            if (i > 0) dotParams.setMarginStart(dotGap);
            dot.setLayoutParams(dotParams);
            dot.setImageDrawable(makeDotDrawable(i == 0));
            dotsLayout.addView(dot);
            dots[i] = dot;
        }
        ((android.view.ViewGroup) viewPager.getParent()).addView(dotsLayout);
        viewPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                for (int i = 0; i < dots.length; i++)
                    dots[i].setImageDrawable(makeDotDrawable(i == position));
            }
        });
    }

    private android.graphics.drawable.GradientDrawable makeDotDrawable(boolean selected) {
        android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
        dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dot.setColor(selected ? com.RobinNotBad.BiliClient.ui.theme.ThemeManager.getPrimary() : 0x66888888);
        return dot;
    }
}