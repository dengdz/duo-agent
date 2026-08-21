package dev.duo.util;

import java.util.Map;

/**
 * 零依赖的 JSON 序列化工具（请求构建侧）。
 * <p>
 * 供各协议 RequestBuilder 共用：字符串转义（RFC 8259 全控制字符）与
 * 值序列化（Map/Iterable/数组/标量）。与 {@link JsonFieldExtractor}
 * （响应解析侧）相对，二者共同替代第三方 JSON 库以满足零依赖红线。
 * 仅用于构建发往已知端点的请求体，不处理任意外部 JSON。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-21
 */
public final class JsonCodec {

    private JsonCodec() {
        // 工具类，禁止实例化
    }

    /**
     * 按 RFC 8259 转义字符串：引号、反斜杠、命名控制字符显式转义，
     * 其余 U+0000 到 U+001F 控制字符以四位十六进制的 unicode 转义形式输出。
     *
     * @param s 原始字符串
     * @return JSON 字符串字面量内容（不含两侧引号）
     */
    public static String escapeJson(String s) {
        var sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * 将值序列化为 JSON：null/字符串/数字/布尔/Map/Iterable/Java 数组。
     * <p>
     * 数组经反射遍历（工具 schema 的 enum/default 数组常见
     * {@code String[]} 等类型，缺失该分支会输出 {@code [Ljava...@hash} 垃圾）。
     * 其余类型按 toString 转字符串兜底。
     * </p>
     *
     * @param value 待序列化的值
     * @return JSON 文本
     */
    public static String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return "\"" + escapeJson(s) + "\"";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            var sb = new StringBuilder("{");
            var first = true;
            for (var entry : map.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append("\"").append(escapeJson(entry.getKey().toString())).append("\": ");
                sb.append(toJson(entry.getValue()));
            }
            sb.append("}");
            return sb.toString();
        }
        if (value instanceof Iterable<?> iter) {
            var sb = new StringBuilder("[");
            var first = true;
            for (var item : iter) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(toJson(item));
            }
            sb.append("]");
            return sb.toString();
        }
        if (value.getClass().isArray()) {
            var sb = new StringBuilder("[");
            var length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(toJson(java.lang.reflect.Array.get(value, i)));
            }
            sb.append("]");
            return sb.toString();
        }
        return "\"" + escapeJson(value.toString()) + "\"";
    }
}
