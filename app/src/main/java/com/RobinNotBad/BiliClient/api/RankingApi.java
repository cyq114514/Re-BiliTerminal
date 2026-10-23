package com.RobinNotBad.BiliClient.api;

import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.StringUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;

/**
 * 全站排行榜。
 *
 * <p>接口 {@code /x/web-interface/ranking/v2} 的返回是
 * {@code {"code":0,"data":{"note":"...","list":[...]}}}——**data 是对象、列表在 data.list 里**，
 * 不是数组。原来按 {@code ApiResponse<List<RankingItem>>} 用 Gson 解析，data 对不上类型后直接变 null，
 * 于是整个页面永远是空的（"排行榜无法加载"）。这里改成直接按 JSONObject 取 data.list，
 * 并兼容 data 本身就是数组的旧形态。
 */
public class RankingApi {

    public static void getRanking(List<VideoCard> videoCardList, int rid, String type) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/web-interface/ranking/v2";
        url += new NetWorkUtil.FormData().setUrlParam(true)
                .put("rid", rid)
                .put("type", type)
                .put("web_location", "333.934");

        JSONObject root;
        try {
            root = NetWorkUtil.getJson(ConfInfoApi.signWBI(url));
        } catch (Exception e) {
            //签名链路自身抛异常（WBI 密钥没取到等）也别让整页失败，先退回无签名请求
            Logu.w("RankingApi", "带 WBI 的排行榜请求异常：" + e + "，改用无签名重试");
            root = NetWorkUtil.getJson(url);
        }
        int code = root.optInt("code", -1);
        if (code != 0) {
            //WBI 签名出问题（密钥轮换/风控）时退回无签名请求：排行榜接口对匿名也放行，
            //这样至少不会因为签名链挂掉整页空白
            Logu.w("RankingApi", "带 WBI 的排行榜请求失败 code=" + code + "，改用无签名重试");
            root = NetWorkUtil.getJson(url);
            code = root.optInt("code", -1);
        }
        if (code != 0) {
            String msg = root.optString("message", "");
            Logu.w("RankingApi", "ranking/v2 code=" + code + " msg=" + msg);
            throw new JSONException("排行榜接口返回 code=" + code + (msg.isEmpty() ? "" : "：" + msg));
        }

        JSONArray list = null;
        JSONObject data = root.optJSONObject("data");
        if (data != null) list = data.optJSONArray("list");
        else list = root.optJSONArray("data");   //兼容旧形态：data 直接是数组
        if (list == null) {
            Logu.w("RankingApi", "ranking/v2 返回里没有 list，data=" + (data == null ? "null" : data.toString()));
            throw new JSONException("排行榜接口返回格式变了：没有 data.list");
        }

        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) continue;
            JSONObject owner = item.optJSONObject("owner");
            JSONObject stat = item.optJSONObject("stat");
            //v2 用 owner.name / stat.view；个别形态（老接口/分区榜）用 author / 无 stat，取到哪个算哪个
            String upName = owner != null ? owner.optString("name", "") : item.optString("author", "");
            int viewCount = stat != null ? stat.optInt("view", 0) : 0;
            String viewStr = viewCount > 0 ? StringUtil.toWan(viewCount) + "观看" : "";
            videoCardList.add(new VideoCard(item.optString("title", ""), upName, viewStr,
                    item.optString("pic", ""), item.optLong("aid", 0), item.optString("bvid", "")));
        }
    }
}
