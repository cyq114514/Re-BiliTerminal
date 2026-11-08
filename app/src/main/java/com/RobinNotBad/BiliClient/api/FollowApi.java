package com.RobinNotBad.BiliClient.api;

import com.RobinNotBad.BiliClient.model.ApiResponse;
import com.RobinNotBad.BiliClient.model.FollowTag;
import com.RobinNotBad.BiliClient.model.UserInfo;
import com.RobinNotBad.BiliClient.util.GsonUtil;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.google.gson.annotations.SerializedName;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class FollowApi {

    public static class FollowListData {
        @SerializedName("list")
        public List<FollowItem> list;
    }

    public static class FollowItem {
        @SerializedName("mid") public long mid;
        @SerializedName("uname") public String uname;
        @SerializedName("face") public String face;
        @SerializedName("sign") public String sign;
        @SerializedName("mtime") public long mtime;
        //关系属性：0=无 1=悄悄关注 2=已关注 6=互相关注 128=特别关注（响应里本来就有，此前被丢弃）
        @SerializedName("attribute") public int attribute;
    }

    public static class TagItem {
        @SerializedName("tagid") public int tagid;
        @SerializedName("name") public String name;
        @SerializedName("count") public int count;
    }


    /**统一把非 0 code 转成带真实 code/message 的异常：MsgUtil.err 按串里的关键词补
     * 「未登录/风控」提示，压成一句"解析失败"会让 token 过期和风控无从区分（1.3.3 反馈）*/
    private static JSONException fail(String what, ApiResponse<?> resp) {
        if (resp == null) return new JSONException(what + "失败（响应为空）");
        return new JSONException(what + "失败（code=" + resp.code
                + (resp.message != null && !resp.message.isEmpty() ? " " + resp.message : "") + "）");
    }
    public static int getFollowingList(long mid, int page, List<UserInfo> userList) throws IOException, JSONException {
        String json = NetWorkUtil.getJson("https://api.bilibili.com/x/relation/followings?vmid=" + mid + "&pn=" + page + "&ps=20&order=desc&order_type=attention").toString();
        ApiResponse<FollowListData> resp = GsonUtil.fromJson(json, new com.google.gson.reflect.TypeToken<ApiResponse<FollowListData>>(){}.getType());
        if (resp == null || !resp.isSuccess()) throw fail("获取关注列表", resp);
        if (resp.data == null || resp.data.list == null || resp.data.list.isEmpty()) return 1;
        for (FollowItem item : resp.data.list) {
            if (item == null) continue;
            UserInfo user = new UserInfo(item.mid, item.uname, item.face, item.sign, 0, 0, 0, true, "", 0, "", item.mtime, 0);
            user.mutualFollow = item.attribute == 6;
            userList.add(user);
        }
        return 0;
    }

    public static int getFollowerList(long mid, int page, List<UserInfo> userList) throws IOException, JSONException {
        String json = NetWorkUtil.getJson("https://api.bilibili.com/x/relation/followers?vmid=" + mid + "&pn=" + page + "&ps=20&order=desc&order_type=attention").toString();
        ApiResponse<FollowListData> resp = GsonUtil.fromJson(json, new com.google.gson.reflect.TypeToken<ApiResponse<FollowListData>>(){}.getType());
        if (resp == null || !resp.isSuccess()) throw fail("获取粉丝列表", resp);
        if (resp.data == null || resp.data.list == null || resp.data.list.isEmpty()) return 1;
        for (FollowItem item : resp.data.list) {
            if (item == null) continue;
            UserInfo user = new UserInfo(item.mid, item.uname, item.face, item.sign, 0, 0, 0, true, "", 0, "", item.mtime, 0);
            user.mutualFollow = item.attribute == 6;
            userList.add(user);
        }
        return 0;
    }

    public static List<FollowTag> getFollowTags() throws IOException, JSONException {
        String json = NetWorkUtil.getJson("https://api.bilibili.com/x/relation/tags").toString();
        ApiResponse<List<TagItem>> resp = GsonUtil.fromJson(json, new com.google.gson.reflect.TypeToken<ApiResponse<List<TagItem>>>(){}.getType());
        List<FollowTag> tagList = new ArrayList<>();
        if (resp == null || !resp.isSuccess() || resp.data == null) return tagList;
        for (TagItem item : resp.data) {
            if (item != null) tagList.add(new FollowTag(item.tagid, item.name, item.count));
        }
        return tagList;
    }

    public static int getFollowTagUsers(int tagid, int page, List<UserInfo> userList) throws IOException, JSONException {
        String json = NetWorkUtil.getJson("https://api.bilibili.com/x/relation/tag?tagid=" + tagid + "&pn=" + page + "&ps=20").toString();
        ApiResponse<List<FollowItem>> resp = GsonUtil.fromJson(json, new com.google.gson.reflect.TypeToken<ApiResponse<List<FollowItem>>>(){}.getType());
        if (resp == null || !resp.isSuccess()) throw new JSONException("获取分组用户失败");
        if (resp.data == null || resp.data.isEmpty()) return 1;
        for (FollowItem item : resp.data) {
            if (item == null) continue;
            UserInfo user = new UserInfo(item.mid, item.uname, item.face, item.sign, 0, 0, 0, true, "", 0, "", 0);
            //分组接口（x/relation/tag）与关注/粉丝列表不同，响应里不带 attribute，
            //这里恒为 0，互关徽章会全灭。真正的互关状态由 fillMutualFollow 批量补（见下）
            user.mutualFollow = item.attribute == 6;
            userList.add(user);
        }
        return 0;
    }

    /**
     * 批量补齐互关状态（分组模式专用）。
     *
     * <p>{@code x/relation/tag} 返回的条目没有 {@code attribute} 字段，按它判断互关永远是 false，
     * 于是「我的关注 → 分组」下互关徽章全部消失。这里改用 {@code x/relation/relations}
     * 一次查一批（fids 逗号分隔，响应是 key=mid 的 map，attribute==6 即互关）。
     *
     * <p>失败/未登录时静默保持原值（与改之前表现一致），不阻断分组加载。
     */
    public static void fillMutualFollow(List<UserInfo> users) {
        if (users == null || users.isEmpty()) return;
        StringBuilder fids = new StringBuilder();
        for (UserInfo user : users) {
            if (user == null || user.mid <= 0) continue;
            if (fids.length() > 0) fids.append(',');
            fids.append(user.mid);
        }
        if (fids.length() == 0) return;
        try {
            String json = NetWorkUtil.getJson("https://api.bilibili.com/x/relation/relations?fids=" + fids).toString();
            org.json.JSONObject data = new org.json.JSONObject(json).optJSONObject("data");
            if (data == null) return;
            for (UserInfo user : users) {
                if (user == null) continue;
                org.json.JSONObject rel = data.optJSONObject(String.valueOf(user.mid));
                if (rel != null) user.mutualFollow = rel.optInt("attribute", 0) == 6;
            }
        } catch (Throwable e) {
            Logu.w("follow", "批量查询互关失败：" + e.getMessage());
        }
    }
}
