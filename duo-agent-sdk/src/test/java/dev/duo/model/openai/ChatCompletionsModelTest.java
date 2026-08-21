package dev.duo.model.openai;

import dev.duo.adapter.openai.ChatCompletionsAdapter;
import dev.duo.api.DuoModel;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ChatCompletionsModel} 的 Builder 校验与工厂语义测试。
 *
 * @author zhangyl
 * @date 2026-08-21
 */
class ChatCompletionsModelTest {

    private static final String TEST_URL = "http://localhost:11434/v1";

    @Test
    void buildShouldRejectMissingBaseUrl() {
        var error = assertThrows(IllegalStateException.class,
                () -> ChatCompletionsModel.builder().model("qwen3:32b").build());
        assertEquals(true, error.getMessage().contains("baseUrl"), "应提示缺少 baseUrl");
    }

    @Test
    void buildShouldRejectMissingModel() {
        var error = assertThrows(IllegalStateException.class,
                () -> ChatCompletionsModel.builder().baseUrl(TEST_URL).build());
        assertEquals(true, error.getMessage().contains("模型名称"), "应提示缺少 model");
    }

    @Test
    void buildShouldAllowMissingApiKeyForLocalEndpoints() {
        // 本地无鉴权端点（Ollama/vLLM）：apiKey 可选
        DuoModel model = ChatCompletionsModel.builder()
                .baseUrl(TEST_URL)
                .model("qwen3:32b")
                .build();

        assertEquals("openai", model.getApiFormat(), "协议标识为 openai");
        assertEquals("qwen3:32b", model.getModelName());
    }

    @Test
    void baseUrlShouldStripTrailingSlash() {
        var model = ChatCompletionsModel.builder()
                .baseUrl("http://localhost:11434/v1/")
                .model("qwen3:32b")
                .build();
        // 经适配器构造后行为不可直接观察，此处验证构建成功且工厂可用即可；
        // 尾斜杠剥离的断言由协议层 HttpServer mock 测试覆盖（请求路径无双斜杠）
        assertInstanceOf(ChatCompletionsAdapter.class,
                model.createAdapter(Duration.ofMinutes(2)));
    }

    @Test
    void gettersShouldReturnConfiguredValues() {
        DuoModel model = ChatCompletionsModel.builder()
                .baseUrl(TEST_URL)
                .apiKey("test-key")
                .model("kimi-latest")
                .contextWindow(128000)
                .maxOutputTokens(4096)
                .temperature(0.5)  // 校验合法即可，Config 层字段不对外暴露 getter
                .systemPrompt("  ")  // 空白视为未设置
                .build();

        assertEquals("kimi-latest", model.getModelName());
        assertEquals(128000, model.getContextWindow());
        assertEquals(4096, model.getMaxOutputTokens());
        assertNull(model.getSystemPrompt(), "空白 systemPrompt 应归一化为未设置");
    }

    @Test
    void builderShouldValidateRanges() {
        assertThrows(IllegalArgumentException.class,
                () -> ChatCompletionsModel.builder().baseUrl(TEST_URL).model("m").contextWindow(0));
        assertThrows(IllegalArgumentException.class,
                () -> ChatCompletionsModel.builder().baseUrl(TEST_URL).model("m").temperature(2.5));
        assertThrows(IllegalArgumentException.class,
                () -> ChatCompletionsModel.builder().baseUrl(TEST_URL).model("m").temperature(Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> ChatCompletionsModel.builder().baseUrl(TEST_URL).model("m")
                        .reasoningTimeout(Duration.ZERO));
    }
}
