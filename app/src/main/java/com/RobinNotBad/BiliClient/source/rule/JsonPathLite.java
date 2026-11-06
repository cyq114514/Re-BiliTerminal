package com.RobinNotBad.BiliClient.source.rule;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;

/**
 * 受限 JSONPath 子集求值器。
 *
 * <p><b>为什么是子集而不是完整实现</b>：Kazumi 用的是受限子集
 * （`RestrictedJsonPath.validate` 只放行 `$`、`.name`、`[*]`、`[0]`、`['key']`），
 * 实现成完整 JSONPath 反而会「接受」Kazumi 自己会拒绝的规则，
 * 造成本 App 能跑、Kazumi 里跑不了的行为分叉。
 *
 * <p>Animeko 的 `json-path-indexed` 条目格式用到 `$[*]['url','link']` 这种多键写法，
 * 语义是「取第一个存在的键」——与上游 `getSingleStringValueOrNull`（取第一个值）一致。
 *
 * <p>不支持（明确报错，不静默返回空）：递归下降 `..`、过滤 `[?(...)]`、切片 `[a:b]`、
 * 函数 `length()`、负索引。
 */
public final class JsonPathLite {

    private interface Step {
    }

    private static final class Field implements Step {
        final String name;

        Field(String name) {
            this.name = name;
        }
    }

    private static final class UnionField implements Step {
        final List<String> names;

        UnionField(List<String> names) {
            this.names = names;
        }
    }

    private static final class Wildcard implements Step {
    }

    private static final class Index implements Step {
        final int index;

        Index(int index) {
            this.index = index;
        }
    }

    private final List<Step> steps;

    private JsonPathLite(List<Step> steps) {
        this.steps = steps;
    }

    // ==================== 解析 ====================

    public static JsonPathLite parse(String expression) {
        if (expression == null) throw new IllegalArgumentException("JSONPath 为空");
        String expr = expression.trim();
        if (expr.isEmpty() || expr.charAt(0) != '$')
            throw new IllegalArgumentException("JSONPath 必须以 $ 开头：" + expr);

        List<Step> steps = new ArrayList<>();
        int index = 1;
        while (index < expr.length()) {
            char ch = expr.charAt(index);
            if (ch == '.') {
                index++;
                int start = index;
                while (index < expr.length() && isFieldChar(expr.charAt(index))) index++;
                if (index == start)
                    throw new IllegalArgumentException("不支持的 JSONPath：" + expr);
                steps.add(new Field(expr.substring(start, index)));
                continue;
            }
            if (ch == '[') {
                int end = findBracketEnd(expr, index);
                String content = expr.substring(index + 1, end).trim();
                steps.add(parseBracket(content, expr));
                index = end + 1;
                continue;
            }
            throw new IllegalArgumentException("不支持的 JSONPath：" + expr);
        }
        return new JsonPathLite(steps);
    }

    private static Step parseBracket(String content, String expr) {
        if (content.isEmpty()) throw new IllegalArgumentException("JSONPath 空的 []：" + expr);
        if (content.equals("*")) return new Wildcard();
        if (isAllDigits(content)) {
            try {
                return new Index(Integer.parseInt(content));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("JSONPath 索引过大：" + expr);
            }
        }
        if (isQuoted(content)) {
            return new UnionField(splitQuoted(content));
        }
        if (content.indexOf(':') >= 0)
            throw new IllegalArgumentException("不支持的 JSONPath 切片：" + expr);
        if (content.startsWith("?("))
            throw new IllegalArgumentException("不支持的 JSONPath 过滤表达式：" + expr);
        throw new IllegalArgumentException("不支持的 JSONPath 片段：[" + content + "]");
    }

    private static List<String> splitQuoted(String content) {
        List<String> names = new ArrayList<>();
        int i = 0;
        while (i < content.length()) {
            char ch = content.charAt(i);
            if (ch != '\'' && ch != '"') {
                i++;
                continue;
            }
            char quote = ch;
            StringBuilder builder = new StringBuilder();
            i++;
            while (i < content.length() && content.charAt(i) != quote) {
                char c = content.charAt(i);
                if (c == '\\' && i + 1 < content.length()) {
                    builder.append(content.charAt(i + 1));
                    i += 2;
                    continue;
                }
                builder.append(c);
                i++;
            }
            i++; // skip closing quote
            names.add(builder.toString());
        }
        if (names.isEmpty()) throw new IllegalArgumentException("JSONPath 引号内没有键名");
        return names;
    }

