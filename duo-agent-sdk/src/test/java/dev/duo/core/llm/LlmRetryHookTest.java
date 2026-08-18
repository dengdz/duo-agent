package dev.duo.core.llm;

import dev.duo.api.agent.RequestErrorAction;
import dev.duo.api.hook.RequestErrorHook;
import dev.duo.model.llm.LlmFailure;
import dev.duo.model.session.SessionId;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LlmRetryHook} 的策略单元测试。
 * <p>
 * 覆盖：可重试判定（code/status）、尝试上限、按 step 重置计数、尊重 Retry-After。
 * 退避时长使用 1ms 级配置，保证测试快速且确定。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
class LlmRetryHookTest {

    private static final SessionId AGENT = new SessionId("retry-hook-test");

    private static RequestErrorHook.RequestErrorContext context(int step, LlmFailure failure) {
        return new RequestErrorHook.RequestErrorContext(AGENT, 1, step, failure);
    }

    private static RequestErrorAction fail() {
        return new RequestErrorAction.Fail();
    }

    @Test
    void nonRetryableAuthFailureDelegatesImmediately() throws Exception {
        var hook = new LlmRetryHook(3, Duration.ofMillis(1), 2.0);
        // 401 认证失败不可重试：应立即委托（Fail），不消耗等待
        var failure = new LlmFailure("认证失败", "HTTP_401", 401, null, null);
        var action = hook.onRequestError(context(1, failure), LlmRetryHookTest::fail);
        assertInstanceOf(RequestErrorAction.Fail.class, action, "401 不应重试");
    }

    @Test
    void retriesUntilMaxAttemptsThenFails() throws Exception {
        var hook = new LlmRetryHook(2, Duration.ofMillis(1), 2.0);
        var failure = new LlmFailure("服务端错误", "TRANSPORT");

        // 同一 step 连续失败：前 maxAttempts 次 Retry，之后 Fail
        assertInstanceOf(RequestErrorAction.Retry.class,
                hook.onRequestError(context(1, failure), LlmRetryHookTest::fail));
        assertInstanceOf(RequestErrorAction.Retry.class,
                hook.onRequestError(context(1, failure), LlmRetryHookTest::fail));
        assertInstanceOf(RequestErrorAction.Fail.class,
                hook.onRequestError(context(1, failure), LlmRetryHookTest::fail));
    }

    @Test
    void attemptCountResetsOnNewStep() throws Exception {
        var hook = new LlmRetryHook(1, Duration.ofMillis(1), 2.0);
        var failure = new LlmFailure("服务端错误", "TRANSPORT");

        // step 1 用尽额度
        assertInstanceOf(RequestErrorAction.Retry.class,
                hook.onRequestError(context(1, failure), LlmRetryHookTest::fail));
        assertInstanceOf(RequestErrorAction.Fail.class,
                hook.onRequestError(context(1, failure), LlmRetryHookTest::fail));
        // step 2 计数自然重置
        assertInstanceOf(RequestErrorAction.Retry.class,
                hook.onRequestError(context(2, failure), LlmRetryHookTest::fail));
    }

    @Test
    void providerRetryAfterIsHonored() throws Exception {
        var hook = new LlmRetryHook(3, Duration.ofMillis(1), 2.0);
        var failure = new LlmFailure("限流", "HTTP_429", 429, 5L, null);

        long startNanos = System.nanoTime();
        var action = hook.onRequestError(context(1, failure), LlmRetryHookTest::fail);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        assertInstanceOf(RequestErrorAction.Retry.class, action, "429 应重试");
        assertTrue(elapsedMillis >= 5, "退避等待不应短于提供方 Retry-After（实际 " + elapsedMillis + "ms）");
    }
}
