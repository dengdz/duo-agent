package dev.dsh.llm.runtime;

import dev.dsh.llm.message.Message;
import dev.dsh.llm.message.MessageFactory;
import dev.dsh.llm.message.MessageSource;
import dev.dsh.llm.types.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link LlmRuntime} 与 mock echo 适配器的测试。
 * <p>
 * 对应原版 echo-agent 示例中的 {@code mock-echo} 适配器。
 * </p>
 */
class LlmRuntimeTest {

    /**
     * 用于测试完整流式流水线的 mock echo 适配器。
     * <p>
     * 行为：如果最后一条用户文本以 "echo " 开头，则调用 echo 工具
     * （演练工具往返）；否则流式返回预设回复。
     * </p>
     */
    static class MockEchoAdapter extends LlmAdapter {
        @Override
        public void stream(GenerateOptions options, StreamCallback callback) {
            try {
                // 查找最后一条用户文本
                var lastUserText = "";
                for (int i = options.messages().size() - 1; i >= 0; i--) {
                    var msg = options.messages().get(i);
                    if (msg instanceof Message.UserMessage userMsg) {
                        for (var block : userMsg.content()) {
                            if (block instanceof ContentBlock.Text text) {
                                lastUserText = text.text();
                                break;
                            }
                        }
                        break;
                    }
                }

                // 检查最后一条消息是否有工具结果
                var hasToolResult = false;
                if (!options.messages().isEmpty()) {
                    var lastMsg = options.messages().getLast();
                    for (var block : lastMsg.content()) {
                        if (block instanceof ContentBlock.ToolResult) {
                            hasToolResult = true;
                            break;
                        }
                    }
                }

                if (lastUserText.startsWith("echo ") && !hasToolResult) {
                    // 模拟工具调用
                    var payload = lastUserText.substring(5);
                    var args = "{\"text\": \"" + payload + "\"}";

                    callback.onChunk(new StreamChunk.BlockStart(0, "text"));
                    callback.onChunk(new StreamChunk.TextDelta(0, "Let me echo that for you."));
                    callback.onChunk(new StreamChunk.BlockEnd(0, new ContentBlock.Text("Let me echo that for you.")));

                    callback.onChunk(new StreamChunk.BlockStart(1, "tool-call"));
                    callback.onChunk(new StreamChunk.ToolCallDelta(1, new dev.dsh.util.CallId("call-echo"), "echo", args));
                    callback.onChunk(new StreamChunk.BlockEnd(1, new ContentBlock.ToolCall(
                            new dev.dsh.util.CallId("call-echo"), "echo", args
                    )));

                    callback.onChunk(new StreamChunk.Usage(new TokenUsage(20, 10)));
                    callback.onChunk(new StreamChunk.Finish(new FinishReason.ToolCalls()));
                } else {
                    var reply = hasToolResult
                            ? "The echo tool has spoken."
                            : "You said: \"" + lastUserText + "\". Try \"echo <something>\" to see a tool call.";

                    callback.onChunk(new StreamChunk.BlockStart(0, "text"));
                    callback.onChunk(new StreamChunk.TextDelta(0, reply));
                    callback.onChunk(new StreamChunk.BlockEnd(0, new ContentBlock.Text(reply)));

                    callback.onChunk(new StreamChunk.Usage(new TokenUsage(20, reply.length())));
                    callback.onChunk(new StreamChunk.Finish(new FinishReason.Stop()));
                }

                callback.onComplete();
            } catch (Exception e) {
                callback.onError(e);
            }
        }
    }

    @Test
    void mockEcho返回文本回复() {
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

        // 验证我们收到了文本块 + 用量 + 结束
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
    void mockEcho触发工具调用() {
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

        // 验证发出了工具调用块
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
    void 未知提供方抛出异常() {
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