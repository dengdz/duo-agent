package dev.duo.tool;

import dev.duo.model.llm.ToolDefinition;
import dev.duo.model.llm.ToolExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * grep 工具：用正则表达式搜索文件内容。
 * <p>
 * 对应 DSH {@code tool-fs-search} 的 grep（v1 纯 Java 实现）：返回带行号的匹配行，
 * 按文件分组；内联保留前 {@value #MAX_MATCHES} 条匹配，超出部分继续计数并报告
 * 总数，提示缩小搜索范围。include 只支持单个正向 glob。VCS 元数据目录与疑似
 * 二进制文件跳过。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public class GrepTool {

    private static final Logger logger = LoggerFactory.getLogger(GrepTool.class);

    /** 单次调用内联保留的最大匹配数（DSH 默认 250）。 */
    private static final int MAX_MATCHES = 250;

    /** 单条匹配行预览的最大字符数。 */
    private static final int MAX_LINE_CHARS = 500;

    private static final String TOOL_NAME = "grep";
    private static final String ARG_PATTERN = "pattern";
    private static final String ARG_PATH = "path";
    private static final String ARG_INCLUDE = "include";

    /** 单条匹配：1 起行号 + 预览后的行文本。 */
    private record Match(int lineNumber, String line) {}

    /** 单文件的搜索结果：内联保留的匹配 + 该文件全部匹配计数。 */
    private record FileMatches(List<Match> retained, int total) {}

    public ToolDefinition getDefinition() {
        return new ToolDefinition(
                TOOL_NAME,
                "用正则表达式搜索文件内容。返回带行号的匹配行，按文件分组。"
                        + "内联最多返回 " + MAX_MATCHES + " 条匹配，被截断时会报告总数；"
                        + "需要上下文时用 file_read 读取匹配文件。",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                ARG_PATTERN, Map.of(
                                        "type", "string",
                                        "description", "要搜索的正则表达式（Java 语法）"
                                ),
                                ARG_PATH, Map.of(
                                        "type", "string",
                                        "description", "搜索的文件或目录，默认为当前工作目录"
                                ),
                                ARG_INCLUDE, Map.of(
                                        "type", "string",
                                        "description", "单个正向文件名 glob 过滤（如 \"*.java\"、\"*.{js,jsx}\"），不支持取反和逗号列表"
                                )
                        ),
                        "required", List.of(ARG_PATTERN)
                ),
                this::execute
        );
    }

    private ToolExecutionResult execute(Map<String, Object> args) {
        Objects.requireNonNull(args, "args must not be null");

        if (!(args.get(ARG_PATTERN) instanceof String patternStr) || patternStr.isEmpty()) {
            return new ToolExecutionResult("错误：缺少 pattern 参数");
        }

        Pattern pattern;
        try {
            pattern = Pattern.compile(patternStr);
        } catch (PatternSyntaxException e) {
            return new ToolExecutionResult("错误：正则表达式非法: " + e.getMessage());
        }

        var root = resolveRoot(argAsString(args, ARG_PATH));
        if (!Files.exists(root)) {
            return new ToolExecutionResult("错误：路径不存在: " + root);
        }

        PathMatcher includeMatcher = null;
        var includeValue = args.get(ARG_INCLUDE);
        if (includeValue != null) {
            try {
                includeMatcher = SearchSupport.compileInclude(includeValue.toString());
            } catch (IllegalArgumentException e) {
                return new ToolExecutionResult("错误：" + e.getMessage());
            }
        }

        var matchesByFile = new LinkedHashMap<String, FileMatches>();
        var retainedTotal = 0;
        var grandTotal = 0;

        for (var file : listFiles(root)) {
            if (includeMatcher != null && !SearchSupport.matchesInclude(includeMatcher, file)) {
                continue;
            }
            if (SearchSupport.isProbablyBinary(file)) {
                continue;
            }
            var budget = MAX_MATCHES - retainedTotal;
            var result = searchFile(file, pattern, budget);
            if (result.total() == 0) {
                continue;
            }
            grandTotal += result.total();
            retainedTotal += result.retained().size();
            matchesByFile.put(SearchSupport.toRelativeDisplay(file), result);
        }

        if (grandTotal == 0) {
            return new ToolExecutionResult("No matches found");
        }
        return new ToolExecutionResult(format(matchesByFile, grandTotal));
    }

    private Path resolveRoot(String rootStr) {
        if (rootStr == null || rootStr.isBlank()) {
            return Paths.get("").toAbsolutePath().normalize();
        }
        return Paths.get(rootStr).toAbsolutePath().normalize();
    }

    /** 取字符串参数，非字符串或缺失返回 null。 */
    private static String argAsString(Map<String, Object> args, String key) {
        return args.get(key) instanceof String value ? value : null;
    }

    private List<Path> listFiles(Path root) {
        if (Files.isRegularFile(root)) {
            return List.of(root);
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !SearchSupport.isExcludedPath(root.relativize(p)))
                    .toList();
        } catch (IOException e) {
            logger.warn("grep 遍历目录失败: {}", root, e);
            return List.of();
        }
    }

    /**
     * 搜索单个文件：全部匹配计数，但只把前 budget 条保留进内联列表（内存有界）。
     */
    private FileMatches searchFile(Path file, Pattern pattern, int budget) {
        var retained = new ArrayList<Match>();
        var total = 0;
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            var lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (pattern.matcher(line).find()) {
                    total++;
                    if (total <= budget) {
                        retained.add(new Match(lineNumber, preview(line)));
                    }
                }
            }
        } catch (IOException e) {
            logger.debug("grep 读取文件失败，跳过: {}", file, e);
        }
        return new FileMatches(retained, total);
    }

    private String preview(String line) {
        return line.length() <= MAX_LINE_CHARS ? line : line.substring(0, MAX_LINE_CHARS) + " (行已截断)";
    }

    private String format(LinkedHashMap<String, FileMatches> matchesByFile, int grandTotal) {
        var retained = 0;
        for (var fileMatches : matchesByFile.values()) {
            retained += fileMatches.retained().size();
        }
        var truncated = retained < grandTotal;

        var sb = new StringBuilder();
        if (truncated) {
            sb.append("Found ").append(retained).append(" of ").append(grandTotal).append(" matches\n\n");
        } else {
            sb.append("Found ").append(grandTotal)
              .append(grandTotal == 1 ? " match" : " matches").append("\n\n");
        }

        var sections = new ArrayList<String>(matchesByFile.size());
        for (var entry : matchesByFile.entrySet()) {
            var rows = new StringBuilder();
            for (var match : entry.getValue().retained()) {
                rows.append("Line ").append(match.lineNumber()).append(": ").append(match.line()).append('\n');
            }
            if (!rows.isEmpty()) {
                sections.add(entry.getKey() + "\n" + rows.toString().stripTrailing());
            }
        }
        sb.append(String.join("\n\n", sections));

        if (truncated) {
            sb.append("\n\n(结果已截断。请缩小 pattern、path 或 include 范围以查看更多。)");
        }
        return sb.toString();
    }
}
