package com.RobinNotBad.BiliClient.api;

import android.util.Log;

import com.RobinNotBad.BiliClient.model.Opus;
import com.RobinNotBad.BiliClient.model.OpusParagraph;
import com.RobinNotBad.BiliClient.model.Stats;
import com.RobinNotBad.BiliClient.model.UserInfo;
import com.RobinNotBad.BiliClient.util.JsonUtil;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.RobinNotBad.BiliClient.model.ArticleInfo;
import com.RobinNotBad.BiliClient.util.ArticleHtmlParser;
import com.RobinNotBad.BiliClient.util.EmoteUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;

import okhttp3.HttpUrl;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class OpusApi {

    public static Opus getOpus(long id) throws IOException, JSONException {
        Opus opus = new Opus();
        opus.type = Opus.TYPE_DYNAMIC;
        opus.id = id;

        if (id > 100000000) {
            // 动态：优先走 opus/detail 接口（单次API请求）。
            // 之前是抓取 www.bilibili.com/opus/{id} 的SSR整页HTML再抠JSON，
            // 页面体积大、还有多级重定向，是图文动态详情打开慢（2~3秒）的元凶。
            try {
                if (fetchOpusFromApi(opus, id)) return finishOpus(opus);
                // 接口明确没有opus数据（纯文字等旧样式动态）：
                // 让OpusInfoActivity跳转旧版DynamicInfoActivity，走dynamic/detail接口
                opus.type = Opus.TYPE_DYNAMIC_OLD_STYLE;
                return opus;
            } catch (Exception apiError) {
                // 接口异常（网络/WBI失败等）时退回网页抓取兜底，保证可用性
                Logu.w("OpusApi", "opus/detail failed, fallback to html scrape: " + apiError.getMessage());
            }
        }

        //专栏（cv）：优先走 /x/article/view 单次 API 直取（PiliPlus 的 articleView 口径）。
        //
        //老路是抓 www.bilibili.com/read/cv{id} 的整页 SSR HTML 再手抠 JSON：页面体积大、
        //还有多级 301 要循环跟跳，而且 HTML 响应会命中 NetWorkUtil 对 doctype 的重试判定，
        //一份正文要付「整页下载 + 最多 5 次重试」的代价——这是专栏打开慢的元凶，
        //与图文动态修复前是同一条路（详见 docs/更新日志.md 里"cv 专栏路径不变"那句）。
        //
        //失败（接口报错/正文转不出段落）则原样落到下面的网页抓取兜底，最坏情况不比改造前差，
        //与动态那条「API 优先、HTML 兜底」的口径一致。
        if (id <= 100000000) {
            try {
                if (fetchArticleFromApi(opus, id)) return finishOpus(opus);
            } catch (Exception articleError) {
                Logu.w("OpusApi", "x/article/view failed, fallback to html scrape: " + articleError.getMessage());
            }
        }

        String url;
        if (id > 100000000)
            url = "https://www.bilibili.com/opus/" + id; // 别问，问就是动态和专栏都被统一了，判断不了类型，只能判断id长度了。能用。
        else url = "https://www.bilibili.com/read/cv" + id;
        try {
            Response response = NetWorkUtil.get(url);
            // /read/cv{id} 有多层301重定向（加斜杠、跳转到/opus/），循环跟随直到拿到最终页面。
            // Location 可能是 //开头的协议相对地址（Request.Builder.url 会直接抛
            // IllegalArgumentException），用 HttpUrl.resolve 基于当前 URL 解析出绝对地址；
            // 这里每次 get 都带完整登录 Cookie，跳转目标必须过 B 站域名白名单，绝不把凭据跟到任意域
            for (int i = 0; i < 5; i++) {
                String location = response.header("Location");
                if (location == null || location.isEmpty()) break;
                HttpUrl current = HttpUrl.parse(response.request().url().toString());
                HttpUrl target = current != null ? current.resolve(location) : null;
                response.close();
                //isHttps 校验不能省：network_security_config 对 B 站域放行了明文 http，
                //服务端 302 到 http://www.bilibili.com 时会把带 Cookie 的请求降级到明文
                if (target == null || !target.isHttps() || !NetWorkUtil.isBilibiliHost(target.host())) {
                    return opus;    //非 https 的 B 站域跳转或外站跳转都不带凭据跟进，放弃抓取
                }
                response = NetWorkUtil.get(target.toString());
            }
            ResponseBody responseBody = response.body();
            if (responseBody == null) return opus;

            String html = responseBody.string();

            String detailStr = JsonUtil.search(html, "detail", "");
            if (detailStr.isEmpty()) return opus;
            JSONObject detail = new JSONObject(detailStr);

            JSONObject basic = detail.optJSONObject("basic");
            if (basic != null) {
                opus.commentId = Long.parseLong(basic.optString("comment_id_str", "0"));
                opus.commentType = basic.optInt("comment_type", 0);
            }

            if (opus.commentId == 0) opus.commentId = id;
            if (opus.commentType == 0) opus.commentType = 17;

            if (detail.isNull("modules")) return opus;    //isNull其实涵盖了!has的情况，之前都是咋想的判断两次，我简直是sb
            parseOpusModules(opus, detail.getJSONArray("modules"));

            return finishOpus(opus);
        } catch (IllegalArgumentException e) { // 取不出来的时候，会重定向，但重定向的域名是//开头的，会报错
            //这里给opus设置一个参数，让OpusInfoActivity跳转到旧版的DynamicInfoActivity，从而无需重写解析
            //判断方式很简单粗暴，看报错信息里有没有URL这个关键字，有就是跳转错误
            String errMsg = e.getMessage();
            if (errMsg != null && errMsg.contains("URL")) opus.type = Opus.TYPE_DYNAMIC_OLD_STYLE;
            else MsgUtil.err(e);
            return opus;
        }
    }

    /**模块数组解析（opus/detail接口与网页内嵌detail同构），供API与HTML两条路径共用*/
    private static void parseOpusModules(Opus opus, JSONArray modules) throws JSONException {
        for (int i = 0; i < modules.length(); i++) {
            JSONObject module = modules.getJSONObject(i);
            switch (module.optString("module_type")) {
                case "MODULE_TYPE_TITLE":
                    JSONObject moduleTitle = module.optJSONObject("module_title");
                    if (moduleTitle != null) opus.title = moduleTitle.optString("text", "");
                    break;
                case "MODULE_TYPE_TOP":
                    ArrayList<String> topImages = new ArrayList<>();
                    JSONObject module_top = module.optJSONObject("module_top");
                    if (module_top != null) {
                        JSONObject display = module_top.optJSONObject("display");
                        if (display != null) {
                            int displayType = display.optInt("type");
                            if (displayType == 1) {
                                JSONObject album = display.optJSONObject("album");
                                if (album != null) {
                                    JSONArray pics = album.optJSONArray("pics");
                                    if (pics != null) {
                                        for (int j = 0; j < pics.length(); j++) {
                                            JSONObject pic = pics.optJSONObject(j);
                                            if (pic != null) topImages.add(pic.optString("url", ""));
                                        }
                                    }
                                }
                            }
                        }
                    }
                    opus.topImages = topImages;
                    Logu.d("yes");
                    break;
                case "MODULE_TYPE_AUTHOR":
                    JSONObject module_author = module.optJSONObject("module_author");    //我感觉b站也是一个巨大的草台班子，用户信息格式都好几种，头像有avatar有face有head的，他们自己的程序员不累吗……
                    if (module_author == null) break;
                    UserInfo author = new UserInfo();
                    author.mid = module_author.optLong("mid", 0);
                    author.name = module_author.optString("name", "");
                    author.followed = module_author.optBoolean("following", false);
                    author.avatar = module_author.optString("face", module_author.optString("avatar", ""));
                    if (!module_author.isNull("vip"))
                        author.vip_nickname_color = module_author.optJSONObject("vip").optString("nickname_color", "");

                    opus.pubTime = module_author.optString("pub_time", "");
                    opus.upInfo = author;
                    break;
                case "MODULE_TYPE_CONTENT":
                    JSONObject moduleContent = module.optJSONObject("module_content");
                    if (moduleContent != null) {
                        JSONArray paragraphs = moduleContent.optJSONArray("paragraphs");
                        if (paragraphs != null) {
                            //整篇正文的表情加载共用一个时间预算：replaceSingle 是串行同步取图的，
                            //k 个未缓存表情的最坏耗时是 k × 单个超时（3s），弱网下几十秒都转不出来。
                            //预算用尽后保留文本、不再等网络——代价从"整篇卡住"降为"少数表情没变成图"
                            EmoteUtil.beginBatch();
                            try {
                                opus.paragraphs = analyzeParagraphs(paragraphs);
                            } finally {
                                EmoteUtil.endBatch();
                            }
                        }
                    }
                    break;
                case "MODULE_TYPE_STAT":
                    opus.stats = Stats.fromOpus(module.optJSONObject("module_stat"));
                    break;
            }
        }
    }

    /**
     * 通过 /x/polymer/web-dynamic/v1/opus/detail 接口获取图文详情（需WBI签名，features=htmlNewStyle）。
     * @return true=成功解析；false=接口无opus数据（调用方转旧样式流程）
     */
    private static boolean fetchOpusFromApi(Opus opus, long id) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/polymer/web-dynamic/v1/opus/detail?timezone_offset=-480&features=htmlNewStyle&id=" + id;
        JSONObject root = NetWorkUtil.getJson(ConfInfoApi.signWBI(url));
        if (root.optInt("code", -1) != 0) return false;
        JSONObject data = root.optJSONObject("data");
        if (data == null) return false;
        //fallback.id非空说明该内容实为旧版载体，走重定向流程
        JSONObject fallback = data.optJSONObject("fallback");
        if (fallback != null && fallback.optLong("id", 0) > 0) return false;
        JSONObject item = data.optJSONObject("item");
        if (item == null) return false;

        JSONObject basic = item.optJSONObject("basic");
        if (basic != null) {
            opus.commentId = Long.parseLong(basic.optString("comment_id_str", "0"));
            opus.commentType = basic.optInt("comment_type", 0);
        }
        if (opus.commentId == 0) opus.commentId = id;
        if (opus.commentType == 0) opus.commentType = 17;
        if (item.isNull("modules")) return false;
        parseOpusModules(opus, item.getJSONArray("modules"));
        return true;
    }

    /**
     * 通过 {@code /x/article/view} 获取专栏详情（PiliPlus 的 articleView 口径）。
     *
     * <p>返回 false 表示"这个接口给不出可用正文"（已失效专栏、正文为空、或 HTML 转不出段落），
     * 由调用方回退到网页抓取——**回退是这条路径的安全边界，不是可选项**。
     *
     * <p>注意这里**不动 {@code opus.type}**：改造前 cv 专栏经网页抓取路径出来时 type 就是
     * {@code TYPE_DYNAMIC}（{@code getOpus} 开头的赋值，网页路径从不改它），
     * 详情页的行为是按这个值定的，擅自改成 {@code TYPE_ARTICLE} 会引入与本次目标无关的回归。
     *
     * @param cvid 专栏号（cv 号）
     */
    private static boolean fetchArticleFromApi(Opus opus, long cvid) throws IOException, JSONException {
        ArticleInfo article = ArticleApi.getArticle(cvid);
        if (article == null) return false;

        OpusParagraph[] paragraphs = ArticleHtmlParser.parse(article.content);
        //转不出正文就当失败：宁可回退多花一秒，也不要给用户一个空白专栏页
        if (paragraphs.length == 0) return false;

        opus.id = cvid;
        opus.title = article.title != null ? article.title : "";
        opus.upInfo = article.upInfo;
        opus.stats = article.stats;
        opus.paragraphs = paragraphs;
        //专栏的评论区挂在 cvid 上（comment_type=12 即专栏这一类）
        opus.commentId = cvid;
        opus.commentType = 12;
        Logu.d("OpusApi", "专栏走 API 直取：cvid=" + cvid + " 段落数=" + paragraphs.length);
        return true;
    }

    private static Opus finishOpus(Opus opus) {
        if (opus.upInfo == null) opus.upInfo = new UserInfo();
        if (opus.stats == null) opus.stats = new Stats();
        // B站是会做图文的
        opus.cover = "";
        return opus;
    }

    public static OpusParagraph[] analyzeParagraphs(JSONArray jsonArray) throws JSONException {
        OpusParagraph[] paragraphs = new OpusParagraph[jsonArray.length()];
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONObject paragraphJson = jsonArray.getJSONObject(i);
            OpusParagraph paragraph = new OpusParagraph(paragraphJson);
            paragraphs[i] = paragraph;
            //链接 span 的 4 个正则在这里（后台线程）算一次并缓存进段落，
            //避免每段落每次绑定都在主线程重算（原来 OpusContentAdapter 每次 bind 都跑 setLink）
            paragraph.linkedContent();
        }
        return paragraphs;
    }

    /**
     * Opus/动态点赞
     *
     * @param dynId 动态id
     * @param up    true=点赞，false=取消赞
     * @return resultCode
     */
    public static int likeOpus(long dynId, boolean up) throws IOException {
        String csrf = NetWorkUtil.getLoginCsrf();
        String url = "https://api.bilibili.com/x/dynamic/feed/dyn/thumb?csrf=" + csrf;
        JSONObject payload = new JSONObject();
        try {
            payload.put("dyn_id_str", String.valueOf(dynId));
            payload.put("up", up ? 1 : 2);
            payload.put("csrf", csrf);
        } catch (JSONException ignored) {
            return -1;
        }
        Response resp = Objects.requireNonNull(NetWorkUtil.postJson(url, payload.toString(), NetWorkUtil.webHeaders));
        try {
            assert resp.body() != null;
            JSONObject respBody = new JSONObject(resp.body().string());
            return respBody.getInt("code");
        } catch (JSONException ignored) {
            return -1;
        }
    }

    public static void analyzeOldStyleDynamic(Opus opus, JSONObject item) throws JSONException {
        JSONObject basic = item.getJSONObject("basic");
        opus.commentId = Long.parseLong(basic.optString("comment_id_str", "0"));
        opus.commentType = basic.optInt("comment_type");

        String dynamicType = item.getString("type");

        if (item.isNull("modules")) return;
        JSONObject modules = item.getJSONObject("modules");

        //up主信息
        UserInfo author = new UserInfo();
        if (!modules.isNull("module_author")) {
            JSONObject module_author = modules.getJSONObject("module_author");
            author.mid = module_author.getLong("mid");
            author.name = module_author.getString("name");
            author.followed = module_author.optBoolean("following", false);
            author.avatar = module_author.getString("face");
            if (!module_author.isNull("vip"))
                author.vip_nickname_color = module_author.getJSONObject("vip").optString("nickname_color", "");
            opus.pubTime = module_author.getString("pub_time");
        }
        opus.upInfo = author;

        if (dynamicType.equals("DYNAMIC_TYPE_NONE")) {
            opus.content = "[动态不存在]";
            return;
        }

        //动态主体内容
        JSONObject module_dynamic = modules.getJSONObject("module_dynamic");

        ArrayList<OpusParagraph> paragraphList = new ArrayList<>();

        if (!module_dynamic.isNull("desc")) {
            JSONObject object = new JSONObject();
            object.put("para_type", OpusParagraph.TYPE_TEXT_OPUS);
            object.put("data", module_dynamic.getJSONObject("desc").getJSONArray("rich_text_nodes"));
            paragraphList.add(new OpusParagraph(object));
        }

        if (!module_dynamic.isNull("major")) {
            JSONObject major = module_dynamic.getJSONObject("major");

            if (!major.isNull("opus")) {
                JSONObject dynamic_opus = major.getJSONObject("opus");
                JSONArray opus_pics = dynamic_opus.getJSONArray("pics");

                // 为了排版正常，这里必须把列表完整传递给OpusParagraph，让OpusParagraph那边解析
                // 这么干主要是为了适配这神秘的代码结构，我研究OpusParagraph的使用方法就研究了半天
                // by Moye

                JSONObject object = new JSONObject();
                object.put("para_type", OpusParagraph.TYPE_TEXT_OPUS);
                object.put("data", dynamic_opus.getJSONObject("summary").getJSONArray("rich_text_nodes"));
                paragraphList.add(new OpusParagraph(object));

                object = new JSONObject();
                object.put("para_type", OpusParagraph.TYPE_PIC);
                object.put("pic", new JSONObject().put("pics", opus_pics));
                paragraphList.add(new OpusParagraph(object));
            }

            if (!major.isNull("archive")) {
                // 这里是视频卡片
            }

        }

        opus.paragraphs = paragraphList.toArray(new OpusParagraph[0]);

        JSONObject module_stat = modules.getJSONObject("module_stat");
        Stats stats = new Stats();
        stats.reply = module_stat.getJSONObject("comment").getInt("count");
        stats.like = module_stat.getJSONObject("like").getInt("count");

        opus.stats = stats;
    }
}
