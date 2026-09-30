package com.RobinNotBad.BiliClient.util;

import android.content.Intent;
import android.view.View;

import com.RobinNotBad.BiliClient.activity.ImageViewerActivity;

import java.util.ArrayList;
import java.util.List;

public class ImageViewer {
    public static ImageViewer from(String url) {
        ArrayList<String> urls = new ArrayList<>();
        urls.add(url);
        return new ImageViewer(urls);
    }

    public static ImageViewer from(List<String> urls) {
        return new ImageViewer(new ArrayList<>(urls));
    }

    private final ArrayList<String> urlList;

    private ImageViewer(ArrayList<String> urlList) {
        this.urlList = urlList;
    }

    public void into(View view) {
        view.setOnClickListener((v) -> {
            Intent intent = new Intent();
            intent.setClass(v.getContext(), ImageViewerActivity.class);
            intent.putExtra("imageList", urlList);
            v.getContext().startActivity(intent);
        });
    }
}
