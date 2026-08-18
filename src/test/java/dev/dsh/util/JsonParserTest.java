package dev.dsh.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link JsonParser} 的测试。
 *
 * @author zhangyl
 * @date 2026-08-18
 */
class JsonParserTest {

    @Test
    void testParse_whenNestedObject_thenParsesArrays() {
        var json = "{\"todos\": [{\"content\": \"买牛奶\", \"status\": \"pending\"}, "
                + "{\"content\": \"写报告\", \"status\": \"pending\"}]}";

        var parsed = (Map<?, ?>) JsonParser.parse(json);
        var todos = (List<?>) parsed.get("todos");
        assertEquals(2, todos.size());

        var first = (Map<?, ?>) todos.get(0);
        assertEquals("买牛奶", first.get("content"));
        assertEquals("pending", first.get("status"));
    }

    @Test
    void testParse_whenPrimitiveTypes_thenParsesValues() {
        var parsed = (Map<?, ?>) JsonParser.parse(
                "{\"name\": \"todo_write\", \"n\": 30, \"flag\": true, \"empty\": null, \"pi\": 3.14}");
        assertEquals("todo_write", parsed.get("name"));
        assertEquals(30, parsed.get("n"));
        assertEquals(true, parsed.get("flag"));
        assertNull(parsed.get("empty"));
        assertEquals(3.14, (Double) parsed.get("pi"), 1e-9);
    }

    @Test
    void testParse_whenEscapesAndWhitespace_thenDecodes() {
        var parsed = (Map<?, ?>) JsonParser.parse("  { \"k\": \"a\\\"b\\\\c\\/d\" }  ");
        assertEquals("a\"b\\c/d", parsed.get("k"));
    }

    @Test
    void testParse_whenMalformedJson_thenThrows() {
        assertThrows(IllegalArgumentException.class, () -> JsonParser.parse("{not valid"));
        assertThrows(IllegalArgumentException.class, () -> JsonParser.parse("[1, 2"));
    }

    @Test
    void testParse_whenTopLevelArray_thenParsesList() {
        var parsed = (List<?>) JsonParser.parse("[1, \"two\", false]");
        assertEquals(List.of(1, "two", false), parsed);
    }
}