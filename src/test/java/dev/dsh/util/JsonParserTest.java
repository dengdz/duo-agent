package dev.dsh.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonParserTest {

    @Test
    void 解析对象与嵌套数组() {
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
    void 解析基本类型() {
        var parsed = (Map<?, ?>) JsonParser.parse(
                "{\"name\": \"todo_write\", \"n\": 30, \"flag\": true, \"empty\": null, \"pi\": 3.14}");
        assertEquals("todo_write", parsed.get("name"));
        assertEquals(30, parsed.get("n"));
        assertEquals(true, parsed.get("flag"));
        assertNull(parsed.get("empty"));
        assertEquals(3.14, (Double) parsed.get("pi"), 1e-9);
    }

    @Test
    void 解析转义与空白() {
        var parsed = (Map<?, ?>) JsonParser.parse("  { \"k\": \"a\\\"b\\\\c\\/d\" }  ");
        assertEquals("a\"b\\c/d", parsed.get("k"));
    }

    @Test
    void 非法输入抛异常() {
        assertThrows(IllegalArgumentException.class, () -> JsonParser.parse("{not valid"));
        assertThrows(IllegalArgumentException.class, () -> JsonParser.parse("[1, 2"));
    }

    @Test
    void 顶层数组() {
        var parsed = (List<?>) JsonParser.parse("[1, \"two\", false]");
        assertEquals(List.of(1, "two", false), parsed);
    }
}
