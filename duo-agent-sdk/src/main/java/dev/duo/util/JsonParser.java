package dev.duo.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析器，仅用于解析工具调用参数。
 * <p>
 * 将 JSON 文本转换为嵌套的 {@code Map<String,Object> / List<Object> / String / Number / Boolean / null}，
 * 不依赖任何第三方库，保持项目零运行时依赖。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public final class JsonParser {

    private final String s;
    private int pos = 0;

    private JsonParser(String s) {
        this.s = s;
    }

    /** 解析 JSON 文本为嵌套对象；非法输入抛出 IllegalArgumentException。 */
    public static Object parse(String json) {
        if (json == null) return null;
        var p = new JsonParser(json.trim());
        p.skipWs();
        var v = p.parseValue();
        p.skipWs();
        if (p.pos != p.s.length()) {
            throw new IllegalArgumentException("JSON 尾部有多余字符: " + p.s.substring(p.pos));
        }
        return v;
    }

    private Object parseValue() {
        skipWs();
        if (pos >= s.length()) throw new IllegalArgumentException("JSON 意外结束");
        return switch (s.charAt(pos)) {
            case '{' -> parseObject();
            case '[' -> parseArray();
            case '"' -> parseString();
            case 't', 'f' -> parseBoolean();
            case 'n' -> parseNull();
            default -> parseNumber();
        };
    }

    private Map<String, Object> parseObject() {
        expect('{');
        var map = new LinkedHashMap<String, Object>();
        skipWs();
        if (peek() == '}') { pos++; return map; }
        while (true) {
            skipWs();
            if (peek() != '"') throw new IllegalArgumentException("期望对象键名");
            var key = parseString();
            skipWs();
            expect(':');
            map.put(key, parseValue());
            skipWs();
            var c = peek();
            if (c == ',') { pos++; continue; }
            if (c == '}') { pos++; break; }
            throw new IllegalArgumentException("期望 ',' 或 '}', 实际 '" + c + "'");
        }
        return map;
    }

    private List<Object> parseArray() {
        expect('[');
        var list = new ArrayList<Object>();
        skipWs();
        if (peek() == ']') { pos++; return list; }
        while (true) {
            list.add(parseValue());
            skipWs();
            var c = peek();
            if (c == ',') { pos++; continue; }
            if (c == ']') { pos++; break; }
            throw new IllegalArgumentException("期望 ',' 或 ']', 实际 '" + c + "'");
        }
        return list;
    }

    private String parseString() {
        expect('"');
        var sb = new StringBuilder();
        while (true) {
            if (pos >= s.length()) throw new IllegalArgumentException("字符串未闭合");
            var c = s.charAt(pos++);
            if (c == '"') break;
            if (c == '\\') {
                var esc = s.charAt(pos++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> sb.append(parseUnicode());
                    default -> throw new IllegalArgumentException("非法转义: \\" + esc);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private char parseUnicode() {
        var code = 0;
        for (var i = 0; i < 4; i++) {
            code = (code << 4) | hex(s.charAt(pos++));
        }
        return (char) code;
    }

    private int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        throw new IllegalArgumentException("非法十六进制字符: " + c);
    }

    private Object parseNumber() {
        var start = pos;
        while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
        var num = s.substring(start, pos);
        try {
            if (num.contains(".") || num.contains("e") || num.contains("E")) {
                return Double.parseDouble(num);
            }
            var l = Long.parseLong(num);
            if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                return (int) l;
            }
            return l;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("非法数字: " + num);
        }
    }

    private Boolean parseBoolean() {
        if (s.startsWith("true", pos)) { pos += 4; return true; }
        if (s.startsWith("false", pos)) { pos += 5; return false; }
        throw new IllegalArgumentException("非法布尔值");
    }

    private Object parseNull() {
        if (s.startsWith("null", pos)) { pos += 4; return null; }
        throw new IllegalArgumentException("非法 null 字面量");
    }

    private void expect(char c) {
        skipWs();
        if (pos >= s.length() || s.charAt(pos) != c) {
            throw new IllegalArgumentException("期望 '" + c + "'");
        }
        pos++;
    }

    private char peek() {
        skipWs();
        if (pos >= s.length()) throw new IllegalArgumentException("JSON 意外结束");
        return s.charAt(pos);
    }

    private void skipWs() {
        while (pos < s.length() && " \t\n\r".indexOf(s.charAt(pos)) >= 0) pos++;
    }
}
