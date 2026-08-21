package dev.duo.adapter.openai;

import dev.duo.api.llm.StreamCallback;
import dev.duo.model.llm.ContentBlock;
import dev.duo.model.llm.FinishReason;
import dev.duo.model.llm.StreamChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResponsesSseParser} 与 {@link ResponsesRequestBuilder} 的协议级测试。
 * <p>
 * 事件样本取自 OpenAI Responses 官方流式事件结构。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-21
 */
class ResponsesSseParserTest {

    @Test
    void shouldParseFullEventSequenceWithReasoningAndFunctionCall() {
        var parser = new ResponsesSseParser();
        var chunks = new ArrayList<StreamChunk>();
        var callback = collectingCallback(chunks);

        parser.parseLine("data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\"}}", callback);
        parser.parseLine("data: {\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\"}}", callback);
        parser.parseLine("data: {\"type\":\"response.reasoning_summary_text.delta\",\"output_index\":0,"
                + "\"delta\":\"先分解任务\"}}", callback);
        parser.parseLine("data: {\"type\":\"response.output_item.done\",\"output_index\":0,"
                + "\"item\":{\"type\":\"reasoning\"}}", callback);
        parser.parseLine("data: {\"type\":\"response.output_item.added\",\"output_index\":1,"
                + "\"item\":{\"type\":\"message\",\"role\":\"assistant\"}}", callback);
        parser.parseLine("data: {\"type\":\"response.output_text.delta\",\"output_index\":1,"
                + "\"delta\":\"查一下\"}}", callback);
        parser.parseLine("data: {\"type\":\"response.output_item.done\",\"output_index\":1,"
                + "\"item\":{\"type\":\"message\"}}", callback);
        parser.parseLine("data: {\"type\":\"response.output_item.added\",\"output_index\":2,"
                + "\"item\":{\"type\":\"function_call\",\"call_id\":\"call_9\",\"name\":\"glob\",\"arguments\":\"\"}}", callback);
        parser.parseLine("data: {\"type\":\"response.function_call_arguments.delta\",\"output_index\":2,"
                + "\"delta\":\"{\\\"pattern\\\":\\\"*.md\\\"}\"}}", callback);
        parser.parseLine("data: {\"type\":\"response.output_item.done\",\"output_index\":2,"
                + "\"item\":{\"type\":\"function_call\"}}", callback);
        parser.parseLine("data: {\"type\":\"response.completed\",\"response\":{"
                + "\"usage\":{\"input_tokens\":40,\"output_tokens\":88}}}}", callback);

        // 思考摘要 → ReasoningDelta + Reasoning 载荷 BlockEnd
        var reasoning = chunks.stream()
                .filter(c -> c instanceof StreamChunk.ReasoningDelta)
                .map(StreamChunk.ReasoningDelta.class::cast)
                .toList();
        assertEquals(List.of("先分解任务"), reasoning.stream().map(StreamChunk.ReasoningDelta::text).toList());

        // 文本
        var text = chunks.stream()
                .filter(c -> c instanceof StreamChunk.TextDelta)
                .map(StreamChunk.TextDelta.class::cast)
                .toList();
        assertEquals(List.of("查一下"), text.stream().map(StreamChunk.TextDelta::text).toList());

        // 工具调用：added 首帧（call_id/name）+ 参数增量 + BlockEnd 组装
        var toolDeltas = chunks.stream()
                .filter(c -> c instanceof StreamChunk.ToolCallDelta)
                .map(StreamChunk.ToolCallDelta.class::cast)
                .toList();
        assertEquals(2, toolDeltas.size());
        assertEquals("call_9", toolDeltas.getFirst().id().value());
        assertEquals("glob", toolDeltas.getFirst().name());
        var toolEnd = blockEnd(chunks, 2);
        assertTrue(toolEnd instanceof ContentBlock.ToolCall);
        var toolCall = (ContentBlock.ToolCall) toolEnd;
        assertEquals("{\"pattern\":\"*.md\"}", toolCall.arguments());

        // completed：usage + Finish(Stop)
        var usage = chunks.stream().filter(c -> c instanceof StreamChunk.Usage).findFirst().orElseThrow();
        assertEquals(40, ((StreamChunk.Usage) usage).usage().inputTokens());
        assertEquals(88, ((StreamChunk.Usage) usage).usage().outputTokens());
        var finish = chunks.stream().filter(c -> c instanceof StreamChunk.Finish).findFirst().orElseThrow();
        assertTrue(((StreamChunk.Finish) finish).reason() instanceof FinishReason.Stop);
    }

    @Test
    void shouldMapIncompleteAndFailed() {
        var parser = new ResponsesSseParser();
        var chunks = new ArrayList<StreamChunk>();
        parser.parseLine("data: {\"type\":\"response.incomplete\",\"response\":"
                + "{\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}}}",
                collectingCallback(chunks));
        var finish = chunks.stream().filter(c -> c instanceof StreamChunk.Finish).findFirst().orElseThrow();
        assertTrue(((StreamChunk.Finish) finish).reason() instanceof FinishReason.MaxTokens,
                "max_output_tokens 截断应映射 MaxTokens");

        var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        new ResponsesSseParser().parseLine("data: {\"type\":\"response.failed\",\"response\":"
                        + "{\"error\":{\"message\":\"server error\"}}}}",
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
        assertEquals("server error", error.get().getMessage(), "failed 事件应转译为 onError");
    }

    @Test
    void shouldBuildRequestWithInstructionsAndFlatTools() {
        var message = dev.duo.model.llm.MessageFactory.createUserMessage(
                List.of(new ContentBlock.Text("列出文件")), new dev.duo.model.llm.MessageSource.User());
        var options = new dev.duo.model.llm.GenerateOptions(
                "responses", "gpt-5.2", List.of(message),
                "你是文件助手",
                List.of(new dev.duo.model.llm.ToolSchema("glob", "查找文件",
                        java.util.Map.of("type", "object"))),
                0.7, 2048, null, null, true);

        var json = ResponsesRequestBuilder.buildRequest(options, "high");

        assertTrue(json.contains("\"instructions\": \"你是文件助手\""), "系统提示应为顶层 instructions");
        assertTrue(json.contains("\"input\": ["), "输入应为 input items");
        assertTrue(json.contains("\"parameters\":"), "工具 schema 字段名应为 parameters（平铺）");
        assertTrue(json.contains("\"max_output_tokens\": 2048"), "maxTokens 应映射 max_output_tokens");
        assertTrue(json.contains("\"reasoning\": {\"effort\": \"high\", \"summary\": \"auto\"}"),
                "推理应映射 reasoning.effort 且显式请求 summary（默认不透出思考）");
        assertTrue(!json.contains("\"messages\":"), "不应出现 Chat Completions 的 messages 结构");
    }

    private ContentBlock blockEnd(List<StreamChunk> chunks, int index) {
        return chunks.stream()
                .filter(c -> c instanceof StreamChunk.BlockEnd be && be.index() == index)
                .map(c -> (StreamChunk.BlockEnd) c)
                .findFirst().orElseThrow().block();
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
