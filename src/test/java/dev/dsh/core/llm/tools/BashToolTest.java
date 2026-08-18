package dev.dsh.core.llm.tools;

import dev.dsh.core.llm.ToolRegistryImpl;
import dev.dsh.model.llm.ContentBlock;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BashToolTest {

    @Test
    void 执行简单命令() {
        var registry = new ToolRegistryImpl();
        registry.register(new BashTool().getDefinition());

        var result = registry.execute("bash", java.util.Map.of("command", "echo hello"));

        assertFalse(result.isError());
        var text = ((ContentBlock.Text) result.content().getFirst()).text();
        assertEquals("hello\n", text);
    }

    @Test
    void 指定工作目录() throws IOException {
        var tmp = Files.createTempDirectory("bash-test");
        Files.writeString(tmp.resolve("marker.txt"), "in here");

        var registry = new ToolRegistryImpl();
        registry.register(new BashTool().getDefinition());

        var result = registry.execute("bash", java.util.Map.of(
                "command", "cat marker.txt",
                "cwd", tmp.toString()
        ));

        assertFalse(result.isError());
        var text = ((ContentBlock.Text) result.content().getFirst()).text();
        assertTrue(text.contains("in here"), text);
    }

    @Test
    void 非零退出码返回错误() {
        var registry = new ToolRegistryImpl();
        registry.register(new BashTool().getDefinition());

        var result = registry.execute("bash", java.util.Map.of("command", "exit 42"));

        assertFalse(result.isError()); // 工具本身没抛异常，结果中带上退出码
        var text = ((ContentBlock.Text) result.content().getFirst()).text();
        assertTrue(text.contains("退出码 42"), text);
    }

    @Test
    void 命令为空返回错误() {
        var registry = new ToolRegistryImpl();
        registry.register(new BashTool().getDefinition());

        var result = registry.execute("bash", java.util.Map.of("command", ""));

        assertFalse(result.isError());
        var text = ((ContentBlock.Text) result.content().getFirst()).text();
        assertTrue(text.contains("缺少 command"), text);
    }
}
