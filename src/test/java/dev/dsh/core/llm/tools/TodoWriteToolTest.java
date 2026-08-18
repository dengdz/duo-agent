package dev.dsh.core.llm.tools;

import dev.dsh.util.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TodoWriteToolTest {

    @Test
    void 解析嵌套参数并记录任务() {
        var tool = new TodoWriteTool();
        var json = "{\"todos\": [{\"content\": \"买牛奶\", \"status\": \"pending\"}, "
                + "{\"content\": \"写报告\", \"status\": \"in_progress\"}]}";

        @SuppressWarnings("unchecked")
        var args = (Map<String, Object>) JsonParser.parse(json);
        var result = tool.getDefinition().executor().apply(args);

        assertFalse(result.isError(), result.content().getFirst().toString());
        var todos = tool.getTodos();
        assertEquals(2, todos.size());
        assertEquals("买牛奶", todos.get(0).content());
        assertEquals("pending", todos.get(0).status());
        assertEquals("写报告", todos.get(1).content());
        assertEquals("in_progress", todos.get(1).status());
    }

    @Test
    void 缺少todos参数返回提示() {
        var tool = new TodoWriteTool();
        var result = tool.getDefinition().executor().apply(Map.of());
        // 业务性提示（非系统异常），isError 为 false，但内容应说明原因
        assertFalse(result.isError());
        assertTrue(result.content().getFirst().toString().contains("缺少 todos"));
    }

    @Test
    void 再次调用覆盖整个列表() {
        var tool = new TodoWriteTool();
        @SuppressWarnings("unchecked")
        var args1 = (Map<String, Object>) JsonParser.parse("{\"todos\":[{\"content\":\"a\",\"status\":\"pending\"}]}");
        tool.getDefinition().executor().apply(args1);

        @SuppressWarnings("unchecked")
        var args2 = (Map<String, Object>) JsonParser.parse("{\"todos\":[{\"content\":\"b\",\"status\":\"pending\"},{\"content\":\"c\",\"status\":\"pending\"}]}");
        tool.getDefinition().executor().apply(args2);

        var todos = tool.getTodos();
        assertEquals(2, todos.size());
        assertEquals("b", todos.get(0).content());
        assertEquals("c", todos.get(1).content());
    }

    @Test
    void 真实管线参数格式可解析() {
        // 模拟 DeepSeek 实际传回的 arguments 字符串
        var argsJson = "{\"todos\":[{\"content\":\"买牛奶\",\"status\":\"pending\"},{\"content\":\"写报告\",\"status\":\"pending\"}]}";
        var parsed = JsonParser.parse(argsJson);
        assertTrue(parsed instanceof List || parsed instanceof Map);
    }
}
