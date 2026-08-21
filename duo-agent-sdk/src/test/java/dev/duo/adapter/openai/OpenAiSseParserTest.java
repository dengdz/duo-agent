package dev.duo.adapter.openai;

import dev.duo.api.llm.StreamCallback;
import dev.duo.model.llm.ContentBlock;
import dev.duo.model.llm.FinishReason;
import dev.duo.model.llm.StreamChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OpenAiSseParser} 的 Chat Completions 协议解析测试。
 * <p>
 * 泛化自 DeepSeek 适配器的解析测试（协议层与厂商无关），并新增
 * 流式思考字段（{@code reasoning_content} 协议变体）的解析覆盖。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-21
 */
class OpenAiSseParserTest {

    @Test
    void shouldAssembleToolCallFromDeltas() {
        var parser = OpenAiSseParser.create(null);
        var chunks = new ArrayList<StreamChunk>();

        // 模拟流式工具调用：先发送 id/name，再分片发送 arguments
        var chunk1 = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_123\","
                + "\"type\":\"function\",\"function\":{\"name\":\"todo_write\",\"arguments\":\"\"}}]},"
                + "\"finish_reason\":null}]}";
        var chunk2 = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":"
                + "{\"arguments\":\"{\"}}]},\"finish_reason\":null}]}";
        var chunk3 = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":"
                + "{\"arguments\":\"\\\"todos\\\"\"}}]},\"finish_reason\":null}]}";
        var chunk4 = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":"
                + "{\"arguments\":\":[{\\\"content\\\":\\\"买牛奶\\\"}]}\"}}]},\"finish_reason\":null}]}";
        var chunk5 = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}";

        for (var line : List.of(chunk1, chunk2, chunk3, chunk4, chunk5)) {
            parser.parseLine(line, collectingCallback(chunks));
        }

        // 验证 BlockStart
        var blockStarts = chunks.stream().filter(c -> c instanceof StreamChunk.BlockStart).toList();
        assertEquals(1, blockStarts.size(), "应只发出 1 个 BlockStart");
        assertEquals("tool-call", ((StreamChunk.BlockStart) blockStarts.getFirst()).blockType());

        // 验证 ToolCallDelta：包含 id、name 和累加的参数
        var toolCallDeltas = chunks.stream()
                .filter(c -> c instanceof StreamChunk.ToolCallDelta)
                .map(StreamChunk.ToolCallDelta.class::cast)
                .toList();
        assertFalse(toolCallDeltas.isEmpty(), "应发出 ToolCallDelta");
        var lastDelta = toolCallDeltas.getLast();
        assertEquals("call_123", lastDelta.id().value());
        assertEquals("todo_write", lastDelta.name());
        assertEquals("{\"todos\":[{\"content\":\"买牛奶\"}]}", lastDelta.argumentsDelta());

        // 验证 BlockEnd 携带完整 ToolCall 块
        var blockEnds = chunks.stream()
                .filter(c -> c instanceof StreamChunk.BlockEnd)
                .map(StreamChunk.BlockEnd.class::cast)
                .toList();
        assertEquals(1, blockEnds.size(), "应发出 1 个 BlockEnd");
        var endBlock = blockEnds.getFirst().block();
        assertTrue(endBlock instanceof ContentBlock.ToolCall, "BlockEnd 应为 ToolCall");
        var toolCall = (ContentBlock.ToolCall) endBlock;
        assertEquals("call_123", toolCall.id().value());
        assertEquals("todo_write", toolCall.name());
        assertEquals("{\"todos\":[{\"content\":\"买牛奶\"}]}", toolCall.arguments());

