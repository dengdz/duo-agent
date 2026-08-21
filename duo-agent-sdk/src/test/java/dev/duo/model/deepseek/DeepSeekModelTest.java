package dev.duo.model.deepseek;

import dev.duo.adapter.openai.ChatCompletionsAdapter;
import dev.duo.api.DuoModel;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link DeepSeekModel} 构建器与工厂行为的单元测试（不发起网络调用）。
 * <p>
 * 验证：必填校验、环境变量回落、参数范围校验、getter 反映配置、
 * 适配器工厂的实例语义。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-20
 */
class DeepSeekModelTest {

    private static final String TEST_KEY = "test-api-key";

    @Test
    void buildShouldRequireModelName() {
        var thrown = assertThrows(IllegalStateException.class,
                () -> DeepSeekModel.builder().apiKey(TEST_KEY).build());
        assertTrue(thrown.getMessage().contains("未配置模型名称"),
                "错误消息应提示未配置模型名称，实际: " + thrown.getMessage());
    }

    @Test
    void buildShouldRequireApiKeyWhenEnvAbsent() {
        // 环境变量存在时无法确定性测试缺失分支，跳过而非误报
        assumeTrue(System.getenv("DEEPSEEK_API_KEY") == null,
                "DEEPSEEK_API_KEY 已设置，跳过缺失分支");

        var thrown = assertThrows(IllegalStateException.class,
                () -> DeepSeekModel.builder().model("deepseek-chat").build());
        assertTrue(thrown.getMessage().contains("未设置 API Key"),
                "错误消息应提示 API Key 缺失及环境变量回落，实际: " + thrown.getMessage());
    }

    @Test
    void contextWindowShouldRejectNonPositive() {
        assertThrows(IllegalArgumentException.class,
                () -> DeepSeekModel.builder().contextWindow(0));
        assertThrows(IllegalArgumentException.class,
                () -> DeepSeekModel.builder().contextWindow(-1));
    }

    @Test
    void maxOutputTokensShouldRejectNonPositive() {
        assertThrows(IllegalArgumentException.class,
                () -> DeepSeekModel.builder().maxOutputTokens(0));
    }

    @Test
    void temperatureShouldRejectOutOfRange() {
        assertThrows(IllegalArgumentException.class,
                () -> DeepSeekModel.builder().temperature(-0.1));
        assertThrows(IllegalArgumentException.class,
                () -> DeepSeekModel.builder().temperature(2.1));
    }

    @Test
    void reasoningTimeoutShouldRejectNonPositive() {
        assertThrows(NullPointerException.class,
                () -> DeepSeekModel.builder().reasoningTimeout(null));
        assertThrows(IllegalArgumentException.class,
                () -> DeepSeekModel.builder().reasoningTimeout(Duration.ZERO));
    }

    @Test
    void gettersShouldReflectConfiguration() {
        DuoModel model = DeepSeekModel.builder()
                .apiKey(TEST_KEY)
                .model("deepseek-reasoner")
                .systemPrompt("你是代码审查专家")
                .contextWindow(64000)
                .maxOutputTokens(8000)
                .temperature(0.2)
                .enableReasoning(true)
                .reasoningTimeout(Duration.ofMinutes(3))
                .build();

        assertEquals("openai", model.getApiFormat());
        assertEquals("deepseek-reasoner", model.getModelName());
        assertEquals("你是代码审查专家", model.getSystemPrompt());
        assertEquals(64000, model.getContextWindow());
        assertEquals(8000, model.getMaxOutputTokens());
        assertTrue(model.isReasoningEnabled());
        assertEquals(Duration.ofMinutes(3), model.getReasoningTimeout());
    }

    @Test
    void defaultsShouldApplyWhenOptionalFieldsUnset() {
        DuoModel model = DeepSeekModel.builder()
                .apiKey(TEST_KEY)
                .model("deepseek-chat")
                .build();

        assertNull(model.getSystemPrompt(), "systemPrompt 默认不设置");
        assertNull(model.getContextWindow(), "contextWindow 默认不设置");
        assertNull(model.getMaxOutputTokens(), "maxOutputTokens 默认不设置（由模型决定）");
        assertFalse(model.isReasoningEnabled());
        assertEquals(Duration.ofMinutes(5), model.getReasoningTimeout(),
                "reasoningTimeout 默认 5 分钟");
    }

    @Test
    void createAdapterShouldReturnChatCompletionsAdapterWithDeepSeekPreset() {
        var model = DeepSeekModel.builder()
                .apiKey(TEST_KEY)
                .model("deepseek-chat")
                .build();

        // 0.3.0 起 DeepSeek 预设复用 Chat Completions 协议适配器
        //（含 reasoning_content 字段变体），不再有独立的 DeepSeek 协议实现
        assertInstanceOf(ChatCompletionsAdapter.class, model.createAdapter(Duration.ofMinutes(6)),
                "带参工厂应产出 Chat Completions 协议适配器");
        assertNotSame(model.createAdapter(Duration.ofMinutes(6)), model.createAdapter(Duration.ofMinutes(6)),
                "带参工厂每次组装创建独立适配器");
        assertSame(model.createAdapter(), model.createAdapter(),
                "无参工厂（Model 自用）复用同一适配器实例");
    }
}
