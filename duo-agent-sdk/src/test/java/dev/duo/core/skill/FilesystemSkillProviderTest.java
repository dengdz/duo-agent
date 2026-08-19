package dev.duo.core.skill;

import dev.duo.api.skill.SkillSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FilesystemSkillProvider 测试。
 *
 * @author zhangyl
 * @date 2026-08-19
 */
class FilesystemSkillProviderTest {

    @Test
    void testDiscover_whenValidSkillFile_thenFound(@TempDir Path tempDir) throws IOException {
        // 创建测试技能文件
        var skillFile = tempDir.resolve("test-skill.md");
        Files.writeString(skillFile, """
                ---
                name: test-skill
                description: A test skill for unit testing
                ---
                # Test Skill
                This is the skill content.
                """);

        var provider = new FilesystemSkillProvider(tempDir, SkillSource.PROJECT, "test", 100);
        var candidates = provider.discover();

        assertEquals(1, candidates.size());
        assertEquals("test-skill", candidates.get(0).name());
        assertEquals("A test skill for unit testing", candidates.get(0).description());
        assertEquals(100, candidates.get(0).rank());
    }

    @Test
    void testDiscover_whenMultipleFiles_thenAllFound(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("skill-a.md"), """
                ---
                name: skill-a
                description: Skill A
                ---
                Content A
                """);

        Files.writeString(tempDir.resolve("skill-b.md"), """
                ---
                name: skill-b
                description: Skill B
                ---
                Content B
                """);

        var provider = new FilesystemSkillProvider(tempDir, SkillSource.USER, "test", 200);
        var candidates = provider.discover();

        assertEquals(2, candidates.size());
    }

    @Test
    void testDiscover_whenNestedDirectory_thenFoundRecursively(@TempDir Path tempDir) throws IOException {
        var subDir = tempDir.resolve("subdir");
        Files.createDirectories(subDir);

        Files.writeString(tempDir.resolve("root-skill.md"), """
                ---
                name: root-skill
                description: Root
                ---
                Content
                """);

        Files.writeString(subDir.resolve("nested-skill.md"), """
                ---
                name: nested-skill
                description: Nested
                ---
                Content
                """);

        var provider = new FilesystemSkillProvider(tempDir, SkillSource.BUNDLED, "test", 600);
        var candidates = provider.discover();

        assertEquals(2, candidates.size());
    }

    @Test
    void testDiscover_whenInvalidName_thenSkipped(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("invalid.md"), """
                ---
                name: InvalidName
                description: Invalid kebab-case
                ---
                Content
                """);

        var provider = new FilesystemSkillProvider(tempDir, SkillSource.PROJECT, "test", 100);
        var candidates = provider.discover();

        assertTrue(candidates.isEmpty());
    }

    @Test
    void testDiscover_whenNameMissing_thenSkipped(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("no-name.md"), """
                ---
                description: No name field
                ---
                Content
                """);

        var provider = new FilesystemSkillProvider(tempDir, SkillSource.PROJECT, "test", 100);
        var candidates = provider.discover();

        assertTrue(candidates.isEmpty());
    }

    @Test
    void testDiscover_whenInvalidFrontmatter_thenSkipped(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("bad-format.md"), """
                ---
                invalid format without colon
                ---
                Content
                """);

        var provider = new FilesystemSkillProvider(tempDir, SkillSource.PROJECT, "test", 100);
        var candidates = provider.discover();

        assertTrue(candidates.isEmpty());
    }

    @Test
    void testLoad_whenSkillExists_thenReturnFullContent(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("my-skill.md"), """
                ---
                name: my-skill
                description: My skill description
                ---
                # My Skill
                Full content here.
                """);

        var provider = new FilesystemSkillProvider(tempDir, SkillSource.USER, "test", 300);
        provider.discover(); // Build cache

        var skill = provider.load("my-skill");

        assertNotNull(skill);
        assertEquals("my-skill", skill.name());
        assertEquals("My skill description", skill.description());
        assertTrue(skill.content().contains("# My Skill"));
        assertTrue(skill.content().contains("Full content here."));
        assertEquals(SkillSource.USER, skill.source());
    }

    @Test
    void testLoad_whenSkillNotExistent_thenReturnNull(@TempDir Path tempDir) throws IOException {
        var provider = new FilesystemSkillProvider(tempDir, SkillSource.PROJECT, "test", 100);
        provider.discover();

        var skill = provider.load("non-existent");
        assertNull(skill);
    }

    @Test
    void testDiscover_whenDirectoryMissing_thenReturnEmpty() throws IOException {
        var provider = new FilesystemSkillProvider(Path.of("/non/existent/path"), 
                SkillSource.BUNDLED, "test", 600);
        var candidates = provider.discover();

        assertTrue(candidates.isEmpty());
    }

    @Test
    void testDiscover_whenCalledTwice_thenCachedResult(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("skill.md"), """
                ---
                name: cached-skill
                description: Test caching
                ---
                Content
                """);

        var provider = new FilesystemSkillProvider(tempDir, SkillSource.PROJECT, "test", 100);
        
        var first = provider.discover();
        var second = provider.discover();

        assertSame(first, second); // 同一实例，证明缓存生效
    }
}
