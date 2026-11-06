package com.RobinNotBad.BiliClient.source.rule;

import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Kazumi 规则的 XPath 求值器。
 *
 * <p><b>依赖事实（已实测，不是推测）</b>：
 * <ul>
 *   <li>项目现有的 jsoup 1.15.3 <b>没有</b> {@code Element#selectXpath}，
 *       也没有 {@code org.jsoup.select.xpath} 包 —— 直接调 {@code selectXpath} 编译不过；</li>
 *   <li>但同一版本里已经有 {@link W3CDom}，它提供
 *       {@code selectXpath(String, org.w3c.dom.Node)} 与 {@code sourceNodes(...)}；</li>
 *   <li>Android 平台自带 {@code javax.xml.xpath}（android.jar 里有 XPath/XPathFactory），
 *       jsoup 的 W3CDom 正是基于它求值。</li>
 * </ul>
 * 因此不需要新增任何 XPath 依赖：jsoup 解析 → W3CDom 转 DOM → 原生 XPath 求值
 * → sourceNodes 映射回 jsoup 取文本/属性。这条路子支持 {@code //}、{@code [2]}、
 * {@code contains()}、{@code /text()}、{@code @attr} 等真实语法，不是残缺的示例解析器。
 *
 * <p><b>相对语义</b>：Kazumi 的 searchName / searchResult / chapterResult 都以
 * 「当前条目/当前线路」为起点填写，但文档示例里仍然以 {@code //} 开头
 * （如 SearchList = {@code //li[@class='clearfix']}，SearchName = {@code //div[2]/h4/a}）。
 * 所以这里统一把开头的 {@code //} 改写成 {@code .//}、{@code /} 改写为 {@code .}，
 * 让「Chrome 里复制出来的绝对路径」在相对上下文里也成立。
 */
public final class XPathPage {

    /** 单页最多接受的节点数，防止一个写坏的 //div 把内存打满 */
    private static final int MAX_NODES = 4000;

    private final String baseUri;
    private final org.w3c.dom.Document dom;
    private final W3CDom w3c;

    private XPathPage(String baseUri, org.w3c.dom.Document dom, W3CDom w3c) {
        this.baseUri = baseUri;
        this.dom = dom;
        this.w3c = w3c;
    }

    public static XPathPage parse(String html, String baseUri) {
        Document document = Jsoup.parse(html == null ? "" : html, baseUri == null ? "" : baseUri);
        W3CDom converter = new W3CDom();
        org.w3c.dom.Document converted;
        try {
            converted = converter.fromJsoup(document);
        } catch (Throwable t) {
            throw new IllegalArgumentException("HTML 转 DOM 失败：" + t.getMessage());
        }
        return new XPathPage(baseUri == null ? "" : baseUri, converted, converter);
    }

    public String baseUri() {
        return baseUri;
    }

    /** 从文档根按表达式取值 */
    public List<Node> select(String expression) {
        return selectRelative(dom, expression);
    }

    public Node first(String expression) {
        List<Node> nodes = select(expression);
        return nodes.isEmpty() ? null : nodes.get(0);
    }

    public List<Element> selectElements(String expression) {
        return toElements(select(expression));
    }

    /** 相对某个上下文节点求值（表达式可继续以 // 开头，见类注释） */
    public List<Node> selectRelative(Node context, String expression) {
        String xpath = normalize(expression);
        try {
            NodeList list = w3c.selectXpath(xpath, context);
            int size = list.getLength();
            if (size == 0) return Collections.emptyList();
            int limit = Math.min(size, MAX_NODES);
            List<Node> nodes = new ArrayList<>(limit);
            for (int i = 0; i < limit; i++) {
                Node node = list.item(i);
                if (node != null) nodes.add(node);
            }
            return nodes;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("XPath 非法：" + expression + "（" + e.getMessage() + "）");
        } catch (Throwable t) {
            // jsoup 对表达式语法错误抛 SelectorParseException（RuntimeException）
            throw new IllegalArgumentException("XPath 求值失败：" + expression + "（" + t.getMessage() + "）");
        }
    }

    public List<Element> selectElementsRelative(Node context, String expression) {
        return toElements(selectRelative(context, expression));
    }

    public Element firstElementRelative(Node context, String expression) {
        List<Element> elements = selectElementsRelative(context, expression);
        return elements.isEmpty() ? null : elements.get(0);
    }

    /**
     * 节点的文本。
     *
     * <p>文本节点（{@code //div[2]/text()}）走 TextNode，元素节点走 jsoup 的 text()。
     */
    public String textOf(Node node) {
        List<Element> elements = w3c.sourceNodes(singleNodeList(node), Element.class);
        if (!elements.isEmpty()) {
            String text = elements.get(0).text();
            return text == null ? "" : text.trim();
        }
        List<TextNode> textNodes = w3c.sourceNodes(singleNodeList(node), TextNode.class);
        if (!textNodes.isEmpty()) {
            String text = textNodes.get(0).getWholeText();
            return text == null ? "" : text.trim();
        }
        String value = node.getTextContent();
        return value == null ? "" : value.trim();
    }

    /** 元素的属性；非元素或属性不存在返回 "" */
    public String attrOf(Node node, String name) {
        List<Element> elements = w3c.sourceNodes(singleNodeList(node), Element.class);
        if (elements.isEmpty()) return "";
        String value = elements.get(0).attr(name);
        return value == null ? "" : value.trim();
    }

    public List<Element> toElements(List<Node> nodes) {
        if (nodes.isEmpty()) return Collections.emptyList();
        List<Element> result = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            Element element = toElement(node);
            if (element != null) result.add(element);
        }
        return result;
    }

    public Element toElement(Node node) {
        if (node == null) return null;
        List<Element> elements = w3c.sourceNodes(singleNodeList(node), Element.class);
        return elements.isEmpty() ? null : elements.get(0);
    }

    private NodeList singleNodeList(final Node node) {
        return new NodeList() {
            @Override
            public Node item(int index) {
                return index == 0 ? node : null;
            }

            @Override
            public int getLength() {
                return node == null ? 0 : 1;
            }
        };
    }

    private static String normalize(String expression) {
        if (expression == null) throw new IllegalArgumentException("XPath 为空");
        String expr = expression.trim();
        if (expr.isEmpty()) throw new IllegalArgumentException("XPath 为空");
        if (expr.startsWith("//")) return "." + expr;
        if (expr.startsWith("/")) return "." + expr;
        if (expr.startsWith(".")) return expr;
        return ".//" + expr;
    }
}
