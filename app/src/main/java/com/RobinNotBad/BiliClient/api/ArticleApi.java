package com.RobinNotBad.BiliClient.api;

import com.RobinNotBad.BiliClient.model.ArticleInfo;
import com.RobinNotBad.BiliClient.model.Opus;
import com.RobinNotBad.BiliClient.model.Stats;
import com.RobinNotBad.BiliClient.model.UserInfo;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Objects;
import java.util.TimeZone;

import okhttp3.Response;

/*
专栏API
API是自己扒的
 */
public class ArticleApi {
    /**
     * 获取专栏详情（正文为 HTML）。
     *
     * <p>字段一律用 {@code opt*} 读取并逐项兜底：本方法此前用 {@code get*}，缺字段直接抛
     * {@code JSONException}。旧调用链会把它吞掉，所以一直没暴露；现在它是专栏详情页的**主路径**，
     * 一个可选字段缺失就会让整篇专栏打不开，必须改成"缺什么就当空"。
     *
     * @param id cvid
     * @return 解析结果；接口明确失败（code!=0 或无 data）时返回 null，由调用方回退到网页抓取
     */
    public static ArticleInfo getArticle(long id) throws JSONException, IOException {
        String url = "https://api.bilibili.com/x/article/view?";
        url += "id=" + id + "&gaia_source=main_web&web_location=333.976";
        JSONObject result = NetWorkUtil.getJson(ConfInfoApi.signWBI(url));
        if (result.optInt("code", -1) != 0) return null;
        JSONObject data = result.optJSONObject("data");
        if (data == null) return null;

        ArticleInfo articleInfo = new ArticleInfo();
        articleInfo.id = id;
        articleInfo.title = data.optString("title", "");
        articleInfo.summary = data.optString("summary", "");
        articleInfo.banner = data.optString("banner_url", "");
        articleInfo.ctime = data.optLong("ctime", 0);
        articleInfo.content = data.optString("content", "");
        articleInfo.keywords = data.optString("keywords", "");
        articleInfo.wordCount = data.optInt("words", 0);

        JSONObject author = data.optJSONObject("author");
        if (author != null) {
            UserInfo upInfo = new UserInfo();
            upInfo.mid = author.optLong("mid", 0);
            upInfo.name = author.optString("name", "");
            upInfo.avatar = author.optString("face", "");
            upInfo.fans = author.optInt("fans", 0);
            upInfo.level = author.optInt("level", 0);
            articleInfo.upInfo = upInfo;
        }

        JSONObject jsonStats = data.optJSONObject("stats");
        if (jsonStats != null) {
            Stats stats = new Stats();
            stats.view = jsonStats.optInt("view", 0);
            stats.favorite = jsonStats.optInt("favorite", 0);
            stats.like = jsonStats.optInt("like", 0);
            stats.reply = jsonStats.optInt("reply", 0);
            stats.share = jsonStats.optInt("share", 0);
            stats.coin = jsonStats.optInt("coin", 0);
            //is_like 的形态不确定：旧实现按 boolean 读（getBoolean），而同类接口里也有回 0/1 的，
            //所以两种都认。读不出来时保持 false —— 那只是"未点赞"的初始态，用户点一下就会自校正
            Object isLike = data.opt("is_like");
            if (isLike instanceof Boolean) stats.liked = (Boolean) isLike;
            else if (isLike instanceof Number) stats.liked = ((Number) isLike).intValue() == 1;
            articleInfo.stats = stats;
        }
        return articleInfo;
    }

    /**
     * 另一个获取专栏相关信息api
     *
     * @param id cvid
     */
    public static ArticleInfo getArticleViewInfo(long id) throws JSONException, IOException {
        String url = "https://api.bilibili.com/x/article/viewinfo?";
        url += "id=" + id + "&gaia_source=main_web&web_location=333.976&mobi_app=pc&from=web";
        JSONObject result = NetWorkUtil.getJson(ConfInfoApi.signWBI(url));
        if (!result.has("data")) return null;
        JSONObject data = result.getJSONObject("data");

        ArticleInfo articleInfo = new ArticleInfo();
        articleInfo.id = id;
        articleInfo.title = data.getString("title");
        articleInfo.banner = data.getString("banner_url");

        UserInfo upInfo = new UserInfo();
        upInfo.mid = data.getLong("mid");
        upInfo.name = data.getString("author_name");
        articleInfo.upInfo = upInfo;

        JSONObject jsonStats = data.getJSONObject("stats");
        Stats stats = new Stats();
        stats.view = jsonStats.getInt("view");
        stats.favorite = jsonStats.getInt("favorite");
        stats.like = jsonStats.getInt("like");
        stats.reply = jsonStats.getInt("reply");
        stats.share = jsonStats.getInt("share");
        stats.coin = jsonStats.getInt("coin");
        stats.liked = data.getInt("like") == 1;
        stats.favoured = data.getBoolean("favorite");
        stats.coined = data.getInt("coin");
        articleInfo.stats = stats;
        return articleInfo;
    }

