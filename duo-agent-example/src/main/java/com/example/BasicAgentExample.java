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
import dev.duo.model.session.SessionId;
import dev.duo.tool.BashTool;
import dev.duo.tool.FileReadTool;
import dev.duo.tool.FileWriteTool;
import dev.duo.tool.TodoWriteTool;

import java.time.Duration;
import java.util.List;

/**
 * 基础 Agent 使用示例。
 * <p>
 * 演示如何创建一个简单的 Agent，并与之进行对话。
 * </p>
 *
 * @author zhangyl
 */
public class BasicAgentExample {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Duo Agent SDK 基础示例 ===\n");

        // 1. 配置 LLM Runtime
        System.out.println("1. 配置 LLM Runtime");
        var llmRuntime = new LlmRuntime();
        llmRuntime.registerAdapter("deepseek", new DeepSeekAdapter());
        System.out.println("   ✓ 已注册 DeepSeek 适配器\n");

        // 2. 创建工具注册表
        System.out.println("2. 注册工具");
        var toolRegistry = new ToolRegistryImpl();
        toolRegistry.register(new BashTool().getDefinition());
        toolRegistry.register(new FileReadTool().getDefinition());
        toolRegistry.register(new FileWriteTool().getDefinition());
        toolRegistry.register(new TodoWriteTool().getDefinition());
        System.out.println("   ✓ 已注册 4 个工具：bash, file_read, file_write, todo_write\n");

        // 3. 创建 Session
        System.out.println("3. 创建 Session");
        var session = new Session(new SessionId("example-session"));
        System.out.println("   ✓ Session ID: example-session\n");

        // 4. 创建 Agent
        System.out.println("4. 创建 Agent");
        var agent = new ReactLoopAgent(
                new SessionId("example-agent"),
                new AgentOptions(
                        "deepseek",
                        "deepseek-chat",
                        4096,
                        Duration.ofSeconds(60)
                ),
                session,
                llmRuntime,
                new SystemPromptImpl("你是一个智能助手，可以使用工具帮助用户完成任务。", false),
                toolRegistry
        );
        System.out.println("   ✓ Agent 创建成功\n");

        // 5. 检查 API Key
        var apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("❌ 错误：未设置 DEEPSEEK_API_KEY 环境变量");
            System.err.println("   请设置环境变量后重试：");
            System.err.println("   export DEEPSEEK_API_KEY=your_api_key");
            System.exit(1);
        }

        // 6. 发送消息并等待响应
        System.out.println("5. 发送消息");
        System.out.println("   用户：你好，请简单介绍一下你自己");
        
        var userMessage = MessageFactory.createUserMessage(
                List.of(new ContentBlock.Text("你好，请简单介绍一下你自己")),
                new MessageSource.User()
        );
        
        agent.followup(userMessage);
        
        // 等待 Agent 完成处理
        agent.whenIdle();
        
        // 7. 打印 Agent 响应
        System.out.println("\n6. Agent 响应：");
        for (var event : session.events()) {
            if (event instanceof SessionEventAssistantMessage assistantMsg) {
                var content = assistantMsg.message().content();
                for (var block : content) {
                    if (block instanceof ContentBlock.Text textBlock) {
                        System.out.println("   " + textBlock.text());
                    }
                }
            }
        }

        System.out.println("\n=== 示例完成 ===");
    }
}
