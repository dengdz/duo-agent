package dev.duo.core.flow;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通用冷发布者：虚拟线程驱动 + 有界缓冲 + 背压。
 * <p>
 * 适用于「一次阻塞式驱动、产出连续增量」的场景——Agent 的 session 事件流
 * 与 Model 的原生 chunk 流共用本机制。语义：
 * <ul>
 *   <li><b>冷</b> - subscribe 时才启动驱动线程，构造不发起任何工作</li>
 *   <li><b>单订阅</b> - 仅支持订阅一次，重复订阅收到 onError</li>
 *   <li><b>背压</b> - 驱动方是推式回调，request(n) 控制不了生产节奏，
 *       消费慢于生产时元素在内部缓冲，溢出以 onError 终止订阅
 *       （底层工作继续执行完毕，仅停止推送）</li>
 *   <li><b>终态旁路</b> - 完成/失败信号不受背压限制，无 demand 时也立即派发；
 *       缓冲满时终态清空积压直达，不被误报为消费过慢
 *       （Reactive Streams 3.5 精神）</li>
 *   <li><b>订阅者约束</b> - 派发在内部锁内串行进行，订阅者回调不得阻塞
 *       （尤其不得等待另一线程对本流的 request/cancel），否则会死锁；
 *       回调抛异常视为违约，订阅终止且不再收到后续信号
 *       （Reactive Streams 2.2/2.13 的实现侧兜底）</li>
 * </ul>
 * </p>
 *
 * @param <T> 推送给订阅者的元素类型
 * @author zhangyl
 * @date 2026-08-20
 */
public final class BufferedPublisher<T> implements Flow.Publisher<T> {

    /**
     * 驱动函数：在专用虚拟线程上执行，通过 {@link Emitter} 推送元素与终态。
     * <p>
     * <b>驱动负责显式发出终态</b>（complete/fail）——底层工作可能是异步的
     * （如适配器的 sendAsync 回调），驱动线程提前返回不代表完成。
     * 驱动抛出异常视为失败。emit 可能被驱动线程之外的线程调用
     * （如事件广播回调），实现保证线程安全。
     * </p>
     */
    @FunctionalInterface
    public interface Driver<T> {
        void drive(Emitter<T> emitter) throws Exception;
    }

    /** 元素与终态的推送入口，实现保证线程安全。 */
    public interface Emitter<T> {
        /** 推送一个元素；订阅已取消或已终态时静默丢弃。 */
        void emit(T item);

        /** 发出完成信号（恰好一次语义由实现保证）。 */
        void complete();

        /** 发出失败信号（恰好一次语义由实现保证）。 */
        void fail(Throwable error);
    }

    /**
     * 内部缓冲上限：订阅者不消费（不 request）时最多缓存这么多元素，
     * 溢出即以 onError 终止订阅——防止全量透传场景（推理模型思考痕迹
     * 可达数千 chunk）下慢消费者导致整轮元素驻留内存。
     */
    private static final int MAX_BUFFERED_ITEMS = 8192;

    private final String threadName;
    private final Driver<T> driver;
    private final AtomicBoolean subscribedOnce = new AtomicBoolean();

    /**
     * @param threadName 驱动虚拟线程名（诊断用）
     * @param driver     驱动函数
     */
    public BufferedPublisher(String threadName, Driver<T> driver) {
        this.threadName = Objects.requireNonNull(threadName, "threadName 不能为 null");
        this.driver = Objects.requireNonNull(driver, "driver 不能为 null");
    }

