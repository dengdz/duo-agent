package dev.dsh.core.llm.deepseek;

import dev.dsh.api.llm.LlmRuntime;
import dev.dsh.api.llm.StreamCallback;
import dev.dsh.core.llm.SystemPromptImpl;
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
                    System.out.println("    text: " + text.text().substring(0, Math.min(50, text.text().length())) + "...");
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

    private String describeChunk(StreamChunk chunk) {
        return switch (chunk) {
            case StreamChunk.BlockStart bs -> "BlockStart(" + bs.index() + ", " + bs.blockType() + ")";
            case StreamChunk.TextDelta td -> "TextDelta(" + td.index() + ", '" + td.text() + "')";
            case StreamChunk.ReasoningDelta rd -> "ReasoningDelta(" + rd.index() + ")";
            case StreamChunk.ToolCallDelta tcd -> "ToolCallDelta(" + tcd.index() + ", " + tcd.name() + ")";
            case StreamChunk.BlockEnd be -> "BlockEnd(" + be.index() + ")";
            case StreamChunk.Usage u -> "Usage(" + u.usage().inputTokens() + " in, " + u.usage().outputTokens() + " out)";
            case StreamChunk.Finish f -> "Finish(" + f.reason() + ")";
        };
    }
}