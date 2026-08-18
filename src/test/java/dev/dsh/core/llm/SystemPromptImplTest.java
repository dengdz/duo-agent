package dev.dsh.core.llm;

import dev.dsh.model.llm.PromptAssembly;
import dev.dsh.model.llm.PromptContext;
import dev.dsh.model.llm.PromptSection;
import dev.dsh.model.llm.ToolProviderResult;
import dev.dsh.model.llm.ToolSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SystemPromptImpl} 的测试。
 *
 * @author zhangyl
 * @date 2026-08-18
 */
class SystemPromptImplTest {

    @Test
    void testAssemble_whenDefault_thenIncludesIdentity() {
        var sp = new SystemPromptImpl("", true);
        var assembly = sp.assemble();

        assertFalse(assembly.sections().isEmpty());
        assertEquals("harness:identity", assembly.sections().getFirst().name());
    }

    @Test
    void testAssemble_whenSectionsGiven_thenSortedByOrder() {
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
    void testSection_whenDuplicateName_thenThrows() {
        var sp = new SystemPromptImpl("", false);
        sp.section(new PromptSection("test", 0, "text"));
        assertThrows(IllegalArgumentException.class, () ->
                sp.section(new PromptSection("test", 1, "other"))
        );
    }

    @Test
    void testSection_whenRegistered_thenInAssembly() {
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
    void testAssemble_whenContextsGiven_thenSortedByOrder() {
        var sp = new SystemPromptImpl("", false);
        sp.context(new PromptContext("ctx2", 200, "second"));
        sp.context(new PromptContext("ctx1", 100, "first"));

        var assembly = sp.assemble();
        assertEquals(2, assembly.contexts().size());
        assertEquals("ctx1", assembly.contexts().get(0).name());
        assertEquals("ctx2", assembly.contexts().get(1).name());
    }

    @Test
    void testAssemble_whenToolProvider_thenCollectsSchemas() {
        var sp = new SystemPromptImpl("", false);
        sp.tools(assembly -> new ToolProviderResult(List.of(
                new ToolSchema("tool1", "First tool", Map.of("type", "object"))
        )));

        var assembly = sp.assemble();
        assertEquals(1, assembly.tools().size());
        assertEquals("tool1", assembly.tools().getFirst().name());
    }

    @Test
    void testAssemble_whenCompleteSection_thenOnlyComplete() {
        var sp = new SystemPromptImpl("", false);
        sp.section(new PromptSection("normal", 100, "normal text"));
        sp.section(new PromptSection("complete", 200, "COMPLETE", true));

        var assembly = sp.assemble();
        assertEquals(1, assembly.sections().size());
        assertEquals("COMPLETE", assembly.sections().getFirst().text());
    }

    @Test
    void testAssemble_whenMultipleComplete_thenThrows() {
        var sp = new SystemPromptImpl("", false);
        sp.section(new PromptSection("a", 0, "a", true));
        sp.section(new PromptSection("b", 1, "b", true));

        assertThrows(IllegalStateException.class, sp::assemble);
    }

    @Test
    void testRenderPrompt_whenSections_thenJoinedWithBlankLine() {
        var sp = new SystemPromptImpl("", false);
        sp.section(new PromptSection("a", 0, "Hello"));
        sp.section(new PromptSection("b", 1, "World"));

        var assembly = sp.assemble();
        var rendered = SystemPromptImpl.renderPrompt(assembly);
        assertEquals("Hello\n\nWorld", rendered);
    }

    @Test
    void testRenderPrompt_whenEmptySection_thenSkipped() {
        var sp = new SystemPromptImpl("", false);
        sp.section(new PromptSection("a", 0, ""));
        sp.section(new PromptSection("b", 1, "Visible"));

        var assembly = sp.assemble();
        var rendered = SystemPromptImpl.renderPrompt(assembly);
        assertEquals("Visible", rendered);
    }

    @Test
    void testRenderPrompt_whenVariable_thenInterpolated() {
        var sp = new SystemPromptImpl("", false);
        sp.variable("model", ctx -> "deepseek-v4");
        sp.section(new PromptSection("test", 0, "You are {{model}}."));

        var assembly = sp.assemble();
        var rendered = SystemPromptImpl.renderPrompt(assembly);
        assertEquals("You are deepseek-v4.", rendered);
    }

    @Test
    void testRenderPrompt_whenUnknownVariable_thenThrows() {
        var sp = new SystemPromptImpl("", false);
        sp.section(new PromptSection("test", 0, "{{unknown}}"));

        var assembly = sp.assemble();
        assertThrows(IllegalArgumentException.class, () ->
                SystemPromptImpl.renderPrompt(assembly)
        );
    }

    @Test
    void testSection_whenDisposed_thenRemoved() throws Exception {
        var sp = new SystemPromptImpl("", false);
        var disposer = sp.section(new PromptSection("temp", 0, "temp text"));
        // 2 = deployment:persona + temp
        assertEquals(2, sp.assemble().sections().size());

        disposer.close();
        // 1 = deployment:persona
        assertEquals(1, sp.assemble().sections().size());
    }

    @Test
    void testAssemble_whenMultipleProviders_thenMergesSchemas() {
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