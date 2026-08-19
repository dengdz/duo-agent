package com.example;

import dev.duo.api.agent.AgentHooks;
import dev.duo.api.agent.AgentOptions;
import dev.duo.api.llm.LlmRuntime;
import dev.duo.api.llm.StreamCallback;
import dev.duo.core.agent.ReactLoopAgent;
import dev.duo.core.compaction.CompactionConfig;
import dev.duo.core.compaction.CompactionHook;
import dev.duo.core.compaction.TokenEstimator;
import dev.duo.core.llm.MockEchoAdapter;
import dev.duo.core.llm.SystemPromptImpl;
import dev.duo.core.llm.ToolRegistryImpl;
import dev.duo.core.session.Session;
import dev.duo.model.llm.ContentBlock;
import dev.duo.model.llm.FinishReason;
import dev.duo.model.llm.GenerateOptions;
import dev.duo.model.llm.Message;
import dev.duo.model.llm.MessageFactory;
import dev.duo.model.llm.MessageSource;
import dev.duo.model.llm.StreamChunk;
import dev.duo.model.session.SessionEventCompactionStart;
import dev.duo.model.session.SessionEventTypes;
import dev.duo.model.session.SessionId;

import java.util.List;

/**
 * 压缩（Compaction）演示：多轮对话攒历史 → 超阈值触发压缩 → 表面被摘要替换 → 对话继续。
 * <p>
 * 无需 API Key（mock 适配器）。观察点：
 * 1. 每轮的表面 token 估算与消息数增长
 * 2. 超阈值时 CompactionHook 的 INFO 日志（替换了哪个表面范围）
 * 3. 压缩后表面首条变为 [对话摘要]，消息数骤降
 * 4. 日志事件数只增不减（回放保真：原事件完整保留）
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public class CompactionExample {

    /** mock：识别压缩指令返回固定摘要，其余回显。 */
    private static class DemoAdapter extends MockEchoAdapter {

        @Override
        public void stream(GenerateOptions options, StreamCallback callback) {
            if (lastUserText(options).contains("压缩为一份摘要")) {
                emit("用户正在测试对话压缩功能。此前完成了多轮简短问答；"
                        + "用户显式要求保留各轮编号与意图；当前没有未完成的任务。", callback);
                return;
            }
            super.stream(options, callback);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 压缩演示：5 轮对话，第 ~3 轮触发（阈值 120 token）===\n");

        var llm = new LlmRuntime();
        llm.registerAdapter("mock", new DemoAdapter());
        var session = new Session(new SessionId("compaction-demo"));
        var agent = new ReactLoopAgent(
                new SessionId("compaction-demo"),
                new AgentOptions("mock", "mock-model", null, null, AgentHooks.builder()
                        .addPreStepHook(new CompactionHook(
                                llm, new SystemPromptImpl("", false), "mock", "mock-model",
                                new CompactionConfig(120, 40, 2)))
                        .build()),
                session, llm, new SystemPromptImpl("", false), new ToolRegistryImpl());

        for (int i = 1; i <= 5; i++) {
            agent.followup(MessageFactory.createUserMessage(
                    List.of(new ContentBlock.Text("第 " + i + " 轮：请帮我记录这个较长的需求描述，"
                            + "包括背景、目标与验收标准，用于后续压缩测试的数据积累。")),
                    new MessageSource.User()));
            agent.whenIdle();

            var messages = session.deriveMessages();
            var compacted = session.events().stream()
                    .anyMatch(e -> e instanceof SessionEventCompactionStart);
            System.out.printf("第 %d 轮结束: 表面 %d 条消息，估算 %d token，日志 %d 个事件%s%n",
                    i, messages.size(), TokenEstimator.estimateAll(messages),
                    session.events().size(), compacted ? "  ← 已发生过压缩" : "");
        }

        System.out.println("\n--- 压缩后的模型可见表面 ---");
        for (var message : session.deriveMessages()) {
            var text = message.content().stream()
                    .filter(b -> b instanceof ContentBlock.Text)
                    .map(b -> ((ContentBlock.Text) b).text())
                    .findFirst().orElse("");
            var role = switch (message) {
                case Message.UserMessage ignored -> "用户";
                case Message.AssistantMessage ignored -> "助手";
                case Message.ToolResultMessage ignored -> "工具";
            };
            System.out.printf("  [%s] %.60s%s%n", role, text, text.length() > 60 ? "…" : "");
        }

        System.out.printf("%n--- 回放保真 ---%n");
        System.out.printf("  日志事件总数: %d（原始对话事件完整保留，从未删除）%n", session.events().size());
        System.out.printf("  模型可见表面: %d 条消息（其余已折叠进摘要）%n", session.deriveMessages().size());
        System.out.println("\n=== 演示完成 ===");
    }

    private static String lastUserText(GenerateOptions options) {
        for (int i = options.messages().size() - 1; i >= 0; i--) {
            if (options.messages().get(i) instanceof Message.UserMessage userMsg) {
                var block = userMsg.content().isEmpty() ? null : userMsg.content().getFirst();
                if (block instanceof ContentBlock.Text text) {
                    return text.text();
                }
                return "";
            }
        }
        return "";
    }

    private static void emit(String text, StreamCallback callback) {
        callback.onChunk(new StreamChunk.BlockStart(0, SessionEventTypes.BLOCK_TEXT));
        callback.onChunk(new StreamChunk.TextDelta(0, text));
        callback.onChunk(new StreamChunk.BlockEnd(0, new ContentBlock.Text(text)));
        callback.onChunk(new StreamChunk.Finish(new FinishReason.Stop()));
        callback.onComplete();
    }
}
