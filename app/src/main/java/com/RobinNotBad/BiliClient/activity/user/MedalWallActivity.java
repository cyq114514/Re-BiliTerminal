package com.RobinNotBad.BiliClient.activity.user;

import android.os.Bundle;

import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity;
import com.RobinNotBad.BiliClient.adapter.user.MedalListAdapter;
import com.RobinNotBad.BiliClient.api.UserInfoApi;
import com.RobinNotBad.BiliClient.model.MedalInfo;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;

public class MedalWallActivity extends RefreshListActivity {

    private long mid;
    private ArrayList<MedalInfo> medalList;
    private MedalListAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mid = getIntent().getLongExtra("mid", -1);
        if (mid == -1) {
            finish();
            return;
        }

        setPageName("粉丝勋章");

        recyclerView.setHasFixedSize(true);
        medalList = new ArrayList<>();

        CenterThreadPool.run(() -> {
            try {
                JSONObject data = UserInfoApi.getMedalWall(mid);
                if (data != null) {
                    JSONArray list = data.optJSONArray("list");
                    if (list != null) {
                        for (int i = 0; i < list.length(); i++) {
                            JSONObject item = list.getJSONObject(i);
                            MedalInfo medal = new MedalInfo();
                            
                            JSONObject medalInfo = item.optJSONObject("medal_info");
                            if (medalInfo != null) {
                                medal.target_id = medalInfo.optLong("target_id", 0);
                                medal.level = medalInfo.optInt("level", 0);
                                medal.medal_name = medalInfo.optString("medal_name", "");
                                medal.medal_color_start = medalInfo.optInt("medal_color_start", 0);
                                medal.medal_color_end = medalInfo.optInt("medal_color_end", 0);
                                medal.medal_color_border = medalInfo.optInt("medal_color_border", 0);
                                medal.guard_level = medalInfo.optInt("guard_level", 0);
                                medal.wearing_status = medalInfo.optInt("wearing_status", 0);
                                medal.medal_id = medalInfo.optLong("medal_id", 0);
                                medal.intimacy = medalInfo.optInt("intimacy", 0);
                                medal.next_intimacy = medalInfo.optInt("next_intimacy", 0);
                                medal.today_feed = medalInfo.optInt("today_feed", 0);
                                medal.day_limit = medalInfo.optInt("day_limit", 0);
                                medal.guard_icon = medalInfo.optString("guard_icon", "");
                                medal.honor_icon = medalInfo.optString("honor_icon", "");
                            }
                            
                            medal.target_name = item.optString("target_name", "");
                            medal.target_icon = item.optString("target_icon", "");
                            medal.link = item.optString("link", "");
                            medal.live_status = item.optInt("live_status", 0);
                            medal.offical = item.optInt("offical", 0);
                            
                            JSONObject uinfoMedal = item.optJSONObject("uinfo_medal");
                            if (uinfoMedal != null) {
                                medal.uinfo_medal_name = uinfoMedal.optString("name", "");
                                medal.uinfo_medal_level = uinfoMedal.optInt("level", 0);
                                medal.uinfo_medal_color_start = uinfoMedal.optInt("color_start", 0);
                                medal.uinfo_medal_color_end = uinfoMedal.optInt("color_end", 0);
                                medal.uinfo_medal_color_border = uinfoMedal.optInt("color_border", 0);
                                medal.uinfo_medal_color = uinfoMedal.optInt("color", 0);
                                medal.uinfo_medal_id = uinfoMedal.optLong("id", 0);
                                medal.uinfo_medal_typ = uinfoMedal.optInt("typ", 0);
                                medal.uinfo_medal_is_light = uinfoMedal.optInt("is_light", 0);
                                medal.uinfo_medal_ruid = uinfoMedal.optLong("ruid", 0);
                                medal.uinfo_medal_guard_level = uinfoMedal.optInt("guard_level", 0);
                                medal.uinfo_medal_score = uinfoMedal.optInt("score", 0);
                                medal.uinfo_medal_guard_icon = uinfoMedal.optString("guard_icon", "");
                                medal.uinfo_medal_honor_icon = uinfoMedal.optString("honor_icon", "");
                                medal.v2_medal_color_start = uinfoMedal.optString("v2_medal_color_start", "");
                                medal.v2_medal_color_end = uinfoMedal.optString("v2_medal_color_end", "");
                                medal.v2_medal_color_border = uinfoMedal.optString("v2_medal_color_border", "");
                                medal.v2_medal_color_text = uinfoMedal.optString("v2_medal_color_text", "");
                                medal.v2_medal_color_level = uinfoMedal.optString("v2_medal_color_level", "");
                                medal.user_receive_count = uinfoMedal.optInt("user_receive_count", 0);
                            }
                            
                            medalList.add(medal);
                        }
                    }
                }
                
                runOnUiThread(() -> {
                    adapter = new MedalListAdapter(this, medalList);
                    setRefreshing(false);
                    setAdapter(adapter);
                    setBottom(true);
                    
                    if (medalList.isEmpty()) {
                        showEmptyView();
                    } else {
                        hideEmptyView();
                    }
                });
            } catch (Exception e) {
                loadFail(e);
            }
        });
    }
}

