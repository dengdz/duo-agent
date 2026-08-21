package com.example;

import dev.duo.api.DuoAgent;
import dev.duo.api.agent.Agent;
import dev.duo.api.agent.AgentCancelCause;
import dev.duo.api.agent.CancelOptions;
import dev.duo.api.agent.InboxTarget;
import dev.duo.model.deepseek.DeepSeekModel;
import dev.duo.model.llm.ContentBlock;
import dev.duo.model.llm.Message;
import dev.duo.model.llm.MessageFactory;
import dev.duo.model.llm.MessageSource;
import dev.duo.model.llm.ToolDefinition;
import dev.duo.model.llm.ToolExecution;
import dev.duo.model.llm.ToolExecutionResult;
import dev.duo.model.session.SessionEvent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 取消功能完整演示：三个场景展示 Agent.cancel() 的中断能力。
 * <p>
 * 场景 1：取消挂起的 LLM 推理流（断连 HTTP）<br>
 * 场景 2：取消正在执行的长时工具（bash sleep 30）<br>
 * 场景 3：取消后保留 inbox 消息继续执行（keepInbox）
 * </p>
 * 
 * @author zhangyl
 * @date 2026-08-21
 */
public class CancellationDemo {

    public static void main(String[] args) throws Exception {
        var apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("错误：请设置环境变量 DEEPSEEK_API_KEY");
            System.exit(1);
        }

        System.out.println("=".repeat(60));
        System.out.println("取消功能演示");
        System.out.println("=".repeat(60));
        System.out.println();

        // 场景 1：取消 LLM stream
        scenario1_cancelLlmStream(apiKey);
        Thread.sleep(1000);

        // 场景 2：取消 bash 长时工具
        scenario2_cancelBashTool(apiKey);
        Thread.sleep(1000);

        // 场景 3：keepInbox 保留消息
        scenario3_keepInbox(apiKey);

