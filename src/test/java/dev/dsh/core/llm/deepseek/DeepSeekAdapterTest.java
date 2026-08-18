package dev.dsh.core.llm.deepseek;

import dev.dsh.api.llm.LlmRuntime;
import dev.dsh.api.llm.StreamCallback;
import dev.dsh.core.llm.SystemPromptImpl;
import dev.dsh.core.llm.ToolRegistryImpl;
import dev.dsh.core.llm.tools.TodoWriteTool;
import dev.dsh.model.llm.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DeepSeek 适配器真实 API 测试。
 */
class DeepSeekAdapterTest {

    private static final String MODEL = "deepseek-v4-flash";

    @Test
    @EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
    void 真实对话测试() throws Exception {
        var llm = new LlmRuntime();
        llm.registerAdapter("deepseek-official", new DeepSeekAdapter());

        var messages = List.<Message>of(
                MessageFactory.createUserMessage(
                        List.of(new ContentBlock.Text("你好，请用一句话介绍你自己")),
                        new MessageSource.User()
                )
        );
        var options = new GenerateOptions("deepseek-official", MODEL, messages);

        var chunks = new ArrayList<StreamChunk>();
        var barrier = new CompletableFuture<Void>();
        var errorRef = new java.util.concurrent.atomic.AtomicReference<Throwable>();

        System.out.println("正在调用 DeepSeek API...");
        llm.stream(options, new StreamCallback() {
            @Override
            public void onChunk(StreamChunk chunk) {
                chunks.add(chunk);
                if (chunk instanceof StreamChunk.TextDelta td) {
                    System.out.print(td.text());
                }
            }
            @Override
            public void onComplete() {
                System.out.println("\n--- 调用完成 ---");
                barrier.complete(null);
            }
            @Override
            public void onError(Throwable err) {
                errorRef.set(err);
                System.out.println("\n--- 错误: " + err.getMessage() + " ---");
                barrier.completeExceptionally(err);
            }
        });

        barrier.get(30, TimeUnit.SECONDS);

        if (errorRef.get() != null) {
            fail("API 调用失败: " + errorRef.get().getMessage());
        }

        System.out.println("收到 " + chunks.size() + " 个 chunk");
        for (var c : chunks) {
            System.out.println("  " + describeChunk(c));
        }

        // 验证收到了文本块
        var textDeltas = chunks.stream()
                .filter(c -> c instanceof StreamChunk.TextDelta)
                .map(StreamChunk.TextDelta.class::cast)
                .toList();
        assertFalse(textDeltas.isEmpty(), "应收到文本回复");

        // 验证收到了 finish
        var finishChunks = chunks.stream()
                .filter(c -> c instanceof StreamChunk.Finish)
                .toList();
        assertEquals(1, finishChunks.size(), "应有 1 个 finish");

        // 验证收到了 usage
        var usageChunks = chunks.stream()
                .filter(c -> c instanceof StreamChunk.Usage)
                .toList();
        assertFalse(usageChunks.isEmpty(), "应有 token 用量");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
    void 使用AgentLoop完成对话() throws Exception {
        var llm = new LlmRuntime();
        llm.registerAdapter("deepseek-official", new DeepSeekAdapter());

        var session = new dev.dsh.core.session.Session(new dev.dsh.model.session.SessionId("deepseek-test"));
        var agent = new dev.dsh.core.agent.ReactLoopAgent(
                new dev.dsh.model.session.SessionId("deepseek-agent"),
                new dev.dsh.api.agent.AgentOptions("deepseek-official", MODEL, null),
                session,
                llm,
                new SystemPromptImpl("", false), new dev.dsh.core.llm.ToolRegistryImpl()
        );

        var userMsg = MessageFactory.createUserMessage(
                List.of(new ContentBlock.Text("用一句话解释什么是 Agent")),
                new MessageSource.User()
        );

        System.out.println("用户: " + ((ContentBlock.Text) userMsg.content().getFirst()).text());
        agent.followup(userMsg);
        agent.whenIdle();

        System.out.println("\n=== 会话事件序列 ===");
        for (var e : session.events()) {
            System.out.println("  " + e.type());
        }

        // 验证派生消息
        var messages = session.deriveMessages();
        assertFalse(messages.isEmpty(), "应有派生消息");

        System.out.println("\n共 " + messages.size() + " 条派生消息:");
        for (var msg : messages) {
            System.out.println("  role=" + msg.role() + " blocks=" + msg.content().size());
            for (var block : msg.content()) {
                if (block instanceof ContentBlock.Text text) {
                    var preview = text.text().substring(0, Math.min(50, text.text().length()));
                    System.out.println("    text: " + preview + "...");
                }
            }
        }

        // 验证最后一条消息是 assistant
        var lastMsg = messages.getLast();
        assertEquals("assistant", lastMsg.role(), "最后一条消息应是 assistant 角色");
    }

    @Test
    void 无APIKey时优雅失败() {
        var apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) {
            return;
        }

        var adapter = new DeepSeekAdapter();
        var options = new GenerateOptions("deepseek-official", MODEL, List.of());

        var errorRef = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var barrier = new CompletableFuture<Void>();

        adapter.stream(options, new StreamCallback() {
            @Override
            public void onChunk(StreamChunk chunk) {}
            @Override
            public void onComplete() { barrier.complete(null); }
            @Override
            public void onError(Throwable err) {
                errorRef.set(err);
                barrier.complete(null);
            }
        });

        try {
            barrier.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // timeout is ok
        }

        assertNotNull(errorRef.get(), "应返回错误");
        System.out.println("错误信息: " + errorRef.get().getMessage());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
    void 工具调用往返测试() throws Exception {
        var llm = new LlmRuntime();
        llm.registerAdapter("deepseek-official", new DeepSeekAdapter());

        var toolRegistry = new ToolRegistryImpl();
        var todoTool = new TodoWriteTool();
        toolRegistry.register(todoTool.getDefinition());

        var sp = new SystemPromptImpl(
                "你是一个有帮助的助手。当用户要求记录任务时，使用 todo_write 工具。", false);
        // 将工具 schema 注册到 system prompt
        var toolDef = todoTool.getDefinition();
        var toolSchema = new ToolSchema(toolDef.name(), toolDef.description(), toolDef.parameters());
        sp.tools(assembly -> new ToolProviderResult(List.of(toolSchema)));

        var session = new dev.dsh.core.session.Session(
                new dev.dsh.model.session.SessionId("tool-test"));
        var agent = new dev.dsh.core.agent.ReactLoopAgent(
                new dev.dsh.model.session.SessionId("tool-agent"),
                new dev.dsh.api.agent.AgentOptions("deepseek-official", MODEL, null),
                session,
                llm,
                sp,
                toolRegistry
        );

        var userMsg = dev.dsh.model.llm.MessageFactory.createUserMessage(
                List.of(new ContentBlock.Text("帮我记录两个任务：买牛奶和写报告")),
                new dev.dsh.model.llm.MessageSource.User()
        );

        System.out.println("用户: 帮我记录两个任务：买牛奶和写报告");
        agent.followup(userMsg);
        agent.whenIdle();

        System.out.println("\n=== 会话事件序列 ===");
        for (var e : session.events()) {
            System.out.println("  " + e.type());
        }

        // 验证有 tool/call 和 tool/result 事件
        var hasToolCall = session.events().stream()
                .anyMatch(e -> e instanceof dev.dsh.model.session.SessionEventToolCall);
        var hasToolResult = session.events().stream()
                .anyMatch(e -> e instanceof dev.dsh.model.session.SessionEventToolResult);

        System.out.println("\n工具调用: " + (hasToolCall ? "✓" : "✗"));
        System.out.println("工具结果: " + (hasToolResult ? "✓" : "✗"));

        // 验证 todo 已写入
        var todos = todoTool.getTodos();
        System.out.println("\n已记录 " + todos.size() + " 个任务:");
        for (var t : todos) {
            System.out.println("  - " + t.content() + " [" + t.status() + "]");
        }
        assertFalse(todos.isEmpty(), "todo 应被实际记录（此前曾因参数解析 bug 记 0 条）");

        // 验证派生消息
        var messages = session.deriveMessages();
        System.out.println("\n共 " + messages.size() + " 条派生消息");
        for (var msg : messages) {
            System.out.println("  role=" + msg.role() + " blocks=" + msg.content().size());
        }
    }

    @Test
    void 工具调用解析_无需真实API() {
        var adapter = new DeepSeekAdapter();
        var textBuf = new StringBuilder();
        var chunks = new ArrayList<StreamChunk>();

        var callback = new StreamCallback() {
            @Override
            public void onChunk(StreamChunk chunk) { chunks.add(chunk); }
            @Override
            public void onComplete() {}
            @Override
            public void onError(Throwable err) { fail("不应报错: " + err.getMessage()); }
        };

        // 模拟 DeepSeek 流式工具调用：先发送 id/name，再分片发送 arguments
        var chunk1 = "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_123\","
                + "\"type\":\"function\",\"function\":{\"name\":\"todo_write\",\"arguments\":\"\"}}]},"
                + "\"finish_reason\":null}]}";
        var chunk2 = "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":"
                + "{\"arguments\":\"{\"}}]},\"finish_reason\":null}]}";
        var chunk3 = "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":"
                + "{\"arguments\":\"\\\"todos\\\"\"}}]},\"finish_reason\":null}]}";
        var chunk4 = "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":"
                + "{\"arguments\":\":[{\\\"content\\\":\\\"买牛奶\\\"}]}\"}}]},\"finish_reason\":null}]}";
        var chunk5 = "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}";

        adapter.parseChunk(chunk1, textBuf, callback);
        adapter.parseChunk(chunk2, textBuf, callback);
        adapter.parseChunk(chunk3, textBuf, callback);
        adapter.parseChunk(chunk4, textBuf, callback);
        adapter.parseChunk(chunk5, textBuf, callback);

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
    void 普通文本解析_无需真实API() {
        var adapter = new DeepSeekAdapter();
        var textBuf = new StringBuilder();
        var chunks = new ArrayList<StreamChunk>();

        var callback = new StreamCallback() {
            @Override
            public void onChunk(StreamChunk chunk) { chunks.add(chunk); }
            @Override
            public void onComplete() {}
            @Override
            public void onError(Throwable err) { fail("不应报错: " + err.getMessage()); }
        };

        adapter.parseChunk(
                "{\"choices\":[{\"delta\":{\"content\":\"你好\"},\"finish_reason\":null}]}", textBuf, callback);
        adapter.parseChunk(
                "{\"choices\":[{\"delta\":{\"content\":\"世界\"},\"finish_reason\":null}]}", textBuf, callback);
        adapter.parseChunk("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}", textBuf, callback);

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

    private String describeChunk(StreamChunk chunk) {
        return switch (chunk) {
            case StreamChunk.BlockStart bs -> "BlockStart(" + bs.index() + ", " + bs.blockType() + ")";
            case StreamChunk.TextDelta td -> "TextDelta(" + td.index() + ", '" + td.text() + "')";
            case StreamChunk.ReasoningDelta rd -> "ReasoningDelta(" + rd.index() + ")";
            case StreamChunk.ToolCallDelta tcd -> "ToolCallDelta(" + tcd.index() + ", " + tcd.name() + ")";
            case StreamChunk.BlockEnd be -> "BlockEnd(" + be.index() + ")";
            case StreamChunk.Usage u -> "Usage(" + u.usage().inputTokens()
                    + " in, " + u.usage().outputTokens() + " out)";
            case StreamChunk.Finish f -> "Finish(" + f.reason() + ")";
        };
    }
}