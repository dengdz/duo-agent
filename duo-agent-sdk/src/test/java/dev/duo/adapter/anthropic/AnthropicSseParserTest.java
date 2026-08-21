package dev.duo.adapter.anthropic;

import dev.duo.api.llm.StreamCallback;
import dev.duo.model.llm.ContentBlock;
import dev.duo.model.llm.FinishReason;
import dev.duo.model.llm.StreamChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AnthropicSseParser} 与 {@link AnthropicRequestBuilder} 的协议级测试。
 * <p>
 * 事件样本取自 Anthropic Messages 官方流式协议结构（智谱兼容端点同构）。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-21
 */
class AnthropicSseParserTest {

    @Test
    void shouldParseFullEventSequenceWithThinkingAndToolUse() {
        var parser = new AnthropicSseParser();
        var chunks = new ArrayList<StreamChunk>();
        var callback = collectingCallback(chunks);

        // 完整事件序：message_start → thinking 块 → text 块 → tool_use 块 → 收尾
        parser.parseLine("data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"role\":\"assistant\","
                + "\"content\":[],\"usage\":{\"input_tokens\":25,\"output_tokens\":1}}}", callback);
        parser.parseLine("data: {\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}", callback);
        parser.parseLine("data: {\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"先分析问题\"}}", callback);
        parser.parseLine("data: {\"type\":\"content_block_stop\",\"index\":0}", callback);
        parser.parseLine("data: {\"type\":\"content_block_start\",\"index\":1,"
                + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}", callback);
        parser.parseLine("data: {\"type\":\"content_block_delta\",\"index\":1,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"需要查目录\"}}", callback);
        parser.parseLine("data: {\"type\":\"content_block_stop\",\"index\":1}", callback);
        parser.parseLine("data: {\"type\":\"content_block_start\",\"index\":2,"
                + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_abc\",\"name\":\"glob\",\"input\":{}}}", callback);
        parser.parseLine("data: {\"type\":\"content_block_delta\",\"index\":2,"
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"pattern\\\":\\\"*.java\\\"}\"}}", callback);
        parser.parseLine("data: {\"type\":\"content_block_stop\",\"index\":2}", callback);
        parser.parseLine("data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},"
                + "\"usage\":{\"output_tokens\":120}}", callback);

        // 思考块：BlockStart(reasoning) + ReasoningDelta + BlockEnd(Reasoning)
        assertEquals("reasoning", blockStartType(chunks, 0), "思考块应以 reasoning BlockStart 开启");
        var reasoning = chunks.stream()
                .filter(c -> c instanceof StreamChunk.ReasoningDelta)
                .map(StreamChunk.ReasoningDelta.class::cast)
                .toList();
        assertEquals(1, reasoning.size());
        assertEquals("先分析问题", reasoning.getFirst().text());
        var reasoningEnd = blockEnd(chunks, 0);
        assertTrue(reasoningEnd instanceof ContentBlock.Reasoning, "思考块 BlockEnd 应为 Reasoning 载荷");
        assertEquals("先分析问题", ((ContentBlock.Reasoning) reasoningEnd).text());

        // 文本块
        var text = chunks.stream()
                .filter(c -> c instanceof StreamChunk.TextDelta)
                .map(StreamChunk.TextDelta.class::cast)
                .toList();
        assertEquals(List.of("需要查目录"), text.stream().map(StreamChunk.TextDelta::text).toList());

        // 工具块：BlockStart 时即有 id/name 的首帧 ToolCallDelta，随后参数增量，BlockEnd 组装完整调用
        var toolDeltas = chunks.stream()
                .filter(c -> c instanceof StreamChunk.ToolCallDelta)
                .map(StreamChunk.ToolCallDelta.class::cast)
                .toList();
        assertEquals(2, toolDeltas.size(), "block_start 首帧 + input_json_delta 各一帧");
        assertEquals("toolu_abc", toolDeltas.getFirst().id().value());
        assertEquals("glob", toolDeltas.getFirst().name());
        var toolEnd = blockEnd(chunks, 2);
        assertTrue(toolEnd instanceof ContentBlock.ToolCall, "工具块 BlockEnd 应为 ToolCall 载荷");
        var toolCall = (ContentBlock.ToolCall) toolEnd;
        assertEquals("toolu_abc", toolCall.id().value());
        assertEquals("glob", toolCall.name());
        assertEquals("{\"pattern\":\"*.java\"}", toolCall.arguments());

        // usage（message_start 输入 + message_delta 输出合成）与 finish
        var usage = chunks.stream().filter(c -> c instanceof StreamChunk.Usage).findFirst().orElseThrow();
        assertEquals(25, ((StreamChunk.Usage) usage).usage().inputTokens());
        assertEquals(120, ((StreamChunk.Usage) usage).usage().outputTokens());
        var finish = chunks.stream().filter(c -> c instanceof StreamChunk.Finish).findFirst().orElseThrow();
        assertTrue(((StreamChunk.Finish) finish).reason() instanceof FinishReason.ToolCalls,
                "stop_reason=tool_use 应映射 ToolCalls");
    }

