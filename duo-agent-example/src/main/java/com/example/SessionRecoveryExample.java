package com.example;

import dev.duo.api.agent.AgentOptions;
import dev.duo.api.llm.LlmRuntime;
import dev.duo.core.agent.ReactLoopAgent;
import dev.duo.core.llm.MockEchoAdapter;
import dev.duo.core.llm.SystemPromptImpl;
import dev.duo.core.llm.ToolRegistryImpl;
import dev.duo.core.session.JsonlSessionPersistence;
import dev.duo.core.session.Session;
import dev.duo.model.llm.ContentBlock;
import dev.duo.model.llm.Message;
import dev.duo.model.llm.MessageFactory;
import dev.duo.model.llm.MessageSource;
import dev.duo.model.llm.ToolDefinition;
import dev.duo.model.llm.ToolExecutionResult;
import dev.duo.model.llm.ToolExecutor;
import dev.duo.model.session.SessionEventTurnEnd;
import dev.duo.model.session.SessionHeader;
import dev.duo.model.session.SessionId;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * "进程重启恢复对话"端到端示例（无需 API Key，使用 mock 适配器）。
 * <p>
 * 用两次独立的进程运行验证 JSONL 持久化：
 * <pre>
 *   java SessionRecoveryExample start    # 进程 A：一轮含工具往返的对话，落盘退出
 *   java SessionRecoveryExample resume   # 进程 B：从磁盘恢复对话并续聊第二轮
 * </pre>
 * resume 进程可看到：第一轮的全部消息从日志派生恢复（含工具往返）、
 * turn 序号衔接、seq 连续续写、两轮对话合并为一份日志。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public class SessionRecoveryExample {

    private static final Path BASE_DIR = Path.of("sessions-demo");
    private static final SessionId SESSION_ID = new SessionId("recovery-demo");

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "resume".equals(args[0])) {
            resumeInNewProcess();
        } else {
            startFirstProcess();
        }
    }

    // ---- 进程 A ----

    private static void startFirstProcess() throws Exception {
        System.out.println("=== 进程 A：首轮对话（start）===");
        try (var persistence = new JsonlSessionPersistence(BASE_DIR)) {
            persistence.create(new SessionHeader(0, SESSION_ID, System.currentTimeMillis(),
                    null, null, null, null, null, null));

            var session = new Session(SESSION_ID);
            session.onAppend(event -> persistence.acceptFromListener(SESSION_ID, event));

            var agent = newAgent(session);
            System.out.println("用户: echo persistence-demo");
            agent.followup(userMessage("echo persistence-demo"));
            agent.whenIdle();
            persistence.flush(SESSION_ID);

            printConversation(session, "进程 A 结束时的对话");
            System.out.printf("已落盘 %d 个事件，进程退出%n%n", session.events().size());
        }
    }

    // ---- 进程 B ----

    private static void resumeInNewProcess() throws Exception {
        System.out.println("=== 进程 B：恢复并续聊（resume）===");
        try (var persistence = new JsonlSessionPersistence(BASE_DIR)) {
            // 加载：撕裂末行丢弃、崩溃遗留的 open turn 自动闭合（本例日志平衡，无合成）
            var inspection = persistence.load(SESSION_ID);
            System.out.printf("从磁盘恢复 %d 个事件%n", inspection.events().size());

            // 种子构造恢复的 Session：初始监听器先于构造期的 end-seed 边界事件注册，
            // 保证从恢复的第一条事件起就不遗漏（load 已按日志尾建立带 seq 校验的写入器）
            var session = new Session(SESSION_ID,
                    inspection.events().toArray(new dev.duo.model.session.SessionEvent[0]),
                    inspection.header(),
                    event -> persistence.acceptFromListener(SESSION_ID, event));

            printConversation(session, "恢复后的历史（未发新消息即可派生）");

            var agent = newAgent(session);
            System.out.println("\n用户: 这是我重启后的新消息");
            agent.followup(userMessage("这是我重启后的新消息"));
            agent.whenIdle();
            persistence.flush(SESSION_ID);

            printConversation(session, "进程 B 结束时的完整对话");
            printTurnSummary(session);
            System.out.printf("最终 %d 个事件，两轮对话已合并为一份日志%n", session.events().size());
        }
    }

    // ---- 组装与打印 ----

    private static ReactLoopAgent newAgent(Session session) {
        var llm = new LlmRuntime();
        llm.registerAdapter("mock-echo", new MockEchoAdapter());
        var tools = new ToolRegistryImpl();
        tools.register(new ToolDefinition(
                "echo", "回显文本", Map.of(),
                ToolExecutor.of(args -> new ToolExecutionResult("echo 工具结果: " + args.get("text")))));
        return new ReactLoopAgent(
                SESSION_ID,
                new AgentOptions("mock-echo", "mock-model", null, null),
                session, llm, new SystemPromptImpl("", false), tools);
    }

    private static Message.UserMessage userMessage(String text) {
        return MessageFactory.createUserMessage(
                List.of(new ContentBlock.Text(text)), new MessageSource.User());
    }

    private static void printConversation(Session session, String title) {
        System.out.println("\n--- " + title + " ---");
        for (var message : session.deriveMessages()) {
            switch (message) {
                case Message.UserMessage m when m.source() instanceof MessageSource.Tool ->
                        System.out.println("  [工具结果] " + firstText(m.content()));
                case Message.UserMessage m -> System.out.println("  [用户] " + firstText(m.content()));
                case Message.AssistantMessage m ->
                        System.out.println("  [助手] " + firstText(m.content()));
                case Message.ToolResultMessage m ->
                        System.out.println("  [工具结果] " + firstText(m.content()));
                default -> { }
            }
        }
    }

    /** 提取顶层 Text 块文本；工具结果消息的文本嵌在 ToolResult 块内。 */
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

    private static void printTurnSummary(Session session) {
        System.out.println("\n--- turn 结束原因 ---");
        session.events().stream()
                .filter(e -> e instanceof SessionEventTurnEnd)
                .map(e -> (SessionEventTurnEnd) e)
                .forEach(t -> System.out.printf("  turn %d: %s%n",
                        t.turn(), t.reason().getClass().getSimpleName()));
    }
}
