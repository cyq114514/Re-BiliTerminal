package com.RobinNotBad.BiliClient.activity.user;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.api.CreativeCenterApi;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.StringUtil;

import org.json.JSONException;
import org.json.JSONObject;

public class CreativeCenterActivity extends BaseActivity {
    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        asyncInflate(R.layout.activity_creative_center, (view, id) -> CenterThreadPool.run(() -> {
            try {
                JSONObject stats = CreativeCenterApi.getVideoStat();
                String beUPTime = (String) CreativeCenterApi.getBeUPTime().result;

                runOnUiThread(() -> {
                    try {
                        if (stats == null) {
                            MsgUtil.showMsg("先去成为UP主吧~");
                            finish();
                        } else {
                            setStatsText(R.id.totalFans_number, stats, "total_fans", "incr_fans");
                            setStatsText(R.id.totalClick_number, stats, "total_click", "incr_click");
                            setStatsText(R.id.totalLike_number, stats, "total_like", "inc_like");
                            setStatsText(R.id.totalCoin_number, stats, "total_coin", "inc_coin");
                            setStatsText(R.id.totalFavourite_number, stats, "total_fav", "inc_fav");
                            setStatsText(R.id.totalShare_number, stats, "total_share", "inc_share");
                            setStatsText(R.id.totalReply_number, stats, "total_reply", "incr_reply");
                            setStatsText(R.id.totalDm_number, stats, "total_dm", "incr_dm");
                            TextView textBeUpTime = findViewById(R.id.beUpTime);
                            textBeUpTime.setText(beUPTime);
                        }
                    } catch (Exception e) {
                        runOnUiThread(() -> MsgUtil.err(e));
                    }

                    View scrollView = findViewById(R.id.scrollView);
                    scrollView.setFocusable(true);
                    scrollView.setFocusableInTouchMode(true);
                    scrollView.requestFocus();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    MsgUtil.err(e);
                    //失败态可见：此前页面停留布局里的假数据占位（X-31）
                    int[] ids = {R.id.totalFans_number, R.id.totalClick_number, R.id.totalLike_number,
                            R.id.totalCoin_number, R.id.totalFavourite_number, R.id.totalShare_number,
                            R.id.totalReply_number, R.id.totalDm_number};
                    for (int statsId : ids) {
                        TextView tv = findViewById(statsId);
                        if (tv != null) tv.setText("——");
                    }
                    TextView beUp = findViewById(R.id.beUpTime);
                    if (beUp != null) beUp.setText("数据加载失败，请退出重进");
                });
            }
        }));
    }

    @SuppressLint("SetTextI18n")
    private void setStatsText(int viewId, JSONObject jsonObject, String totalKey, String incrKey) throws JSONException {
        TextView textView = findViewById(viewId);
        int totalValue = jsonObject.getInt(totalKey);
        int incrValue = jsonObject.getInt(incrKey);

        String totalText = StringUtil.toWan(totalValue);
        String incrSymbol = (incrValue < 0) ? "" : "+";
        String incrText = StringUtil.toWan(incrValue);

        textView.setText(totalText + incrSymbol + incrText);
    }
}