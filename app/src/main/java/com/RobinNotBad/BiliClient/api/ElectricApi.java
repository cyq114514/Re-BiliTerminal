package com.RobinNotBad.BiliClient.api;

import com.RobinNotBad.BiliClient.model.ApiResponse;
import com.RobinNotBad.BiliClient.model.ElectricPanel;
import com.RobinNotBad.BiliClient.model.ElectricUser;
import com.RobinNotBad.BiliClient.util.GsonUtil;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.google.gson.annotations.SerializedName;

import org.json.JSONException;

import java.io.IOException;
import java.util.List;

public class ElectricApi {
    public static class ElectricPanelData {
        @SerializedName("count") public int count;
        @SerializedName("total_count") public int total_count;
        @SerializedName("total") public int total;
        @SerializedName("special_day") public int special_day;
        @SerializedName("list") public List<ElectricUserData> list;
    }
    public static class ElectricUserData {
        @SerializedName("uname") public String uname;
        @SerializedName("avatar") public String avatar;
        @SerializedName("mid") public long mid;
        @SerializedName("pay_mid") public long pay_mid;
        @SerializedName("rank") public int rank;
        @SerializedName("trend_type") public int trend_type;
        @SerializedName("message") public String message;
        @SerializedName("msg_hidden") public int msg_hidden;
        @SerializedName("vip_info") public VipInfoData vip_info;
    }
    public static class VipInfoData {
        @SerializedName("vipDueMsec") public long vipDueMsec;
        @SerializedName("vipStatus") public int vipStatus;
        @SerializedName("vipType") public int vipType;
    }

    public static ElectricPanel getElectricPanel(long up_mid) throws IOException, JSONException {
        String json = NetWorkUtil.getJson("https://api.bilibili.com/x/ugcpay-rank/elec/month/up?up_mid=" + up_mid, NetWorkUtil.webHeaders).toString();
        ApiResponse<ElectricPanelData> resp = GsonUtil.fromJson(json, new com.google.gson.reflect.TypeToken<ApiResponse<ElectricPanelData>>(){}.getType());
        if (resp == null || !resp.isSuccess() || resp.data == null) return null;
        ElectricPanelData d = resp.data;
        ElectricPanel panel = new ElectricPanel();
        panel.count = d.count; panel.total_count = d.total_count; panel.total = d.total; panel.special_day = d.special_day;
        if (d.list != null) {
            for (ElectricUserData u : d.list) {
                if (u == null) continue;
                ElectricUser user = new ElectricUser();
                user.uname = u.uname; user.avatar = u.avatar; user.mid = u.mid; user.pay_mid = u.pay_mid;
                user.rank = u.rank; user.trend_type = u.trend_type; user.message = u.message; user.msg_hidden = u.msg_hidden;
                if (u.vip_info != null) {
                    user.vip_info = new ElectricUser.VipInfo();
                    user.vip_info.vipDueMsec = u.vip_info.vipDueMsec; user.vip_info.vipStatus = u.vip_info.vipStatus; user.vip_info.vipType = u.vip_info.vipType;
                }
                panel.list.add(user);
            }
        }
        return panel;
    }
}
