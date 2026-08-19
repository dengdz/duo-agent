package dev.duo.api;

import dev.duo.tool.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DuoAgentBuilder} 单元测试。
 *
 * @author zhangyl
 * @date 2026-08-19
 */
class DuoAgentBuilderTest {

    @Test
    void testBuilderCreation() {
        var builder = DuoAgent.builder();
        assertNotNull(builder, "builder 不应为 null");
    }

    @Test
    void testApiFormat() {
        var builder = DuoAgent.builder()
                .apiFormat("openai");

        assertNotNull(builder, "配置 apiFormat 后 builder 不应为 null");
    }

    @Test
    void testApiFormatInvalid() {
        // High #2: 现在 apiFormat() 直接拒绝不支持的格式（包括 "anthropic"）
        assertThrows(IllegalArgumentException.class, () -> {
            DuoAgent.builder().apiFormat("unsupported");
        }, "不支持的 API 格式应该抛出异常");
        
        assertThrows(IllegalArgumentException.class, () -> {
            DuoAgent.builder().apiFormat("anthropic");
        }, "anthropic 格式暂未实现，应该抛出异常");
    }

    @Test
    void testModel() {
        var builder = DuoAgent.builder()
                .model("deepseek-chat");

        assertNotNull(builder, "配置 model 后 builder 不应为 null");
    }

    @Test
    void testApiKey() {
        var builder = DuoAgent.builder()
                .apiKey("test-key");

        assertNotNull(builder, "配置 apiKey 后 builder 不应为 null");
    }

    @Test
    void testBaseUrl() {
        var builder = DuoAgent.builder()
                .baseUrl("https://api.example.com");

        assertNotNull(builder, "配置 baseUrl 后 builder 不应为 null");
    }

    @Test
    void testContextWindow() {
        var builder = DuoAgent.builder()
                .contextWindow(128000);

        assertNotNull(builder, "配置 contextWindow 后 builder 不应为 null");
    }

    @Test
    void testContextWindowInvalid() {
        assertThrows(IllegalArgumentException.class, () -> {
            DuoAgent.builder().contextWindow(0);
        }, "contextWindow <= 0 应该抛出异常");
    }

    @Test
    void testMaxOutputTokens() {
        var builder = DuoAgent.builder()
                .maxOutputTokens(8000);

        assertNotNull(builder, "配置 maxOutputTokens 后 builder 不应为 null");
    }

    @Test
    void testMaxOutputTokensInvalid() {
        assertThrows(IllegalArgumentException.class, () -> {
            DuoAgent.builder().maxOutputTokens(-100);
        }, "maxOutputTokens < 0 应该抛出异常");
    }

    @Test
    void testEnableReasoning() {
        var builder = DuoAgent.builder()
                .enableReasoning(true);

        assertNotNull(builder, "启用推理后 builder 不应为 null");
    }

    @Test
    void testReasoningTimeout() {
        var builder = DuoAgent.builder()
                .reasoningTimeout(Duration.ofMinutes(10));

        assertNotNull(builder, "配置推理超时后 builder 不应为 null");
    }

    @Test
    void testTimeout() {
        var builder = DuoAgent.builder()
                .timeout(Duration.ofSeconds(120));

        assertNotNull(builder, "设置 timeout 后 builder 不应为 null");
    }

    @Test
    void testSystemPrompt() {
        var builder = DuoAgent.builder()
                .systemPrompt("你是专业的 Java 助手");

        assertNotNull(builder, "设置 systemPrompt 后 builder 不应为 null");
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
    void testBuildWithoutApiFormat() {
        var builder = DuoAgent.builder()
                .model("deepseek-chat")
                .apiKey("test-key")
                .baseUrl("https://api.example.com")
                .withFileTools();

        var exception = assertThrows(IllegalStateException.class, builder::build);
        assertTrue(exception.getMessage().contains("未配置 API 格式"),
                "错误消息应提示未配置 API 格式");
    }

    @Test
    void testBuildWithoutModel() {
        var builder = DuoAgent.builder()
                .apiFormat("openai")
                .apiKey("test-key")
                .baseUrl("https://api.example.com")
                .withFileTools();

        var exception = assertThrows(IllegalStateException.class, builder::build);
        assertTrue(exception.getMessage().contains("未配置模型名称"),
                "错误消息应提示未配置模型名称");
    }

    @Test
    void testBuildWithoutApiKey() {
        var builder = DuoAgent.builder()
                .apiFormat("openai")
                .model("deepseek-chat")
                .baseUrl("https://api.example.com")
                .withFileTools();

        var exception = assertThrows(IllegalStateException.class, builder::build);
        assertTrue(exception.getMessage().contains("未设置 API Key"),
                "错误消息应提示未设置 API Key");
    }

    @Test
    void testBuildWithoutBaseUrl() {
        var builder = DuoAgent.builder()
                .apiFormat("openai")
                .model("deepseek-chat")
                .apiKey("test-key")
                .withFileTools();

        var exception = assertThrows(IllegalStateException.class, builder::build);
        assertTrue(exception.getMessage().contains("未设置 API Base URL"),
                "错误消息应提示未设置 Base URL");
    }

    @Test
    void testBuildSuccess() {
        var agent = DuoAgent.builder()
                .apiFormat("openai")
                .model("deepseek-chat")
                .apiKey("test-api-key")
                .baseUrl("https://api.deepseek.com")
                .contextWindow(128000)
                .maxOutputTokens(4096)
                .withFileTools()
                .build();

        assertNotNull(agent, "构建的 agent 不应为 null");
        assertNotNull(agent.getAgent(), "底层 agent 不应为 null");
        assertNotNull(agent.getSession(), "session 不应为 null");
    }

    @Test
    void testBuildWithReasoning() {
        var agent = DuoAgent.builder()
                .apiFormat("openai")
                .model("deepseek-reasoner")
                .apiKey("test-api-key")
                .baseUrl("https://api.deepseek.com")
                .contextWindow(64000)
                .maxOutputTokens(8000)
                .enableReasoning(true)
                .reasoningTimeout(Duration.ofMinutes(5))
                .withCodeTools()
                .build();

        assertNotNull(agent, "构建推理 agent 不应为 null");
        assertNotNull(agent.getAgent(), "底层 agent 不应为 null");
        assertNotNull(agent.getSession(), "session 不应为 null");
    }

    @Test
    void testFluentChaining() {
        // 验证所有方法都返回 builder 自身，支持链式调用
        var builder = DuoAgent.builder()
                .apiFormat("openai")
                .model("deepseek-chat")
                .apiKey("test-key")
                .baseUrl("https://api.deepseek.com")
                .contextWindow(128000)
                .maxOutputTokens(6000)
                .timeout(Duration.ofSeconds(90))
                .systemPrompt("测试")
                .withFileTools()
                .withSearchTools()
                .tool(new EditTool().getDefinition());

        assertNotNull(builder, "链式调用后 builder 不应为 null");

        var agent = builder.build();
        assertNotNull(agent, "链式调用构建的 agent 不应为 null");
    }
}
