package dev.duo.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * YAML frontmatter 解析器（v1 简化版）。
 * <p>
 * 支持格式：
 * <pre>
 * ---
 * key: value
 * another: value with spaces
 * ---
 * body content
 * </pre>
 * 仅支持单行 {@code key: value} 格式，不支持嵌套、数组、多行值。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public class FrontmatterParser {

    private static final String DELIMITER = "---";
    private static final Pattern KEY_VALUE = Pattern.compile("^([a-z][a-z0-9_-]*):(.*)$");
    private static final Pattern SKILL_NAME = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    private FrontmatterParser() {
        // 工具类，禁止实例化
    }

    /**
     * 解析结果：frontmatter 字段 + body 正文。
     */
    public record ParseResult(
            /** frontmatter 中的键值对（不可变）。 */
            Map<String, String> frontmatter,
            /** frontmatter 之后的正文内容。 */
            String body
    ) {}

    /**
     * 解析 Markdown 文本。
     * @param markdown 原始 Markdown 文本
     * @return 解析结果
     * @throws IllegalArgumentException 如果格式非法
     */
    public static ParseResult parse(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return new ParseResult(Map.of(), "");
        }

        var lines = markdown.split("\n", -1);

        // 检查首行是否为 ---
        if (lines.length == 0 || !DELIMITER.equals(lines[0].trim())) {
            // 无 frontmatter，整个文本作为 body
            return new ParseResult(Map.of(), markdown);
        }

        // 寻找闭合的 ---
        int closeIndex = -1;
        for (int i = 1; i < lines.length; i++) {
            if (DELIMITER.equals(lines[i].trim())) {
                closeIndex = i;
                break;
            }
        }

        if (closeIndex == -1) {
            // frontmatter 未闭合，整个文本作为 body
            return new ParseResult(Map.of(), markdown);
        }

        // 解析 frontmatter 区域（lines[1] 到 lines[closeIndex-1]）
        var frontmatter = new LinkedHashMap<String, String>();
        for (int i = 1; i < closeIndex; i++) {
            var line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue; // 跳过空行和注释
            }

            var matcher = KEY_VALUE.matcher(line);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("frontmatter 格式非法（行 " + (i + 1) + "）: " + line);
            }

            var key = matcher.group(1).trim();
            var value = matcher.group(2).trim();
            frontmatter.put(key, value);
        }

        // body 是闭合 --- 之后的内容
        var bodyBuilder = new StringBuilder();
        for (int i = closeIndex + 1; i < lines.length; i++) {
            if (i > closeIndex + 1) {
                bodyBuilder.append('\n');
            }
            bodyBuilder.append(lines[i]);
        }

        // 移除末尾的单个换行符（如果存在），保持与原始文档一致
        var body = bodyBuilder.toString();
        if (body.endsWith("\n") && !markdown.endsWith("\n\n")) {
            body = body.substring(0, body.length() - 1);
        }

        return new ParseResult(Map.copyOf(frontmatter), body);
    }

    /**
     * 校验技能名称格式（kebab-case）。
     * @param name 技能名称
     * @return 是否合法
     */
    public static boolean isValidSkillName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return SKILL_NAME.matcher(name).matches();
    }
}
