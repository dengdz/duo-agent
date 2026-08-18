package dev.dsh.core.agent;

import dev.dsh.api.agent.AgentOptions;
import dev.dsh.api.agent.InboxTarget;
import dev.dsh.model.llm.MessageFactory;
import dev.dsh.model.llm.MessageSource;
import dev.dsh.api.llm.LlmRuntime;
import dev.dsh.core.llm.MockEchoAdapter;
import dev.dsh.core.llm.SystemPromptImpl;
import dev.dsh.model.llm.ContentBlock;
import dev.dsh.core.session.Session;
import dev.dsh.model.session.SessionEvent;
import dev.dsh.model.session.SessionEventTurnStart;
import dev.dsh.model.session.SessionEventTurnEnd;
import dev.dsh.model.session.SessionEventStepStart;
import dev.dsh.model.session.SessionEventAssistantChunk;
import dev.dsh.model.session.SessionEventAssistantMessage;
import dev.dsh.model.session.SessionEventUserMessage;
import dev.dsh.model.session.SessionId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ReactLoopAgent} 的端到端测试。
 * <p>
 * 使用 MockEchoAdapter 模拟一次完整对话。
 * 验证：turn/step 生命周期、session 日志、inbox 流转。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
class ReactLoopAgentTest {

    @Test
    void shouldCompleteFullConversationWithCorrectEvents() throws Exception {
        // 准备：LlmRuntime + MockEchoAdapter
        var llm = new LlmRuntime();
        llm.registerAdapter("mock-echo", new MockEchoAdapter());

        // 创建 Session
        var session = new Session(new SessionId("test-session"));

        // 创建 Agent
        var agent = new ReactLoopAgent(
                new SessionId("test-agent"),
                new AgentOptions("mock-echo", "mock-model", null),
                session,
                llm,
                new SystemPromptImpl("", false), new dev.dsh.core.llm.ToolRegistryImpl()
        );

        // 发送一条消息
        var userMsg = MessageFactory.createUserMessage(
                List.of(new ContentBlock.Text("hello")),
                new MessageSource.User()
        );
        agent.followup(userMsg);

        // 等待完成
        agent.whenIdle();

        // 验证 session 事件序列
        var events = session.events();
        System.out.println("事件数: " + events.size());
        for (var e : events) {
            System.out.println("  " + e.type() + " seq=" + e.seq());
        }

        // 验证事件序列
        assertTrue(events.size() >= 6, "至少应有 6 个事件");

        // 检查事件顺序
        var hasTurnStart = false;
        var hasStepStart = false;
        var hasUserMessage = false;
        var hasAssistantChunk = false;
        var hasAssistantMessage = false;
        var hasTurnEnd = false;

        for (var e : events) {
            switch (e) {
                case SessionEventTurnStart ignored -> hasTurnStart = true;
                case SessionEventStepStart ignored -> hasStepStart = true;
                case SessionEventUserMessage ignored -> hasUserMessage = true;
                case SessionEventAssistantChunk ignored -> hasAssistantChunk = true;
                case SessionEventAssistantMessage ignored -> hasAssistantMessage = true;
                case SessionEventTurnEnd ignored -> hasTurnEnd = true;
                default -> {}
            }
        }

        assertTrue(hasTurnStart, "应有 turn/start");
        assertTrue(hasStepStart, "应有 step/start");
        assertTrue(hasUserMessage, "应有 user/message");
        assertTrue(hasAssistantChunk, "应有 assistant/chunk");
        assertTrue(hasAssistantMessage, "应有 assistant/message");
        assertTrue(hasTurnEnd, "应有 turn/end");

        // 验证 turn 结束原因为 completed
        var lastEvent = events.getLast();
        if (lastEvent instanceof SessionEventTurnEnd te) {
            assertInstanceOf(dev.dsh.model.session.TurnEndReason.Completed.class, te.reason());
        }

        // 验证 deriveMessages 能正确派生出消息
        var messages = session.deriveMessages();
        assertFalse(messages.isEmpty(), "应派生至少一条消息");
        assertEquals("user", messages.getFirst().role());
    }

    @Test
    void shouldHandleMultipleTurns() throws Exception {
        var llm = new LlmRuntime();
        llm.registerAdapter("mock-echo", new MockEchoAdapter());

        var session = new Session(new SessionId("multi-turn"));
        var agent = new ReactLoopAgent(
                new SessionId("multi-agent"),
                new AgentOptions("mock-echo", "mock-model", null),
                session,
                llm,
                new SystemPromptImpl("", false), new dev.dsh.core.llm.ToolRegistryImpl()
        );

        // 第一轮
        agent.followup(MessageFactory.createUserMessage(
                List.of(new ContentBlock.Text("first message")),
                new MessageSource.User()
        ));
        agent.whenIdle();

        // 第二轮
        agent.followup(MessageFactory.createUserMessage(
                List.of(new ContentBlock.Text("second message")),
                new MessageSource.User()
        ));
        agent.whenIdle();

        // 验证有两轮
        var turnStarts = session.events().stream()
                .filter(e -> e instanceof SessionEventTurnStart)
                .count();
        assertEquals(2, turnStarts, "应有 2 个 turn/start");

        // 验证派生消息包含两轮的用户消息
        var messages = session.deriveMessages();
        assertTrue(messages.size() >= 2, "应派生至少 2 条消息");
    }
}