    @Test
    void shouldMapEndTurnAndMaxTokensStopReasons() {
        var parser = new AnthropicSseParser();
        var chunks = new ArrayList<StreamChunk>();
        var callback = collectingCallback(chunks);

        parser.parseLine("data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":5}}}", callback);
        parser.parseLine("data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
                + "\"usage\":{\"output_tokens\":9}}", callback);
        parser.onStreamComplete(callback);

        var finish = chunks.stream().filter(c -> c instanceof StreamChunk.Finish).findFirst().orElseThrow();
        assertTrue(((StreamChunk.Finish) finish).reason() instanceof FinishReason.Stop,
                "end_turn 应映射 Stop");

        // max_tokens 映射
        var parser2 = new AnthropicSseParser();
        var chunks2 = new ArrayList<StreamChunk>();
        parser2.parseLine("data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"}}",
                collectingCallback(chunks2));
        var finish2 = chunks2.stream().filter(c -> c instanceof StreamChunk.Finish).findFirst().orElseThrow();
        assertTrue(((StreamChunk.Finish) finish2).reason() instanceof FinishReason.MaxTokens,
                "max_tokens 应映射 MaxTokens");
    }

    @Test
    void shouldReportStreamErrorEvent() {
        var parser = new AnthropicSseParser();
        var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        parser.parseLine("data: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}",
                new StreamCallback() {
                    @Override
                    public void onChunk(StreamChunk chunk) {
                    }

                    @Override
                    public void onComplete() {
                    }

                    @Override
                    public void onError(Throwable t) {
                        error.set(t);
                    }
                });
        assertEquals("Overloaded", error.get().getMessage(), "error 事件应转译为 onError");
    }

    @Test
    void shouldBuildRequestWithTopLevelSystemAndFlatTools() {
        var message = dev.duo.model.llm.MessageFactory.createUserMessage(
                List.of(new ContentBlock.Text("列出文件")), new dev.duo.model.llm.MessageSource.User());
        var options = new dev.duo.model.llm.GenerateOptions(
                "anthropic", "glm-4.6", List.of(message),
                "你是文件助手",
                List.of(new dev.duo.model.llm.ToolSchema("glob", "查找文件",
                        java.util.Map.of("type", "object"))),
                0.7, null, null, null, false);

        var json = AnthropicRequestBuilder.buildRequest(options, 8192, null);

        assertTrue(json.contains("\"system\": \"你是文件助手\""), "system 应为顶层参数");
        assertTrue(json.contains("\"max_tokens\": 8192"), "max_tokens 必填由兜底填充");
        assertTrue(json.contains("\"input_schema\":"), "工具 schema 字段名应为 input_schema（平铺格式）");
        assertTrue(!json.contains("\"function\":"), "不应出现 Chat Completions 的嵌套 function 结构");
        assertTrue(json.contains("\"temperature\": 0.7"));
        assertTrue(!json.contains("\"thinking\":"), "未启用思考时不应有 thinking 参数");
    }

    @Test
    void shouldBuildRequestWithThinkingAndSuppressTemperature() {
        var message = dev.duo.model.llm.MessageFactory.createUserMessage(
                List.of(new ContentBlock.Text("深度分析")), new dev.duo.model.llm.MessageSource.User());
        var options = new dev.duo.model.llm.GenerateOptions(
                "anthropic", "glm-4.6", List.of(message),
                null, null, 0.7, null, null, null, true);

        var json = AnthropicRequestBuilder.buildRequest(options, null, 2048L);

        assertTrue(json.contains("\"thinking\": {\"type\": \"enabled\", \"budget_tokens\": 2048}"),
                "启用思考时应携带预算参数");
        assertTrue(!json.contains("\"temperature\""), "思考模式下不发送 temperature（协议要求固定）");
    }

    private String blockStartType(List<StreamChunk> chunks, int index) {
        return chunks.stream()
                .filter(c -> c instanceof StreamChunk.BlockStart bs && bs.index() == index)
                .map(c -> (StreamChunk.BlockStart) c)
                .findFirst().orElseThrow().blockType();
    }

    private ContentBlock blockEnd(List<StreamChunk> chunks, int index) {
        return chunks.stream()
                .filter(c -> c instanceof StreamChunk.BlockEnd be && be.index() == index)
                .map(c -> (StreamChunk.BlockEnd) c)
                .findFirst().orElseThrow().block();
    }

    @Test
    void shouldParseDataLinesWithoutSpaceAfterColon() {
        // SSE 规范允许 data: 后无空格——部分网关/中转站会重写成无空格式，
        // 解析必须两种写法都认（否则所有数据行被静默忽略）
        var parser = new AnthropicSseParser();
        var chunks = new ArrayList<StreamChunk>();
        var callback = collectingCallback(chunks);

        parser.parseLine("data:{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":3}}}", callback);
        parser.parseLine("data:{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}", callback);
        parser.parseLine("data:{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"无空格\"}}", callback);
        parser.parseLine("data:{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
                + "\"usage\":{\"output_tokens\":5}}", callback);

        var text = chunks.stream()
                .filter(c -> c instanceof StreamChunk.TextDelta)
                .map(StreamChunk.TextDelta.class::cast)
                .toList();
        assertEquals(List.of("无空格"), text.stream().map(StreamChunk.TextDelta::text).toList(),
                "data: 无空格写法的数据行不应被忽略");
        var usage = chunks.stream().filter(c -> c instanceof StreamChunk.Usage).findFirst().orElseThrow();
        assertEquals(3, ((StreamChunk.Usage) usage).usage().inputTokens());
    }

    @Test
    void shouldRelyOnEventLineWhenDataFieldsReordered() {
        // 字段重排的网关（如部分中转站）把 delta 放在事件 type 之前：
        // data 内按首次出现提取 type 会误匹配嵌套对象（"thinking_delta" 而非
        // "content_block_delta"）——event: 行是 SSE 标准的权威事件名来源
        var parser = new AnthropicSseParser();
        var chunks = new ArrayList<StreamChunk>();
        var callback = collectingCallback(chunks);

        parser.parseLine("event:message_start", callback);
        parser.parseLine("data:{\"message\":{\"usage\":{\"input_tokens\":7}},\"type\":\"message_start\"}", callback);
        parser.parseLine("", callback);
        parser.parseLine("event:content_block_delta", callback);
        parser.parseLine("data:{\"delta\":{\"type\":\"text_delta\",\"text\":\"重排\"},\"type\":\"content_block_delta\",\"index\":0}", callback);
        parser.parseLine("", callback);
        parser.parseLine("event:message_delta", callback);
        parser.parseLine("data:{\"delta\":{\"stop_reason\":\"end_turn\"},\"type\":\"message_delta\",\"usage\":{\"output_tokens\":9}}", callback);

        var text = chunks.stream()
                .filter(c -> c instanceof StreamChunk.TextDelta)
                .map(StreamChunk.TextDelta.class::cast)
                .toList();
        assertEquals(List.of("重排"), text.stream().map(StreamChunk.TextDelta::text).toList(),
                "event 行驱动的事件判别不受 data 字段顺序影响");
        var usage = chunks.stream().filter(c -> c instanceof StreamChunk.Usage).findFirst().orElseThrow();
        assertEquals(7, ((StreamChunk.Usage) usage).usage().inputTokens(),
                "message_start 事件的 usage 不应因字段重排被跳过");
        assertEquals(true, chunks.stream().anyMatch(c -> c instanceof StreamChunk.Finish),
                "message_delta 的 stop_reason 应正常产出 Finish");
    }

    private StreamCallback collectingCallback(List<StreamChunk> chunks) {
        return new StreamCallback() {
            @Override
            public void onChunk(StreamChunk chunk) {
                chunks.add(chunk);
            }

            @Override
            public void onComplete() {
            }

            @Override
            public void onError(Throwable err) {
                throw new AssertionError("不应报错: " + err.getMessage(), err);
            }
        };
    }
}
