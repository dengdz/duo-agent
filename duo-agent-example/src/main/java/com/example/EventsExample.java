package com.example;

import dev.duo.api.DuoAgent;
import dev.duo.api.DuoModel;
import dev.duo.model.deepseek.DeepSeekModel;
import dev.duo.model.llm.ContentBlock;
import dev.duo.model.llm.StreamChunk;
import dev.duo.model.session.SessionEvent;
import dev.duo.model.session.SessionEventAssistantChunk;
import dev.duo.model.session.SessionEventAssistantMessage;
import dev.duo.model.session.SessionEventToolCall;
import dev.duo.model.session.SessionEventToolResult;
import dev.duo.model.session.SessionEventTurnEnd;
import dev.duo.model.session.SessionEventTurnStart;
import dev.duo.model.session.SessionEventStepStart;
import dev.duo.model.session.SessionEventStepEnd;
import dev.duo.model.session.SessionEventUserMessage;
import dev.duo.model.session.TurnEndReason;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 完整事件流演示 - 完整观察 Agent 的工作过程。
 * <p>
 * stream() 全量透传 session 事件：
 * 思考推理、文本增量、工具调用与结果、step/turn 边界全部可见，
 * 适合渲染 IDE Agent 式的工作过程界面。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public class EventsExample {

    public static void main(String[] args) throws InterruptedException {
        var apiKey = EnvLoader.get("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("❌ 错误：未设置 DEEPSEEK_API_KEY");
            System.exit(1);
        }

        DuoModel model = DeepSeekModel.builder()
                .apiKey(apiKey)
                .model("deepseek-chat")
                .contextWindow(128000)
                .build();
        var agent = DuoAgent.builder()
                .model(model)
                .withCodeTools()  // bash + file + grep + glob + edit，触发工具调用事件
                .build();

        System.out.println("=== stream() 完整事件流演示 ===");
        System.out.println("（完整观察：step 边界 → 文本/思考增量 → 工具调用 → 工具结果 → turn 结束）\n");

        var done = new CountDownLatch(1);
        var error = new AtomicReference<Throwable>();
        var subscriptionRef = new AtomicReference<Flow.Subscription>();

        agent.stream("列出当前目录下所有的 Java 文件名，并统计一共有多少个。")
                .subscribe(new Flow.Subscriber<SessionEvent>() {
                    @Override
                    public void onSubscribe(Flow.Subscription s) {
                        subscriptionRef.set(s);
                        s.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(SessionEvent event) {
                        // 全部事件展示：重点事件详细渲染，其余以协议原值一行带过
                        switch (event) {
                            case SessionEventAssistantChunk c
                                    when c.chunk() instanceof StreamChunk.TextDelta d ->
                                    System.out.print(d.text());
                            case SessionEventAssistantChunk c
                                    when c.chunk() instanceof StreamChunk.ReasoningDelta r ->
                                    System.out.print(r.text());
                            case SessionEventToolCall call ->
                                    System.out.printf("%n🔧 [turn %d/step %d] 工具调用: %s(%s)%n",
                                            call.turn(), call.step(), call.name(),
                                            truncate(call.arguments(), 80));
                            case SessionEventToolResult result ->
                                    System.out.printf("📤 [turn %d/step %d] 工具结果: %s%n",
                                            result.turn(), result.step(),
                                            truncate(extractText(result), 120));
                            case SessionEventAssistantMessage msg -> {
                                var usage = msg.usage();
                                System.out.printf("%n📊 [turn %d/step %d] 消息组装完成（tokens: 输入 %d / 输出 %d，回链 %d 个 chunk）%n",
                                        msg.turn(), msg.step(),
                                        usage != null ? usage.inputTokens() : -1,
                                        usage != null ? usage.outputTokens() : -1,
                                        msg.sourceEventSeqs() != null ? msg.sourceEventSeqs().length : 0);
                            }
                            case SessionEventTurnEnd end ->
                                    System.out.printf("%n🏁 turn 结束，原因: %s%n",
                                            describe(end.reason()));
                            default ->
                                    System.out.printf("  · [seq %3d] %s%s%n",
                                            event.seq(), event.type(), detailOf(event));
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        error.set(t);
                        done.countDown();
                    }

                    @Override
                    public void onComplete() {
                        done.countDown();
                    }
                });

        if (!done.await(5, TimeUnit.MINUTES)) {
            var s = subscriptionRef.get();
            if (s != null) {
                s.cancel();
            }
            System.err.println("\n❌ 超时未完成，已取消订阅");
            return;
        }
        if (error.get() != null) {
            System.err.println("\n❌ 失败: " + error.get().getMessage());
            error.get().printStackTrace();
            return;
        }
        System.out.println("\n✅ 事件流演示完成");
    }

    /** 静默事件的补充摘要：边界事件带 turn/step，chunk 变体带具体类型。 */
    private static String detailOf(SessionEvent event) {
        return switch (event) {
            case SessionEventTurnStart t -> "  (turn " + t.turn() + ")";
            case SessionEventTurnEnd t -> "";
            case SessionEventStepStart s -> "  (turn " + s.turn() + " / step " + s.step() + ")";
            case SessionEventStepEnd s -> "  (turn " + s.turn() + " / step " + s.step() + ")";
            case SessionEventAssistantChunk c -> "  (" + chunkKind(c) + ")";
            case SessionEventUserMessage u -> "  (\"" + truncate(extractUserText(u), 40) + "\")";
            default -> "";
        };
    }

    private static String chunkKind(SessionEventAssistantChunk event) {
        return switch (event.chunk()) {
            case StreamChunk.BlockStart b -> "block-start: " + b.blockType();
            case StreamChunk.BlockEnd b -> "block-end";
            case StreamChunk.ToolCallDelta d -> "tool-call-delta: " + d.name();
            case StreamChunk.Usage u -> "usage";
            case StreamChunk.Finish f -> "finish: " + f.reason().getClass().getSimpleName();
            default -> "?";
        };
    }

    private static String extractUserText(SessionEventUserMessage event) {
        var sb = new StringBuilder();
        for (var block : event.message().content()) {
            if (block instanceof ContentBlock.Text t) {
                sb.append(t.text());
            }
        }
        return sb.toString();
    }

    private static String extractText(SessionEventToolResult result) {
        var sb = new StringBuilder();
        for (var block : result.message().content()) {
            if (block instanceof ContentBlock.ToolResult tr) {
                for (var inner : tr.content()) {
                    if (inner instanceof ContentBlock.Text t) {
                        sb.append(t.text());
                    }
                }
            }
        }
        return sb.isEmpty() ? "(无文本)" : sb.toString();
    }

    private static String describe(TurnEndReason reason) {
        return switch (reason) {
            case TurnEndReason.Completed c -> "正常完成";
            case TurnEndReason.Aborted a -> "被取消（" + a.reason().getClass().getSimpleName() + "）";
            case TurnEndReason.Blocked b -> "预步被拒";
            case TurnEndReason.Error e -> "失败: " + e.failure().message();
            case TurnEndReason.MaxTokens m -> "token 上限";
            case TurnEndReason.Interrupted i -> "中断（崩溃恢复）";
        };
    }

    private static String truncate(String s, int max) {
        var compact = s.replaceAll("\\s+", " ").trim();
        return compact.length() <= max ? compact : compact.substring(0, max) + "…";
    }
}
