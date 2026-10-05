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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(R.style.Theme_BiliClient);
        setContentView(R.layout.activity_image_viewer);
        Intent intent = getIntent();
        ArrayList<String> imageList = intent.getStringArrayListExtra("imageList");

        PhotoViewpager viewPager = findViewById(R.id.viewPager);
        TextView textView = findViewById(R.id.text_page);

        ImageButton download = findViewById(R.id.btn_download);
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
                return photoView;
            }

            @Override
            public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
                //先清请求（此刻视图还 attach 着）再移除，释放离屏页位图
                Glide.with((View) object).clear((View) object);
                container.removeView((View) object);
            }
        });

        viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @SuppressLint("SetTextI18n")
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
                if (positionOffset % 1 == 0)
                    textView.setText("第" + (position + 1) + "/" + imageList.size() + "张");
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