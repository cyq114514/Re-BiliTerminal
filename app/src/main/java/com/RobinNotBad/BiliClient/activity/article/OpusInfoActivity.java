package com.RobinNotBad.BiliClient.activity.article;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;

import androidx.fragment.app.Fragment;
import androidx.viewpager.widget.ViewPager;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.activity.dynamic.DynamicInfoActivity;
import com.RobinNotBad.BiliClient.activity.reply.ReplyFragment;
import com.RobinNotBad.BiliClient.adapter.viewpager.ViewPagerFragmentAdapter;
import com.RobinNotBad.BiliClient.event.ReplyEvent;
import com.RobinNotBad.BiliClient.helper.TutorialHelper;
import com.RobinNotBad.BiliClient.model.Opus;
import com.RobinNotBad.BiliClient.util.AnimationUtils;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.TerminalContext;
import com.RobinNotBad.BiliClient.util.ToolsUtil;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

public class OpusInfoActivity extends BaseActivity {
    private long oid;

    private ReplyFragment replyFragment;
    private long seek_reply;

    //页面内容完全由 intent 的 id 决定，冷启动可恢复
    @Override
    public boolean isRestorablePage() {
        return true;
    }

    private ImageView loadingView;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_simple_viewpager);
        Intent intent = getIntent();
        oid = intent.getLongExtra("id", 114514);
        seek_reply = getIntent().getLongExtra("seekReply", -1);

        setPageName("文章详情");
        loadingView = findViewById(R.id.loading);

        //TutorialHelper.showTutorialList(this, R.array.tutorial_article, 7);

        ViewPager viewPager = findViewById(R.id.viewPager);


        TerminalContext.getInstance().getOpusById(oid)
                .observe(this, (result) -> result.onSuccess((opus) -> {
                    if (!ToolsUtil.isHostAlive(this)) return;
                    if (opus.type == Opus.TYPE_DYNAMIC_OLD_STYLE) {
                        Intent intent1 = new Intent(this, DynamicInfoActivity.class);
                        intent1.putExtra("id", oid);
                        intent1.putExtra("seekReply", seek_reply);
                        startActivity(intent1);
                        finish();
                        return;
                    }

                    List<Fragment> fragmentList = new ArrayList<>();

                    OpusInfoFragment oiFragment = OpusInfoFragment.newInstance(oid);
                    fragmentList.add(oiFragment);

                    //接口在部分专栏（尤其实时数据缺失时）不返回 stats / upInfo，
                    //直接取字段会 NPE（线上已收到 2 例：Attempt to read field 'int ...Stats.reply' on null）
                    int replyCount = opus.stats != null ? opus.stats.reply : 0;
                    replyFragment = ReplyFragment.newInstance(opus.commentId, opus.commentType, replyCount,
                            seek_reply, opus.upInfo != null ? opus.upInfo.mid : 0);
                    if (opus.upInfo != null) replyFragment.setManager(opus.upInfo);
                    fragmentList.add(replyFragment);

                    ViewPagerFragmentAdapter vpfAdapter = new ViewPagerFragmentAdapter(getSupportFragmentManager(), fragmentList);
                    viewPager.setAdapter(vpfAdapter);
                    if (seek_reply != -1) viewPager.setCurrentItem(1);

                    AnimationUtils.fadeOut(loadingView, 100);
                    TutorialHelper.showPagerTutorial(this, 2);
                }).onFailure((error) -> {
                    loadingView.setImageResource(R.mipmap.loading_2233_error);
                    MsgUtil.showMsgLong("专栏信息获取失败！\n可能已被删除？");
                    Logu.e("OpusInfoActivity", "getOpus failed: " + error.getMessage());
                    AnimationUtils.fadeOut(loadingView, 100);
                    //注意是"没在关闭中"才关：写成 isFinishing() 时条件永假（页面还活着时它是 false），
                    //自动关闭永远不会发生（同 VideoInfoActivity 的正确写法）
                    CenterThreadPool.runOnUIThreadAfter(2L, java.util.concurrent.TimeUnit.SECONDS, () -> {
                        if (!isFinishing() && !isDestroyed()) finish();
                    });
                }));


    }

    @Override
    protected boolean eventBusEnabled() {
        return true;
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEvent(ReplyEvent event) {
        //专栏页原先这里被注释掉了：发完评论只更新了"共 N 条评论"，列表里根本不插这条，
        //要退出重进（还得等服务端索引）才看得到。视频页/动态页一直是转发给 Fragment 的
        if (replyFragment != null) replyFragment.notifyReplyInserted(event);
    }

    @Override
    protected void onDestroy() {
        //TerminalContext.getInstance().leaveDetailPage();
        super.onDestroy();
    }
}
