package dev.duo.api;

import dev.duo.core.ScriptedStreamAdapter;
import dev.duo.core.model.ScriptedDuoModel;
import dev.duo.tool.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DuoAgentBuilder} 单元测试。
 * <p>
 * 除常规链式配置验证外，重点覆盖两条组装红线：
 * systemPrompt 优先级（Agent 显式 &gt; Model &gt; 内置默认，经 RequestHook
 * 捕获实际请求验证）与超时分层（HTTP 兜底 = max(llmTimeout, reasoningTimeout)
 * + 1 分钟，经适配器工厂入参验证）。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
class DuoAgentBuilderTest {

    /** Builder 内置兜底文案（与实现常量保持一致，优先级测试用）。 */
    private static final String BUILTIN_DEFAULT_PROMPT =
            "你是一个智能助手，可以使用工具帮助用户完成任务。";

    /** 组装一个「捕获最终 system prompt」的 Agent 并执行一次对话。 */
    private String callAndCaptureSystemPrompt(DuoModel model, String agentPrompt) {
        var captured = new AtomicReference<String>();
        var builder = DuoAgent.builder().model(model);
        if (agentPrompt != null) {
            builder.systemPrompt(agentPrompt);
        }
        builder.hooks().addRequestHook((context, next) -> {
            var options = next.proceed();
            captured.set(options.system());
            return options;
        });
        builder.build().call("hi");
        return captured.get();
    }

    @Test
    void testBuilderCreation() {
        var builder = DuoAgent.builder();
        assertNotNull(builder, "builder 不应为 null");
    }

    @Test
    void testModelNull() {
        assertThrows(NullPointerException.class, () -> {
            DuoAgent.builder().model(null);
        }, "model 为 null 应该抛出异常");
    }

    @Test
    void testTimeoutInvalid() {
        assertThrows(IllegalArgumentException.class, () -> {
            DuoAgent.builder().timeout(Duration.ZERO);
        }, "timeout 为零应该抛出异常");

        assertThrows(IllegalArgumentException.class, () -> {
            DuoAgent.builder().timeout(Duration.ofSeconds(-1));
        }, "timeout 为负应该抛出异常");

        assertThrows(NullPointerException.class, () -> {
            DuoAgent.builder().timeout(null);
        }, "timeout 为 null 应该抛出异常");
    }

    @Test
    void testSystemPromptNull() {
        assertThrows(NullPointerException.class, () -> {
            DuoAgent.builder().systemPrompt(null);
        }, "systemPrompt 为 null 应该抛出异常");
    }

    @Test
    void testWithFileTools() {
        var builder = DuoAgent.builder()
                .withFileTools();

        assertNotNull(builder, "启用 withFileTools 后 builder 不应为 null");
    }

    @Test
    void testWithSearchTools() {
        var builder = DuoAgent.builder()
                .withSearchTools();

        assertNotNull(builder, "启用 withSearchTools 后 builder 不应为 null");
    }

    @Test
    void testWithEditTools() {
        var builder = DuoAgent.builder()
                .withEditTools();

        assertNotNull(builder, "启用 withEditTools 后 builder 不应为 null");
    }

    @Test
    void testWithCodeTools() {
        var builder = DuoAgent.builder()
                .withCodeTools();

        assertNotNull(builder, "启用 withCodeTools 后 builder 不应为 null");
    }

    @Test
    void testWithAllBuiltinTools() {
        var builder = DuoAgent.builder()
                .withAllBuiltinTools();

        assertNotNull(builder, "启用 withAllBuiltinTools 后 builder 不应为 null");
    }

    @Test
    void testAddSingleTool() {
        var builder = DuoAgent.builder()
                .tool(new FileReadTool().getDefinition());

        assertNotNull(builder, "添加单个工具后 builder 不应为 null");
    }

    @Test
    void testAddMultipleTools() {
        var builder = DuoAgent.builder()
                .tools(
                        new FileReadTool().getDefinition(),
                        new FileWriteTool().getDefinition(),
                        new GrepTool().getDefinition()
                );

        assertNotNull(builder, "添加多个工具后 builder 不应为 null");
    }

    @Test
    void testAddToolNull() {
        assertThrows(NullPointerException.class, () -> {
            DuoAgent.builder().tool(null);
        }, "添加 null 工具应该抛出异常");
    }

    @Test
    void testBuildWithoutModel() {
        var builder = DuoAgent.builder()
                .withFileTools();

        var exception = assertThrows(IllegalStateException.class, builder::build);
        assertTrue(exception.getMessage().contains("未设置 Model"),
                "错误消息应提示未设置 Model，实际: " + exception.getMessage());
    }

    @Test
    void testBuildSuccess() {
        var agent = DuoAgent.builder()
                .model(new ScriptedDuoModel(new ScriptedStreamAdapter(
                        ScriptedStreamAdapter.textReply("ok"))))
                .withFileTools()
                .build();

        assertNotNull(agent, "构建的 agent 不应为 null");
        assertNotNull(agent.getAgent(), "底层 agent 不应为 null");
        assertNotNull(agent.getSession(), "session 不应为 null");
    }