        System.out.println();
        System.out.println("=".repeat(60));
        System.out.println("全部场景演示完成");
        System.out.println("=".repeat(60));
    }

    /**
     * 场景 1：取消挂起的 LLM 推理流。
     * <p>
     * 发起需要长时间推理的请求（如"写一篇 5000 字论文"），2 秒后取消，
     * 验证 HTTP stream 立即断开、不等推理完成。
     * </p>
     */
    private static void scenario1_cancelLlmStream(String apiKey) throws Exception {
        System.out.println("场景 1：取消挂起的 LLM 推理流");
        System.out.println("-".repeat(60));

        var model = DeepSeekModel.builder()
                .apiKey(apiKey)
                .model("deepseek-chat")
                .build();

        var duoAgent = DuoAgent.builder()
                .model(model)
                .build();
        
        Agent agent = duoAgent.getAgent();

        var eventCounter = new AtomicInteger(0);
        var startTime = System.currentTimeMillis();

        // 异步流式调用
        new Thread(() -> {
            try {
                duoAgent.stream("请详细分析量子计算的发展历史，至少 2000 字").subscribe(new Flow.Subscriber<SessionEvent>() {
                    private Flow.Subscription subscription;

                    @Override
                    public void onSubscribe(Flow.Subscription s) {
                        subscription = s;
                        subscription.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(SessionEvent event) {
                        eventCounter.incrementAndGet();
                        if (event.type().equals("assistant/chunk")) {
                            System.out.print(".");
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        System.out.println("\n流异常: " + t.getMessage());
                    }

                    @Override
                    public void onComplete() {
                        var elapsed = System.currentTimeMillis() - startTime;
                        System.out.println("\n流结束，耗时 " + elapsed + " ms，收到 " + eventCounter.get() + " 事件");
                    }
                });
            } catch (Exception e) {
                System.err.println("流调用失败: " + e.getMessage());
            }
        }, "scenario1-stream").start();

        // 等待 2 秒后取消
        Thread.sleep(2000);
        System.out.println("\n[2s] 发起取消...");
        duoAgent.cancel(new AgentCancelCause.User(), new CancelOptions(false));

        // 等待收敛
        agent.whenIdle();
        var totalElapsed = System.currentTimeMillis() - startTime;
        System.out.println("✓ 取消完成，总耗时 " + totalElapsed + " ms（应远小于完整推理时间）");
        System.out.println();
    }

    /**
     * 场景 2：取消正在执行的 bash 长时工具。
     * <p>
     * 让模型调用 bash sleep 30，1 秒后取消，验证：
     * - bash 子进程被 SIGTERM/SIGKILL 终止
     * - 不等 30 秒即返回
     * - tool_result 事件 errorCode = "ABORTED"
     * </p>
     */
    private static void scenario2_cancelBashTool(String apiKey) throws Exception {
        System.out.println("场景 2：取消正在执行的 bash 长时工具");
        System.out.println("-".repeat(60));

        var model = DeepSeekModel.builder()
                .apiKey(apiKey)
                .model("deepseek-chat")
                .build();

        var duoAgent = DuoAgent.builder()
                .model(model)
                .withCodeTools()  // bash 在 withCodeTools 中，不在 withFileTools
                .build();
        
        Agent agent = duoAgent.getAgent();

        var startTime = System.currentTimeMillis();
        var abortedToolResult = new AtomicInteger(0);
        var beforeDispatchToolResult = new AtomicInteger(0);

        // 异步流式调用
        new Thread(() -> {
            try {
                duoAgent.stream("用 bash 执行 sleep 30，然后告诉我现在的时间").subscribe(new Flow.Subscriber<SessionEvent>() {
                    private Flow.Subscription subscription;

                    @Override
                    public void onSubscribe(Flow.Subscription s) {
                        subscription = s;
                        subscription.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(SessionEvent event) {
                        if (event.type().equals("tool/call")) {
                            System.out.println("检测到 tool/call: bash");
                        }
                        if (event.type().equals("tool/result")) {
                            var toolResult = (dev.duo.model.session.SessionEventToolResult) event;
                            if ("ABORTED".equals(toolResult.errorCode())) {
                                abortedToolResult.incrementAndGet();
                                System.out.println("检测到 ABORTED tool_result（已执行可能有副作用）");
                            } else if ("ABORTED_BEFORE_DISPATCH".equals(toolResult.errorCode())) {
                                beforeDispatchToolResult.incrementAndGet();
                                System.out.println("检测到 ABORTED_BEFORE_DISPATCH tool_result（未执行无副作用）");
                            }
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        System.out.println("流异常: " + t.getMessage());
                    }

                    @Override
                    public void onComplete() {
                        var elapsed = System.currentTimeMillis() - startTime;
                        System.out.println("流结束，耗时 " + elapsed + " ms");
                    }
                });
            } catch (Exception e) {
                System.err.println("流调用失败: " + e.getMessage());
            }
        }, "scenario2-stream").start();

        // 等待 bash 真正启动并执行（3 秒足够模型推理 + bash 启动）
        Thread.sleep(3000);
        System.out.println("[3s] 发起取消（bash sleep 30 应正在运行）...");
        duoAgent.cancel(new AgentCancelCause.User(), new CancelOptions(false));

        // 等待收敛
        agent.whenIdle();
        var totalElapsed = System.currentTimeMillis() - startTime;
        System.out.println("✓ 取消完成，总耗时 " + totalElapsed + " ms（应远小于 30 秒）");
        System.out.println("✓ 收到 " + abortedToolResult.get() + " 个 ABORTED");
        System.out.println("✓ 收到 " + beforeDispatchToolResult.get() + " 个 ABORTED_BEFORE_DISPATCH");
        System.out.println();
    }

    /**
     * 场景 3：keepInbox 保留消息，取消后继续执行。
     * <p>
     * 发送两条消息，第一条触发慢工具后取消（keepInbox=true），
     * 验证第二条消息在新 turn 中继续执行。
     * </p>
     */
    private static void scenario3_keepInbox(String apiKey) throws Exception {
        System.out.println("场景 3：keepInbox 保留消息");
        System.out.println("-".repeat(60));

        var model = DeepSeekModel.builder()
                .apiKey(apiKey)
                .model("deepseek-chat")
                .build();

        // 自定义慢工具
        var slowTool = new ToolDefinition(
                "slow_operation",
                "模拟耗时操作（10 秒）",
                Map.of("type", "object", "properties", Map.of()),
                (ToolExecution execution) -> {
                    try {
                        Thread.sleep(10_000);
                        return new ToolExecutionResult("操作完成");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        if (execution.cancellation().isCancelled()) {
                            throw new dev.duo.api.agent.TurnCancelledException(execution.cancellation().cause());
                        }
                        return new ToolExecutionResult("操作被中断");
                    }
                }
        );

        var duoAgent = DuoAgent.builder()
                .model(model)
                .tool(slowTool)
                .build();
        
        Agent agent = duoAgent.getAgent();

        var turnCount = new AtomicInteger(0);
        var streamReady = new java.util.concurrent.CountDownLatch(1);

        // 先订阅 stream，再发起调用（避免订阅晚于事件导致历史重放混淆）
        new Thread(() -> {
            try {
                duoAgent.stream("调用 slow_operation").subscribe(new Flow.Subscriber<SessionEvent>() {
                    private Flow.Subscription subscription;

                    @Override
                    public void onSubscribe(Flow.Subscription s) {
                        subscription = s;
                        subscription.request(Long.MAX_VALUE);
                        streamReady.countDown();  // 通知订阅已就绪
                    }

                    @Override
                    public void onNext(SessionEvent event) {
                        if (event.type().equals("turn/start")) {
                            var turnStart = ((dev.duo.model.session.SessionEventTurnStart) event);
                            var turn = turnStart.turn();
                            var seq = turnStart.seq();
                            turnCount.incrementAndGet();
                            System.out.println("turn " + turn + " 开始（seq=" + seq + ", turnCount=" + turnCount.get() + "）");
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        System.out.println("流异常: " + t.getMessage());
                    }

                    @Override
                    public void onComplete() {
                        System.out.println("流结束");
                    }
                });
            } catch (Exception e) {
                System.err.println("流调用失败: " + e.getMessage());
            }
        }, "scenario3-stream").start();

        // 等待订阅就绪
        streamReady.await();
        Thread.sleep(500);

        // 等待工具执行
        Thread.sleep(1500);
        
        // 取消前先发送第二条消息（排队在 inbox）
        System.out.println("[2s] 发送第二条消息（排队 inbox）...");
        Message userMsg = MessageFactory.createUserMessage(
            List.of(new ContentBlock.Text("现在几点了？")),
            new MessageSource.User()
        );
        agent.send(userMsg, InboxTarget.NEXT_TURN, true);
        
        Thread.sleep(500);
        System.out.println("[2.5s] 发起取消（keepInbox=true）...");
        duoAgent.cancel(new AgentCancelCause.User(), new CancelOptions(true));

        // 等待收敛
        agent.whenIdle();
        System.out.println("✓ 取消完成，共执行 " + turnCount.get() + " 个 turn");
        System.out.println("✓ 第二条消息应在新 turn 中执行（turn count 应 = 2）");
        System.out.println();
    }
}
