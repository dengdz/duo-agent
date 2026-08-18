package dev.duo.core.llm;

import dev.duo.api.agent.RequestErrorAction;
import dev.duo.api.hook.RequestErrorHook;
import dev.duo.model.llm.LlmFailure;
import dev.duo.model.session.SessionId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 内置的模型请求重试 hook：指数退避 + 抖动，尊重提供方 Retry-After。
 * <p>
 * 对应 TS 源码中的 {@code dsh-llm-retry} 插件（挂在 {@code agent/request-error}
 * waterfall 上）：适配器只报告结构化失败，重试在循环扩展点执行。
 * 重试计数按 (agent, turn, step) 隔离——同一 step 的连续失败累计，
 * step 推进后自然重置；可重试的失败为 HTTP 5xx/429、超时与传输错误。
 * </p>
 *
 * <p>生命周期说明：内部按 agentId 维护计数条目，条目随 agent 存活；
 * 大量短生命周期 agent 共享同一实例时请为每批 agent 创建新实例，
 * 避免计数条目累积。</p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public final class LlmRetryHook implements RequestErrorHook {

    private static final Logger logger = LoggerFactory.getLogger(LlmRetryHook.class);

    /** 可重试的失败 code（无 HTTP 状态码时按 code 判定）。 */
    private static final Set<String> RETRYABLE_CODES = Set.of("TIMEOUT", "TRANSPORT");

    /** 默认重试参数：最多 3 次、500ms 起步、2 倍指数退避。 */
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final Duration DEFAULT_INITIAL_DELAY = Duration.ofMillis(500);
    private static final double DEFAULT_BACKOFF_MULTIPLIER = 2.0;

    private final int maxAttempts;
    private final Duration initialDelay;
    private final double multiplier;

    /** 每个 agent 的连续失败计数；key 变化即重置。 */
    private final Map<SessionId, AttemptState> attempts = new ConcurrentHashMap<>();

    public LlmRetryHook() {
        this(DEFAULT_MAX_ATTEMPTS, DEFAULT_INITIAL_DELAY, DEFAULT_BACKOFF_MULTIPLIER);
    }

    public LlmRetryHook(int maxAttempts, Duration initialDelay, double multiplier) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts 必须 >= 1");
        }
        Objects.requireNonNull(initialDelay, "initialDelay must not be null");
        this.maxAttempts = maxAttempts;
        this.initialDelay = initialDelay;
        this.multiplier = multiplier;
    }

    @Override
    public RequestErrorAction onRequestError(RequestErrorContext context, Chain next) throws Exception {
        var failure = context.failure();
        var attempt = recordAttempt(context);
        if (attempt <= maxAttempts && isRetryable(failure)) {
            var delay = delayFor(failure, attempt);
            logger.warn("Agent {} turn {} step {} 请求失败（code: {}, status: {}），第 {}/{} 次重试，等待 {}ms",
                    context.agentId(), context.turn(), context.step(),
                    failure.code(), failure.status(), attempt, maxAttempts, delay.toMillis());
            Thread.sleep(delay.toMillis());
            return new RequestErrorAction.Retry();
        }
        return next.proceed();
    }

    /** 记录连续失败次数：同一 (turn, step) 累计，key 变化即从 1 重新计。 */
    private int recordAttempt(RequestErrorContext context) {
        var key = new StepKey(context.turn(), context.step());
        var state = attempts.compute(context.agentId(), (id, prev) -> {
            if (prev != null && prev.key().equals(key)) {
                return new AttemptState(key, prev.count() + 1);
            }
            return new AttemptState(key, 1);
        });
        return state.count();
    }

    /** 计算退避延迟：指数退避 + 随机抖动，不小于提供方 Retry-After。 */
    private Duration delayFor(LlmFailure failure, int attempt) {
        var base = initialDelay.toMillis() * Math.pow(multiplier, attempt - 1);
        var jitter = ThreadLocalRandom.current().nextDouble(0.5, 1.5);
        var delay = Math.round(base * jitter);
        if (failure.providerRetryAfterMs() != null) {
            delay = Math.max(delay, failure.providerRetryAfterMs());
        }
        return Duration.ofMillis(delay);
    }

    /** 判定失败是否可重试：HTTP 5xx/429，或超时/传输错误。 */
    private static boolean isRetryable(LlmFailure failure) {
        if (failure.status() != null) {
            return failure.status() >= 500 || failure.status() == 429;
        }
        return RETRYABLE_CODES.contains(failure.code());
    }

    /** step 定位 key。 */
    private record StepKey(int turn, int step) {}

    /** 连续失败计数状态。 */
    private record AttemptState(StepKey key, int count) {}
}
