package com.RobinNotBad.BiliClient.activity.video.shortvideo;

import android.content.Context;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.widget.FrameLayout;

/**
 * 短视频信息流的根布局：只做一件事——把触摸事件「旁听」一份给 {@link GestureDetector}。
 *
 * <p>为什么不能直接 {@code setOnTouchListener}：这一层下面铺着一个铺满全屏的 RecyclerView，
 * 它的 {@code onTouchEvent} 对 ACTION_DOWN 无条件返回 true，触摸在子 View 就被消费掉了，
 * 根布局的 OnTouchListener <b>一次都不会被调用</b>；而且即便收到 DOWN，从 OnTouchListener
 * 返回 false 也意味着系统不再给它派发后续 MOVE/UP，手势检测器等不到 UP，
 * {@code onSingleTapConfirmed} / {@code onDoubleTap} 永远不会确认——第一版"操作栏收起后
 * 双击唤不出来、单击也不暂停"就是这个原因。
 *
 * <p>而 {@code dispatchTouchEvent} 位于派发链最上游：在这里喂一份给手势检测器，
 * 既能看到完整手势（DOWN→MOVE→UP），又不必消费事件（照旧 {@code return super}），
 * 纵向滚动、横向翻页、按钮点击全都不受影响。
 */
public class FeedGestureLayout extends FrameLayout {

    private GestureDetector detector;
    private Runnable touchEndListener;

    public FeedGestureLayout(Context context) {
        super(context);
    }

    public FeedGestureLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public FeedGestureLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setGestureDetector(GestureDetector detector) {
        this.detector = detector;
    }

    /** 手指离开/手势取消：长按倍速要在这里还原（v1 依赖的 OnTouchListener 已经收不到事件了） */
    public void setOnTouchEnd(Runnable listener) {
        this.touchEndListener = listener;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            Runnable end = touchEndListener;
            if (end != null) {
                try {
                    end.run();
                } catch (Throwable ignored) {
                }
            }
        }
        GestureDetector target = detector;
        if (target != null) {
            try {
                target.onTouchEvent(ev);
            } catch (Throwable ignored) {
            }
        }
        return super.dispatchTouchEvent(ev);
    }
}
