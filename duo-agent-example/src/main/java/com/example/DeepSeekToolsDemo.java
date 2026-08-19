package com.example;

import dev.duo.api.agent.AgentOptions;
import dev.duo.api.llm.LlmRuntime;
import dev.duo.adapter.deepseek.DeepSeekAdapter;
import dev.duo.core.agent.ReactLoopAgent;
import dev.duo.core.llm.SystemPromptImpl;
import dev.duo.core.llm.ToolRegistryImpl;
import dev.duo.core.session.Session;
import dev.duo.model.llm.ContentBlock;
import dev.duo.model.llm.Message;
import dev.duo.model.llm.ToolProviderResult;
import dev.duo.model.llm.ToolSchema;
import dev.duo.model.session.SessionEventToolCall;
import dev.duo.model.session.SessionEventToolResult;
import dev.duo.model.session.SessionEventTurnEnd;
import dev.duo.model.session.SessionId;
import dev.duo.tool.EditTool;
import dev.duo.tool.FileReadTool;
import dev.duo.tool.FileWriteTool;
import dev.duo.tool.GlobTool;
import dev.duo.tool.GrepTool;
import dev.duo.util.MessageId;

import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 真实 DeepSeek API + grep/glob/edit 工具链集成演示。
 * <p>
 * 场景：现场目录中放置多个使用 Hashtable 的 Java 文件，
 * 要求模型自主协作调用 grep（定位）→ file_read（查看）→ edit（修复）完成任务。
 * 运行结束后现场保留，可直接查看修复结果。
 * </p>
 * <p>
 * 运行方式（项目根目录）：
 * <pre>
 * mvn -pl duo-agent-example -am install -DskipTests -q
 * java -cp "duo-agent-example/target/duo-agent-example-0.1.0-SNAPSHOT.jar:duo-agent-example/target/lib/*" \
 *      com.example.DeepSeekToolsDemo
 * </pre>
 * 需要 DEEPSEEK_API_KEY 环境变量或项目根目录 .env 文件。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public class DeepSeekToolsDemo {

    /** 固定现场目录：运行后可直接查看（每次运行前清空重建）。 */
    private static final Path WORKSPACE = Path.of("target/tools-demo-workspace");

    public static void main(String[] args) throws Exception {
        System.out.println("=== DeepSeek + grep/glob/edit 工具链集成演示 ===\n");

        String apiKey = loadApiKey();
        if (apiKey == null) {
            System.out.println("❌ 未找到 DEEPSEEK_API_KEY（环境变量或 .env 文件）");
            return;
        }
        System.out.println("✓ API Key 已加载\n");

        // 1. 准备测试现场：三个目录层级的坏代码文件
        prepareWorkspace();
        var workspace = WORKSPACE.toAbsolutePath().normalize();
        System.out.println("📁 测试现场: " + workspace);
        System.out.println("   （4 个 Java 文件，其中 3 个使用了 Hashtable，1 个无辜文件）\n");

        // 2. 组装运行时
        var llmRuntime = new LlmRuntime();
        llmRuntime.registerAdapter("deepseek", new DeepSeekAdapter(apiKey, "https://api.deepseek.com"));

        var toolRegistry = new ToolRegistryImpl();
        toolRegistry.register(new GlobTool().getDefinition());
        toolRegistry.register(new GrepTool().getDefinition());
        toolRegistry.register(new EditTool().getDefinition());
        toolRegistry.register(new FileReadTool().getDefinition());
        toolRegistry.register(new FileWriteTool().getDefinition());

        var systemPrompt = new SystemPromptImpl(
                "你是一个专业的 Java 开发助手，负责维护给定目录中的代码。", false);
        systemPrompt.tools(assembly -> {
            var schemas = new java.util.ArrayList<ToolSchema>();
            for (var tool : toolRegistry.getAll()) {
                schemas.add(new ToolSchema(tool.name(), tool.description(), tool.parameters()));
            }
            return new ToolProviderResult(schemas);
        });
        
        // 增加工具调用格式指导
        systemPrompt.section(new dev.duo.model.llm.PromptSection(
                "tool-format-guide", -50,
                """
                ## Tool Call Format
                
                When calling tools, use flat JSON objects directly:
                
                ✓ Correct:
                {"command": "str_replace", "path": "/path/to/file", "old_str": "...", "new_str": "..."}
                
                ✗ Wrong (nested arguments):
                {"arguments": "{\\"command\\": \\"str_replace\\", ...}"}
                """
        ));

        System.out.println("=== 注册的工具 ===");
        for (var tool : systemPrompt.assemble().tools()) {
            System.out.println("  - " + tool.name());
        }
        System.out.println();

        // 3. 任务指令：要求自主使用工具链
        var task = """
                工作目录 %s 中有一些 Java 文件违反了阿里巴巴规范（使用 Hashtable）。

                重要：每次调用工具时都必须显式传入 path 参数 "%s"，
                绝对不要使用当前工作目录（那不是任务现场，搜错了就完不成任务）。

                请按以下步骤完成任务：
                1. 用 grep 工具（path 传上面目录）定位所有使用 Hashtable 的位置
                2. 逐个文件用 file_read 查看内容，用 edit 工具把 Hashtable 改为 ConcurrentHashMap
                   （泛型声明和 import 一并处理；edit 的 old_str 必须在文件中唯一，多带上下文）
                3. 最后汇报：修改了哪些文件、每个文件改了几处

                注意：只处理 Hashtable 相关内容，不要做其他重构。
                """.formatted(workspace, workspace);

        System.out.println("=== 任务下达 ===\n");
        System.out.println(task.indent(2).stripTrailing());
        System.out.println();

        // 4. 驱动 Agent
        var sessionId = new SessionId("deepseek-tools-demo");
        var session = new Session(sessionId);
        var options = new AgentOptions("deepseek", "deepseek-v4-flash", 8000, Duration.ofSeconds(60));
        var agent = new ReactLoopAgent(sessionId, options, session, llmRuntime, systemPrompt, toolRegistry);

        agent.followup(new Message.UserMessage(
                MessageId.random(),
                List.of(new ContentBlock.Text(task)),
                new dev.duo.model.llm.MessageSource.User()
        ));
        agent.whenIdle();

        // 5. 工具调用轨迹（带结果摘要）
        System.out.println("\n=== 工具调用轨迹 ===\n");
        var callArgs = new LinkedHashMap<String, String>();
        var callOrder = new LinkedHashMap<String, Integer>();
        var toolSuccessCount = new LinkedHashMap<String, Integer>();
        var toolErrorCount = new LinkedHashMap<String, Integer>();
        
        for (var event : session.events()) {
            if (event instanceof SessionEventToolCall call) {
                callOrder.put(call.callId().value(), callOrder.size());
                System.out.println("  ▶ " + call.name() + briefArgs(call.arguments()));
                callArgs.put(call.callId().value(), call.name());
            } else if (event instanceof SessionEventToolResult result) {
                var toolName = result.message().content().isEmpty()
                        ? "?" : callArgs.getOrDefault(toolCallId(result), "?");
                var summary = briefResult(firstText(result.message().content()));
                if (result.errorName() != null) {
                    System.out.println("    ✗ " + toolName + " 错误: " + result.errorName());
                    toolErrorCount.merge(toolName, 1, Integer::sum);
                } else {
                    System.out.println("    ← " + toolName + " 结果: " + summary);
                    toolSuccessCount.merge(toolName, 1, Integer::sum);
                }
            }
        }

        var counts = new LinkedHashMap<String, Integer>();
        callArgs.values().forEach(name -> counts.merge(name, 1, Integer::sum));
        System.out.println("\n=== 调用统计 ===");
        counts.forEach((name, count) -> {
            var success = toolSuccessCount.getOrDefault(name, 0);
            var error = toolErrorCount.getOrDefault(name, 0);
            var successRate = count > 0 ? String.format("%.0f%%", 100.0 * success / count) : "N/A";
            System.out.println("  " + name + ": " + count + " 次（成功 " + success 
                    + "，失败 " + error + "，成功率 " + successRate + "）");
        });
        
        var totalCalls = counts.values().stream().mapToInt(Integer::intValue).sum();
        var totalSuccess = toolSuccessCount.values().stream().mapToInt(Integer::intValue).sum();
        var totalErrors = toolErrorCount.values().stream().mapToInt(Integer::intValue).sum();
        System.out.println("  ---");
        System.out.println("  总调用: " + totalCalls + " 次");
        System.out.println("  总成功: " + totalSuccess + " 次");
        System.out.println("  总失败: " + totalErrors + " 次");
        System.out.println("  整体成功率: " + String.format("%.1f%%", 100.0 * totalSuccess / totalCalls));
        
        var turns = session.events().stream().filter(e -> e instanceof SessionEventTurnEnd).count();
        System.out.println("  总轮次: " + turns);

        // 6. 助手最终汇报
        System.out.println("\n=== 助手最终汇报 ===\n");
        printFinalReport(session);

        // 7. 验证现场：Hashtable 是否真的被清除
        System.out.println("\n=== 修复验证（工具真实效果） ===");
        verify(workspace);
        System.out.println("\n现场文件保留在: " + workspace + "（可自行查看）");
        System.out.println("\n✅ 演示完成");
    }

    private static void prepareWorkspace() throws Exception {
        if (Files.exists(WORKSPACE)) {
            try (Stream<Path> stream = Files.walk(WORKSPACE)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (Exception e) {
                        // ignore
                    }
                });
            }
        }
        var root = WORKSPACE;
        Files.createDirectories(root.resolve("src/main/java/demo"));
        Files.createDirectories(root.resolve("src/main/java/demo/inner"));
        Files.writeString(root.resolve("src/main/java/demo/UserService.java"), """
                package demo;

                import java.util.Hashtable;

                public class UserService {
                    private Hashtable users = new Hashtable();

                    public void addUser(String name) {
                        users.put(name, name);
                    }
                }
                """);
        Files.writeString(root.resolve("src/main/java/demo/OrderService.java"), """
                package demo;

                import java.util.Hashtable;
                import java.util.Map;

                public class OrderService {
                    private Map<String, String> cache = new Hashtable();
                }
                """);
        Files.writeString(root.resolve("src/main/java/demo/inner/InventoryService.java"), """
                package demo.inner;

                import java.util.Hashtable;

                public class InventoryService {
                    private Hashtable stock = new Hashtable();
                    private Hashtable reserved = new Hashtable();
                }
                """);
        Files.writeString(root.resolve("src/main/java/demo/CleanService.java"), """
                package demo;

                public class CleanService {
                    // 这个文件没有问题，不应被修改
                    private int count = 0;
                }
                """);
    }

    private static void verify(Path workspace) throws Exception {
        try (var stream = Files.walk(workspace)) {
            for (var file : stream.filter(Files::isRegularFile).toList()) {
                var content = Files.readString(file);
                var hasHashtable = content.contains("Hashtable");
                var hasFixed = content.contains("ConcurrentHashMap");
                if (hasHashtable && hasFixed) {
                    System.out.println("  ⚠ 部分修复: " + workspace.relativize(file)
                            + "（import 已改，仍有 Hashtable 残留）");
                } else if (hasHashtable) {
                    System.out.println("  ✗ 未修复: " + workspace.relativize(file));
                } else if (hasFixed) {
                    System.out.println("  ✓ 已修复: " + workspace.relativize(file));
                } else if (file.getFileName().toString().equals("CleanService.java")) {
                    System.out.println("  ✓ 无辜文件未被误改: " + workspace.relativize(file));
                }
            }
        }
    }

    private static void printFinalReport(Session session) {
        // 找最后一条**纯文本**（无 tool_calls）的助手消息
        Message.AssistantMessage lastTextMessage = null;
        for (var message : session.deriveMessages()) {
            if (message instanceof Message.AssistantMessage asst) {
                // 检查是否包含工具调用
                var hasToolCall = asst.content().stream()
                        .anyMatch(block -> block instanceof ContentBlock.ToolCall);
                if (!hasToolCall) {
                    var text = firstText(asst.content());
                    if (!text.isBlank()) {
                        lastTextMessage = asst;
                    }
                }
            }
        }
        
        if (lastTextMessage != null) {
            var text = firstText(lastTextMessage.content());
            System.out.println(text.indent(2).stripTrailing());
        } else {
            System.out.println("  （模型未输出最终文本汇报）");
        }
    }

    private static String toolCallId(SessionEventToolResult result) {
        var content = result.message().content();
        if (!content.isEmpty() && content.getFirst() instanceof ContentBlock.ToolResult tr) {
            return tr.toolCallId().value();
        }
        return "?";
    }

    private static String firstText(List<ContentBlock> content) {
        for (var block : content) {
            if (block instanceof ContentBlock.Text text) {
                return text.text();
            }
            if (block instanceof ContentBlock.ToolResult toolResult) {
                return firstText(toolResult.content());
            }
        }
        return "";
    }

    private static String briefArgs(String args) {
        if (args == null || args.isBlank()) {
            return "";
        }
        var brief = args.replace("\n", " ");
        if (brief.length() > 90) {
            brief = brief.substring(0, 90) + "…";
        }
        return "(" + brief + ")";
    }

    private static String briefResult(String text) {
        if (text == null || text.isBlank()) {
            return "(空)";
        }
        var brief = text.replace("\n", " ⏎ ");
        if (brief.length() > 150) {
            brief = brief.substring(0, 150) + "…";
        }
        return brief;
    }

    private static String loadApiKey() {
        var key = System.getenv("DEEPSEEK_API_KEY");
        if (key != null && !key.isBlank()) {
            return key;
        }
        for (var envFile : new Path[]{Path.of(".env"), Path.of("../.env")}) {
            if (Files.exists(envFile)) {
                try (var reader = new BufferedReader(new FileReader(envFile.toFile()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("DEEPSEEK_API_KEY=")) {
                            return line.substring("DEEPSEEK_API_KEY=".length()).trim();
                        }
                    }
                } catch (Exception e) {
                    // ignore
                }
            }
        }
        return null;
    }
}
