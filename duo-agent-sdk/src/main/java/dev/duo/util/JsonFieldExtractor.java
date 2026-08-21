package dev.duo.util;

/**
 * 零依赖的 JSON 字段提取工具。
 * <p>
 * 提供基于字符串匹配的 JSON 字段提取（字符串值、对象、整数、括号配对），
 * 供各协议 SSE 解析器共用（Chat Completions / Anthropic Messages / Responses），
 * 替代第三方 JSON 库以满足零依赖红线。提取按字段名首次出现匹配，
 * 仅适用于已知结构的协议响应，不用于任意外部 JSON。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-21
 */
public final class JsonFieldExtractor {

    /** 转义序列长度：反斜杠 + 被转义字符 = 2 个字符。 */
    private static final int ESCAPE_SEQUENCE_LENGTH = 2;

    private JsonFieldExtractor() {
        // 工具类，禁止实例化
    }

    /** 从 choices[0].delta.tool_calls[0] 中提取工具调用 id。 */
    public static String extractToolCallId(String json) {
        var arrIdx = json.indexOf("\"tool_calls\"");
        if (arrIdx < 0) {
            return null;
        }
        var objStart = json.indexOf("{", arrIdx);
        if (objStart < 0) {
            return null;
        }
        var objEnd = findMatchingBrace(json, objStart);
        if (objEnd < 0) {
            return null;
        }
        return extractString(json.substring(objStart, objEnd + 1), "id");
    }

    /** 提取第一个 tool_call 的 function 对象字符串。 */
    private static String extractToolCallFunctionObject(String json) {
        var arrIdx = json.indexOf("\"tool_calls\"");
        if (arrIdx < 0) {
            return null;
        }
        var funcIdx = json.indexOf("\"function\"", arrIdx);
        if (funcIdx < 0) {
            return null;
        }
        var objStart = json.indexOf("{", funcIdx);
        if (objStart < 0) {
            return null;
        }
        var objEnd = findMatchingBrace(json, objStart);
        if (objEnd < 0) {
            return null;
        }
        return json.substring(objStart, objEnd + 1);
    }

    /** 从第一个 tool_call.function 中提取工具名。 */
    public static String extractToolCallFunctionName(String json) {
        var funcObj = extractToolCallFunctionObject(json);
        return funcObj != null ? extractString(funcObj, "name") : null;
    }

    /** 从第一个 tool_call.function 中提取参数增量字符串。 */
    public static String extractToolCallArguments(String json) {
        var funcObj = extractToolCallFunctionObject(json);
        return funcObj != null ? extractString(funcObj, "arguments") : null;
    }

    /** 查找与指定开括号匹配的同层闭括号位置（跳过字符串内容）。 */
    private static int findMatchingBrace(String json, int openIdx) {
        var depth = 1;
        var inString = false;
        var i = openIdx + 1;
        while (depth > 0 && i < json.length()) {
            var c = json.charAt(i);
            if (c == '\\') {
                // 跳过转义序列（反斜杠 + 被转义字符）
                i += ESCAPE_SEQUENCE_LENGTH;
                continue;
            }
            if (c == '"') {
                inString = !inString;
            } else if (!inString) {
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                }
            }
            i++;
        }
        return depth == 0 ? i - 1 : -1;
    }

    /** 提取 JSON 字符串值。查找 "fieldName": "value" 格式。 */
    public static String extractString(String json, String fieldName) {
        var search = "\"" + fieldName + "\":";
        var idx = json.indexOf(search);
        if (idx < 0) {
            return null;
        }
        var start = idx + search.length();
        // 跳过空格
        while (start < json.length() && json.charAt(start) == ' ') {
            start++;
        }
        if (start >= json.length() || json.charAt(start) != '"') {
            return null;
        }
        start++; // 跳过开头的引号
        var end = start;
        while (end < json.length()) {
            var c = json.charAt(end);
            if (c == '\\') {
                // 跳过转义序列（反斜杠 + 被转义字符）
                end += ESCAPE_SEQUENCE_LENGTH;
                continue;
            }
            if (c == '"') {
                break;
            }
            end++;
        }
        if (end >= json.length()) {
            return null;
        }
        return decodeEscapes(json, start, end);
    }

    /**
     * 单遍扫描解码 JSON 转义序列。
     * <p>
     * 不能用链式 replace 还原：先还原 {@code \\n} 再还原 {@code \\\\} 的顺序会把
     * 字面转义反斜杠+n（{@code \\n}）错误地变成换行——必须按位置单遍解码。
     * </p>
     */
    private static String decodeEscapes(String json, int start, int end) {
        var decoded = new StringBuilder(end - start);
        int i = start;
        while (i < end) {
            char c = json.charAt(i);
            if (c != '\\' || i + 1 >= end) {
                decoded.append(c);
                i++;
                continue;
            }
            char next = json.charAt(i + 1);
            if (next == 'u' && i + 5 < end) {
                decoded.append((char) Integer.parseInt(json.substring(i + 2, i + 6), 16));
                i += 6;
            } else {
                decoded.append(switch (next) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case '"' -> '"';
                    case '\\' -> '\\';
                    case '/' -> '/';
                    default -> next;
                });
                i += 2;
            }
        }
        return decoded.toString();
    }

    /** 提取 JSON 对象（从 key 后的第一个 { 到匹配的 }，跳过字符串内容）。 */
    public static String extractObject(String json, String fieldName) {
        var search = "\"" + fieldName + "\":";
        var idx = json.indexOf(search);
        if (idx < 0) {
            return null;
        }
        var start = json.indexOf("{", idx + search.length());
        if (start < 0) {
            return null;
        }
        var depth = 1;
        var inString = false;
        var end = start + 1;
        while (depth > 0 && end < json.length()) {
            var c = json.charAt(end);
            if (c == '\\') {
                // 跳过转义序列（反斜杠 + 被转义字符）
                end += ESCAPE_SEQUENCE_LENGTH;
                continue;
            }
            if (c == '"') {
                inString = !inString;
            } else if (!inString) {
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                }
            }
            end++;
        }
        return json.substring(start, end);
    }

    /** 提取 JSON 整数值。 */
    public static Integer extractInt(String json, String fieldName) {
        var search = "\"" + fieldName + "\":";
        var idx = json.indexOf(search);
        if (idx < 0) {
            return null;
        }
        var start = idx + search.length();
        // 跳过空格和冒号
        while (start < json.length()) {
            var c = json.charAt(start);
            if (c != ' ' && c != ':') {
                break;
            }
            start++;
        }
        var end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        if (end == start) {
            return null;
        }
        return Integer.parseInt(json.substring(start, end));
    }
}
