package com.RobinNotBad.BiliClient.util;

import android.content.SharedPreferences;

import com.RobinNotBad.BiliClient.api.UserInfoApi;
import com.RobinNotBad.BiliClient.model.UserInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 多账号管理：登录成功后把当前登录态快照进 account_list（SP 内 JSON 数组，明文，与 cookies 同级），
 * 切换账号时把所选快照覆写回全局登录态（mid/cookies/csrf/refresh_token）。
 * 快照的 cookie 依赖登录与 Cookie 自动刷新的钩子回写，否则切回时可能已过期。
 */
public class AccountManager {

    private static final String ACCOUNT_LIST_KEY = "account_list";
    private static final String CURRENT_ACCOUNT_KEY = "current_account_mid";
    private static final Object lock = new Object();

    public static class AccountInfo {
        public long mid;
        public String name;
        public String cookies;
        public String refreshToken;
        public String accessKey;
        public String csrf;
        public String avatar;

        public AccountInfo(long mid, String name, String cookies, String refreshToken, String accessKey, String csrf, String avatar) {
            this.mid = mid;
            this.name = name != null ? name : "";
            this.cookies = cookies != null ? cookies : "";
            this.refreshToken = refreshToken != null ? refreshToken : "";
            this.accessKey = accessKey != null ? accessKey : "";
            this.csrf = csrf != null ? csrf : "";
            this.avatar = avatar != null ? avatar : "";
        }

        public static AccountInfo fromJson(JSONObject json) throws JSONException {
            if (json == null) return null;
            return new AccountInfo(
                    json.optLong("mid", 0),
                    json.optString("name", ""),
                    json.optString("cookies", ""),
                    json.optString("refresh_token", ""),
                    json.optString("access_key", ""),
                    json.optString("csrf", ""),
                    json.optString("avatar", "")
            );
        }

        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("mid", mid);
            json.put("name", name);
            json.put("cookies", cookies);
            json.put("refresh_token", refreshToken);
            json.put("access_key", accessKey);
            json.put("csrf", csrf);
            json.put("avatar", avatar);
            return json;
        }

        public boolean isValid() {
            return mid > 0 && cookies != null && !cookies.isEmpty();
        }
    }

    public static List<AccountInfo> getAccounts() {
        synchronized (lock) {
            List<AccountInfo> accounts = new ArrayList<>();
            String jsonStr = SharedPreferencesUtil.getString(ACCOUNT_LIST_KEY, "");
            if (jsonStr.isEmpty()) return accounts;
            try {
                JSONArray array = new JSONArray(jsonStr);
                for (int i = 0; i < array.length(); i++) {
                    AccountInfo info = AccountInfo.fromJson(array.getJSONObject(i));
                    if (info != null && info.isValid()) {
                        accounts.add(info);
                    }
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return accounts;
        }
    }

    private static void saveAccounts(List<AccountInfo> accounts) {
        synchronized (lock) {
            JSONArray array = new JSONArray();
            for (AccountInfo account : accounts) {
                if (account != null && account.isValid()) {
                    try {
                        array.put(account.toJson());
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
            }
            SharedPreferencesUtil.putString(ACCOUNT_LIST_KEY, array.toString());
        }
    }

    /**把当前登录态快照进账号列表。内部有网络请求（拉昵称头像），必须在后台线程调用。*/
    public static void saveCurrentAccount() {
        long mid = SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0);
        if (mid == 0) return;

        String cookies = SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "");
        if (cookies.isEmpty()) return;

        String refreshToken = SharedPreferencesUtil.getString(SharedPreferencesUtil.refresh_token, "");
        String accessKey = SharedPreferencesUtil.getString(SharedPreferencesUtil.access_key, "");
        String csrf = SharedPreferencesUtil.getString(SharedPreferencesUtil.csrf, "");

        String avatar = "";
        String name = "UID:" + mid;

        try {
            UserInfo userInfo = UserInfoApi.getUserInfo(mid);
            if (userInfo != null) {
                avatar = userInfo.avatar;
                name = userInfo.name;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        synchronized (lock) {
            List<AccountInfo> accounts = getAccounts();
            boolean found = false;
            for (int i = 0; i < accounts.size(); i++) {
                if (accounts.get(i).mid == mid) {
                    accounts.get(i).cookies = cookies;
                    accounts.get(i).refreshToken = refreshToken;
                    accounts.get(i).accessKey = accessKey;
                    accounts.get(i).csrf = csrf;
                    accounts.get(i).avatar = avatar;
                    accounts.get(i).name = name;
                    found = true;
                    break;
                }
            }
            if (!found) {
                accounts.add(new AccountInfo(mid, name, cookies, refreshToken, accessKey, csrf, avatar));
            }
            saveAccounts(accounts);
            SharedPreferencesUtil.putLong(CURRENT_ACCOUNT_KEY, mid);
        }
    }

    public static AccountInfo getAccountByMid(long mid) {
        synchronized (lock) {
            List<AccountInfo> accounts = getAccounts();
            for (AccountInfo account : accounts) {
                if (account.mid == mid) {
                    return account;
                }
            }
            return null;
        }
    }

    /**覆写全局登录态为所选账号。调用方随后应重启到 Splash 以完成请求头等初始化。*/
    public static void switchToAccount(AccountInfo account) {
        if (account == null || !account.isValid()) return;

        synchronized (lock) {
            SharedPreferences.Editor editor = SharedPreferencesUtil.sharedPreferences.edit();
            editor.putLong(SharedPreferencesUtil.mid, account.mid);
            editor.putString(SharedPreferencesUtil.cookies, account.cookies);
            editor.putString(SharedPreferencesUtil.refresh_token, account.refreshToken);
            editor.putString(SharedPreferencesUtil.access_key, account.accessKey);
            editor.putString(SharedPreferencesUtil.csrf, account.csrf);
            editor.putLong(CURRENT_ACCOUNT_KEY, account.mid);
            editor.putBoolean(SharedPreferencesUtil.cookie_refresh, true);
            editor.putBoolean(SharedPreferencesUtil.setup, true);
            editor.apply();
            //setCookies 会按新 cookie 重建请求头（纯内存操作），切换立即对后续请求生效；
            //Splash 启动后还会调用 refreshHeaders() 补齐设备 Cookie
            NetWorkUtil.setCookies(new Cookies(account.cookies));
        }
    }

    public static void removeAccount(long mid) {
        synchronized (lock) {
            List<AccountInfo> accounts = getAccounts();
            for (int i = accounts.size() - 1; i >= 0; i--) {
                if (accounts.get(i).mid == mid) {
                    accounts.remove(i);
                }
            }
            saveAccounts(accounts);

            long currentMid = SharedPreferencesUtil.getLong(CURRENT_ACCOUNT_KEY, 0);
            if (currentMid == mid) {
                SharedPreferencesUtil.putLong(CURRENT_ACCOUNT_KEY, 0);
            }
        }
    }

    public static boolean isCurrentAccount(long mid) {
        long currentMid = SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0);
        return currentMid == mid;
    }

    public static long getCurrentMid() {
        return SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0);
    }

    public static boolean hasAccounts() {
        return !getAccounts().isEmpty();
    }

    public static int getAccountCount() {
        return getAccounts().size();
    }
}
