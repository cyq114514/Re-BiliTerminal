package com.RobinNotBad.BiliClient.activity.settings.login;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.SplashActivity;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.api.UserInfoApi;
import com.RobinNotBad.BiliClient.model.UserInfo;
import com.RobinNotBad.BiliClient.util.AccountManager;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.GlideUtil;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.google.android.material.card.MaterialCardView;

import java.util.List;

public class AccountSwitchActivity extends BaseActivity {

    private LinearLayout accountList;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account_switch);

        setTopbarExit();

        accountList = findViewById(R.id.accountList);

        findViewById(R.id.scrollView).requestFocus();

        refreshAccountList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAccountList();
    }

    private void refreshAccountList() {
        accountList.removeAllViews();
        List<AccountManager.AccountInfo> accounts = AccountManager.getAccounts();

        if (accounts.isEmpty()) {
            addEmptyHint();
            return;
        }

        for (AccountManager.AccountInfo account : accounts) {
            accountList.addView(createAccountCard(account));
        }

        addNewAccountCard();
    }

    private void addEmptyHint() {
        MaterialCardView card = new MaterialCardView(this);
        card.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView text = new TextView(this);
        text.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        text.setGravity(Gravity.CENTER);
        text.setPadding(32, 48, 32, 48);
        text.setText("暂无已保存的账号\n登录后会自动保存账号凭证");
        text.setTextSize(13);
        card.addView(text);

        accountList.addView(card);
        addNewAccountCard();
    }

    private View createAccountCard(AccountManager.AccountInfo account) {
        View view = LayoutInflater.from(this).inflate(R.layout.item_account, accountList, false);

        boolean isCurrent = AccountManager.isCurrentAccount(account.mid);

        ImageView avatarView = view.findViewById(R.id.account_avatar);
        TextView nameView = view.findViewById(R.id.account_name);
        TextView uidView = view.findViewById(R.id.account_uid);

        uidView.setText("UID: " + account.mid);

        String savedName = account.name;
        String savedAvatar = account.avatar;
        String currentSuffix = isCurrent ? "（当前）" : "";

        if (!savedAvatar.isEmpty() && !savedName.isEmpty()) {
            nameView.setText(savedName + currentSuffix);
            GlideUtil.requestRound(avatarView, savedAvatar, R.mipmap.akari);
        } else {
            nameView.setText("UID:" + account.mid + currentSuffix);

            CenterThreadPool.run(() -> {
                try {
                    UserInfo userInfo = UserInfoApi.getUserInfo(account.mid);
                    if (userInfo != null) {
                        runOnUiThread(() -> {
                            //网络请求返回时页面可能已被关闭，Glide 对销毁的 Activity 加载会抛异常
                            if (isDestroyed() || isFinishing()) return;
                            nameView.setText(userInfo.name + currentSuffix);
                            if (userInfo.avatar != null && !userInfo.avatar.isEmpty()) {
                                GlideUtil.requestRound(avatarView, userInfo.avatar, R.mipmap.akari);
                            }
                        });
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        MaterialCardView card = (MaterialCardView) view;
        card.setOnClickListener(v -> {
            if (isCurrent) {
                MsgUtil.showMsg("这是当前登录的账号");
            } else {
                AccountManager.switchToAccount(account);
                String displayName = savedName.isEmpty() ? "UID:" + account.mid : savedName;
                MsgUtil.showMsg("已切换至 " + displayName);
                Intent intent = new Intent(this, SplashActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                finish();
            }
        });

        card.setOnLongClickListener(v -> {
            if (accountsCanBeRemoved()) {
                String displayName = savedName.isEmpty() ? "UID:" + account.mid : savedName;
                new AlertDialog.Builder(this)
                        .setTitle("删除账号")
                        .setMessage("确定要删除账号 " + displayName + " 吗？\n删除后需重新登录才能恢复。")
                        .setPositiveButton("删除", (dialog, which) -> {
                            AccountManager.removeAccount(account.mid);
                            MsgUtil.showMsg("已删除账号");
                            refreshAccountList();
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
            return true;
        });

        return view;
    }

    private boolean accountsCanBeRemoved() {
        return AccountManager.getAccountCount() > 1
                || SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) == 0L;
    }

    private void addNewAccountCard() {
        MaterialCardView card = new MaterialCardView(this);
        card.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView text = new TextView(this);
        text.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (35 * getResources().getDisplayMetrics().density)));
        text.setPadding(16, 0, 16, 0);
        text.setGravity(Gravity.CENTER_VERTICAL);
        text.setText("添加新账号");
        text.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
        text.setTextSize(13);
        text.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.arrow_forward, 0);
        card.addView(text);

        card.setOnClickListener(v -> {
            Intent intent = new Intent(this, LoginActivity.class);
            startActivity(intent);
        });

        accountList.addView(card);
    }
}
