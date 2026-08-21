package com.example;

import dev.duo.api.agent.AgentHooks;
import dev.duo.api.agent.AgentOptions;
import dev.duo.api.agent.PreStepDecision;
import dev.duo.api.llm.LlmRuntime;
import dev.duo.core.agent.ReactLoopAgent;
import dev.duo.core.llm.MockEchoAdapter;
import dev.duo.core.llm.SystemPromptImpl;
import dev.duo.core.llm.ToolRegistryImpl;
import dev.duo.core.session.Session;
import dev.duo.model.llm.ContentBlock;
import dev.duo.model.llm.GenerateOptions;
import dev.duo.model.llm.MessageFactory;
import dev.duo.model.llm.MessageSource;
import dev.duo.model.llm.ToolDefinition;
import dev.duo.model.llm.ToolExecutionResult;
import dev.duo.model.llm.ToolExecutor;
import dev.duo.model.session.SessionEventAssistantMessage;
import dev.duo.model.session.SessionEventToolResult;
import dev.duo.model.session.SessionEventTurnEnd;
import dev.duo.model.session.SessionId;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hook 扩展点验证示例（无需 API Key，使用 mock 适配器）。
 * <p>
 * 三个场景肉眼可验证：
 * 1. 全链路顺序：pre-step → request → tool hook 的触发顺序与改写效果
 * 2. tool hook 短路：工具本体不执行，模型收到 hook 的替代结果
 * 3. pre-step 拒绝：turn 以 Blocked 结束，不产生任何 step
 * </p>
 *
 * @author zhangyl
 */
public class HookVerificationExample {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Hook 扩展点验证（mock，无需 API Key）===\n");

        scenario1ChainOrder();
        scenario2ToolShortCircuit();
        scenario3PreStepReject();

        System.out.println("=== 验证完成 ===");
    }

    /** 场景 1：观察 hook 触发顺序 + request hook 改写 system prompt。 */
    private static void scenario1ChainOrder() throws Exception {
        System.out.println("--- 场景 1：hook 链触发顺序（echo 演练工具往返）---");

        var order = new AtomicInteger();
        var tools = new ToolRegistryImpl();
        tools.register(new ToolDefinition(
                "echo", "回显文本", Map.of(),
                ToolExecutor.of(args -> new ToolExecutionResult("echo-tool-body: " + args.get("text")))));

        var hooks = AgentHooks.builder()
                .addPreStepHook((ctx, next) -> {
                    System.out.printf("  [%d] PreStepHook  收到消息数=%d，放行%n",
                            order.incrementAndGet(), ctx.messages().size());
                    return next.proceed();
                })
                .addRequestHook((ctx, next) -> {
                    var options = next.proceed();
                    System.out.printf("  [%d] RequestHook  turn=%d step=%d，改写 system prompt%n",
                            order.incrementAndGet(), ctx.turn(), ctx.step());
                    return new GenerateOptions(
                            options.provider(), options.model(),
                            options.messages(), "[hook 注入的系统提示词]", options.tools());
                })
                .addToolHook((ctx, next) -> {
                    System.out.printf("  [%d] ToolHook     工具=%s 参数=%s，放行执行%n",
                            order.incrementAndGet(), ctx.toolName(), ctx.arguments());
                    return next.proceed();
                })
                .build();

        var session = runOnce("scenario-1", hooks, tools, "echo hello-hook");

        System.out.println("  >>> 结果验证：");
        printAssistantText(session);
        System.out.println("  >>> 顺序应为: [1]PreStep → [2]Request(第1次) → [3]Tool → [4]Request(第2次)\n");
    }

    /** 场景 2：tool hook 短路——工具本体不执行，模型收到替代结果。 */
    private static void scenario2ToolShortCircuit() throws Exception {
        System.out.println("--- 场景 2：tool hook 短路（工具被拦截）---");

        var toolExecuted = new AtomicInteger();
        var tools = new ToolRegistryImpl();
        tools.register(new ToolDefinition(
                "echo", "回显文本", Map.of(),
                ToolExecutor.of(args -> {
                    toolExecuted.incrementAndGet();
                    return new ToolExecutionResult("真实工具结果");
                })));

        var hooks = AgentHooks.builder()
                .addToolHook((ctx, next) -> {
                    System.out.printf("  ToolHook 拦截工具 [%s]，不调 next，返回替代结果%n", ctx.toolName());
                    return new ToolExecutionResult("hook 审批拒绝：该工具被策略拦截");
                })
                .build();

        var session = runOnce("scenario-2", hooks, tools, "echo blocked");

        System.out.printf("  >>> 工具本体执行次数 = %d（应为 0）%n", toolExecuted.get());
        var toolText = session.events().stream()
                .filter(e -> e instanceof SessionEventToolResult)
                .map(e -> (SessionEventToolResult) e)
                .map(e -> (ContentBlock.ToolResult) e.message().content().getFirst())
                .map(b -> (ContentBlock.Text) b.content().getFirst())
                .map(ContentBlock.Text::text)
                .findFirst().orElse("");
        System.out.printf("  >>> 日志中的工具结果 = \"%s\"%n", toolText);
        System.out.println("  >>> 模型收到的也是替代结果（回显: The echo tool has spoken.）\n");
    }

    /** 场景 3：pre-step 拒绝——turn 以 Blocked 结束，无 step/start。 */
    private static void scenario3PreStepReject() throws Exception {
        System.out.println("--- 场景 3：pre-step 拒绝 ---");

        var hooks = AgentHooks.builder()
                .addPreStepHook((ctx, next) -> {
                    System.out.println("  PreStepHook 拒绝本 step（不调 next）");
                    return new PreStepDecision.Reject();
                })
                .build();

        var session = runOnce("scenario-3", hooks, new ToolRegistryImpl(), "anything");

        var turnEnd = (SessionEventTurnEnd) session.events().getLast();
        var stepStarts = session.events().stream()
                .filter(e -> e.type().equals("step/start")).count();
        System.out.printf("  >>> turn 结束原因 = %s%n", turnEnd.reason().getClass().getSimpleName());
        System.out.printf("  >>> step/start 事件数 = %d（应为 0，未消耗模型调用）%n%n", stepStarts);
    }

    /** 用 mock-echo 适配器跑一轮完整对话。 */
    private static Session runOnce(String name, AgentHooks hooks,
                                   ToolRegistryImpl tools, String userText) throws Exception {
        var llm = new LlmRuntime();
        llm.registerAdapter("mock-echo", new MockEchoAdapter());

        var session = new Session(new SessionId(name));
        var agent = new ReactLoopAgent(
                new SessionId(name + "-agent"),
                new AgentOptions("mock-echo", "mock-model", null, null, hooks),
                session, llm, new SystemPromptImpl("", false), tools);

        agent.followup(MessageFactory.createUserMessage(
                List.of(new ContentBlock.Text(userText)), new MessageSource.User()));
        agent.whenIdle();
        return session;
    }

    private static void printAssistantText(Session session) {
        session.events().stream()
                .filter(e -> e instanceof SessionEventAssistantMessage)
                .map(e -> (SessionEventAssistantMessage) e)
                .flatMap(e -> e.message().content().stream())
                .filter(b -> b instanceof ContentBlock.Text)
                .map(b -> ((ContentBlock.Text) b).text())
                .forEach(text -> System.out.println("  最终回复: " + text));
    }
}
