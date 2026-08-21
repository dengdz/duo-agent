package dev.duo.model.openai;

import dev.duo.api.DuoModel;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ResponsesModel} 的 Builder 校验测试。
 *
 * @author zhangyl
 * @date 2026-08-21
 */
class ResponsesModelTest {

    @Test
    void buildShouldRejectMissingModel() {
        var error = assertThrows(IllegalStateException.class,
                () -> ResponsesModel.builder().apiKey("key").build());
        assertEquals(true, error.getMessage().contains("模型名称"), "应提示缺少 model");
    }

    @Test
    void buildShouldRejectMissingApiKey() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv("OPENAI_API_KEY") == null
                        || System.getenv("OPENAI_API_KEY").isBlank());
        assertThrows(IllegalStateException.class,
                () -> ResponsesModel.builder().model("gpt-5.2").build());
    }

    @Test
    void shouldUseOfficialEndpointByDefault() {
        DuoModel model = ResponsesModel.builder()
                .apiKey("test-key")
                .model("gpt-5.2")
                .enableReasoning(true)
                .reasoningEffort("high")
                .build();

        assertEquals("responses", model.getApiFormat(), "协议标识为 responses");
        assertEquals("gpt-5.2", model.getModelName());
        assertEquals(true, model.isReasoningEnabled());
    }

    @Test
    void builderShouldValidateReasoningEffortAndRanges() {
        // null 直接抛 IllegalArgument（Set.of 的 contains(null) 是裸 NPE，消息无上下文）
        assertThrows(IllegalArgumentException.class,
                () -> ResponsesModel.builder().apiKey("k").model("m").reasoningEffort(null));
        assertThrows(IllegalArgumentException.class,
                () -> ResponsesModel.builder().apiKey("k").model("m").reasoningEffort("ultra"));
        // NaN 穿透校验会序列化出非法 JSON，必须显式拦截
        assertThrows(IllegalArgumentException.class,
                () -> ResponsesModel.builder().apiKey("k").model("m").temperature(Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> ResponsesModel.builder().apiKey("k").model("m").contextWindow(-1));
        assertThrows(IllegalArgumentException.class,
                () -> ResponsesModel.builder().apiKey("k").model("m")
                        .reasoningTimeout(Duration.ZERO));
    }
}
