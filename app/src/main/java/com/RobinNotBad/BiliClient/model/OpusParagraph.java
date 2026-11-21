package com.RobinNotBad.BiliClient.model;

import static com.RobinNotBad.BiliClient.util.LinkUrlUtil.TYPE_WEB_URL;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;

import com.RobinNotBad.BiliClient.util.EmoteUtil;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.StringUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class OpusParagraph {
    //这几个是b站的
    public static final int TYPE_TEXT = 1;
    public static final int TYPE_PIC = 2;
    public static final int TYPE_DIVIDER = 3;
    public static final int TYPE_TEXT_BLOCKQUOTE = 4;
    public static final int TYPE_LIST = 5;
    /**
     * 链接卡片。这类段落的 {@code text} 恒为 null，数据全在 {@code link_card.card} 里，
     * 未处理时会落进 default 分支显示成「[无法识别段落：6]」。
     * （真实样本见 opus/1152583520807288834，里面有 4 个专栏卡片 + 1 个失效卡片）
     */
    public static final int TYPE_LINK_CARD = 6;
    public static final int TYPE_HEADING = 8;

    //这几个是自定义的
    public static final int TYPE_TEXT_OPUS = 99;
    public static final int TYPE_VIDEO = 100;
    public static final int TYPE_DYNAMIC = 101;
    public static final int TYPE_ARTICLE = 102;

    public static final int LIST_STYLE_NUMBER = 1;
    public static final int LIST_STYLE_DOT = 2;

    public static int FONT_SIZE_BILI_DEFAULT = 17;

    public int align;
    public int type;
    public Object content;

    public OpusParagraph() {
    }

    /**
     * 已加好链接 span 的富文本缓存。
     *
     * <p>绑定阶段原来每次都调 {@code StringUtil.setLink(textView)}——它会跑 4 个正则扫描整段
     * 文本（URL / BV / AV / CV）再重建 Spannable，全在主线程。而段落文本在解析之后就固定不变了，
     * 滚动时反复绑定同一段落等于在主线程反复做同一件纯 CPU 的事，直接表现为滑动卡顿。
     *
     * <p>这里把结果算一次存下来：解析阶段（后台）预热，绑定阶段只做 setText。
     * volatile：解析在后台线程、绑定在主线程。
     */
    private volatile CharSequence linkedContent;

    /**
     * 取"已加好链接 span"的富文本；首次调用时计算并缓存。
     * 非文本段落（图片等）返回 null，调用方回退到 {@link #content}。
     */
    public CharSequence linkedContent() {
        CharSequence cached = linkedContent;
        if (cached != null) return cached;
        if (!(content instanceof CharSequence)) return null;
        SpannableStringBuilder builder = new SpannableStringBuilder((CharSequence) content);
        StringUtil.setLink(builder);
        linkedContent = builder;
        return builder;
    }

    public OpusParagraph(JSONObject para) throws JSONException {
        this.type = para.optInt("para_type");
        this.align = para.optInt("align");
        switch (type) {
            case TYPE_TEXT:
                this.content = analyzeText(para.optJSONObject("text"));
                break;
            case TYPE_TEXT_BLOCKQUOTE:
                this.content = analyzeBlockQuote(para.optJSONObject("blockquote"));
                break;
            case TYPE_HEADING:
                this.content = analyzeText(para.optJSONObject("heading"));
                break;
            case TYPE_PIC:
                this.content = analyzePic(para.optJSONObject("pic"));
                break;
            case TYPE_DIVIDER:
                this.content = analyzeDivider(para.optJSONObject("line"));
                break;
            case TYPE_LIST:
                this.content = analyzeList(para.optJSONObject("list"));
                break;
            case TYPE_LINK_CARD:
                this.content = analyzeLinkCard(para.optJSONObject("link_card"));
                break;
            case TYPE_TEXT_OPUS:
                this.content = analyzeOpus(para.optJSONArray("data"));
                break;
            default:
                this.content = "[无法识别段落：" + type + "]";
        }
    }

    public CharSequence analyzeBlockQuote(JSONObject blockquote) throws JSONException {
        if (blockquote == null) return "";
        JSONArray children = blockquote.optJSONArray("children");
        if (children == null) return "";

        SpannableStringBuilder stringBuilder = new SpannableStringBuilder();

        for (int i = 0; i < children.length(); i++) {
            JSONObject child = children.optJSONObject(i);
            if (child == null) break;
            stringBuilder.append(analyzeText(child.optJSONObject("text")));
        }

        stringBuilder.setSpan(new BackgroundColorSpan(0x33ffffff),
                0, stringBuilder.length() - 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        return stringBuilder;
    }

    public CharSequence analyzeList(JSONObject list) throws JSONException {
        if (list == null) return "";
        int listStyle = list.optInt("style");

        SpannableStringBuilder stringBuilder = new SpannableStringBuilder();

        JSONArray items = list.optJSONArray("items");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                if (i != 0) stringBuilder.append("\n");
                JSONObject item = items.getJSONObject(i);
                switch (listStyle) {
                    case LIST_STYLE_NUMBER:
                        stringBuilder.append(String.valueOf(item.optInt("order"))).append(". ");
                        break;
                    case LIST_STYLE_DOT:
                        stringBuilder.append("· ");
                        break;
                }
                stringBuilder.append(analyzeText(item));
            }
        }

        JSONArray childrenPrimary = list.optJSONArray("children");
        if (childrenPrimary != null) {
            for (int i = 0; i < childrenPrimary.length(); i++) {
                if (i != 0) stringBuilder.append("\n");
                JSONObject childPrimary = childrenPrimary.optJSONObject(i);
                if (childPrimary == null) break;
                switch (listStyle) {
                    case LIST_STYLE_NUMBER:
                        stringBuilder.append(String.valueOf(childPrimary.optInt("order"))).append(". ");
                        break;
                    case LIST_STYLE_DOT:
                        stringBuilder.append("· ");
                        break;
                }

                JSONArray childrenSecondary = childPrimary.optJSONArray("children");
                if (childrenSecondary != null) {
                    SpannableStringBuilder childBuilder = new SpannableStringBuilder();
                    for (int j = 0; j < childrenSecondary.length(); j++) {
                        JSONObject childSecondary = childrenSecondary.optJSONObject(j);
                        childBuilder.append(analyzeText(childSecondary.optJSONObject("text")));
                    }
                    stringBuilder.append(childBuilder);
                }
            }
        }

        return stringBuilder;
    }

    public CharSequence analyzeText(JSONObject text) throws JSONException {
        if (text == null) return "";
        JSONArray nodes = text.optJSONArray("nodes");
        if (nodes == null) return "";

        SpannableStringBuilder stringBuilder = new SpannableStringBuilder();
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            switch (node.optString("type")) {
                case "TEXT_NODE_TYPE_WORD":
                    JSONObject word = node.getJSONObject("word");

                    int startPosition = stringBuilder.length();
                    stringBuilder.append(word.optString("words", ""));
                    int endPosition = stringBuilder.length();

                    //粗体斜体，BOLD/ITALIC/BOLD_ITALIC 正好对应 1/2/3 所以可以这样减少判断量？
                    JSONObject style = word.optJSONObject("style");
                    if (style != null) {
                        boolean bold = style.optBoolean("bold");
                        boolean italic = style.optBoolean("italic");
                        int styleInt = (bold ? Typeface.BOLD : 0) + (italic ? Typeface.ITALIC : 0);
                        if (styleInt != 0)
                            stringBuilder.setSpan(new StyleSpan(styleInt), startPosition, endPosition, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }

                    //字体大小，使用的是相对大小，17号的字体在手表上还是太逆天了
                    int fontSize = word.optInt("font_size", 17);
                    if (fontSize != FONT_SIZE_BILI_DEFAULT) {
                        float fontScale = fontSize * 1.0f / FONT_SIZE_BILI_DEFAULT;
                        stringBuilder.setSpan(new RelativeSizeSpan(fontScale), startPosition, endPosition, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }

                    //字体颜色
                    String color = word.optString("color", "#18191c");
                    if (color.startsWith("#") && !color.equals("#18191c")) try {
                        stringBuilder.setSpan(new ForegroundColorSpan(Color.parseColor(color)), startPosition, endPosition, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    } catch (Exception ignored) {
                        Logu.e("color error", color);
                    }

                    break;

                case "TEXT_NODE_TYPE_RICH":
                    JSONObject rich = node.getJSONObject("rich");
                    stringBuilder.append(rich.getString("text"));
                    switch (rich.optString("type")) {
                        case "RICH_TEXT_NODE_TYPE_EMOJI":
                            JSONObject emoji = rich.getJSONObject("emoji");
                            EmoteUtil.replaceSingle(stringBuilder, emoji.optString("icon_url"), emoji.optInt("size"), stringBuilder.length() - rich.getString("text").length(), stringBuilder.length(), 1.0f);
                            break;
                        case "RICH_TEXT_NODE_TYPE_RICH":  //TODO:忘记这个叫什么名字了，根据记忆应该是这个，如果不对请调整
                            stringBuilder.setSpan(new StringUtil.LinkClickableSpan(rich.optString("jump_url"), TYPE_WEB_URL, rich.getString("jump_url")), stringBuilder.length() - rich.getString("text").length(), stringBuilder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                            break;
                    }
                    break;
                default:
                    stringBuilder.append("[").append(node.optString("type")).append("]");
            }
        }

        return stringBuilder;
    }

    public String[] analyzePic(JSONObject allJson) throws JSONException {
        if (allJson == null) return new String[0];
        JSONArray picsJson = allJson.optJSONArray("pics");
        if (picsJson == null) return new String[0];
        String[] pics = new String[picsJson.length()];
        for (int i = 0; i < picsJson.length(); i++) {
            String url = picsJson.getJSONObject(i).optString("url");
            pics[i] = url.startsWith("http") ? url : "http:" + url;
        }
        return pics;
    }

    public String[] analyzeDivider(JSONObject allJson) throws JSONException {
        if (allJson == null) return new String[0];
        String url = allJson.getJSONObject("pic").optString("url");
        return new String[]{url.startsWith("http") ? url : "http:" + url};
    }

    /**
     * 链接卡片段落（{@code para_type=6}）的渲染数据。
     *
     * <p>B站各种卡片的字段名完全不统一（{@code opus}/{@code ugc}/{@code common}/{@code live}/
     * {@code music} 各一套嵌套），但渲染需要的只有「封面 / 标题 / 副标题 / 跳转地址」四项，
     * 所以解析阶段就归一到这个类，渲染层只写一种布局。
     */
    public static class LinkCard {
        public String cover = "";
        public String title = "";
        public String desc = "";
        public String jumpUrl = "";

        /** 跳转地址非空才可点；失效卡片（ITEM_NULL）没有地址，点了什么也不会发生 */
        public boolean clickable() {
            return !jumpUrl.isEmpty();
        }
    }

    /**
     * 把 {@code link_card.card} 归一化成 {@link LinkCard}。
     *
     * @return 卡片数据；卡片整体为空（数据缺失）时返回 null，由调用方跳过该段落
     */
    public LinkCard analyzeLinkCard(JSONObject linkCard) {
        if (linkCard == null) return null;
        JSONObject card = linkCard.optJSONObject("card");
        if (card == null) return null;

        LinkCard out = new LinkCard();
        switch (card.optString("type", "")) {
            case "LINK_CARD_TYPE_OPUS": {
                JSONObject opus = card.optJSONObject("opus");
                if (opus == null) break;
                out.title = opus.optString("title", "");
                out.cover = opus.optString("cover", "");
                out.jumpUrl = opus.optString("jump_url", "");
                JSONObject author = opus.optJSONObject("author");
                out.desc = joinDesc(
                        author != null ? author.optString("name", "") : "",
                        viewDesc(opus.optJSONObject("stat")));
                break;
            }
            case "LINK_CARD_TYPE_UGC": {
                JSONObject ugc = card.optJSONObject("ugc");
                if (ugc == null) break;
                out.title = ugc.optString("title", "");
                out.cover = ugc.optString("cover", "");
                out.jumpUrl = ugc.optString("jump_url", "");
                //desc_second 已经是「42.5万观看 512弹幕」这种成品文案，不再二次格式化
                out.desc = ugc.optString("desc_second", "");
                break;
            }
            case "LINK_CARD_TYPE_COMMON": {
                JSONObject common = card.optJSONObject("common");
                if (common == null) break;
                out.title = common.optString("title", "");
                out.cover = common.optString("cover", "");
                out.jumpUrl = common.optString("jump_url", "");
                out.desc = joinDesc(common.optString("desc1", ""), common.optString("desc2", ""));
                break;
            }
            case "LINK_CARD_TYPE_LIVE": {
                JSONObject live = card.optJSONObject("live");
                if (live == null) break;
                out.title = live.optString("title", "");
                out.cover = live.optString("cover", "");
                out.jumpUrl = live.optString("jump_url", "");
                out.desc = joinDesc(
                        live.optInt("live_state", 0) == 1 ? "直播中" : "直播已结束",
                        joinDesc(live.optString("desc_first", ""), live.optString("desc_second", "")));
                break;
            }
            case "LINK_CARD_TYPE_MUSIC": {
                JSONObject music = card.optJSONObject("music");
                if (music == null) break;
                out.title = music.optString("title", "");
                out.cover = music.optString("cover", "");
                out.jumpUrl = music.optString("jump_url", "");
                out.desc = music.optString("label", "");
                break;
            }
            case "LINK_CARD_TYPE_ITEM_NULL": {
                //内容已删/失效的卡片：B站只给一句「暂不支持」加一个 oid，没有可跳转地址
                JSONObject itemNull = card.optJSONObject("item_null");
                out.title = itemNull != null ? itemNull.optString("text", "") : "";
                out.desc = "原内容已失效";
                break;
            }
            default:
                //未知卡片类型：不渲染任何东西（比显示一串类型号对用户友好），按空处理
                break;
        }

        if (out.title.isEmpty() && out.cover.isEmpty()) return null;
        return out;
    }

    /** 统计字段是字符串（{@code "view":"118848"}），空串与非数字都要当作 0 */
    private static String viewDesc(JSONObject stat) {
        if (stat == null) return "";
        String raw = stat.optString("view", "");
        if (raw.isEmpty()) return "";
        try {
            long view = Long.parseLong(raw);
            return view > 0 ? StringUtil.toWan(view) + "阅读" : "";
        } catch (NumberFormatException e) {
            return "";
        }
    }

    /** 用「 · 」连接非空片段，避免出现「作者 · 」这种尾巴 */
    private static String joinDesc(String first, String second) {
        boolean hasFirst = first != null && !first.isEmpty();
        boolean hasSecond = second != null && !second.isEmpty();
        if (hasFirst && hasSecond) return first + " · " + second;
        if (hasFirst) return first;
        return hasSecond ? second : "";
    }

    public CharSequence analyzeOpus(JSONArray rich_list) throws JSONException {
        if (rich_list == null) return "";
        SpannableStringBuilder stringBuilder = new SpannableStringBuilder();
        for (int i = 0; i < rich_list.length(); i++) {
            JSONObject rich = rich_list.getJSONObject(i);
            int startLength = stringBuilder.length();
            switch (rich.getString("type")) {
                case "RICH_TEXT_NODE_TYPE_AT":
                    stringBuilder.append(rich.getString("text"));
                    At at = new At(rich.optLong("rid"), startLength, stringBuilder.length());
                    StringUtil.setSingleAt(stringBuilder, at);
                    break;
                case "RICH_TEXT_NODE_TYPE_TOPIC":
                    stringBuilder.append(rich.getString("text"));
                    stringBuilder.setSpan(new StringUtil.LinkClickableSpan(rich.getString("jump_url"), TYPE_WEB_URL, rich.getString("jump_url")), startLength, stringBuilder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                case "RICH_TEXT_NODE_TYPE_EMOJI":
                    stringBuilder.append(rich.optString("text"));
                    JSONObject emoji = rich.getJSONObject("emoji");
                    String url = emoji.optString("icon_url");
                    int size = emoji.optInt("size");
                    EmoteUtil.replaceSingle(stringBuilder, url, size, startLength, stringBuilder.length(), 1.0f);
                    break;
                case "RICH_TEXT_NODE_TYPE_TEXT":
                default:
                    stringBuilder.append(rich.getString("orig_text"));
                    break;
            }
        }
        return stringBuilder;
    }

}
