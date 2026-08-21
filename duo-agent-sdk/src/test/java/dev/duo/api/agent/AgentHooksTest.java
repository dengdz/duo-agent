package dev.duo.api.agent;

import dev.duo.api.hook.PreStepHook;
import dev.duo.api.hook.RequestHook;
import dev.duo.api.hook.ToolExecutionHook;
import dev.duo.model.llm.ContentBlock;
import dev.duo.model.llm.GenerateOptions;
import dev.duo.model.llm.ToolExecutionResult;
import dev.duo.model.session.SessionId;
import dev.duo.util.CallId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentHooks} 链机制的单元测试。
 * <p>
 * 覆盖：注册序（外→内）、不调 next 短路、防重入、空集合直落内置行为。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
class AgentHooksTest {

    private static final SessionId AGENT = new SessionId("hook-test-agent");

    @Test
    void emptyHooksFallThroughToInnerBehavior() throws Exception {
        var hooks = AgentHooks.empty();
        var decision = hooks.dispatchPreStep(
                new PreStepHook.PreStepContext(AGENT, 1, 1, List.of(), new dev.duo.core.session.Session(AGENT)),
                () -> new PreStepDecision.Reject());
        assertTrue(decision instanceof PreStepDecision.Reject, "空链应直落内置行为");
    }

    @Test
    void hooksRunInRegistrationOrderOuterFirst() throws Exception {
        var order = new ArrayList<String>();
        var hooks = AgentHooks.builder()
                .addPreStepHook((ctx, next) -> {
                    order.add("outer");
                    return next.proceed();
                })
                .addPreStepHook((ctx, next) -> {
                    order.add("inner");
                    return next.proceed();
                })
                .build();

        var decision = hooks.dispatchPreStep(
                new PreStepHook.PreStepContext(AGENT, 1, 1, List.of(), new dev.duo.core.session.Session(AGENT)),
                () -> {
                    order.add("builtin");
                    return new PreStepDecision.Reject();
                });

        assertEquals(List.of("outer", "inner", "builtin"), order);
        assertTrue(decision instanceof PreStepDecision.Reject);
    }

    @Test
    void notCallingNextVetoesRestOfChain() throws Exception {
        var innerReached = new AtomicInteger();
        var hooks = AgentHooks.builder()
                .addPreStepHook((ctx, next) -> new PreStepDecision.Reject())
                .addPreStepHook((ctx, next) -> {
                    innerReached.incrementAndGet();
                    return next.proceed();
                })
                .build();

        var decision = hooks.dispatchPreStep(
                new PreStepHook.PreStepContext(AGENT, 1, 1, List.of(), new dev.duo.core.session.Session(AGENT)),
                () -> {
                    innerReached.incrementAndGet();
                    return new PreStepDecision.Reject();
                });

        assertTrue(decision instanceof PreStepDecision.Reject);
        assertEquals(0, innerReached.get(), "外层不调 next 时，内层与内置行为都不应执行");
    }

    @Test
    void doubleProceedThrowsIllegalState() {
        var hooks = AgentHooks.builder()
                .addRequestHook((ctx, next) -> {
                    next.proceed();
                    return next.proceed();
                })
                .build();

        assertThrows(IllegalStateException.class, () -> hooks.dispatchRequest(
                new RequestHook.RequestContext(AGENT, 1, 1),
                () -> new GenerateOptions("p", "m", List.of(), null, null)));
    }

    @Test
    void innerRewriteVisibleToOuterReturn() throws Exception {
        var hooks = AgentHooks.builder()
                .addToolHook((ctx, next) -> {
                    var result = next.proceed();
                    return new ToolExecutionResult("outer-saw: " + result.content().size() + " blocks");
                })
                .build();

        var result = hooks.dispatchTool(
                new ToolExecutionHook.ToolCallContext(AGENT, 1, 1,
                        new CallId("c1"), "echo", Map.of(), new CancellationSignal()),
                () -> new ToolExecutionResult("tool-body"));

        var firstBlock = assertInstanceOf(ContentBlock.Text.class, result.content().getFirst());
        assertTrue(firstBlock.text().startsWith("outer-saw:"),
                "外层应看到内置行为的产物");
    }

    @Test
    void threeLayerChainWrapsReturnInReverseOrder() throws Exception {
        var order = new ArrayList<String>();
        var hooks = AgentHooks.builder()
                .addRequestHook((ctx, next) -> {
                    order.add("outer-enter");
                    var options = next.proceed();
                    order.add("outer-exit");
                    return withSystem(options, options.system() + "+outer");
                })
                .addRequestHook((ctx, next) -> {
                    order.add("middle-enter");
                    var options = next.proceed();
                    order.add("middle-exit");
                    return withSystem(options, options.system() + "+middle");
                })
                .addRequestHook((ctx, next) -> {
                    order.add("inner-enter");
                    return next.proceed();
                })
                .build();

        var result = hooks.dispatchRequest(new RequestHook.RequestContext(AGENT, 1, 1),
                () -> new GenerateOptions("p", "m", List.of(), "BASE", null));

        assertEquals("BASE+middle+outer", result.system(),
                "内置行为最内层执行，透传层不改写，包装层由内向外依次包装");
        assertEquals(List.of("outer-enter", "middle-enter", "inner-enter",
                "middle-exit", "outer-exit"), order,
                "进入顺序 = 注册序，退出顺序 = 逆序（洋葱模型；inner 为透传层无 exit 标记）");
    }

    @Test
    void hookExceptionPropagatesOutOfDispatch() {
        var hooks = AgentHooks.builder()
                .addRequestHook((ctx, next) -> {
                    throw new IllegalStateException("hook 内部错误");
                })
                .build();

        var error = assertThrows(IllegalStateException.class, () -> hooks.dispatchRequest(
                new RequestHook.RequestContext(AGENT, 1, 1),
                () -> new GenerateOptions("p", "m", List.of(), null, null)));
        assertEquals("hook 内部错误", error.getMessage(), "决策型 hook 异常必须原样传播（fail loud）");
    }

    @Test
    void builderRejectsNullHook() {
        assertThrows(NullPointerException.class,
                () -> AgentHooks.builder().addPreStepHook(null));
        assertThrows(NullPointerException.class,
                () -> AgentHooks.builder().addRequestHook(null));
        assertThrows(NullPointerException.class,
                () -> AgentHooks.builder().addRequestErrorHook(null));
        assertThrows(NullPointerException.class,
                () -> AgentHooks.builder().addToolHook(null));
    }

    private static GenerateOptions withSystem(GenerateOptions options, String system) {
        return new GenerateOptions(options.provider(), options.model(),
                options.messages(), system, options.tools());
    }
}
