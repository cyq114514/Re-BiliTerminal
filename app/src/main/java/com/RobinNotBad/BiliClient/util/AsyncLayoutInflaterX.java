package com.RobinNotBad.BiliClient.util;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.util.Pools;
import androidx.core.view.LayoutInflaterCompat;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class AsyncLayoutInflaterX {
    private static final String TAG = "AsyncLayoutInflatePlus";

    private final Pools.SynchronizedPool<InflateRequest> mRequestPool = new Pools.SynchronizedPool<>(10);

    final LayoutInflater mInflater;
    final Handler mHandler;
    final Dispather mDispatcher;


    public AsyncLayoutInflaterX(@NonNull Context context) {
        mInflater = new BasicInflater(context);
        mHandler = new Handler(mHandlerCallback);
        mDispatcher = new Dispather();
    }

    @UiThread
    public void inflate(@LayoutRes int resid, @Nullable ViewGroup parent,
                        @NonNull OnInflateFinishedListener callback) {
        // API<23 强制走主线程 Handler 路径：Android 5.x 上后台线程 inflate 会与主线程
        // 并发克隆 StateListAnimator（selector 里 <set> 型，如 CardStyle/ButtonStyle 的
        // press_scale），命中 AnimatorSet.clone 共享 Node.mTmpClone 的平台竞态 NPE；
        // 这条后台路径还会把失败吞成一次主线程重试，只能看到「第二次」现场。
        // 详见 notes/platform.md §1；API 23+ 才允许线程池 inflate。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.ASYNC_INFLATE_ENABLE, true)) {
            InflateRequest request = obtainRequest();
            request.inflater = this;
            request.resid = resid;
            request.parent = parent;
            request.callback = callback;
            mDispatcher.enqueue(request);
        } else {
            InflateRequest request = new InflateRequest();
            request.inflater = this;
            request.resid = resid;
            request.parent = parent;
            request.callback = callback;
            Message.obtain(mHandler, 0, request)
                    .sendToTarget();
        }
    }

    private Handler.Callback mHandlerCallback = new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            InflateRequest request = (InflateRequest) msg.obj;
            if (request.view == null) {
                request.view = mInflater.inflate(
                        request.resid, request.parent, false);
            }
            request.callback.onInflateFinished(
                    request.view, request.resid, request.parent);
            releaseRequest(request);
            return true;
        }
    };

    public interface OnInflateFinishedListener {
        void onInflateFinished(@NonNull View view, @LayoutRes int resid,
                               @Nullable ViewGroup parent);
    }

    private static class InflateRequest {
        AsyncLayoutInflaterX inflater;
        ViewGroup parent;
        int resid;
        View view;
        OnInflateFinishedListener callback;

        InflateRequest() {
        }
    }


    private static class Dispather {

        //获得当前CPU的核心数
        private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
        //设置线程池的核心线程数2-4之间,但是取决于CPU核数
        private static final int CORE_POOL_SIZE = Math.max(2, Math.min(CPU_COUNT - 1, 4));
        //设置线程池的最大线程数为 CPU核数 * 2 + 1
        private static final int MAXIMUM_POOL_SIZE = CPU_COUNT * 2 + 1;
        //设置线程池空闲线程存活时间30s
        private static final int KEEP_ALIVE_SECONDS = 30;

        private static final ThreadFactory sThreadFactory = new ThreadFactory() {
            private final AtomicInteger mCount = new AtomicInteger(1);

            public Thread newThread(Runnable r) {
                return new Thread(r, "AsyncLayoutInflatePlus #" + mCount.getAndIncrement());
            }
        };

        //LinkedBlockingQueue 默认构造器，队列容量是Integer.MAX_VALUE
        private static final BlockingQueue<Runnable> sPoolWorkQueue =
                new LinkedBlockingQueue<>();

        /**
         * An {@link Executor} that can be used to execute tasks in parallel.
         */
        public static final ThreadPoolExecutor THREAD_POOL_EXECUTOR;

        static {
            Log.i(TAG, "static initializer: " + " CPU_COUNT = " + CPU_COUNT + " CORE_POOL_SIZE = " + CORE_POOL_SIZE + " MAXIMUM_POOL_SIZE = " + MAXIMUM_POOL_SIZE);
            ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(
                    CORE_POOL_SIZE, MAXIMUM_POOL_SIZE, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                    sPoolWorkQueue, sThreadFactory);
            threadPoolExecutor.allowCoreThreadTimeOut(true);
            THREAD_POOL_EXECUTOR = threadPoolExecutor;
        }

        public void enqueue(InflateRequest request) {
            THREAD_POOL_EXECUTOR.execute((new InflateRunnable(request)));

        }

    }

    private static class BasicInflater extends LayoutInflater {
        private static final String[] sClassPrefixList = {
                "android.widget.",
                "android.webkit.",
                "android.app."
        };

        BasicInflater(Context context) {
            super(context);
            if (context instanceof AppCompatActivity) {
                // 手动setFactory2，兼容AppCompatTextView等控件
                AppCompatDelegate appCompatDelegate = ((AppCompatActivity) context).getDelegate();
                if (appCompatDelegate instanceof LayoutInflater.Factory2) {
                    LayoutInflaterCompat.setFactory2(this, (LayoutInflater.Factory2) appCompatDelegate);
                }
            }
        }

        @Override
        public LayoutInflater cloneInContext(Context newContext) {
            return new BasicInflater(newContext);
        }

        @Override
        protected View onCreateView(String name, AttributeSet attrs) throws ClassNotFoundException {
            for (String prefix : sClassPrefixList) {
                try {
                    View view = createView(name, prefix, attrs);
                    if (view != null) {
                        return view;
                    }
                } catch (ClassNotFoundException e) {
                    // In this case we want to let the base class take a crack
                    // at it.
                }
            }

            return super.onCreateView(name, attrs);
        }
    }


    private static class InflateRunnable implements Runnable {
        private final InflateRequest request;
        private boolean isRunning;

        public InflateRunnable(InflateRequest request) {
            this.request = request;
        }

        @Override
        public void run() {
            isRunning = true;
            try {
                request.view = request.inflater.mInflater.inflate(
                        request.resid, request.parent, false);
            } catch (Throwable ex) {
                // Probably a Looper failure, retry on the UI thread
                Log.w(TAG, "Failed to inflate resource in the background! Retrying on the UI"
                        + " thread", ex);
                // 同时把原始异常写进崩溃追踪：此前这条路径只进 logcat，主线程重试
                // 再崩时只剩「第二次」的现场，原始失败原因（如 5.x 的 AnimatorSet.clone
                // 竞态）完全丢失。CrashTrace 本身绝不外抛，可以放心放在这里。
                CrashTrace.log("AsyncLayoutInflaterX: 后台 inflate 失败（原始异常） "
                        + Log.getStackTraceString(ex));
            }
            Message.obtain(request.inflater.mHandler, 0, request)
                    .sendToTarget();
        }

        public boolean isRunning() {
            return isRunning;
        }
    }


    public InflateRequest obtainRequest() {
        InflateRequest obj = mRequestPool.acquire();
        if (obj == null) {
            obj = new InflateRequest();
        }
        return obj;
    }

    public void releaseRequest(InflateRequest obj) {
        obj.callback = null;
        obj.inflater = null;
        obj.parent = null;
        obj.resid = 0;
        obj.view = null;
        mRequestPool.release(obj);
    }


    public void cancel() {
        mHandler.removeCallbacksAndMessages(null);
        mHandlerCallback = null;
    }
}