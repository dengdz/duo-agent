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
import dev.duo.model.llm.MessageFactory;
import dev.duo.model.llm.MessageSource;
import dev.duo.model.llm.ToolProviderResult;
import dev.duo.model.llm.ToolSchema;
import dev.duo.model.session.SessionId;
import dev.duo.tool.BashTool;
import dev.duo.tool.FileReadTool;
import dev.duo.tool.GrepTool;

import java.time.Duration;
import java.util.List;

/**
 * 快速开始示例 - 展示简化后的使用方式。
 * <p>
 * 这个示例展示了如何用最少的代码创建一个功能完整的 AI Agent。
 * 未来将进一步简化为 Builder API。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public class QuickStartExample {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Duo Agent 快速开始 ===\n");

        // 检查 API Key
        var apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("❌ 未设置 DEEPSEEK_API_KEY 环境变量");
            System.err.println("请执行: export DEEPSEEK_API_KEY=your-api-key");
            System.exit(1);
        }

        // 使用辅助方法快速创建 Agent
        var agent = createSimpleAgent();

        System.out.println("✓ Agent 创建成功\n");
        System.out.println("=== 开始对话 ===\n");

        // 发送消息并获取响应
        String response = chat(agent, "列出当前目录的 Java 文件数量");

        System.out.println("\n=== 响应 ===\n");
        System.out.println(response);
        System.out.println("\n✅ 完成");
    }

    /**
     * 快速创建一个配置好的 Agent（简化版）。
     * <p>
     * 注意：这是直接使用底层 API 的示例。
     * 推荐使用 {@link dev.duo.api.DuoAgentBuilder} 简化配置，参见 HelloWorldExample。
     * </p>
     */
    private static ReactLoopAgent createSimpleAgent() {
        // 1. LLM Runtime
        var llmRuntime = new LlmRuntime();
        llmRuntime.registerAdapter("deepseek", new DeepSeekAdapter());

        // 2. 工具注册
        var toolRegistry = new ToolRegistryImpl();
        var tools = List.of(
                new BashTool().getDefinition(),
                new FileReadTool().getDefinition(),
                new GrepTool().getDefinition()
        );
        tools.forEach(toolRegistry::register);

        // 3. System Prompt
        var systemPrompt = new SystemPromptImpl("你是一个智能助手，可以使用工具帮助用户完成任务。", false);
        systemPrompt.tools(assembly -> new ToolProviderResult(
                tools.stream()
                        .map(t -> new ToolSchema(t.name(), t.description(), t.parameters()))
                        .toList()
        ));

        // 4. Session
        var session = new Session(new SessionId("quick-start"));

        // 5. Agent
        return new ReactLoopAgent(
                new SessionId("agent-quick-start"),
                new AgentOptions("deepseek", "deepseek-chat", 4096, Duration.ofSeconds(60)),
                session,
                llmRuntime,
                systemPrompt,
                toolRegistry
        );
    }

    /**
     * 发送消息并等待响应（简化版）。
     * <p>
     * 注意：这是直接操作底层 Agent 的示例。
     * 推荐使用 {@link dev.duo.api.DuoAgent#chat(String)} 简化调用，参见 HelloWorldExample。
     * </p>
     */
    private static String chat(ReactLoopAgent agent, String message) throws InterruptedException {
        var userMessage = MessageFactory.createUserMessage(
                List.of(new ContentBlock.Text(message)),
                new MessageSource.User()
        );

        agent.followup(userMessage);
        agent.whenIdle();

        // 提取最后一条 assistant 消息
        var messages = agent.session().deriveMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof Message.AssistantMessage asst) {
                var text = extractText(asst.content());
                return text.isEmpty() ? "(响应不包含文本内容)" : text;
            }
        }
        return "(无响应)";
    }

    private static String extractText(List<ContentBlock> content) {
        var sb = new StringBuilder();
        for (var block : content) {
            if (block instanceof ContentBlock.Text text) {
                sb.append(text.text());
            }
        }
        return sb.toString();
    }
}
