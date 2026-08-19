package dev.duo.util;

import java.util.List;
import java.util.Map;

/**
 * 最小 JSON 值序列化器：把 {@code Map / List / String / Number / Boolean / null}
 * 嵌套结构序列化为 JSON 文本（用于透传任意 JSON 对象，如工具 schema）。
 * <p>解析侧见 {@link JsonParser}；二者覆盖相同的值域。</p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public final class JsonWriter {

    private JsonWriter() {}

    /**
     * 序列化一个 JSON 值。
     *
     * @param value Map/List/String/Number/Boolean/null 结构
     * @return JSON 文本
     */
    public static String toJson(Object value) {
        var sb = new StringBuilder(64);
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value) {
        switch (value) {
            case null -> sb.append("null");
            case String s -> quote(sb, s);
            case Boolean b -> sb.append(b);
            case Double d when !d.isInfinite() && !d.isNaN() -> sb.append(d);
            case Float f when !f.isInfinite() && !f.isNaN() -> sb.append(f);
            case Number n -> sb.append(n);
            case Map<?, ?> m -> {
                sb.append('{');
                boolean first = true;
                for (var entry : m.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    quote(sb, String.valueOf(entry.getKey()));
                    sb.append(':');
                    writeValue(sb, entry.getValue());
                }
                sb.append('}');
            }
            case List<?> l -> {
                sb.append('[');
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    writeValue(sb, l.get(i));
                }
                sb.append(']');
            }
            default -> quote(sb, String.valueOf(value));
        }
    }

    /**
     * 把字符串写入 JSON 字符串字面量（含两侧引号与转义）。
     * <p>供其它手写编码器（如会话事件编解码）复用，保证全库转义一致。</p>
     *
     * @param value 原始字符串
     * @return 带引号与转义的 JSON 字面量
     */
    public static String quote(String value) {
        var sb = new StringBuilder(value.length() + 8);
        quote(sb, value);
        return sb.toString();
    }

    /** 追加模式的双引号字符串写入；NaN/Infinity 是非法 JSON，由调用方保证不进入。 */
    private static void quote(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