    /**
     * 专栏点赞
     *
     * @param cvid cvid
     * @param type true=点赞，false=取消赞
     * @return resultCode
     */
    public static int like(long cvid, boolean type) throws IOException {
        String url = "https://api.bilibili.com/x/article/like";
        Response resp = Objects.requireNonNull(NetWorkUtil.post(url, new NetWorkUtil.FormData()
                .put("id", cvid)
                .put("type", type ? 1 : 2)
                .put("csrf", SharedPreferencesUtil.getString("csrf", ""))
                .toString(), NetWorkUtil.webHeaders));
        try {
            assert resp.body() != null;
            JSONObject respBody = new JSONObject(resp.body().string());
            return respBody.getInt("code");
        } catch (JSONException ignored) {
            return -1;
        }
    }


    /**
     * 专栏投币
     *
     * @param cvid     CVID
     * @param upid     UP主ID
     * @param multiply 投币数量
     * @return 返回码
     */
    public static int addCoin(long cvid, long upid, int multiply) throws IOException {
        String url = "https://api.bilibili.com/x/web-interface/coin/add";
        Response resp = Objects.requireNonNull(NetWorkUtil.post(url, new NetWorkUtil.FormData()
                .put("aid", cvid)
                .put("upid", upid)
                .put("avtype", 2)
                .put("multiply", multiply)
                .put("csrf", SharedPreferencesUtil.getString("csrf", ""))
                .toString(), NetWorkUtil.webHeaders));
        try {
            assert resp.body() != null;
            JSONObject respBody = new JSONObject(resp.body().string());
            return respBody.getInt("code");
        } catch (JSONException ignored) {
            return -1;
        }
    }

    /**
     * 收藏专栏
     *
     * @param cvid CVID
     * @return 返回码
     */
    public static int favorite(long cvid) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/article/favorites/add";
        Response resp = Objects.requireNonNull(NetWorkUtil.post(url, new NetWorkUtil.FormData()
                .put("id", cvid)
                .put("csrf", SharedPreferencesUtil.getString("csrf", ""))
                .toString(), NetWorkUtil.webHeaders));
        assert resp.body() != null;
        JSONObject respBody = new JSONObject(resp.body().string());
        return respBody.getInt("code");
    }

    /**
     * 取消收藏专栏
     *
     * @param cvid CVID
     * @return 返回码
     */
    public static int delFavorite(long cvid) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/article/favorites/del";
        Response resp = Objects.requireNonNull(NetWorkUtil.post(url, new NetWorkUtil.FormData()
                .put("id", cvid)
                .put("csrf", SharedPreferencesUtil.getString("csrf", ""))
                .toString(), NetWorkUtil.webHeaders));
        assert resp.body() != null;
        JSONObject respBody = new JSONObject(resp.body().string());
        return respBody.getInt("code");
    }

    public static Opus opusId2cvid(long opusId) throws JSONException, IOException {
        String url = "https://api.bilibili.com/x/polymer/web-dynamic/v1/opus/detail?id=" + opusId + "&time_zone_offset=" + TimeZone.getDefault().getRawOffset() / 100000;
        JSONObject result = NetWorkUtil.getJson(url);
        if (result.getJSONObject("data").has("item"))
            return new Opus(Opus.TYPE_DYNAMIC, Long.parseLong(result.getJSONObject("data").getJSONObject("item").getJSONObject("basic").getString("rid_str")));
        else
            return new Opus(Opus.TYPE_ARTICLE, Long.parseLong(result.getJSONObject("data").getJSONObject("fallback").getString("id")));
    }
}
