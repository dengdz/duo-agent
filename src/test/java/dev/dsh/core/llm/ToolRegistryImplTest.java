package dev.dsh.core.llm;

import dev.dsh.core.llm.tools.TodoWriteTool;
import dev.dsh.model.llm.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ToolRegistryImpl} 的测试。
 */
class ToolRegistryImplTest {

    @Test
    void 注册和查询工具() {
        var registry = new ToolRegistryImpl();
        var tool = new ToolDefinition("test", "Test tool", Map.of("type", "object"), args -> null);
        registry.register(tool);

        assertNotNull(registry.get("test"));
        assertEquals(1, registry.getAll().size());
    }

    @Test
    void 重复注册抛出异常() {
        var registry = new ToolRegistryImpl();
        var tool = new ToolDefinition("dup", "Dup", Map.of("type", "object"), args -> null);
        registry.register(tool);
        assertThrows(IllegalArgumentException.class, () -> registry.register(tool));
    }

    @Test
    void 执行工具() {
        var registry = new ToolRegistryImpl();
        registry.register(new ToolDefinition("echo", "Echo", Map.of(
                "type", "object",
                "properties", Map.of("text", Map.of("type", "string"))
        ), args -> {
            var text = args.getOrDefault("text", "none");
            return new dev.dsh.model.llm.ToolExecutionResult("ECHO: " + text);
        }));

        var result = registry.execute("echo", Map.of("text", "hello"));
        assertFalse(result.isError());
        assertEquals("ECHO: hello", ((dev.dsh.model.llm.ContentBlock.Text) result.content().getFirst()).text());
    }

    @Test
    void 执行未知工具抛出异常() {
        var registry = new ToolRegistryImpl();
        assertThrows(IllegalArgumentException.class, () ->
                registry.execute("unknown", Map.of())
        );
    }

    @Test
    void 工具执行异常返回错误结果() {
        var registry = new ToolRegistryImpl();
        registry.register(new ToolDefinition("crash", "Crash", Map.of("type", "object"), args -> {
            throw new RuntimeException("爆炸");
        }));

        var result = registry.execute("crash", Map.of());
        assertTrue(result.isError());
    }

    @Test
    void TodoWrite工具注册和执行() {
        var registry = new ToolRegistryImpl();
        var todoTool = new TodoWriteTool();
        registry.register(todoTool.getDefinition());

        var result = registry.execute("todo_write", Map.of(
                "todos", List.of(
                        Map.of("content", "任务1", "status", "pending"),
                        Map.of("content", "任务2", "status", "in_progress")
                )
        ));

        assertFalse(result.isError());
        assertEquals(2, todoTool.getTodos().size());
        assertEquals("任务1", todoTool.getTodos().getFirst().content());
    }

    @Test
    void 注册后dispose工具() throws Exception {
        var registry = new ToolRegistryImpl();
        var disposer = registry.register(new ToolDefinition("temp", "Temp", Map.of("type", "object"), args -> null));
        assertEquals(1, registry.getAll().size());

        disposer.close();
        assertEquals(0, registry.getAll().size());
    }
}