        // 验证 Finish 原因为 tool_calls
        var finishes = chunks.stream().filter(c -> c instanceof StreamChunk.Finish).toList();
        assertEquals(1, finishes.size());
        assertTrue(((StreamChunk.Finish) finishes.getFirst()).reason() instanceof FinishReason.ToolCalls);
    }

    @Test
    void shouldAssembleTextFromDeltas() {
        var parser = OpenAiSseParser.create(null);
        var chunks = new ArrayList<StreamChunk>();
        var callback = collectingCallback(chunks);

        parser.parseLine(
                "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"},\"finish_reason\":null}]}", callback);
        parser.parseLine(
                "data: {\"choices\":[{\"delta\":{\"content\":\"世界\"},\"finish_reason\":null}]}", callback);
        parser.parseLine("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}", callback);

        assertTrue(chunks.getFirst() instanceof StreamChunk.BlockStart);
        assertEquals("text", ((StreamChunk.BlockStart) chunks.getFirst()).blockType());

        var texts = chunks.stream()
                .filter(c -> c instanceof StreamChunk.TextDelta)
                .map(StreamChunk.TextDelta.class::cast)
                .map(StreamChunk.TextDelta::text)
                .toList();
        assertEquals(List.of("你好", "世界"), texts);

        var ends = chunks.stream().filter(c -> c instanceof StreamChunk.BlockEnd).toList();
        assertEquals(1, ends.size());
        assertEquals("你好世界", ((ContentBlock.Text) ((StreamChunk.BlockEnd) ends.getFirst()).block()).text());

        var finishes = chunks.stream().filter(c -> c instanceof StreamChunk.Finish).toList();
        assertEquals(1, finishes.size());
        assertTrue(((StreamChunk.Finish) finishes.getFirst()).reason() instanceof FinishReason.Stop);
    }

    @Test
    void shouldEmitReasoningDeltasWhenFieldConfigured() {
        // DeepSeek 系协议变体：思考经 reasoning_content 字段透出，思考在前、回答在后
        var parser = OpenAiSseParser.create("reasoning_content");
        var chunks = new ArrayList<StreamChunk>();
        var callback = collectingCallback(chunks);

        parser.parseLine("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"先想\"},\"finish_reason\":null}]}",
                callback);
        parser.parseLine("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"一想\"},\"finish_reason\":null}]}",
                callback);
        parser.parseLine("data: {\"choices\":[{\"delta\":{\"content\":\"答案\"},\"finish_reason\":null}]}",
                callback);
        parser.parseLine("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}", callback);

        var reasoning = chunks.stream()
                .filter(c -> c instanceof StreamChunk.ReasoningDelta)
                .map(StreamChunk.ReasoningDelta.class::cast)
                .map(StreamChunk.ReasoningDelta::text)
                .toList();
        assertEquals(List.of("先想", "一想"), reasoning, "应透出思考增量");

        // 思考块在首个文本 delta 前关闭，携带完整思考内容
        var blockEnds = chunks.stream()
                .filter(c -> c instanceof StreamChunk.BlockEnd)
                .map(StreamChunk.BlockEnd.class::cast)
                .toList();
        assertEquals(2, blockEnds.size(), "思考块与文本块各一个 BlockEnd");
        assertTrue(blockEnds.getFirst().block() instanceof ContentBlock.Reasoning, "首个 BlockEnd 应为思考块");
        assertEquals("先想一想", ((ContentBlock.Reasoning) blockEnds.getFirst().block()).text());
        assertEquals("答案", ((ContentBlock.Text) blockEnds.getLast().block()).text());
    }

    @Test
    void shouldIgnoreReasoningFieldWhenNotConfigured() {
        // 标准端点（无协议变体字段）：reasoning_content 即使出现也不解析
        var parser = OpenAiSseParser.create(null);
        var chunks = new ArrayList<StreamChunk>();
        var callback = collectingCallback(chunks);

        parser.parseLine("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"思考\"},\"finish_reason\":null}]}",
                callback);
        parser.parseLine("data: {\"choices\":[{\"delta\":{\"content\":\"回答\"},\"finish_reason\":null}]}",
                callback);
        parser.parseLine("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}", callback);

        assertTrue(chunks.stream().noneMatch(c -> c instanceof StreamChunk.ReasoningDelta),
                "未配置思考字段时不应产生 ReasoningDelta");
    }

    @Test
    void shouldEmitUsageBeforeFinishWhenBothInSameChunk() {
        // StreamChunk 契约：usage 在终结 finish 之前发送。
        // DeepSeek 等端点的 usage 与 finish_reason 常在同一条 data 中，
        // 解析顺序必须先 usage 后 finish
        var parser = OpenAiSseParser.create(null);
        var chunks = new ArrayList<StreamChunk>();
        var callback = collectingCallback(chunks);

        parser.parseLine("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":11}}", callback);

        var usageIdx = -1;
        var finishIdx = -1;
        for (int i = 0; i < chunks.size(); i++) {
            if (chunks.get(i) instanceof StreamChunk.Usage) {
                usageIdx = i;
            }
            if (chunks.get(i) instanceof StreamChunk.Finish) {
                finishIdx = i;
            }
        }
        assertEquals(true, usageIdx >= 0 && finishIdx >= 0 && usageIdx < finishIdx,
                "usage 必须先于 finish（实际位置 usage=" + usageIdx + ", finish=" + finishIdx + "）");
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
