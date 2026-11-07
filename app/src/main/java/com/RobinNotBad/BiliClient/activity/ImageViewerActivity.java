package com.RobinNotBad.BiliClient.activity;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.ui.widget.PhotoViewpager;
import com.RobinNotBad.BiliClient.util.FileUtil;
import com.RobinNotBad.BiliClient.util.GlideUtil;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.target.Target;
import com.github.chrisbanes.photoview.PhotoView;

import java.util.ArrayList;

public class ImageViewerActivity extends BaseActivity {

    //简简单单的图片查看页面
    //2023-07-21

    private long longClickTimestamp;
    /** 表冠缩放（用户反馈 2026-11-07）：需要拿到当前页的 PhotoView */
    private PhotoViewpager viewPager;
    private final java.util.HashMap<Integer, PhotoView> photoViews = new java.util.HashMap<>();

    /**
     * 旋转表冠缩放当前图片。开关见 设置 → 实验室 →「表冠缩放图片」。
     *
     * <p>表冠事件是 {@code SOURCE_ROTARY_ENCODER} 上的 {@code ACTION_SCROLL}，
     * 直接调 {@code PhotoView.setScale} 而不是模拟双指手势——后者要拼 MotionEvent，
     * 在手势识别器里很容易和"单指拖动"互相打架。
     */
    @Override
    public boolean onGenericMotionEvent(android.view.MotionEvent event) {
        if (event.getAction() == android.view.MotionEvent.ACTION_SCROLL
                && event.getSource() == android.view.InputDevice.SOURCE_ROTARY_ENCODER
                && com.RobinNotBad.BiliClient.util.SharedPreferencesUtil.getBoolean("ui_rotatory_image_zoom", false)) {
            PhotoView pv = viewPager != null ? photoViews.get(viewPager.getCurrentItem()) : null;
            if (pv != null) {
                float axis = event.getAxisValue(android.view.MotionEvent.AXIS_SCROLL);
                if (axis != 0f) {
                    float next = pv.getScale() * (axis > 0 ? 1.15f : 1f / 1.15f);
                    next = Math.max(1f, Math.min(6.25f, next));
                    pv.setScale(next);
                    return true;
                }
            }
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        //必须在 super.onCreate 之前：基类 onCreate 会调 findViewById（界面边距那条路径），
        //而 AppCompat 的 findViewById 会强制安装 subDecor，要求主题是 AppCompat 后代；
        //看图页在 Manifest 里挂的是 Theme.NoSwipe（parent 是 android:Theme.Black），
        //放在 super 之后等于"基类在旧主题下先崩了"，用户侧表现就是点开图片直接闪退。
        //Manifest 主题保持不变：它仍用于排除氛围背景/自定义底图（读的是清单里的 theme）。
        setTheme(R.style.Theme_BiliClient);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_viewer);
        Intent intent = getIntent();
        ArrayList<String> imageList = intent.getStringArrayListExtra("imageList");

        viewPager = findViewById(R.id.viewPager);
        TextView textView = findViewById(R.id.text_page);

        ImageButton download = findViewById(R.id.btn_download);
        //圆屏适配（适配审计 P2-24）：下载按钮/页码贴屏幕底角 8dp，圆表上正落四角裁切区。
        //圆屏开关开启时按比例内缩（本页 NoSwipe 不吃 BaseActivity 内容边距，需自己处理）
        if (com.RobinNotBad.BiliClient.util.SharedPreferencesUtil.getBoolean("player_ui_round", false)) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int cornerInset = (int) (dm.widthPixels * 0.08);
            int bottomInset = (int) (dm.heightPixels * 0.06);
            android.widget.RelativeLayout.LayoutParams dl = (android.widget.RelativeLayout.LayoutParams) download.getLayoutParams();
            dl.setMargins(cornerInset, 0, 0, bottomInset);
            download.setLayoutParams(dl);
            View pageText = findViewById(R.id.text_page);
            if (pageText != null) {
                android.widget.RelativeLayout.LayoutParams pl = (android.widget.RelativeLayout.LayoutParams) pageText.getLayoutParams();
                pl.setMargins(0, 0, cornerInset, bottomInset);
                pageText.setLayoutParams(pl);
            }
        }
        download.setOnClickListener(v -> {
            long time_now = System.currentTimeMillis();
            if (time_now - longClickTimestamp < 3000) {
                Intent intent1 = new Intent(this, DownloadActivity.class)
                        .putExtra("link", imageList.get(viewPager.getCurrentItem()))
                        .putExtra("path", FileUtil.getPicturePath().getAbsolutePath())
                        .putExtra("type", 0);
                startActivity(intent1);
            } else MsgUtil.showMsg("再次点击下载");
            longClickTimestamp = time_now;
        });

        //懒加载：此前为全部图一次性 new PhotoView 并 SIZE_ORIGINAL 解码全量驻留，
        //图组一多直接吃穿 32 位进程内存。改为 instantiateItem 时才建视图、开解码，
        //离屏页 destroyItem 时连同位图一起释放（默认 offscreenPageLimit=1，同屏最多 3 张在内存）。
        viewPager.setAdapter(new PagerAdapter() {
            @Override
            public int getCount() {
                return imageList != null ? imageList.size() : 0;
            }

            @Override
            public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
                return view == object;
            }

            @NonNull
            @Override
            public Object instantiateItem(@NonNull ViewGroup container, int position) {
                PhotoView photoView = new PhotoView(ImageViewerActivity.this);
                photoView.setMaximumScale(6.25f);
                try {
                    Glide.with(ImageViewerActivity.this).asDrawable()
                            .load(GlideUtil.url_hq(imageList.get(position)))  //让b站自己压缩一下以加速获取
                            .transition(GlideUtil.getTransitionOptions())
                            //限宽不设高：B 站高清档 CDN 图本身 ≤1024w 不受影响，兜住外站/afdian 超大原图；
                            //高度保持原图，长图放大依旧清晰
                            .override(1024, Target.SIZE_ORIGINAL)
                            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                            .into(photoView);
                } catch (OutOfMemoryError e) {
                    MsgUtil.showMsg("超出内存，加载失败");
                } catch (Exception e) {
                    MsgUtil.err("图片查看", e);
                }
                container.addView(photoView);
                photoViews.put(position, photoView);
                return photoView;
            }

            @Override
            public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
                photoViews.remove(position);
                //先清请求（此刻视图还 attach 着）再移除，释放离屏页位图。
                //必须用 application 的 RequestManager：ViewPager 的 populate→destroyItem 可能被主线程
                //排到 Activity onDestroy 之后才执行，Glide.with(Activity/View) 会直接抛
                //"You cannot start a load for a destroyed activity"（线上 5 例）。
                //clear(View) 走 View 上的 target tag 找请求，换 manager 一样清得掉。
                Glide.with(getApplicationContext()).clear((View) object);
                container.removeView((View) object);
            }
        });

        viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @SuppressLint("SetTextI18n")
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
                if (positionOffset % 1 == 0)
                    //文案进 strings（适配审计 P3；DESIGN.md 硬编码文案约定）
                    textView.setText(getString(R.string.image_page_fmt, position + 1, imageList.size()));
            }

            @Override
            public void onPageSelected(int position) {

            }

            @Override
            public void onPageScrollStateChanged(int state) {

            }
        });
    }


}