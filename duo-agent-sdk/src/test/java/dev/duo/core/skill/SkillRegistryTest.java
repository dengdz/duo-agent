package dev.duo.core.skill;

import dev.duo.api.skill.Skill;
import dev.duo.api.skill.SkillCandidate;
import dev.duo.api.skill.SkillProvider;
import dev.duo.api.skill.SkillSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SkillRegistry 测试。
 *
 * @author zhangyl
 * @date 2026-08-19
 */
class SkillRegistryTest {

    private SkillRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SkillRegistry();
    }

    @Test
    void testRegister_whenSingleProvider_thenListAllSkills() throws IOException {
        var provider = new MockProvider(List.of(
                new SkillCandidate("skill-a", "desc-a", SkillSource.PROJECT, "mock", 100, "path-a")
        ));

        registry.register(provider);
        
        var candidates = registry.listAll();
        assertEquals(1, candidates.size());
        assertEquals("skill-a", candidates.get(0).name());
    }

    @Test
    void testListAll_whenMultipleProviders_thenMerged() throws IOException {
        var provider1 = new MockProvider(List.of(
                new SkillCandidate("skill-a", "desc-a", SkillSource.PROJECT, "p1", 100, "path-a")
        ));
        var provider2 = new MockProvider(List.of(
                new SkillCandidate("skill-b", "desc-b", SkillSource.USER, "p2", 200, "path-b")
        ));

        registry.register(provider1);
        registry.register(provider2);

        var candidates = registry.listAll();
        assertEquals(2, candidates.size());
    }

    @Test
    void testListAll_whenRankConflict_thenSmallerRankWins() throws IOException {
        var provider1 = new MockProvider(List.of(
                new SkillCandidate("skill-a", "project-desc", SkillSource.PROJECT, "p1", 100, "path-1")
        ));
        var provider2 = new MockProvider(List.of(
                new SkillCandidate("skill-a", "user-desc", SkillSource.USER, "p2", 200, "path-2")
        ));

        registry.register(provider1);
        registry.register(provider2);

        var candidates = registry.listAll();
        assertEquals(1, candidates.size());
        assertEquals("project-desc", candidates.get(0).description());
        assertEquals(100, candidates.get(0).rank());
    }

    @Test
    void testListAll_whenSameRank_thenFirstRegisteredWins() throws IOException {
        var provider1 = new MockProvider(List.of(
                new SkillCandidate("skill-a", "first", SkillSource.CUSTOM, "p1", 300, "path-1")
        ));
        var provider2 = new MockProvider(List.of(
                new SkillCandidate("skill-a", "second", SkillSource.CUSTOM, "p2", 300, "path-2")
        ));

        registry.register(provider1);
        registry.register(provider2);

        var candidates = registry.listAll();
        assertEquals(1, candidates.size());
        assertEquals("first", candidates.get(0).description());
    }

    @Test
    void testLoad_whenSkillExists_thenReturnSkill() throws IOException {
        var skill = new Skill("skill-a", "desc", "content", SkillSource.PROJECT, "mock", "path");
        var provider = new MockProvider(
                List.of(new SkillCandidate("skill-a", "desc", SkillSource.PROJECT, "mock", 100, "path")),
                skill
        );

        registry.register(provider);
        
        var loaded = registry.load("skill-a");
        assertNotNull(loaded);
        assertEquals("skill-a", loaded.name());
        assertEquals("content", loaded.content());
    }

    @Test
    void testLoad_whenSkillNotExistent_thenReturnNull() {
        var loaded = registry.load("non-existent");
        assertNull(loaded);
    }

    @Test
    void testListAll_whenProviderRegisteredAfter_thenCacheInvalidated() throws IOException {
        var provider1 = new MockProvider(List.of(
                new SkillCandidate("skill-a", "desc-a", SkillSource.PROJECT, "p1", 100, "path-a")
        ));

        registry.register(provider1);
        assertEquals(1, registry.listAll().size());

        var provider2 = new MockProvider(List.of(
                new SkillCandidate("skill-b", "desc-b", SkillSource.USER, "p2", 200, "path-b")
        ));

        registry.register(provider2);
        assertEquals(2, registry.listAll().size());
    }

    /**
     * Mock SkillProvider for testing.
     */
    private static class MockProvider implements SkillProvider {
        private final List<SkillCandidate> candidates;
        private final Skill skill;

        MockProvider(List<SkillCandidate> candidates) {
            this(candidates, null);
        }

        MockProvider(List<SkillCandidate> candidates, Skill skill) {
            this.candidates = candidates;
            this.skill = skill;
        }

        @Override
        public List<SkillCandidate> discover() {
            return candidates;
        }

        @Override
        public Skill load(String name) {
            return skill != null && skill.name().equals(name) ? skill : null;
        }
    }
}
