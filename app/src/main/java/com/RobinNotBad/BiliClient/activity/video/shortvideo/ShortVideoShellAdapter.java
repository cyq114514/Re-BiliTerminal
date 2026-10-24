package com.RobinNotBad.BiliClient.activity.video.shortvideo;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;

import com.RobinNotBad.BiliClient.activity.reply.ReplyFragment;
import com.RobinNotBad.BiliClient.activity.video.info.VideoInfoFragment;
import com.RobinNotBad.BiliClient.activity.video.info.VideoRcmdFragment;
import com.RobinNotBad.BiliClient.api.ReplyApi;

import java.util.HashMap;
import java.util.Map;

/**
 * 短视频外壳的横向四页：
 * <ol>
 *   <li>短视频流（{@link ShortVideoFeedFragment}，常驻，翻视频不重建）</li>
 *   <li>视频详情（复用 {@link VideoInfoFragment}）</li>
 *   <li>评论（复用 {@link ReplyFragment}）</li>
 *   <li>相关推荐（复用 {@link VideoRcmdFragment}）</li>
 * </ol>
 *
 * <p>右侧三页的内容取决于「当前这条短视频是谁」，aid 变了就整页重建：这三个 Fragment 的 aid 都是
 * 创建时定死的，只 notifyDataSetChanged 不会刷新数据，因此用 {@link #fragmentAid} 记下每个实例
 * 是按哪个 aid 建的，aid 不一致就返回 POSITION_NONE 让它销毁重建。
 */
public class ShortVideoShellAdapter extends FragmentStatePagerAdapter {

    private final Fragment feedFragment;
    private final Map<Object, Long> fragmentAid = new HashMap<>();

    private long detailAid = 0;
    private String detailBvid = "";

    public ShortVideoShellAdapter(@NonNull FragmentManager fm, @NonNull Fragment feedFragment) {
        super(fm, BEHAVIOR_SET_USER_VISIBLE_HINT);
        this.feedFragment = feedFragment;
    }

    /** 当前这条短视频换了：右侧三页跟着换 */
    public void setDetail(long aid, String bvid) {
        if (aid == detailAid) return;
        detailAid = aid;
        detailBvid = bvid == null ? "" : bvid;
        notifyDataSetChanged();
    }

    public long detailAid() {
        return detailAid;
    }

    @NonNull
    @Override
    public Fragment getItem(int position) {
        if (position != 0 && detailAid <= 0) {
            //首屏还没拉到内容：右侧三页先放空壳，别拿 aid=0 去打接口（会弹"视频不存在"的错）。
            //★ 空壳也要登记进 fragmentAid（记 0）：不登记的话 getItemPosition 认不出来，
            //  锚点变了也返回 POSITION_UNCHANGED，这页就永远是这张空壳——
            //  表现就是"点评论进去一片空白、拉不到任何数据"
            Fragment placeholder = new Fragment();
            fragmentAid.put(placeholder, 0L);
            return placeholder;
        }
        switch (position) {
            case 1: {
                VideoInfoFragment fragment = VideoInfoFragment.newInstance(detailAid, detailBvid);
                fragmentAid.put(fragment, detailAid);
                return fragment;
            }
            case 2: {
                //count 传 0：这里拿不到统计数，评论页只是少了"共 N 条"的兜底提示
                ReplyFragment fragment = ReplyFragment.newInstance(detailAid, ReplyApi.REPLY_TYPE_VIDEO, 0, -1, 0);
                fragmentAid.put(fragment, detailAid);
                return fragment;
            }
            case 3: {
                VideoRcmdFragment fragment = VideoRcmdFragment.newInstance(detailAid);
                fragmentAid.put(fragment, detailAid);
                return fragment;
            }
            case 0:
            default:
                return feedFragment;
        }
    }

    @Override
    public int getItemPosition(@NonNull Object object) {
        if (object == feedFragment) return POSITION_UNCHANGED;
        Long builtWith = fragmentAid.get(object);
        if (builtWith == null) {
            //认不出来的实例（老版本留下的空壳、异常路径）：锚点已经就绪就重建，
            //否则它会一直挂着不刷新——这是"右侧三页永远空白"的另一个入口
            return detailAid > 0 ? POSITION_NONE : POSITION_UNCHANGED;
        }
        return builtWith == detailAid ? POSITION_UNCHANGED : POSITION_NONE;
    }

    @Override
    public int getCount() {
        return 4;
    }
}
