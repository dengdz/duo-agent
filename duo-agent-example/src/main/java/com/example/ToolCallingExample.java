package com.example;

import dev.duo.api.agent.AgentOptions;
import dev.duo.api.llm.LlmRuntime;
import dev.duo.adapter.deepseek.DeepSeekAdapter;
import dev.duo.core.agent.ReactLoopAgent;
import dev.duo.core.llm.SystemPromptImpl;
import dev.duo.core.llm.ToolRegistryImpl;
import dev.duo.core.session.Session;
import dev.duo.model.llm.ContentBlock;
import dev.duo.model.llm.MessageFactory;
import dev.duo.model.llm.MessageSource;
import dev.duo.model.session.SessionEventAssistantMessage;
import dev.duo.model.session.SessionEventToolCall;
import dev.duo.model.session.SessionEventToolResult;
import dev.duo.model.session.SessionId;
import dev.duo.tool.BashTool;
import dev.duo.tool.FileReadTool;
import dev.duo.tool.FileWriteTool;

import java.time.Duration;
import java.util.List;

/**
 * 工具调用示例。
 * <p>
 * 演示 Agent 如何使用工具完成任务。
 * </p>
 *
 * @author zhangyl
 */
public class ToolCallingExample {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Duo Agent SDK 工具调用示例 ===\n");

        // 1. 配置并创建 Agent
        var llmRuntime = new LlmRuntime();
        llmRuntime.registerAdapter("deepseek", new DeepSeekAdapter());

        var toolRegistry = new ToolRegistryImpl();
        toolRegistry.register(new BashTool().getDefinition());
        toolRegistry.register(new FileReadTool().getDefinition());
        toolRegistry.register(new FileWriteTool().getDefinition());

        var session = new Session(new SessionId("tool-demo-session"));

        var agent = new ReactLoopAgent(
                new SessionId("tool-demo-agent"),
                new AgentOptions("deepseek", "deepseek-chat", 4096, Duration.ofSeconds(60)),
                session,
                llmRuntime,
                new SystemPromptImpl(
                        "你是一个智能助手，擅长使用工具完成任务。" +
                        "你可以使用 bash 执行命令，使用 file_write 写文件，使用 file_read 读文件。",
                        false
                ),
                toolRegistry
        );

        // 2. 检查 API Key
        var apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("❌ 错误：未设置 DEEPSEEK_API_KEY 环境变量");
            System.exit(1);
        }

        // 3. 发送需要使用工具的任务
        System.out.println("任务：创建一个文件 hello.txt，写入 'Hello from Duo Agent!'，然后读取并显示内容\n");
        
        var userMessage = MessageFactory.createUserMessage(
                List.of(new ContentBlock.Text(
                        "请执行以下任务：\n" +
                        "1. 创建一个文件 /tmp/hello.txt\n" +
                        "2. 写入内容：Hello from Duo Agent!\n" +
                        "3. 读取该文件并告诉我内容"
                )),
                new MessageSource.User()
        );
        
        agent.followup(userMessage);
        
        // 等待 Agent 完成处理
        agent.whenIdle();
        
        // 4. 打印执行过程
        System.out.println("执行过程：\n");
        for (var event : session.events()) {
            switch (event) {
                case SessionEventToolCall toolCall -> {
                    System.out.println("🔧 调用工具: " + toolCall.name());
                    System.out.println("   参数: " + toolCall.arguments());
                }
                case SessionEventToolResult toolResult -> {
                    var msg = toolResult.message();
                    System.out.println("✓ 工具结果: " + 
                            (msg.content().size() > 0 && 
                             msg.content().get(0) instanceof ContentBlock.ToolResult tr
                                    ? flattenToolResult(tr.content())
                                    : "无输出"));
                    System.out.println();
                }
                case SessionEventAssistantMessage assistantMsg -> {
                    System.out.println("💬 Agent 响应:");
                    for (var block : assistantMsg.message().content()) {
                        if (block instanceof ContentBlock.Text textBlock) {
                            System.out.println("   " + textBlock.text());
                        }
                    }
                }
                default -> {}
            }
        }

        System.out.println("\n=== 示例完成 ===");
    }

    private static String flattenToolResult(List<ContentBlock> blocks) {
        var sb = new StringBuilder();
        for (var block : blocks) {
            if (block instanceof ContentBlock.Text t) {
                sb.append(t.text());
            }
        }
        return sb.toString();
    }
}
