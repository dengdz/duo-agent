package dev.duo.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link JsonWriter} 的序列化测试。
 *
 * @author zhangyl
 * @date 2026-08-19
 */
class JsonWriterTest {

    @Test
    void nestedValueRoundTripsThroughParser() {
        var json = JsonWriter.toJson(Map.of("s", "v", "n", 1, "b", true,
                "list", List.of(1, "x", Map.of("k", "v"))));
        var parsed = JsonParser.parse(json);
        assertEquals(Map.of("s", "v", "n", 1, "b", true,
                "list", List.of(1, "x", Map.of("k", "v"))), parsed);
    }

    @Test
    void nonFiniteDoubleIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> JsonWriter.toJson(Map.of("bad", Double.NaN)),
                "NaN 不是合法 JSON，必须拒绝而非变形");
        assertThrows(IllegalArgumentException.class,
                () -> JsonWriter.toJson(Map.of("bad", Double.POSITIVE_INFINITY)));
    }
}
