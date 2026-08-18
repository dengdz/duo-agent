package dev.dsh.api.llm;

import dev.dsh.model.llm.Message;
import dev.dsh.model.llm.MessageFactory;
import dev.dsh.model.llm.MessageSource;
import dev.dsh.model.llm.*;
import dev.dsh.core.llm.MockEchoAdapter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link LlmRuntime} 与 mock echo 适配器的测试。
 */
class LlmRuntimeTest {

    @Test
    void mockEchoShouldReturnTextReply() {
        var runtime = new LlmRuntime();
        runtime.registerAdapter("mock-echo", new MockEchoAdapter());

        var messages = List.<Message>of(
                MessageFactory.createUserMessage(
                        List.of(new ContentBlock.Text("hello")),
                        new MessageSource.User()
                )
        );
        var options = new GenerateOptions("mock-echo", "mock-model", messages);

        var chunks = new ArrayList<StreamChunk>();
        var completed = new AtomicBoolean(false);
        var error = new AtomicReference<Throwable>();

        runtime.stream(options, new StreamCallback() {
            @Override
            public void onChunk(StreamChunk chunk) {
                chunks.add(chunk);
            }

            @Override
            public void onComplete() {
                completed.set(true);
            }

            @Override
            public void onError(Throwable e) {
                error.set(e);
            }
        });

        assertNull(error.get());
        assertTrue(completed.get());

        var textBlocks = chunks.stream()
                .filter(c -> c instanceof StreamChunk.TextDelta)
                .map(StreamChunk.TextDelta.class::cast)
                .toList();
        assertFalse(textBlocks.isEmpty());

        var finishChunks = chunks.stream()
                .filter(c -> c instanceof StreamChunk.Finish)
                .map(StreamChunk.Finish.class::cast)
                .toList();
        assertEquals(1, finishChunks.size());
        assertInstanceOf(FinishReason.Stop.class, finishChunks.getFirst().reason());
    }

    @Test
    void mockEchoShouldTriggerToolCall() {
        var runtime = new LlmRuntime();
        runtime.registerAdapter("mock-echo", new MockEchoAdapter());

        var messages = List.<Message>of(
                MessageFactory.createUserMessage(
                        List.of(new ContentBlock.Text("echo hello world")),
                        new MessageSource.User()
                )
        );
        var options = new GenerateOptions("mock-echo", "mock-model", messages);

        var chunks = new ArrayList<StreamChunk>();
        var completed = new AtomicBoolean(false);
        var error = new AtomicReference<Throwable>();

        runtime.stream(options, new StreamCallback() {
            @Override
            public void onChunk(StreamChunk chunk) {
                chunks.add(chunk);
            }

            @Override
            public void onComplete() {
                completed.set(true);
            }

            @Override
            public void onError(Throwable e) {
                error.set(e);
            }
        });

        assertNull(error.get());
        assertTrue(completed.get());

        var toolCallDeltas = chunks.stream()
                .filter(c -> c instanceof StreamChunk.ToolCallDelta)
                .map(StreamChunk.ToolCallDelta.class::cast)
                .toList();
        assertFalse(toolCallDeltas.isEmpty(), "期望有工具调用 delta");
        assertEquals("echo", toolCallDeltas.getFirst().name());

        var finishChunks = chunks.stream()
                .filter(c -> c instanceof StreamChunk.Finish)
                .map(StreamChunk.Finish.class::cast)
                .toList();
        assertEquals(1, finishChunks.size());
        assertInstanceOf(FinishReason.ToolCalls.class, finishChunks.getFirst().reason());
    }

    @Test
    void shouldThrowOnUnknownProvider() {
        var runtime = new LlmRuntime();
        var options = new GenerateOptions("unknown-provider", "model", List.of());

        assertThrows(IllegalArgumentException.class, () -> {
            runtime.stream(options, new StreamCallback() {
                @Override
                public void onChunk(StreamChunk chunk) {}
                @Override
                public void onComplete() {}
                @Override
                public void onError(Throwable e) {}
            });
        });
    }
}