    @Override
    public void subscribe(Flow.Subscriber<? super T> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber 不能为 null");
        // 单订阅：重复订阅无法共享同一次底层工作
        if (!subscribedOnce.compareAndSet(false, true)) {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    // no-op：即将进入终态
                }

                @Override
                public void cancel() {
                    // no-op
                }
            });
            subscriber.onError(new IllegalStateException(
                    "该 Publisher 仅支持订阅一次，请重新调用获取新的 Publisher"));
            return;
        }
        new SubscriptionImpl(subscriber).start();
    }

    /** 单个订阅的状态机：缓冲 + 背压 + 终态信号。 */
    private final class SubscriptionImpl implements Flow.Subscription {

        /** 完成哨兵（正常终态）。 */
        private static final Object TERMINAL = new Object();

        private final Flow.Subscriber<? super T> subscriber;
        /** 元素为 T、TERMINAL（完成）或 StreamError（失败）；有界，防慢消费者内存放大。 */
        private final BlockingQueue<Object> buffer = new LinkedBlockingQueue<>(MAX_BUFFERED_ITEMS);
        private final AtomicLong demand = new AtomicLong();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        /** 终态（onComplete/onError）是否已发出，保证恰好一次。 */
        private volatile boolean terminated;
        /** drain 派发进行中标志（guarded by this）：订阅者在 onNext 内同步调用 request() 时阻止重入派发。 */
        private boolean draining;

        SubscriptionImpl(Flow.Subscriber<? super T> subscriber) {
            this.subscriber = subscriber;
        }

        void start() {
            subscriber.onSubscribe(this);
            // 虚拟线程驱动底层工作（与 ReactLoopAgent 内部驱动方式一致）
            Thread.ofVirtual().name(threadName).start(() -> {
                try {
                    driver.drive(new Emitter<T>() {
                        @Override
                        public void emit(T item) {
                            SubscriptionImpl.this.emit(item);
                        }

                        @Override
                        public void complete() {
                            SubscriptionImpl.this.emit(TERMINAL);
                        }

                        @Override
                        public void fail(Throwable error) {
                            // null 错误归一化：onError(null) 违反 Reactive Streams 非空信号规则
                            SubscriptionImpl.this.emit(new StreamError(
                                    error != null ? error
                                            : new IllegalArgumentException("fail 参数不能为 null")));
                        }
                    });
                } catch (Throwable error) {
                    SubscriptionImpl.this.emit(new StreamError(error));
                }
            });
        }

        private void emit(Object item) {
            if (cancelled.get() || item == null) {
                return;
            }
            // 慢消费者缓冲溢出。终态例外：完成/失败信号不受缓冲上限约束
            // （否则成功完成的流会被误报为消费过慢失败），满时清空积压直接入队
            if (!buffer.offer(item)) {
                if (item == TERMINAL || item instanceof StreamError) {
                    buffer.clear();
                    buffer.offer(item);
                    drain();
                    return;
                }
                cancelled.set(true);
                synchronized (this) {
                    if (!terminated) {
                        terminated = true;
                        subscriber.onError(new IllegalStateException(
                                "订阅者消费过慢：内部缓冲达到上限 " + MAX_BUFFERED_ITEMS
                                        + " 个元素，订阅已终止（请增大 request 批量或及时消费）"));
                    }
                    buffer.clear();
                }
                return;
            }
            drain();
        }

        /**
         * 串行派发：synchronized 保证 onNext/onComplete/onError 不并发。
         * synchronized 是可重入锁，订阅者在 onNext 内同步调用 request() 会重入本方法——
         * 由 {@code draining} 标志挡回（否则积压大时嵌套 onNext 会耗尽栈），
         * 新增需求由外层 while 循环每轮重新读取 demand 继续派发。
         */
        private void drain() {
            synchronized (this) {
                if (terminated || cancelled.get()) {
                    buffer.clear();
                    return;
                }
                if (draining) {
                    return;
                }
                draining = true;
                try {
                    while (true) {
                        // 订阅者可能在派发过程中（如 onNext 内）cancel：
                        // 每轮检查，cancel 后不再发出任何信号
                        if (cancelled.get()) {
                            buffer.clear();
                            return;
                        }
                        var item = demand.get() > 0 ? buffer.poll() : peekTerminal();
                        if (item == null) {
                            return;
                        }
                        if (item == TERMINAL) {
                            terminated = true;
                            safeSignal(() -> subscriber.onComplete());
                            return;
                        }
                        if (item instanceof StreamError error) {
                            terminated = true;
                            safeSignal(() -> subscriber.onError(error.cause()));
                            return;
                        }
                        demand.decrementAndGet();
                        @SuppressWarnings("unchecked")
                        T typedItem = (T) item;
                        try {
                            subscriber.onNext(typedItem);
                        } catch (Throwable subscriberFailure) {
                            // 订阅者回调抛异常属违约（Reactive Streams 2.13）：
                            // 终止订阅并停止派发，不再向已损坏的订阅者发信号
                            terminated = true;
                            cancelled.set(true);
                            buffer.clear();
                            return;
                        }
                    }
                } finally {
                    draining = false;
                }
            }
        }

        /** 终态信号同样可能因订阅者违约抛出，吞掉以免异常逃逸打断驱动线程的清理路径。 */
        private void safeSignal(Runnable signal) {
            try {
                signal.run();
            } catch (Throwable ignored) {
                cancelled.set(true);
            }
        }

        /** 无 demand 时终态信号（完成/错误）不受背压限制，仍需立即派发。 */
        private Object peekTerminal() {
            var head = buffer.peek();
            return head == TERMINAL || head instanceof StreamError ? buffer.poll() : null;
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                // Reactive Streams 规范 3.9：非正数 request 是协议违规。
                // 与其它终态路径一致：锁内先置位 terminated，保证 onError 恰好一次且不与 onNext 并发
                synchronized (this) {
                    if (!terminated) {
                        terminated = true;
                        subscriber.onError(new IllegalArgumentException(
                                "request 参数必须为正数（Reactive Streams 3.9），当前: " + n));
                    }
                }
                cancel();
                return;
            }
            // 溢出时饱和为 Long.MAX_VALUE（视为无限需求）
            demand.accumulateAndGet(n, (current, add) ->
                    current > Long.MAX_VALUE - add ? Long.MAX_VALUE : current + add);
            drain();
        }

        @Override
        public void cancel() {
            cancelled.set(true);
            synchronized (this) {
                buffer.clear();
            }
        }
    }

    /** 流内失败信号载体。 */
    private record StreamError(Throwable cause) {
    }
}
