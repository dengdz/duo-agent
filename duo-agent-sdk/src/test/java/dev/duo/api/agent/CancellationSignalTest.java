package dev.duo.api.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CancellationSignal 单元测试。
 * <p>
 * 覆盖：首写固化、监听器恰好一次、注册时已取消的立即触发、
 * 监听器异常不传播、checkPoint 抛出、并发 abort。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-21
 */
class CancellationSignalTest {

    @Test
    void firstAbortWinsAndLaterAbortsAreNoOp() {
        var signal = new CancellationSignal();
        assertTrue(signal.abort(new AgentCancelCause.User()));
        assertFalse(signal.abort(new AgentCancelCause.Disposed()));
        assertInstanceOf(AgentCancelCause.User.class, signal.cause());
    }

    @Test
    void listenerFiresExactlyOnceOnAbort() throws Exception {
        var signal = new CancellationSignal();
        var fired = new AtomicInteger();
        try (var unlisten = signal.addListener(fired::incrementAndGet)) {
            assertEquals(0, fired.get());
            signal.abort(new AgentCancelCause.Hook("test"));
            assertEquals(1, fired.get());
        }
        // 移除后重复 abort 不再触发
        signal.abort(new AgentCancelCause.Disposed());
        assertEquals(1, fired.get());
    }

    @Test
    void listenerRegisteredAfterAbortFiresImmediately() throws Exception {
        var signal = new CancellationSignal();
        signal.abort(new AgentCancelCause.User());
        var fired = new AtomicInteger();
        try (var unlisten = signal.addListener(fired::incrementAndGet)) {
            assertEquals(1, fired.get(), "注册时已取消必须立即触发，断连不得丢失");
        }
    }

    @Test
    void listenerFailureDoesNotAbortTheAbort() {
        var signal = new CancellationSignal();
        var healthy = new AtomicInteger();
        signal.addListener(() -> { throw new IllegalStateException("监听器爆炸"); });
        signal.addListener(healthy::incrementAndGet);
        assertDoesNotThrow(() -> signal.abort(new AgentCancelCause.User()));
        assertTrue(signal.isCancelled(), "监听器失败不得让取消本身失败");
        assertEquals(1, healthy.get(), "一个监听器失败不阻断其余监听器");
    }

    @Test
    void concurrentAbortsFireListenerExactlyOnce() throws Exception {
        var signal = new CancellationSignal();
        var fired = new AtomicInteger();
        signal.addListener(fired::incrementAndGet);
        var threads = 8;
        var ready = new CountDownLatch(threads);
        var done = new CountDownLatch(threads);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    ready.countDown();
                    try {
                        ready.await();
                        signal.abort(new AgentCancelCause.User());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(5, TimeUnit.SECONDS));
        }
        assertEquals(1, fired.get(), "并发 abort 下监听器恰好触发一次");
    }

    @Test
    void checkPointPassesWhenNotCancelledAndThrowsWithCauseWhenCancelled() throws Exception {
        var signal = new CancellationSignal();
        assertDoesNotThrow(signal::checkPoint);
        var cause = new AgentCancelCause.Hook("policy");
        signal.abort(cause);
        var e = assertThrows(TurnCancelledException.class, signal::checkPoint);
        assertSame(cause, e.cancelCause());
    }

    @Test
    void multipleListenersAllFireInOrder() {
        var signal = new CancellationSignal();
        List<String> order = new CopyOnWriteArrayList<>();
        signal.addListener(() -> order.add("first"));
        signal.addListener(() -> order.add("second"));
        signal.abort(new AgentCancelCause.User());
        assertEquals(List.of("first", "second"), order);
    }
}