    private static boolean isQuoted(String content) {
        if (content.length() < 2) return false;
        char first = content.charAt(0);
        char last = content.charAt(content.length() - 1);
        return (first == '\'' || first == '"') && first == last;
    }

    private static boolean isAllDigits(String text) {
        if (text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) return false;
        }
        return true;
    }

    private static boolean isFieldChar(char ch) {
        return (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')
                || (ch >= '0' && ch <= '9') || ch == '_' || ch == '$' || ch == '-';
    }

    private static int findBracketEnd(String expr, int start) {
        char quote = 0;
        boolean escaped = false;
        for (int i = start + 1; i < expr.length(); i++) {
            char ch = expr.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            if (quote != 0) {
                if (ch == quote) quote = 0;
                continue;
            }
            if (ch == '\'' || ch == '"') {
                quote = ch;
                continue;
            }
            if (ch == ']') return i;
        }
        throw new IllegalArgumentException("JSONPath 缺少 ]：" + expr);
    }

    // ==================== 求值 ====================

    /** 求值，返回所有命中的值（按文档顺序） */
    public List<JsonElement> read(JsonElement root) {
        List<JsonElement> current = new ArrayList<>();
        if (root != null) current.add(root);
        for (Step step : steps) {
            List<JsonElement> next = new ArrayList<>();
            for (JsonElement element : current) apply(step, element, next);
            current = next;
            if (current.isEmpty()) break;
        }
        return current;
    }

    private static void apply(Step step, JsonElement element, List<JsonElement> out) {
        if (element == null || element.isJsonNull()) return;
        if (step instanceof Wildcard) {
            if (element.isJsonArray()) {
                JsonArray array = element.getAsJsonArray();
                for (JsonElement item : array) if (item != null && !item.isJsonNull()) out.add(item);
            } else if (element.isJsonObject()) {
                for (java.util.Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                    JsonElement value = entry.getValue();
                    if (value != null && !value.isJsonNull()) out.add(value);
                }
            }
            return;
        }
        if (step instanceof Index) {
            if (!element.isJsonArray()) return;
            JsonArray array = element.getAsJsonArray();
            int i = ((Index) step).index;
            if (i < array.size()) {
                JsonElement value = array.get(i);
                if (value != null && !value.isJsonNull()) out.add(value);
            }
            return;
        }
        if (step instanceof Field) {
            if (!element.isJsonObject()) return;
            JsonElement value = element.getAsJsonObject().get(((Field) step).name);
            if (value != null && !value.isJsonNull()) out.add(value);
            return;
        }
        if (step instanceof UnionField) {
            if (!element.isJsonObject()) return;
            JsonObject object = element.getAsJsonObject();
            List<String> names = ((UnionField) step).names;
            for (String name : names) {
                JsonElement value = object.get(name);
                if (value != null && !value.isJsonNull()) {
                    // 多键只取第一个存在的键：对齐上游 getSingleStringValueOrNull 的语义
                    out.add(value);
                    return;
                }
            }
        }
    }

    /** 取第一个命中的字符串值；缺失/空返回 "" */
    public String readFirstString(JsonElement root) {
        List<JsonElement> values = read(root);
        if (values.isEmpty()) return "";
        return stringValue(values.get(0));
    }

    /** 数字/布尔等标量转字符串，对象/数组返回空串（调用方据此判定「结构不符」） */
    public static String stringValue(JsonElement element) {
        if (element == null || element.isJsonNull()) return "";
        if (element instanceof JsonPrimitive) {
            JsonPrimitive primitive = (JsonPrimitive) element;
            try {
                return primitive.getAsString().trim();
            } catch (Exception e) {
                return "";
            }
        }
        return "";
    }

    /** 文档里的「值是数组时取第一个标量」：对齐 Animeko 的 getSingleStringValueOrNull */
    public static String firstScalar(JsonElement element) {
        if (element == null || element.isJsonNull()) return "";
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement item : array) {
                String value = firstScalar(item);
                if (!value.isEmpty()) return value;
            }
            return "";
        }
        if (element.isJsonObject()) {
            for (java.util.Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                String value = firstScalar(entry.getValue());
                if (!value.isEmpty()) return value;
            }
            return "";
        }
        return stringValue(element);
    }
}
