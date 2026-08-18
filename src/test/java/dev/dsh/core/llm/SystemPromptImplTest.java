package dev.dsh.core.llm;

import dev.dsh.model.llm.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SystemPromptImpl} 的测试。
 */
class SystemPromptImplTest {

    @Test
    void 默认注册包含harness身份() {
        var sp = new SystemPromptImpl("", true);
        var assembly = sp.assemble();

        assertFalse(assembly.sections().isEmpty());
        assertEquals("harness:identity", assembly.sections().getFirst().name());
    }

    @Test
    void section按order排序() {
        var sp = new SystemPromptImpl("", false);
        sp.section(new PromptSection("middle", 100, "middle text"));
        sp.section(new PromptSection("first", -50, "first text"));
        sp.section(new PromptSection("last", 200, "last text"));

        var assembly = sp.assemble();
        // 4 = deployment:persona + first + middle + last
        assertEquals(4, assembly.sections().size());
        assertEquals("first", assembly.sections().get(0).name());
        assertEquals("deployment:persona", assembly.sections().get(1).name());
        assertEquals("middle", assembly.sections().get(2).name());
        assertEquals("last", assembly.sections().get(3).name());
    }

    @Test
    void 重复section抛出异常() {
        var sp = new SystemPromptImpl("", false);
        sp.section(new PromptSection("test", 0, "text"));
        assertThrows(IllegalArgumentException.class, () ->
                sp.section(new PromptSection("test", 1, "other"))
        );
    }

    @Test
    void section注册后出现在assembly中() {
        var sp = new SystemPromptImpl("", false);
        sp.section(new PromptSection("custom", 50, "custom text"));

        var assembly = sp.assemble();
        var section = assembly.sections().stream()
                .filter(s -> s.name().equals("custom"))
                .findFirst();
        assertTrue(section.isPresent());
        assertEquals("custom text", section.get().text());
    }

    @Test
    void context按order排序() {
        var sp = new SystemPromptImpl("", false);
        sp.context(new PromptContext("ctx2", 200, "second"));
        sp.context(new PromptContext("ctx1", 100, "first"));

        var assembly = sp.assemble();
        assertEquals(2, assembly.contexts().size());
        assertEquals("ctx1", assembly.contexts().get(0).name());
        assertEquals("ctx2", assembly.contexts().get(1).name());
    }

    @Test
    void toolProvider贡献schema() {
        var sp = new SystemPromptImpl("", false);
        sp.tools(assembly -> new ToolProviderResult(List.of(
                new ToolSchema("tool1", "First tool", Map.of("type", "object"))
        )));

        var assembly = sp.assemble();
        assertEquals(1, assembly.tools().size());
        assertEquals("tool1", assembly.tools().getFirst().name());
    }

    @Test
    void completeSection独占prompt() {
        var sp = new SystemPromptImpl("", false);
        sp.section(new PromptSection("normal", 100, "normal text"));
        sp.section(new PromptSection("complete", 200, "COMPLETE", true));

        var assembly = sp.assemble();
        assertEquals(1, assembly.sections().size());
        assertEquals("COMPLETE", assembly.sections().getFirst().text());
    }

    @Test
    void 多completeSection抛出异常() {
        var sp = new SystemPromptImpl("", false);
        sp.section(new PromptSection("a", 0, "a", true));
        sp.section(new PromptSection("b", 1, "b", true));

        assertThrows(IllegalStateException.class, sp::assemble);
    }

    @Test
    void renderPrompt连接section() {
        var sp = new SystemPromptImpl("", false);
        sp.section(new PromptSection("a", 0, "Hello"));
        sp.section(new PromptSection("b", 1, "World"));

        var assembly = sp.assemble();
        var rendered = SystemPromptImpl.renderPrompt(assembly);
        assertEquals("Hello\n\nWorld", rendered);
    }

    @Test
    void renderPrompt跳过空section() {
        var sp = new SystemPromptImpl("", false);
        sp.section(new PromptSection("a", 0, ""));
        sp.section(new PromptSection("b", 1, "Visible"));

        var assembly = sp.assemble();
        var rendered = SystemPromptImpl.renderPrompt(assembly);
        assertEquals("Visible", rendered);
    }

    @Test
    void 变量插值替换() {
        var sp = new SystemPromptImpl("", false);
        sp.variable("model", ctx -> "deepseek-v4");
        sp.section(new PromptSection("test", 0, "You are {{model}}."));

        var assembly = sp.assemble();
        var rendered = SystemPromptImpl.renderPrompt(assembly);
        assertEquals("You are deepseek-v4.", rendered);
    }

    @Test
    void 未知变量抛出异常() {
        var sp = new SystemPromptImpl("", false);
        sp.section(new PromptSection("test", 0, "{{unknown}}"));

        var assembly = sp.assemble();
        assertThrows(IllegalArgumentException.class, () ->
                SystemPromptImpl.renderPrompt(assembly)
        );
    }

    @Test
    void section注册和dispose() throws Exception {
        var sp = new SystemPromptImpl("", false);
        var disposer = sp.section(new PromptSection("temp", 0, "temp text"));
        // 2 = deployment:persona + temp
        assertEquals(2, sp.assemble().sections().size());

        disposer.close();
        // 1 = deployment:persona
        assertEquals(1, sp.assemble().sections().size());
    }

    @Test
    void 多个toolProvider合并schema() {
        var sp = new SystemPromptImpl("", false);
        sp.tools(assembly -> new ToolProviderResult(List.of(
                new ToolSchema("tool_a", "A", Map.of("type", "object"))
        )));
        sp.tools(assembly -> new ToolProviderResult(List.of(
                new ToolSchema("tool_b", "B", Map.of("type", "object"))
        )));

        var assembly = sp.assemble();
        assertEquals(2, assembly.tools().size());
    }
}