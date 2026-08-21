package dev.duo.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link JsonFieldExtractor} 的转义解码测试。
 * <p>
 * 重点覆盖转义还原顺序：链式 replace 会把字面转义反斜杠+n
 * 错误还原为换行，单遍扫描解码必须正确区分两种序列。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-21
 */
class JsonFieldExtractorTest {

    @Test
    void shouldDecodeNamedEscapes() {
        assertEquals("换行\n制表\t引号\"反斜杠\\",
                JsonFieldExtractor.extractString("{\"content\":\"换行\\n制表\\t引号\\\"反斜杠\\\\\"}", "content"),
                "命名转义应正确还原");
    }

    @Test
    void shouldNotMisdecodeLiteralBackslashFollowedByLetter() {
        // JSON 字面 a\\nb 表示字面 "a反斜杠nb"（Windows 路径场景）：
        // 链式 replace 的顺序缺陷会把它错误还原为 "a\换行b"
        assertEquals("a\\nb",
                JsonFieldExtractor.extractString("{\"content\":\"a\\\\nb\"}", "content"),
                "转义反斜杠后跟字母 n 不得被误还原为换行");
    }

    @Test
    void shouldDecodeUnicodeEscape() {
        assertEquals("中文",
                JsonFieldExtractor.extractString("{\"content\":\"\\u4e2d\\u6587\"}", "content"),
                "unicode 转义应解码为对应字符");
    }

    @Test
    void shouldReturnNullForMissingOrNonStringField() {
        assertEquals(null, JsonFieldExtractor.extractString("{\"other\":1}", "content"));
        assertEquals(null, JsonFieldExtractor.extractString("{\"content\":123}", "content"),
                "非字符串值应返回 null");
    }
}
