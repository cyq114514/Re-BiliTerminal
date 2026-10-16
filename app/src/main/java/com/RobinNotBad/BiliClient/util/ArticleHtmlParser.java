package com.RobinNotBad.BiliClient.util;

import android.os.Build;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.URLSpan;

import com.RobinNotBad.BiliClient.model.OpusParagraph;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 专栏正文 HTML → {@link OpusParagraph} 数组。
 *
 * <p>存在理由只有一条：{@code x/article/view} 接口返回的正文是一整段 HTML，
 * 而本应用的详情页渲染链（{@code OpusInfoFragment} + {@code OpusContentAdapter}）吃的是
 * 结构化段落数组。有了这一层转换，"把专栏的数据源从网页抓取换成单次 API"就不需要动渲染层。
 *
 * <p><b>实现取舍：不做完整的 HTML 解析器。</b>
 * 块级结构用轻量扫描切分（图片单独成段，标题单独成段），行内富文本（粗体、斜体、链接、
 * HTML 实体、嵌套标签）直接交给系统的 {@link Html#fromHtml}——它对实体解码与嵌套处理
 * 都比自研可靠，而且零依赖。认不出的标签不会丢内容：fromHtml 会保留其中的文本。
 *
 * <p><b>失败即返回空数组</b>，由调用方回退到原有的网页抓取路径，保证"最坏也不比改造前差"。
 * 因此这里任何异常都不外抛、也不做部分返回——半截正文比回退更糟。
 */
public class ArticleHtmlParser {

    /** 图片标签：专栏正文里图片是独立块，抽出来单独成段（渲染链的 TYPE_PIC 吃 String[]） */
    private static final Pattern IMG_TAG = Pattern.compile("(?is)<img\\b[^>]*>");
    /** 图片地址：src 可能带引号也可能不带，值里不会出现空白与尖括号 */
    private static final Pattern IMG_SRC = Pattern.compile("(?is)src\\s*=\\s*[\"']?([^\"'\\s>]+)");
    /** 标题块：h1~h6，整块取出单独成段，交给渲染层用大字号 */
    private static final Pattern HEADING_BLOCK = Pattern.compile("(?is)<h([1-6])\\b[^>]*>(.*?)</h\\1>");
    /** 分隔线：本渲染链的 TYPE_DIVIDER 需要一张分隔图 URL，专栏正文里的 <hr> 没有图，
     *  所以直接剥掉标签而不生成段落——少一种类型就少一处出错可能 */
    private static final Pattern HR_TAG = Pattern.compile("(?is)<hr\\b[^>]*>");

    private ArticleHtmlParser() {
    }

    /**
     * 把专栏正文 HTML 转成段落数组。
     *
     * @param html {@code x/article/view} 的 {@code data.content}
     * @return 解析出的段落；任何异常或解析不出内容时返回**空数组**，调用方应据此回退
     */
    public static OpusParagraph[] parse(String html) {
        if (html == null || html.trim().isEmpty()) return new OpusParagraph[0];
        try {
            List<OpusParagraph> out = new ArrayList<>();

            //先按图片标签把正文切成若干文本块：图片独立成段，文本块再细分标题
            Matcher imgMatcher = IMG_TAG.matcher(html);
            int cursor = 0;
            while (imgMatcher.find()) {
                appendTextBlocks(out, html.substring(cursor, imgMatcher.start()));
                String url = extractImgSrc(imgMatcher.group());
                if (url != null && !url.isEmpty()) appendPic(out, url);
                cursor = imgMatcher.end();
            }
            appendTextBlocks(out, html.substring(cursor));

            return out.toArray(new OpusParagraph[0]);
        } catch (Throwable t) {
            //宁可空手而归让调用方走网页抓取兜底，也不要抛给上层或返回半截正文
            Logu.w("article-html", "专栏正文转换失败，按空结果处理以触发回退：" + t);
            return new OpusParagraph[0];
        }
    }

    /**
     * 把一段不含图片的 HTML 切成段落：标题单独成段，其余合并为一个正文段。
     *
     * <p>合并而不是逐 {@code <p>} 成段，是有意的：{@code <p>} 之间需要用空行分隔才好看，
     * 而 {@code fromHtml} 已经会把块级标签转换成换行，逐段拆开反而会丢掉它们之间的间距。
     */
    private static void appendTextBlocks(List<OpusParagraph> out, String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        String rest = HR_TAG.matcher(chunk).replaceAll("");

        //标题：逐个取出单独成段（保持文档顺序）
        while (true) {
            Matcher heading = HEADING_BLOCK.matcher(rest);
            if (!heading.find()) break;
            appendHtml(out, rest.substring(0, heading.start()), OpusParagraph.TYPE_TEXT);
            appendHtml(out, heading.group(2), OpusParagraph.TYPE_HEADING);
            rest = rest.substring(heading.end());
        }
        appendHtml(out, rest, OpusParagraph.TYPE_TEXT);
    }

    private static void appendHtml(List<OpusParagraph> out, String html, int type) {
        if (html == null) return;
        String trimmed = html.trim();
        if (trimmed.isEmpty()) return;

        CharSequence text = normalizeLinks(toSpanned(trimmed));
        //只有空白（例如 `<p>&nbsp;</p>` 这种空行）的块不生成段落，否则会在正文里插进空行
        if (text == null || text.toString().trim().isEmpty()) return;

        OpusParagraph paragraph = new OpusParagraph();
        paragraph.type = type;
        paragraph.content = text;
        out.add(paragraph);
    }

    private static void appendPic(List<OpusParagraph> out, String url) {
        OpusParagraph paragraph = new OpusParagraph();
        paragraph.type = OpusParagraph.TYPE_PIC;
        //渲染链的图片段吃 String[]（与 analyzePic 的输出形态一致）
        paragraph.content = new String[]{url.startsWith("http") ? url : "http:" + url};
        out.add(paragraph);
    }

    private static String extractImgSrc(String imgTag) {
        if (imgTag == null) return null;
        Matcher matcher = IMG_SRC.matcher(imgTag);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static CharSequence toSpanned(String html) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            //LEGACY 模式才是与老版本 fromHtml 等价的换行/间距行为
            return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY);
        }
        //noinspection deprecation
        return Html.fromHtml(html);
    }

    /**
     * 把 {@code fromHtml} 产生的系统 {@link URLSpan} 换成应用自己的可点击 span。
     *
     * <p>不换的话，专栏正文里的链接会走系统浏览器（URLSpan 默认行为），
     * 与图文路径（{@code OpusParagraph.analyzeText} 用 LinkClickableSpan）的行为不一致——
     * 同样的链接在两条路径下点击效果不同，属于看得见的割裂。
     */
    private static CharSequence normalizeLinks(CharSequence text) {
        if (!(text instanceof Spanned)) return text;
        Spanned spanned = (Spanned) text;
        URLSpan[] urlSpans = spanned.getSpans(0, spanned.length(), URLSpan.class);
        if (urlSpans.length == 0) return text;

        SpannableStringBuilder builder = new SpannableStringBuilder(spanned);
        for (URLSpan span : urlSpans) {
            int start = builder.getSpanStart(span);
            int end = builder.getSpanEnd(span);
            builder.removeSpan(span);
            String url = span.getURL();
            if (start < 0 || end <= start || url == null || url.isEmpty()) continue;
            builder.setSpan(
                    new StringUtil.LinkClickableSpan(url, LinkUrlUtil.TYPE_WEB_URL, url),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return builder;
    }
}
