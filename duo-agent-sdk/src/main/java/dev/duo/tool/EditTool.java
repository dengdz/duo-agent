package dev.duo.tool;

import dev.duo.model.llm.ToolDefinition;
import dev.duo.model.llm.ToolExecutor;
import dev.duo.model.llm.ToolExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * edit 工具：对文件做精确字符串替换或按行插入。
 * <p>
 * 对应 DSH {@code tool-str-replace-editor} 的 str_replace/insert 命令
 * （view/create 由 {@link FileReadTool}/{@link FileWriteTool} 承担）。
 * str_replace 的 old_str 必须在文件中精确匹配且唯一：未找到或多处出现都拒绝执行，
 * 多处出现时列出全部冲突行号，要求扩大上下文使匹配唯一。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public class EditTool {

    private static final Logger logger = LoggerFactory.getLogger(EditTool.class);

    /** 错误消息中 old_str 摘要的最大长度。 */
    private static final int OLD_STR_SUMMARY_CHARS = 80;

    private static final String TOOL_NAME = "edit";
    private static final String ARG_COMMAND = "command";
    private static final String ARG_PATH = "path";
    private static final String ARG_OLD_STR = "old_str";
    private static final String ARG_NEW_STR = "new_str";
    private static final String ARG_INSERT_LINE = "insert_line";

    private static final String COMMAND_STR_REPLACE = "str_replace";
    private static final String COMMAND_INSERT = "insert";

    public ToolDefinition getDefinition() {
        return new ToolDefinition(
                TOOL_NAME,
                "对已有文件做精确编辑。command=str_replace：old_str 必须与文件中的连续行精确匹配（注意空白字符），"
                        + "且在文件中唯一——不唯一时返回全部冲突行号，请扩大上下文使其唯一；"
                        + "new_str 省略表示删除 old_str。"
                        + "command=insert：将 new_str 插入到第 insert_line 行之前，0 表示文件最开头，"
                        + "范围 [0, 总行数]。"
                        + "编辑前先用 file_read 查看文件内容和行号。",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                ARG_COMMAND, Map.of(
                                        "type", "string",
                                        "enum", List.of(COMMAND_STR_REPLACE, COMMAND_INSERT),
                                        "description", "编辑命令：str_replace 或 insert"
                                ),
                                ARG_PATH, Map.of(
                                        "type", "string",
                                        "description", "要编辑的文件路径"
                                ),
                                ARG_OLD_STR, Map.of(
                                        "type", "string",
                                        "description", "str_replace 必填：要被替换的连续文本，必须在文件中唯一"
                                ),
                                ARG_NEW_STR, Map.of(
                                        "type", "string",
                                        "description", "str_replace：替换后的文本（省略表示删除）；insert 必填：要插入的文本"
                                ),
                                ARG_INSERT_LINE, Map.of(
                                        "type", "integer",
                                        "description", "insert 必填：插入位置——新文本插入到该行之前，0 表示文件最开头"
                                )
                        ),
                        "required", List.of(ARG_COMMAND, ARG_PATH)
                ),
                ToolExecutor.of(this::execute)
        );
    }

    private ToolExecutionResult execute(Map<String, Object> args) {
        Objects.requireNonNull(args, "args must not be null");

        if (!(args.get(ARG_COMMAND) instanceof String command) || command.isBlank()) {
            return new ToolExecutionResult(
                    "错误：缺少 command 参数（str_replace 或 insert）\n\n"
                    + "提示：edit 工具需要明确指定 command 参数。\n"
                    + "示例：{\"command\": \"str_replace\", \"path\": \"...\", \"old_str\": \"...\", \"new_str\": \"...\"}"
            );
        }

        return switch (command) {
            case COMMAND_STR_REPLACE -> executeReplace(args);
            case COMMAND_INSERT -> executeInsert(args);
            default -> new ToolExecutionResult("错误：不支持的 command: " + command);
        };
    }

    private ToolExecutionResult executeReplace(Map<String, Object> args) {
        var path = resolveExistingFile(args.get(ARG_PATH));
        if (path.isError()) {
            return path.result();
        }

        if (!(args.get(ARG_OLD_STR) instanceof String oldStr) || oldStr.isEmpty()) {
            return new ToolExecutionResult(
                    "错误：str_replace 需要 old_str 参数（非空）\n\n"
                    + "提示：old_str 必须是文件中实际存在的连续文本。\n"
                    + "- 请用 file_read 工具先查看文件内容\n"
                    + "- 确保包含足够上下文使 old_str 在文件中唯一\n"
                    + "- 注意空白字符（空格、换行、缩进）必须完全一致"
            );
        }
        var newStr = args.get(ARG_NEW_STR) == null ? "" : args.get(ARG_NEW_STR).toString();

        String content;
        try {
            content = Files.readString(path.file(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warn("edit 读取文件失败: {}", path.file(), e);
            return new ToolExecutionResult("错误：读取文件失败: " + e.getMessage());
        }

        var offsets = matchOffsets(content, oldStr);
        if (offsets.isEmpty()) {
            return new ToolExecutionResult(
                    "未执行替换：old_str 未在文件中找到\n\n"
                    + "查找的文本片段：\n" + summarize(oldStr) + "\n\n"
                    + "可能原因：\n"
                    + "- 空白字符不一致（空格、制表符、换行）\n"
                    + "- 文本已被之前的编辑修改\n"
                    + "- 复制粘贴时引入了额外字符\n\n"
                    + "建议：用 file_read 重新查看当前文件内容，再构造 old_str"
            );
        }
        if (offsets.size() > 1) {
            var lines = lineNumbersAt(content, offsets);
            return new ToolExecutionResult(
                    "未执行替换：old_str 在文件中出现 " + offsets.size() + " 次\n\n"
                    + "冲突位置：行 " + lines + "\n\n"
                    + "解决方法：\n"
                    + "- 在 old_str 前后添加更多上下文行，使其在文件中唯一\n"
                    + "- 或者分多次替换，每次针对一处位置"
            );
        }

        var offset = offsets.getFirst();
        var updated = content.substring(0, offset) + newStr + content.substring(offset + oldStr.length());
        return writeBack(path.file(), updated, lineOfOffset(content, offset));
    }

    private ToolExecutionResult executeInsert(Map<String, Object> args) {
        var path = resolveExistingFile(args.get(ARG_PATH));
        if (path.isError()) {
            return path.result();
        }

        var newStr = args.get(ARG_NEW_STR);
        if (newStr == null || newStr.toString().isEmpty()) {
            return new ToolExecutionResult("错误：insert 需要 new_str 参数（非空）");
        }
        var insertLineValue = args.get(ARG_INSERT_LINE);
        if (!(insertLineValue instanceof Number insertLine)) {
            return new ToolExecutionResult("错误：insert 需要 insert_line 参数（整数）");
        }

        String content;
        try {
            content = Files.readString(path.file(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warn("edit 读取文件失败: {}", path.file(), e);
            return new ToolExecutionResult("错误：读取文件失败: " + e.getMessage());
        }

        var lines = content.split("\n", -1);
        var line = insertLine.intValue();
        if (line < 0 || line > lines.length) {
            return new ToolExecutionResult("错误：insert_line 超出范围 [0, " + lines.length + "]: " + line);
        }

        // DSH 语义：新内容插在 lines[line] 之前（0 表示文件最开头）
        var updated = new StringBuilder();
        for (var i = 0; i <= lines.length; i++) {
            if (i == line) {
                updated.append(newStr).append('\n');
            }
            if (i < lines.length) {
                updated.append(lines[i]);
                if (i < lines.length - 1) {
                    updated.append('\n');
                }
            }
        }
        return writeBack(path.file(), updated.toString(), Math.max(1, line));
    }

    private ToolExecutionResult writeBack(Path file, String updated, int changedLine) {
        try {
            Files.writeString(file, updated, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warn("edit 写回文件失败: {}", file, e);
            return new ToolExecutionResult("错误：写入文件失败: " + e.getMessage());
        }
        return new ToolExecutionResult("文件已编辑成功: " + SearchSupport.toRelativeDisplay(file)
                + "（修改起始行: " + changedLine + "）");
    }

    private ResolvedFile resolveExistingFile(Object pathValue) {
        if (!(pathValue instanceof String pathStr) || pathStr.isBlank()) {
            return ResolvedFile.error("错误：缺少 path 参数");
        }
        var file = Paths.get(pathStr).toAbsolutePath().normalize();
        if (!Files.exists(file)) {
            return ResolvedFile.error("错误：文件不存在: " + file);
        }
        if (!Files.isRegularFile(file)) {
            return ResolvedFile.error("错误：不是普通文件: " + file);
        }
        return ResolvedFile.ok(file);
    }

    private record ResolvedFile(Path file, ToolExecutionResult result) {
        static ResolvedFile ok(Path file) {
            return new ResolvedFile(file, null);
        }

        static ResolvedFile error(String message) {
            return new ResolvedFile(null, new ToolExecutionResult(message));
        }

        boolean isError() {
            return result != null;
        }
    }

    private static List<Integer> matchOffsets(String content, String search) {
        var offsets = new ArrayList<Integer>();
        var offset = 0;
        int match;
        while ((match = content.indexOf(search, offset)) >= 0) {
            offsets.add(match);
            offset = match + search.length();
        }
        return offsets;
    }

    private static List<Integer> lineNumbersAt(String content, List<Integer> offsets) {
        var line = 1;
        var cursor = 0;
        var numbers = new ArrayList<Integer>(offsets.size());
        for (var offset : offsets) {
            while (cursor < offset) {
                if (content.charAt(cursor) == '\n') {
                    line++;
                }
                cursor++;
            }
            numbers.add(line);
        }
        return numbers;
    }

    private static int lineOfOffset(String content, int offset) {
        var line = 1;
        for (var i = 0; i < offset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String summarize(String oldStr) {
        var oneLine = oldStr.replace("\n", "\\n");
        return oneLine.length() <= OLD_STR_SUMMARY_CHARS
                ? oneLine
                : oneLine.substring(0, OLD_STR_SUMMARY_CHARS) + "…";
    }
}
