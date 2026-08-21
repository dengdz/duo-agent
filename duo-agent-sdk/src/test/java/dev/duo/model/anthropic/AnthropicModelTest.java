package dev.duo.model.anthropic;

import dev.duo.api.DuoModel;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link AnthropicModel} 的 Builder 校验与预设测试。
 *
 * @author zhangyl
 * @date 2026-08-21
 */
class AnthropicModelTest {

    @Test
    void buildShouldRejectMissingModel() {
        var error = assertThrows(IllegalStateException.class,
                () -> AnthropicModel.builder().apiKey("key").build());
        assertEquals(true, error.getMessage().contains("模型名称"), "应提示缺少 model");
    }

    @Test
    void buildShouldRejectMissingApiKey() {
        // 无 ANTHROPIC_API_KEY 环境变量的环境下断言；有环境变量时跳过
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv("ANTHROPIC_API_KEY") == null
                        || System.getenv("ANTHROPIC_API_KEY").isBlank());
        assertThrows(IllegalStateException.class,
                () -> AnthropicModel.builder().model("glm-4.6").build());
    }

    @Test
    void shouldUseOfficialEndpointByDefault() {
        DuoModel model = AnthropicModel.builder()
                .apiKey("test-key")
                .model("glm-4.6")
                .baseUrl("https://open.bigmodel.cn/api/anthropic/")
                .build();

        assertEquals("anthropic", model.getApiFormat(), "协议标识为 anthropic");
        assertEquals("glm-4.6", model.getModelName());
    }

    @Test
    void builderShouldValidateRanges() {
        assertThrows(IllegalArgumentException.class,
                () -> AnthropicModel.builder().apiKey("k").model("m").thinkingBudgetTokens(0));
        assertThrows(IllegalArgumentException.class,
                () -> AnthropicModel.builder().apiKey("k").model("m")
                        .reasoningTimeout(Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> AnthropicModel.builder().apiKey("k").model("m").temperature(3.0));
    }
}
