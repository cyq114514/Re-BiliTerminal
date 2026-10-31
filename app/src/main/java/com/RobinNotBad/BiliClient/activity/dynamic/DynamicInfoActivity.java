package com.RobinNotBad.BiliClient.activity.dynamic;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import androidx.viewpager.widget.ViewPager;
import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.activity.reply.ReplyFragment;
import com.RobinNotBad.BiliClient.adapter.viewpager.ViewPagerFragmentAdapter;
import com.RobinNotBad.BiliClient.api.ReplyApi;
import com.RobinNotBad.BiliClient.event.ReplyEvent;
import com.RobinNotBad.BiliClient.helper.TutorialHelper;
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

//动态信息页面
//2023-10-03

public class DynamicInfoActivity extends BaseActivity {

    ReplyFragment rFragment;
    private long seek_reply;
    /** 消息跳转时要高亮的子评论 rpid（-1 表示无） */
    private long seek_child = -1;

    //页面内容完全由 intent 的 id 决定，冷启动可恢复
    @Override
    public boolean isRestorablePage() {
        return true;
    }

    @SuppressLint({"MissingInflatedId", "InflateParams"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_simple_viewpager);
        seek_reply = getIntent().getLongExtra("seekReply", -1);
        seek_child = getIntent().getLongExtra("seekChild", -1);
        Logu.e("seek-debug", "DynamicInfoActivity id=" + getIntent().getLongExtra("id", 0)
                + " seekReply=" + seek_reply + " seekChild=" + seek_child);

        Intent intent = getIntent();
        long id = intent.getLongExtra("id", 0);

        TextView pageName = findViewById(R.id.pageName);
        pageName.setText("动态详情");

        TutorialHelper.showTutorialList(this, R.array.tutorial_dynamic_info, 6);
        TerminalContext.getInstance().getDynamicById(id)
                .observe(this, (dynamicResult) -> dynamicResult.onSuccess((dynamic) -> {
                    if (!ToolsUtil.isHostAlive(this)) return;
                    //查不到的动态（已删除 / id 传错）服务端仍返回 code=0，但 item 是
                    //DYNAMIC_TYPE_NONE 的空壳：id_str 与 comment_id_str 都是 "0"（已实测确认）。
                    //此前只把正文改成「[动态不存在]」就照常建评论 tab，于是拿 oid=0 去请求评论接口，
                    //服务端回 invalid oid，用户看到的是"评论区加载失败"而不是"动态不存在"。
                    if (dynamic.dynamicId == 0 || "DYNAMIC_TYPE_NONE".equals(dynamic.type)) {
                        Logu.e("DynamicInfoActivity", "动态不存在 id=" + id + " type=" + dynamic.type);
                        ((ImageView) findViewById(R.id.loading)).setImageResource(R.mipmap.loading_2233_error);
                        MsgUtil.showMsgLong("动态不存在或已被删除");
                        CenterThreadPool.runOnUIThreadAfter(2L, java.util.concurrent.TimeUnit.SECONDS, () -> {
                            if (!isFinishing() && !isDestroyed()) finish();
                        });
                        return;
                    }
                    List<Fragment> fragmentList = new ArrayList<>();
                    DynamicInfoFragment diFragment = DynamicInfoFragment.newInstance(id);
                    fragmentList.add(diFragment);
                    //评论数取不到时按 0 降级：接口在部分动态（实时数据缺失、modules 缺失）不返回 stats，
                    //直接取字段会 NPE（线上 7 例：Attempt to read field 'int ...Stats.reply' on null）
                    //接口在部分动态（实时数据缺失、动态已删除、modules 缺失）不返回 stats / author，
                    //直接取字段会 NPE（线上 7 例：Attempt to read field 'int ...Stats.reply' on null）。
                    //与 OpusInfoActivity 同一套处理：取不到就按 0 / 空作者降级，不阻断页面。
                    int replyCount = dynamic.stats != null ? dynamic.stats.reply : 0;
                    long upMid = dynamic.userInfo != null ? dynamic.userInfo.mid : 0;
                    rFragment = ReplyFragment.newInstance(dynamic.comment_id, dynamic.comment_type, replyCount, seek_reply, upMid, seek_child);
                    if (dynamic.userInfo != null) rFragment.setManager(dynamic.userInfo);
                    rFragment.replyType = ReplyApi.REPLY_TYPE_DYNAMIC;
                    fragmentList.add(rFragment);
                    ViewPagerFragmentAdapter vpfAdapter = new ViewPagerFragmentAdapter(getSupportFragmentManager(), fragmentList);
                    ViewPager viewPager = findViewById(R.id.viewPager);
                    viewPager.setAdapter(vpfAdapter);  //没啥好说的，教科书式的ViewPager使用方法
                    if (seek_reply != -1) viewPager.setCurrentItem(1);

                    AnimationUtils.fadeOut(findViewById(R.id.loading), 100);
                    TutorialHelper.showPagerTutorial(this, 2);
                }).onFailure((e) -> {
                    ((ImageView) findViewById(R.id.loading)).setImageResource(R.mipmap.loading_2233_error);
                    MsgUtil.showMsgLong("动态信息获取失败！\n可能已被删除？");
                    Logu.e("DynamicInfoActivity", "getDynamic failed: " + e.getMessage());
                    //"没在关闭中"才关（原写法条件永假，失效动态页永远不会自动关闭）
                    CenterThreadPool.runOnUIThreadAfter(2L, java.util.concurrent.TimeUnit.SECONDS, () -> {
                        if (!isFinishing() && !isDestroyed()) finish();
                    });
                }));

    }

    @Override
    protected boolean eventBusEnabled() {
        return true;
    }

    //必须留在主线程：rFragment.notifyReplyInserted 会改列表数据并通知 RecyclerView
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEvent(ReplyEvent event) {
        rFragment.notifyReplyInserted(event);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        //动态操作菜单（置顶/可见范围/编辑）的选择结果
        com.RobinNotBad.BiliClient.adapter.dynamic.DynamicHolder.onDynamicOpResult(requestCode, resultCode, data, this);
    }

    @Override
    protected void onDestroy() {
        TerminalContext.getInstance().leaveDetailPage();
        super.onDestroy();
    }
}