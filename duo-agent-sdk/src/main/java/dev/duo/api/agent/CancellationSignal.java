package dev.duo.api.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * turn 级取消信号：一次 {@link Agent#cancel} 的语义权威。
 * <p>
 * 一个 turn 一个实例，随调用链显式传参（不使用 ThreadLocal——其生命周期无法
 * 与 turn 边界对齐）。{@link #abort(AgentCancelCause)} 首写固化：第一次取消
 * 原因胜出，后续调用 no-op，保证收尾归因唯一。
 * </p>
 * <p>
 * 与 {@code Thread.interrupt()} 分工：interrupt 即时唤醒阻塞原语（无语义），
 * 本信号承载取消原因与检查点；被 interrupt 唤醒的代码凭 {@link #isCancelled()}
 * 区分「用户取消」与「意外中断」。
 * </p>
 * <p>
 * <b>线程安全</b>：全部公开方法可被任意线程并发调用。监听器在触发
 * {@code abort} 的线程上同步执行（保证断连/杀进程的即时性），单个监听器
 * 异常只记 WARN 不传播——取消本身不得因监听器失败而失败；同一监听器保证
 * 恰好触发一次（注册与取消并发时由 CAS 裁决）。监听器实现应尽量幂等。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-21
 */
public final class CancellationSignal {

    private static final Logger logger = LoggerFactory.getLogger(CancellationSignal.class);

    private final AtomicReference<AgentCancelCause> cause = new AtomicReference<>();
    private final CopyOnWriteArrayList<Registration> listeners = new CopyOnWriteArrayList<>();

    /** 触发取消：置入原因（首写胜出）并同步执行全部监听器。已取消时 no-op。 */
    public boolean abort(AgentCancelCause cancelCause) {
        Objects.requireNonNull(cancelCause, "cancelCause must not be null");
        if (!cause.compareAndSet(null, cancelCause)) {
            return false;
        }
        for (var registration : listeners) {
            fire(registration);
        }
        return true;
    }

    /** 是否已取消。 */
    public boolean isCancelled() {
        return cause.get() != null;
    }

    /** 取消原因；未取消时为 null。首写固化后不再变化。 */
    public AgentCancelCause cause() {
        return cause.get();
    }

    /**
     * 协作式检查点：已取消则抛出 {@link TurnCancelledException}。
     * <p>
     * 供长循环、非阻塞代码段在安全边界主动让出（阻塞原语由 interrupt 唤醒，
     * 不经过此方法）。
     * </p>
     */
    public void checkPoint() throws TurnCancelledException {
        var current = cause.get();
        if (current != null) {
            throw new TurnCancelledException(current);
        }
    }

    /**
     * 注册取消监听器（断连 HTTP 流、杀进程树等收尾动作）。
     * <p>
     * 注册时已取消则立即同步触发——监听器不得因注册晚于取消而丢失；
     * 这也意味着本方法可能执行耗时 IO，调用方不应持锁调用。
     * </p>
     *
     * @param listener 取消时执行的动作（应幂等）
     * @return 处置器：移除监听器（已触发过的监听器移除为 no-op）
     */
    public AutoCloseable addListener(Runnable listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        var registration = new Registration(listener);
        listeners.add(registration);
        if (isCancelled()) {
            fire(registration);
        }
        return () -> listeners.remove(registration);
    }

    private void fire(Registration registration) {
        if (!registration.fired.compareAndSet(false, true)) {
            return;
        }
        try {
            registration.action.run();
        } catch (RuntimeException e) {
            // 取消监听器（断连/杀进程）失败只降级不断送：WARN 单行摘要，
            // 堆栈留 DEBUG，取消流程必须继续
            logger.warn("取消监听器执行失败（忽略并继续取消）: {}", e.toString());
            logger.debug("取消监听器失败堆栈", e);
        }
    }

    /** 恰好触发一次的监听器注册项：CAS 裁决注册线程与 abort 线程的并发触发。 */
    private record Registration(Runnable action, AtomicBoolean fired) {
        Registration(Runnable action) {
            this(action, new AtomicBoolean());
        }
    }
}
