package dev.duo.tool;

import dev.duo.model.llm.ToolExecution;
import dev.duo.api.skill.Skill;
import dev.duo.api.skill.SkillSource;
import dev.duo.core.skill.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SkillTool 测试。
 *
 * @author zhangyl
 * @date 2026-08-19
 */
class SkillToolTest {

    private SkillRegistry registry;
    private SkillTool tool;

    @BeforeEach
    void setUp() throws Exception {
        registry = new SkillRegistry();
        tool = new SkillTool(registry);
        
        // 注册一个 mock provider
        registry.register(new MockSkillProvider());
    }

    @Test
    void testGetDefinition_whenCalled_thenNameIsSkill() throws Exception {
        var definition = tool.getDefinition();
        
        assertEquals("skill", definition.name());
        assertNotNull(definition.description());
        assertNotNull(definition.parameters());
        assertNotNull(definition.executor());
    }

    @Test
    void testExecute_whenSkillExists_thenReturnContent() throws Exception {
        var result = tool.getDefinition().executor().execute(ToolExecution.of(Map.of("name", "test-skill")));
        
        assertFalse(result.isError());
        var block = result.content().get(0);
        assertTrue(block instanceof dev.duo.model.llm.ContentBlock.Text);
        var text = ((dev.duo.model.llm.ContentBlock.Text) block).text();
        assertEquals("# Test Skill\nComplete instructions here.", text);
    }

    @Test
    void testExecute_whenSkillNotExistent_thenReturnNotFoundMessage() throws Exception {
        var result = tool.getDefinition().executor().execute(ToolExecution.of(Map.of("name", "non-existent")));
        
        assertFalse(result.isError());
        var block = result.content().get(0);
        assertTrue(block instanceof dev.duo.model.llm.ContentBlock.Text);
        var text = ((dev.duo.model.llm.ContentBlock.Text) block).text();
        assertTrue(text.contains("不存在"));
    }

    @Test
    void testExecute_whenNameMissing_thenReturnErrorMessage() throws Exception {
        var result = tool.getDefinition().executor().execute(ToolExecution.of(Map.of()));
        
        assertFalse(result.isError());
        var block = result.content().get(0);
        assertTrue(block instanceof dev.duo.model.llm.ContentBlock.Text);
        var text = ((dev.duo.model.llm.ContentBlock.Text) block).text();
        assertTrue(text.contains("缺少技能名称"));
    }

    @Test
    void testExecute_whenNameBlank_thenReturnErrorMessage() throws Exception {
        var result = tool.getDefinition().executor().execute(ToolExecution.of(Map.of("name", "  ")));
        
        assertFalse(result.isError());
        var block = result.content().get(0);
        assertTrue(block instanceof dev.duo.model.llm.ContentBlock.Text);
        var text = ((dev.duo.model.llm.ContentBlock.Text) block).text();
        assertTrue(text.contains("缺少技能名称"));
    }

    /**
     * Mock provider for testing.
     */
    private static class MockSkillProvider implements dev.duo.api.skill.SkillProvider {
        @Override
        public java.util.List<dev.duo.api.skill.SkillCandidate> discover() {
            return java.util.List.of(
                    new dev.duo.api.skill.SkillCandidate(
                            "test-skill",
                            "A test skill",
                            SkillSource.PROJECT,
                            "mock",
                            100,
                            "path"
                    )
            );
        }

        @Override
        public Skill load(String name) {
            if ("test-skill".equals(name)) {
                return new Skill(
                        "test-skill",
                        "A test skill",
                        "# Test Skill\nComplete instructions here.",
                        SkillSource.PROJECT,
                        "mock",
                        "path"
                );
            }
            return null;
        }
    }
}
