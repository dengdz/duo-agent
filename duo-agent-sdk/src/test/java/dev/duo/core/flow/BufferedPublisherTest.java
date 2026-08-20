package dev.duo.core.flow;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;/**
 * {@link BufferedPublisher} 的派发契约测试。
 * <p>
 * 聚焦两个易错点：订阅者在 onNext 内同步 request 的重入语义
 * （draining 保护），以及 request 非正数时 onError 的恰好一次语义。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-20
 */
class BufferedPublisherTest {

    @Test
    void shouldNotNestOnNextWhenSubscriberRequestsSynchronously() throws InterruptedException {
        int total = 200;
        var received = new AtomicInteger();
        var depth = new AtomicInteger();
        var maxDepth = new AtomicInteger();
        var done = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var subscriptionRef = new AtomicReference<Flow.Subscription>();

        var publisher = new BufferedPublisher<String>("nested-request-test", emitter -> {
            for (int i = 1; i <= total; i++) {
                emitter.emit("item-" + i);
            }
            emitter.complete();
        });

        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription s) {
                subscriptionRef.set(s);
                s.request(1);
            }

            @Override
            public void onNext(String item) {
                // depth 在 request 之前递增、之后递减：
                // 若 drain 无重入保护，request 会同步重入派发下一个元素，内层 depth 递增到 2
                int current = depth.incrementAndGet();
                maxDepth.accumulateAndGet(current, Math::max);
                received.incrementAndGet();
                subscriptionRef.get().request(1);
                depth.decrementAndGet();
            }

            @Override
            public void onError(Throwable t) {
                failure.set(t);
                done.countDown();
            }

            @Override
            public void onComplete() {
                done.countDown();
            }
        });

        assertTrue(done.await(5, TimeUnit.SECONDS), "事件流应在超时内到达终态");
        assertNull(failure.get());
        assertEquals(total, received.get(), "应串行收到全部元素");
        assertEquals(1, maxDepth.get(), "onNext 不得嵌套：订阅者同步 request 时应返回外层循环继续派发");
    }

    @Test
    void shouldEmitOnErrorExactlyOnceWhenRequestNonPositive() throws InterruptedException {
        var errors = new AtomicInteger();
        var completed = new AtomicInteger();
        var done = new CountDownLatch(1);
        var subscriptionRef = new AtomicReference<Flow.Subscription>();

        // 驱动不发任何信号：终态只能来自 request(0) 的协议违规路径
        var publisher = new BufferedPublisher<String>("nonpositive-request-test", emitter -> {
        });

        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription s) {
                subscriptionRef.set(s);
            }

            @Override
            public void onNext(String item) {
            }

            @Override
            public void onError(Throwable t) {
                errors.incrementAndGet();
                done.countDown();
            }

            @Override
            public void onComplete() {
                completed.incrementAndGet();
            }
        });

        var subscription = subscriptionRef.get();
        subscription.request(0);  // 第一次：onError 在 request 调用栈内同步发出
        subscription.request(0);  // 第二次：terminated 已置位，不得再发
        subscription.request(1);  // 终态后的 request 无任何信号

        assertTrue(done.await(5, TimeUnit.SECONDS), "request(0) 应同步发出 onError");
        assertEquals(1, errors.get(), "onError 必须恰好一次");
        assertEquals(0, completed.get(), "终态已发出后不得再派发 onComplete");
    }

    @Test
    void shouldDeliverTerminalEvenWhenBufferIsFull() throws InterruptedException {
        var done = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();

        // 订阅者从不 request：emit 填满整个缓冲（上限 8192）后 complete
        var publisher = new BufferedPublisher<String>("full-buffer-terminal-test", emitter -> {
            for (int i = 0; i < 8192; i++) {
                emitter.emit("item-" + i);
            }
            emitter.complete();
        });

        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription s) {
                // 不 request：制造满缓冲场景
            }

            @Override
            public void onNext(String item) {
            }

            @Override
            public void onError(Throwable t) {
                failure.set(t);
                done.countDown();
            }

            @Override
            public void onComplete() {
                done.countDown();
            }
        });

        assertTrue(done.await(5, TimeUnit.SECONDS), "终态应在缓冲满时仍可送达");
        assertNull(failure.get(), "缓冲满时的完成信号不得被误报为消费过慢失败");
    }

    @Test
    void shouldStopDispatchingAfterCancelInsideOnNext() throws InterruptedException {
        var received = new AtomicInteger();
        var terminalSignals = new AtomicInteger();
        var subscriptionRef = new AtomicReference<Flow.Subscription>();

        var publisher = new BufferedPublisher<String>("cancel-inside-onnext-test", emitter -> {
            for (int i = 0; i < 1000; i++) {
                emitter.emit("item-" + i);
            }
            emitter.complete();
        });

        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription s) {
                subscriptionRef.set(s);
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(String item) {
                if (received.incrementAndGet() == 3) {
                    subscriptionRef.get().cancel();  // 派发过程中取消
                }
            }

            @Override
            public void onError(Throwable t) {
                terminalSignals.incrementAndGet();
            }

            @Override
            public void onComplete() {
                terminalSignals.incrementAndGet();
            }
        });

        // cancel 后不得再派发元素或终态信号（Reactive Streams 1.8）
        Thread.sleep(300);
        assertTrue(received.get() <= 4, "cancel 后不得继续派发，实际收到: " + received.get());
        assertEquals(0, terminalSignals.get(), "cancel 后不得收到任何终态信号");
    }

    @Test
    void shouldTerminateSilentlyWhenSubscriberOnNextThrows() throws InterruptedException {
        var received = new AtomicInteger();
        var terminalSignals = new AtomicInteger();
        var subscriptionRef = new AtomicReference<Flow.Subscription>();

        var publisher = new BufferedPublisher<String>("throwing-subscriber-test", emitter -> {
            for (int i = 1; i <= 100; i++) {
                emitter.emit("item-" + i);
            }
            emitter.complete();
        });

        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription s) {
                subscriptionRef.set(s);
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(String item) {
                if (received.incrementAndGet() == 2) {
                    // 模拟订阅者违约：回调抛异常
                    throw new RuntimeException("订阅者回调异常");
                }
            }

            @Override
            public void onError(Throwable t) {
                terminalSignals.incrementAndGet();
            }

            @Override
            public void onComplete() {
                terminalSignals.incrementAndGet();
            }
        });

        // 违约订阅者：派发静默终止，不再收到元素与任何终态信号，
        // 异常也不得逃逸打断驱动线程
        Thread.sleep(300);
        assertEquals(2, received.get(), "违约后不再派发元素");
        assertEquals(0, terminalSignals.get(), "不再向已损坏的订阅者发终态信号");
    }
}
