package dev.dsh.core.llm;

import dev.dsh.model.llm.ContentBlock;
import dev.dsh.model.llm.FinishReason;
import dev.dsh.model.llm.StreamChunk;
import dev.dsh.model.llm.TokenUsage;
import dev.dsh.util.CallId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BlockAssembler} 的测试。
 * <p>
 * 覆盖：文本流、工具调用流、用量、结束原因、max-tokens 截断。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
class BlockAssemblerTest {

    @Test
    void shouldAssembleTextBlocks() {
        var assembler = new BlockAssembler();

        assembler.push(new StreamChunk.BlockStart(0, "text"));
        assembler.push(new StreamChunk.TextDelta(0, "Hello"));
        assembler.push(new StreamChunk.TextDelta(0, ", world"));
        assembler.push(new StreamChunk.BlockEnd(0, new ContentBlock.Text("Hello, world")));
        assembler.push(new StreamChunk.Usage(new TokenUsage(10, 5)));
        assembler.push(new StreamChunk.Finish(new FinishReason.Stop()));

        var blocks = assembler.blocks();
        assertEquals(1, blocks.size());
        assertEquals("Hello, world", ((ContentBlock.Text) blocks.getFirst()).text());

        assertTrue(assembler.usage().isPresent());
        assertEquals(10, assembler.usage().get().inputTokens());
        assertEquals(5, assembler.usage().get().outputTokens());

        assertInstanceOf(FinishReason.Stop.class, assembler.finish());
    }

    @Test
    void shouldAssembleToolCallBlocks() {
        var assembler = new BlockAssembler();
        var callId = new CallId("call-echo");

        assembler.push(new StreamChunk.BlockStart(0, "text"));
        assembler.push(new StreamChunk.TextDelta(0, "Let me echo that."));
        assembler.push(new StreamChunk.BlockEnd(0, new ContentBlock.Text("Let me echo that.")));

        assembler.push(new StreamChunk.BlockStart(1, "tool-call"));
        assembler.push(new StreamChunk.ToolCallDelta(1, callId, "echo", "{\"text\": \"hello\"}"));
        assembler.push(new StreamChunk.BlockEnd(1, new ContentBlock.ToolCall(callId, "echo", "{\"text\": \"hello\"}")));

        assembler.push(new StreamChunk.Usage(new TokenUsage(20, 10)));
        assembler.push(new StreamChunk.Finish(new FinishReason.ToolCalls()));

        var blocks = assembler.blocks();
        assertEquals(2, blocks.size());

        assertInstanceOf(ContentBlock.Text.class, blocks.get(0));
        assertInstanceOf(ContentBlock.ToolCall.class, blocks.get(1));

        var toolCall = (ContentBlock.ToolCall) blocks.get(1);
        assertEquals(callId, toolCall.id());
        assertEquals("echo", toolCall.name());
        assertEquals("{\"text\": \"hello\"}", toolCall.arguments());

        assertInstanceOf(FinishReason.ToolCalls.class, assembler.finish());
    }

    @Test
    void shouldDropToolCallsOnMaxTokens() {
        var assembler = new BlockAssembler();

        assembler.push(new StreamChunk.BlockStart(0, "text"));
        assembler.push(new StreamChunk.TextDelta(0, "Partial response"));
        assembler.push(new StreamChunk.BlockEnd(0, new ContentBlock.Text("Partial response")));

        assembler.push(new StreamChunk.BlockStart(1, "tool-call"));
        // 工具调用开始但未完成——max-tokens 截断会丢弃它
        assembler.push(new StreamChunk.ToolCallDelta(1, new CallId("call-incomplete"), "echo", "{\"text\": \""));
        assembler.push(new StreamChunk.BlockEnd(1, new ContentBlock.ToolCall(
                new CallId("call-incomplete"), "echo", "{\"text\": \""
        )));

        assembler.push(new StreamChunk.Finish(new FinishReason.MaxTokens()));

        var blocks = assembler.blocks();
        assertEquals(1, blocks.size());
        assertInstanceOf(ContentBlock.Text.class, blocks.getFirst());
        assertInstanceOf(FinishReason.MaxTokens.class, assembler.finish());
    }

    @Test
    void shouldHandleDeltaOnlyProtocol() {
        var assembler = new BlockAssembler();

        // 没有 block-start/end，直接是 delta（畸形但容忍）
        assembler.push(new StreamChunk.TextDelta(0, "Hello"));
        assembler.push(new StreamChunk.TextDelta(0, " world"));
        assembler.push(new StreamChunk.Finish(new FinishReason.Stop()));

        var blocks = assembler.blocks();
        assertEquals(1, blocks.size());
        assertEquals("Hello world", ((ContentBlock.Text) blocks.getFirst()).text());
    }

    @Test
    void shouldIgnoreStragglersAfterBlockEnd() {
        var assembler = new BlockAssembler();

        assembler.push(new StreamChunk.BlockStart(0, "text"));
        assembler.push(new StreamChunk.TextDelta(0, "Done"));
        assembler.push(new StreamChunk.BlockEnd(0, new ContentBlock.Text("Done")));
        // block-end 后的掉队 delta——必须被忽略
        assembler.push(new StreamChunk.TextDelta(0, "extra"));

        assembler.push(new StreamChunk.Finish(new FinishReason.Stop()));

        var blocks = assembler.blocks();
        assertEquals(1, blocks.size());
        assertEquals("Done", ((ContentBlock.Text) blocks.getFirst()).text());
    }

    @Test
    void shouldDefaultToStopReason() {
        var assembler = new BlockAssembler();
        // 未发出 finish chunk
        assertInstanceOf(FinishReason.Stop.class, assembler.finish());
    }

    @Test
    void shouldDefaultToEmptyUsage() {
        var assembler = new BlockAssembler();
        assertTrue(assembler.usage().isEmpty());
    }
}