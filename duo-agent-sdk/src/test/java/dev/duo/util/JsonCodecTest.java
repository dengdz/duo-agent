package dev.duo.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link JsonCodec} 的序列化测试。
 * <p>
 * 重点覆盖：Java 数组序列化（工具 schema 的 enum/default 数组场景，
 * 缺失该分支会输出对象哈希垃圾）与 RFC 8259 全控制字符转义。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-21
 */
class JsonCodecTest {

    @Test
    void shouldSerializeObjectArray() {
        assertEquals("[\"a\", \"b\"]", JsonCodec.toJson(new String[]{"a", "b"}),
                "String[] 应序列化为 JSON 数组（而非对象哈希）");
    }

    @Test
    void shouldSerializePrimitiveArray() {
        assertEquals("[1, 2, 3]", JsonCodec.toJson(new int[]{1, 2, 3}),
                "int[] 应序列化为 JSON 数组");
    }

    @Test
    void shouldSerializeArrayInsideSchemaMap() {
        // 工具 schema 的典型形态：enum 数组
        var schema = Map.of("type", "string", "enum", new String[]{"read", "write"});
        var json = JsonCodec.toJson(schema);
        assertEquals(true, json.contains("[\"read\", \"write\"]"),
                "Map 内的数组应序列化为 JSON 数组: " + json);
    }

    @Test
    void shouldEscapeAllControlCharacters() {
        // \b \f（此前缺失）与未命名控制字符（以四位十六进制转义）
        assertEquals("a\\bb\\fc\\u0001d", JsonCodec.escapeJson("a\bb\fc\u0001d"),
                "全部控制字符必须转义");
    }

    @Test
    void shouldEscapeNamedControls() {
        assertEquals("\\n\\r\\t\\\"\\\\", JsonCodec.escapeJson("\n\r\t\"\\"),
                "命名转义应保持既有行为");
    }

    @Test
    void shouldSerializeNestedStructures() {
        // LinkedHashMap 保证键序确定（Map.of 无序会让断言不稳定）
        var value = new java.util.LinkedHashMap<String, Object>();
        value.put("list", List.of(1, Map.of("k", "v")));
        value.put("s", "x");
        assertEquals("{\"list\": [1, {\"k\": \"v\"}], \"s\": \"x\"}", JsonCodec.toJson(value),
                "嵌套 Map/List 序列化保持既有行为");
    }
}
