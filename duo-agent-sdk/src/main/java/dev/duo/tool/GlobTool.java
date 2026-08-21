package dev.duo.tool;

import dev.duo.model.llm.ToolDefinition;
import dev.duo.model.llm.ToolExecutor;
import dev.duo.model.llm.ToolExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * glob 工具：按路径模式发现文件。
 * <p>
 * 结果只含文件不含目录，按修改时间排序（新在前）；内联最多 {@value #MAX_RESULTS} 条，
 * 超出报告总数并提示缩小范围。不含分隔符的模式匹配任意深度的文件名
 * （如 {@code *.java} 匹配整棵树），含分隔符的模式匹配相对搜索根的路径
 * （如 {@code src/**&#47;*Test.java}）。VCS 元数据目录排除。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public class GlobTool {

    private static final Logger logger = LoggerFactory.getLogger(GlobTool.class);

    /** 单次调用内联返回的最大路径数。 */
    private static final int MAX_RESULTS = 100;

    private static final String TOOL_NAME = "glob";
    private static final String ARG_PATTERN = "pattern";
    private static final String ARG_PATH = "path";

    public ToolDefinition getDefinition() {
        return new ToolDefinition(
                TOOL_NAME,
                "按 glob 模式查找文件路径。结果只含文件（不含目录），按修改时间排序（最新在前），"
                        + "最多返回 " + MAX_RESULTS + " 条，超出时报告总数。"
                        + "不含 \"/\" 的模式匹配任意深度的文件名（\"*.java\" 搜索整棵树）；"
                        + "包含 \"/\" 的模式匹配相对搜索根的路径（\"src/**/*Test.java\"）。",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                ARG_PATTERN, Map.of(
                                        "type", "string",
                                        "description", "glob 模式（如 \"**/*.java\"、\"src/**/*Test.java\"）"
                                ),
                                ARG_PATH, Map.of(
                                        "type", "string",
                                        "description", "搜索目录，默认为当前工作目录"
                                )
                        ),
                        "required", List.of(ARG_PATTERN)
                ),
                ToolExecutor.of(this::execute)
        );
    }

    private ToolExecutionResult execute(Map<String, Object> args) {
        Objects.requireNonNull(args, "args must not be null");

        if (!(args.get(ARG_PATTERN) instanceof String patternStr) || patternStr.isBlank()) {
            return new ToolExecutionResult("错误：缺少 pattern 参数");
        }

        var root = resolveRoot(argAsString(args, ARG_PATH));
        if (!Files.isDirectory(root)) {
            return new ToolExecutionResult("错误：目录不存在: " + root);
        }

        // 不含分隔符的模式匹配任意深度的文件名；
        // 含分隔符的模式匹配相对搜索根的路径。
        // 注意 Java glob 的 "**/x" 不匹配根层文件，故文件名模式直接对 getFileName() 匹配
        var fileNameOnly = patternStr.indexOf('/') < 0;
        PathMatcher matcher;
        try {
            matcher = FileSystems.getDefault().getPathMatcher("glob:" + patternStr);
        } catch (IllegalArgumentException e) {
            return new ToolExecutionResult("错误：glob 模式非法: " + e.getMessage());
        }

        var matched = new ArrayList<Path>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !SearchSupport.isExcludedPath(root.relativize(p)))
                    .filter(p -> fileNameOnly
                            ? matcher.matches(p.getFileName())
                            : matcher.matches(root.relativize(p)))
                    .forEach(matched::add);
        } catch (IOException e) {
            logger.warn("glob 遍历目录失败: {}", root, e);
            return new ToolExecutionResult("错误：遍历目录失败: " + root);
        }

        if (matched.isEmpty()) {
            return new ToolExecutionResult("No files found");
        }

        // 修改时间排序，新在前
        matched.sort(Comparator.comparing((Path p) -> lastModifiedMillis(p)).reversed());

        var sb = new StringBuilder();
        var shown = Math.min(matched.size(), MAX_RESULTS);
        for (var i = 0; i < shown; i++) {
            sb.append(SearchSupport.toRelativeDisplay(matched.get(i))).append('\n');
        }
        if (matched.size() > MAX_RESULTS) {
            sb.append("\n(Showing ").append(MAX_RESULTS).append(" of ").append(matched.size())
              .append(" paths. 请缩小 pattern 或 path 范围以查看更多。)");
        }
        return new ToolExecutionResult(sb.toString().stripTrailing());
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

    private long lastModifiedMillis(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
