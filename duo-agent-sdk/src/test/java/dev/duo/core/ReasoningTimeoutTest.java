package dev.duo.core;

import dev.duo.api.agent.AgentOptions;
import dev.duo.api.llm.LlmRuntime;
import dev.duo.core.agent.ReactLoopAgent;
import dev.duo.core.llm.SystemPromptImpl;
import dev.duo.core.llm.ToolRegistryImpl;
import dev.duo.core.session.Session;
import dev.duo.model.session.SessionId;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 推理模式超时行为测试。
 * <p>
 * 验证：reasoningEnabled=true 时 LLM 调用应用 reasoningTimeout（而非 llmTimeout），
 * 思考耗时超过 llmTimeout 但小于 reasoningTimeout 的调用应成功完成；
 * 未启用推理时同样的耗时应按 llmTimeout 超时失败。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
class ReasoningTimeoutTest {

    /** 思考延迟：超过 llmTimeout(1s) 但远小于 reasoningTimeout(10s)。 */
    private static final long THINKING_DELAY_MS = 1500;
    private static final String SLOW_REPLY = "思考完成后的回答";

    /** 组装指定 AgentOptions 的 DuoAgentImpl，mock 适配器模拟思考延迟。 */
    private DuoAgentImpl newAgent(AgentOptions options) {
        var llm = new LlmRuntime();
        llm.registerAdapter("mock-slow", new ScriptedStreamAdapter(
                ScriptedStreamAdapter.textReply(SLOW_REPLY), null, THINKING_DELAY_MS));
        var session = new Session(new SessionId("timeout-test"));
        var agent = new ReactLoopAgent(
                new SessionId("agent-timeout-test"),
                options,
                session,
                llm,
                new SystemPromptImpl("", false),
                new ToolRegistryImpl()
        );
        return new DuoAgentImpl(agent, session);
    }

    @Test
    void reasoningModeShouldUseReasoningTimeout() {
        // llmTimeout=1s 会被 1.5s 思考延迟超过；reasoningTimeout=10s 足够
        var agent = newAgent(new AgentOptions(
                "openai", "mock-slow", "mock-reasoner", null, null,
                true,                       // reasoningEnabled
                Duration.ofSeconds(10),     // reasoningTimeout
                Duration.ofSeconds(1),      // llmTimeout（推理模式下不生效）
                null));

        long start = System.currentTimeMillis();
        String reply = agent.chat("需要深思的问题");
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(SLOW_REPLY, reply, "思考延迟超过 llmTimeout 但在 reasoningTimeout 内，应成功");
        assertTrue(elapsed >= THINKING_DELAY_MS,
                "实际等待了思考延迟，耗时: " + elapsed + "ms");
    }

    @Test
    void normalModeShouldTimeOutOnSameDelay() {
        // 同样的 1.5s 延迟，未启用推理 → 按 llmTimeout=1s 超时失败
        var agent = newAgent(new AgentOptions(
                "openai", "mock-slow", "mock-model", null, null,
                false,                      // reasoningEnabled=false
                Duration.ofSeconds(10),     // reasoningTimeout（不生效）
                Duration.ofSeconds(1),      // llmTimeout=1s
                null));

        long start = System.currentTimeMillis();
        var thrown = assertThrows(IllegalStateException.class,
                () -> agent.chat("普通问题"));
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(thrown.getMessage().contains("未生成新的响应"),
                "超时失败应体现为 chat 的失败异常，实际: " + thrown.getMessage());
        assertTrue(elapsed < THINKING_DELAY_MS + 2000,
                "应在 llmTimeout 附近快速失败而非等完思考延迟，耗时: " + elapsed + "ms");
    }
}
