package com.RobinNotBad.BiliClient.activity.settings;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.adapter.QualityChooseAdapter;
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager;
import com.RobinNotBad.BiliClient.util.NetworkPlaybackQuality;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Objects;

public class SettingQualityActivity extends BaseActivity {
    QualityChooseAdapter adapter;

    /** 本次要写哪个偏好键：全局 / 移动数据 / Wi-Fi。默认还是全局。 */
    private String preferenceKey = NetworkPlaybackQuality.DEFAULT;

    static final LinkedHashMap<String, Integer> qnMap = new LinkedHashMap<>() {{
        put("360P", 16);
        put("720P", 64);
        put("1080P", 80);
    }};

    static LinkedHashMap<String, Integer> getQnMap() {
        return qnMap;
    }

    private LinkedHashMap<String, Integer> choices() {
        LinkedHashMap<String, Integer> map = new LinkedHashMap<>();
        // 网络分档专属的“跟随全局”选项：不选就用全局默认
        if (!NetworkPlaybackQuality.DEFAULT.equals(preferenceKey)) {
            map.put("跟随全局", -1);
        }
        map.putAll(qnMap);
        return map;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_simple_list);
        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        findViewById(R.id.top).setOnClickListener(view -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        preferenceKey = NetworkPlaybackQuality.preferenceKey(
                getIntent().getStringExtra(NetworkPlaybackQuality.EXTRA_KEY));

        setPageName(preferenceKey.equals(NetworkPlaybackQuality.MOBILE) ? "移动数据默认清晰度"
                : preferenceKey.equals(NetworkPlaybackQuality.WIFI) ? "Wi-Fi 默认清晰度"
                : "请选择清晰度");

        adapter = new QualityChooseAdapter(this);
        adapter.setNameList(new ArrayList<>(choices().keySet()));
        adapter.setOnItemClickListener((this::save));

        recyclerView.setLayoutManager(new CustomLinearManager(this));
        recyclerView.setAdapter(adapter);
    }

    private void save(int position) {
        String str = adapter.getName(position);
        LinkedHashMap<String, Integer> map = choices();
        if (map.containsKey(str))
            SharedPreferencesUtil.putInt(preferenceKey, Objects.requireNonNull(map.get(str)));
        finish();
    }
}
