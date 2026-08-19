package dev.duo.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FrontmatterParser 测试。
 *
 * @author zhangyl
 * @date 2026-08-19
 */
class FrontmatterParserTest {

    @Test
    void testParse_whenStandardFormat_thenFieldsExtracted() {
        var markdown = """
                ---
                name: test-skill
                description: A test skill
                ---
                # Test Content
                Body text here.
                """;

        var result = FrontmatterParser.parse(markdown);
        
        assertEquals("test-skill", result.frontmatter().get("name"));
        assertEquals("A test skill", result.frontmatter().get("description"));
        assertTrue(result.body().contains("# Test Content"));
        assertTrue(result.body().contains("Body text here."));
    }

    @Test
    void testParse_whenNoFrontmatter_thenAllAsBody() {
        var markdown = """
                # Just Body
                No frontmatter here.
                """;

        var result = FrontmatterParser.parse(markdown);
        
        assertTrue(result.frontmatter().isEmpty());
        assertEquals(markdown, result.body());
    }

    @Test
    void testParse_whenUnclosedFrontmatter_thenAllAsBody() {
        var markdown = """
                ---
                name: test
                # Missing closing delimiter
                Body content
                """;

        var result = FrontmatterParser.parse(markdown);
        
        assertTrue(result.frontmatter().isEmpty());
        assertEquals(markdown, result.body());
    }

    @Test
    void testParse_whenEmptyFrontmatter_thenEmptyMap() {
        var markdown = """
                ---
                ---
                Body only
                """;

        var result = FrontmatterParser.parse(markdown);
        
        assertTrue(result.frontmatter().isEmpty());
        assertEquals("Body only", result.body());
    }

    @Test
    void testParse_whenCommentLines_thenSkipped() {
        var markdown = """
                ---
                # This is a comment
                name: my-skill
                # Another comment
                description: desc
                ---
                body
                """;

        var result = FrontmatterParser.parse(markdown);
        
        assertEquals("my-skill", result.frontmatter().get("name"));
        assertEquals("desc", result.frontmatter().get("description"));
    }

    @Test
    void testParse_whenInvalidLine_thenThrowIllegalArgument() {
        var markdown = """
                ---
                invalid line without colon
                name: test
                ---
                body
                """;

        assertThrows(IllegalArgumentException.class, () -> FrontmatterParser.parse(markdown));
    }

    @Test
    void testParse_whenMultilineBody_thenBodyPreserved() {
        var markdown = """
                ---
                name: skill-a
                ---
                Line 1
                Line 2
                Line 3
                """;

        var result = FrontmatterParser.parse(markdown);
        
        assertEquals("skill-a", result.frontmatter().get("name"));
        assertEquals("Line 1\nLine 2\nLine 3", result.body());
    }

    @Test
    void testIsValidSkillName_whenKebabCase_thenValid() {
        assertTrue(FrontmatterParser.isValidSkillName("code-review"));
        assertTrue(FrontmatterParser.isValidSkillName("git-commit"));
        assertTrue(FrontmatterParser.isValidSkillName("test"));
        assertTrue(FrontmatterParser.isValidSkillName("test-123"));
        assertTrue(FrontmatterParser.isValidSkillName("a"));
    }

    @Test
    void testIsValidSkillName_whenInvalidFormats_thenInvalid() {
        assertFalse(FrontmatterParser.isValidSkillName("CodeReview"));
        assertFalse(FrontmatterParser.isValidSkillName("code_review"));
        assertFalse(FrontmatterParser.isValidSkillName("code review"));
        assertFalse(FrontmatterParser.isValidSkillName("-start-dash"));
        assertFalse(FrontmatterParser.isValidSkillName("end-dash-"));
        assertFalse(FrontmatterParser.isValidSkillName("double--dash"));
        assertFalse(FrontmatterParser.isValidSkillName(""));
        assertFalse(FrontmatterParser.isValidSkillName(null));
    }
}
