package com.RobinNotBad.BiliClient.activity.user;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.api.VipApi;
import com.RobinNotBad.BiliClient.model.VipInfo;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.StringUtil;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class VipActivity extends BaseActivity {
    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        asyncInflate(R.layout.activity_vip, (view, id) -> CenterThreadPool.run(() -> {
            try {
                VipInfo vipInfo = VipApi.getVipInfo();

                runOnUiThread(() -> {
                    try {
                        TextView vipStatusText = findViewById(R.id.vipStatus);
                        TextView vipTypeText = findViewById(R.id.vipType);
                        TextView vipDueDateText = findViewById(R.id.vipDueDate);
                        TextView levelText = findViewById(R.id.level);
                        TextView expText = findViewById(R.id.exp);
                        TextView bindPhoneText = findViewById(R.id.bindPhone);

                        if (vipInfo.isVip) {
                            vipStatusText.setText("是");
                            if (vipInfo.vipIsAnnual) {
                                vipTypeText.setText("年度大会员");
                            } else if (vipInfo.vipIsMonth) {
                                vipTypeText.setText("月大会员");
                            } else {
                                vipTypeText.setText("大会员");
                            }
                            if (vipInfo.vipDueDate > 0) {
                                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                                vipDueDateText.setText(sdf.format(new Date(vipInfo.vipDueDate * 1000)));
                            } else {
                                vipDueDateText.setText("未知");
                            }
                        } else {
                            vipStatusText.setText("否");
                            vipTypeText.setText("无");
                            vipDueDateText.setText("无");
                        }

                        levelText.setText(String.valueOf(vipInfo.level));
                        if (vipInfo.nextExp == -1) {
                            expText.setText(StringUtil.toWan(vipInfo.curExp) + " (已满级)");
                        } else {
                            expText.setText(StringUtil.toWan(vipInfo.curExp) + " / " + StringUtil.toWan(vipInfo.nextExp));
                        }

                        if (!vipInfo.bindPhone.isEmpty()) {
                            bindPhoneText.setText(vipInfo.bindPhone);
                        } else {
                            bindPhoneText.setText("未绑定");
                        }

                        if (vipInfo.privilegeList != null && !vipInfo.privilegeList.isEmpty()) {
                            View privilegeSection = findViewById(R.id.privilegeSection);
                            privilegeSection.setVisibility(View.VISIBLE);
                            TextView privilegeListText = findViewById(R.id.privilegeList);
                            StringBuilder privilegeBuilder = new StringBuilder();
                            String[] privilegeNames = {"B币兑换", "会员购优惠券", "漫画福利券", "会员购包邮券", 
                                "漫画商城优惠券", "装扮体验卡", "课堂优惠券", "游戏礼盒", "每日10经验"};
                            for (VipInfo.Privilege privilege : vipInfo.privilegeList) {
                                if (privilege.type >= 1 && privilege.type <= 9) {
                                    String name = privilegeNames[privilege.type - 1];
                                    String state;
                                    if (privilege.state == 0) {
                                        state = "未兑换";
                                    } else if (privilege.state == 1) {
                                        state = "已兑换";
                                    } else {
                                        state = "未完成";
                                    }
                                    privilegeBuilder.append(name).append(": ").append(state).append("\n");
                                }
                            }
                            if (privilegeBuilder.length() > 0) {
                                privilegeListText.setText(privilegeBuilder.toString().trim());
                            }
                        }

                        Button experienceButton = findViewById(R.id.experienceButton);

                        experienceButton.setOnClickListener(v -> CenterThreadPool.run(() -> {
                            try {
                                JSONObject result = VipApi.addExperience();
                                int code = result.getInt("code");
                                String message = result.optString("message", "");
                                JSONObject data = result.optJSONObject("data");
                                runOnUiThread(() -> {
                                    if (code == 0 && data != null && data.optBoolean("is_grant", false)) {
                                        MsgUtil.showMsg("领取成功");
                                    } else if (code == 69198) {
                                        MsgUtil.showMsg("今日已领取");
                                    } else {
                                        MsgUtil.showMsg("领取失败: " + message);
                                    }
                                });
                            } catch (Exception e) {
                                runOnUiThread(() -> MsgUtil.err(e));
                            }
                        }));

                        View scrollView = findViewById(R.id.scrollView);
                        scrollView.setFocusable(true);
                        scrollView.setFocusableInTouchMode(true);
                        scrollView.requestFocus();
                    } catch (Exception e) {
                        runOnUiThread(() -> MsgUtil.err(e));
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> MsgUtil.err(e));
            }
        }));
    }
}