    @Test
    void testFluentChaining() {
        // 验证所有方法都返回 builder 自身，支持链式调用
        var builder = DuoAgent.builder()
                .model(new ScriptedDuoModel(new ScriptedStreamAdapter(
                        ScriptedStreamAdapter.textReply("ok"))))
                .timeout(Duration.ofSeconds(90))
                .systemPrompt("测试")
                .withFileTools()
                .withSearchTools()
                .tool(new EditTool().getDefinition());

        assertNotNull(builder, "链式调用后 builder 不应为 null");

        var agent = builder.build();
        assertNotNull(agent, "链式调用构建的 agent 不应为 null");
    }

    // ==================== systemPrompt 优先级 ====================

    @Test
    void systemPromptAgentExplicitShouldWin() {
        var model = new ScriptedDuoModel(
                timeout -> new ScriptedStreamAdapter(ScriptedStreamAdapter.textReply("ok")),
                "model-prompt", false, Duration.ofMinutes(5));

        assertEquals("agent-prompt", callAndCaptureSystemPrompt(model, "agent-prompt"),
                "Agent 显式 systemPrompt 应具有最高优先级");
    }

    @Test
    void systemPromptShouldFallBackToModelWhenAgentUnset() {
        var model = new ScriptedDuoModel(
                timeout -> new ScriptedStreamAdapter(ScriptedStreamAdapter.textReply("ok")),
                "model-prompt", false, Duration.ofMinutes(5));

        assertEquals("model-prompt", callAndCaptureSystemPrompt(model, null),
                "Agent 未设置时应回落到 Model 的 systemPrompt，而非内置默认");
    }

    @Test
    void systemPromptShouldFallBackToBuiltinDefaultWhenBothUnset() {
        var model = new ScriptedDuoModel(
                new ScriptedStreamAdapter(ScriptedStreamAdapter.textReply("ok")));

        assertEquals(BUILTIN_DEFAULT_PROMPT, callAndCaptureSystemPrompt(model, null),
                "两者均未设置时才使用内置默认文案");
    }

    // ==================== 超时分层红线 ====================

    @Test
    void httpTimeoutShouldBeMaxOfAgentAndModelTimeoutsPlusMargin() {
        // Model reasoningTimeout(5min) > Agent timeout(90s)：取 5min + 1min 余量
        var captured = new AtomicReference<Duration>();
        var model = new ScriptedDuoModel(
                timeout -> {
                    captured.set(timeout);
                    return new ScriptedStreamAdapter(ScriptedStreamAdapter.textReply("ok"));
                },
                null, true, Duration.ofMinutes(5));

        DuoAgent.builder()
                .model(model)
                .timeout(Duration.ofSeconds(90))
                .build();

        assertEquals(Duration.ofMinutes(6), captured.get(),
                "HTTP 兜底应等于 max(llmTimeout, reasoningTimeout) + 1 分钟");
    }

    @Test
    void httpTimeoutShouldUseAgentTimeoutWhenLarger() {
        // Agent timeout(90s) > Model reasoningTimeout(30s)：取 90s + 1min 余量
        var captured = new AtomicReference<Duration>();
        var model = new ScriptedDuoModel(
                timeout -> {
                    captured.set(timeout);
                    return new ScriptedStreamAdapter(ScriptedStreamAdapter.textReply("ok"));
                },
                null, true, Duration.ofSeconds(30));

        DuoAgent.builder()
                .model(model)
                .timeout(Duration.ofSeconds(90))
                .build();

        assertEquals(Duration.ofSeconds(150), captured.get(),
                "Agent llmTimeout 更大时应以其为应用层最大超时");
    }

    @Test
    void httpTimeoutShouldIgnoreReasoningTimeoutWhenReasoningDisabled() {
        // 非推理模型：reasoningTimeout 不纳入 HTTP 兜底计算，
        // 否则默认 5min 的推理超时会把普通调用的失败检测上限无谓放大到 6 分钟
        var captured = new AtomicReference<Duration>();
        var model = new ScriptedDuoModel(
                timeout -> {
                    captured.set(timeout);
                    return new ScriptedStreamAdapter(ScriptedStreamAdapter.textReply("ok"));
                },
                null, false, Duration.ofMinutes(5));

        DuoAgent.builder()
                .model(model)
                .timeout(Duration.ofSeconds(90))
                .build();

        assertEquals(Duration.ofSeconds(150), captured.get(),
                "非推理模型的 reasoningTimeout 不应影响 HTTP 兜底超时");
    }

    @Test
    void buildShouldAcceptAllThreeProtocolFormats() {
        // AgentOptions 的 apiFormat 白名单必须覆盖全部协议标识——
        // responses 缺失曾导致 ResponsesModel 组装 Agent 时直接抛异常（实机发现）
        for (var format : new String[]{"openai", "anthropic", "responses"}) {
            var model = new ScriptedDuoModel(
                    timeout -> new ScriptedStreamAdapter(ScriptedStreamAdapter.textReply("ok")),
                    null, false, Duration.ofMinutes(5), format);
            var agent = DuoAgent.builder().model(model).build();
            assertEquals(true, agent != null, format + " 协议应可组装 Agent");
        }
    }
}
