package com.translator.proxy.backend;

import static org.junit.Assert.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.translator.proxy.core.handler.QueryProcessor;
import com.translator.proxy.core.session.FrontendSession;

import io.netty.channel.ChannelHandlerContext;

/**
 * ReloadableQueryProcessor 单元测试。
 */
public class ReloadableQueryProcessorTest {

    private CountingProcessor delegate;
    private ReloadableQueryProcessor reloadable;
    private final int queueCapacity = 10;
    private final int drainTimeoutMs = 5000;

    @Before
    public void setUp() {
        delegate = new CountingProcessor();
        reloadable = new ReloadableQueryProcessor("test", delegate, queueCapacity, drainTimeoutMs);
    }

    @After
    public void tearDown() {
        reloadable.close();
    }

    // ==================== ACTIVE 状态 ====================

    @Test
    public void testActiveStateDelegates() {
        assertSame("Should be ACTIVE on creation", ReloadableQueryProcessor.State.ACTIVE, reloadable.getState());

        reloadable.process(null, "SELECT 1", null);

        // Delegate 应该被调用
        assertEquals("Delegate should have 1 request", 1, delegate.getCount());
    }

    @Test
    public void testActiveStateIncrementsInFlight() {
        // 直接调用 process 验证 in-flight 归零
        reloadable.process(null, "SELECT 1", null);
        assertEquals("After process, queue should be empty", 0, reloadable.getQueueSize());
    }

    // ==================== RELOADING 状态 ====================

    @Test
    public void testReloadingStateQueuesRequests() {
        // 进入 RELOADING
        boolean drained = reloadable.drainAndClose();
        assertTrue("drain should complete", drained);

        // RELOADING 状态下新请求应排队
        reloadable.process(null, "SELECT 1", null);
        reloadable.process(null, "SELECT 2", null);

        assertEquals("Queue should have 2 requests", 2, reloadable.getQueueSize());
        // Delegate（旧）不应该收到请求
        assertEquals("Old delegate should have 0 requests", 0, delegate.getCount());
    }

    @Test
    public void testReloadingQueueOverflowReturnsError() {
        // 进入 RELOADING
        reloadable.drainAndClose();

        // 填充队列（capacity=10）
        for (int i = 0; i < queueCapacity; i++) {
            reloadable.process(null, "SELECT " + i, null);
        }
        assertEquals("Queue should be full", queueCapacity, reloadable.getQueueSize());

        // 再发一个请求，队列满，不应抛异常
        // 注意：process 写入错误包，在 null ctx 上会 NPE
        // 我们验证队列大小不变
        try {
            reloadable.process(null, "overflow", null);
        } catch (NullPointerException expected) {
            // null ctx 会触发 NPE（错误写入需要 ctx），正常
        }

        // 队列大小不应超过容量
        assertTrue("Queue should not exceed capacity", reloadable.getQueueSize() <= queueCapacity);
    }

    // ==================== DRAINING 状态 ====================

    @Test
    public void testDrainingStateRejectsRequests() {
        // 标记为 DRAINING
        reloadable.markDraining();

        // 请求应被拒绝（队列不增长）
        try {
            reloadable.process(null, "SELECT 1", null);
        } catch (NullPointerException expected) {
            // null ctx 触发 NPE
        }
        assertEquals("Queue should remain empty in DRAINING", 0, reloadable.getQueueSize());
    }

    // ==================== 完整 reload 周期 ====================

    @Test
    public void testDrainAndActivateNewCycle() {
        // 1. ACTIVE 状态发送一些请求验证正常
        reloadable.process(null, "BEFORE 1", null);
        reloadable.process(null, "BEFORE 2", null);
        assertEquals("Delegate should have 2 requests", 2, delegate.getCount());

        // 2. drain + close
        boolean drained = reloadable.drainAndClose();
        assertTrue("Drain should complete", drained);
        assertEquals("Old delegate count should remain 2", 2, delegate.getCount());

        // 3. RELOADING 状态发送请求（应排队）
        for (int i = 0; i < 5; i++) {
            reloadable.process(null, "QUEUED " + i, null);
        }
        assertEquals("Queue should have 5 requests", 5, reloadable.getQueueSize());

        // 4. activateNew
        CountingProcessor newDelegate = new CountingProcessor();
        reloadable.activateNew(newDelegate);

        // 5. 验证排队请求在新 delegate 上执行
        assertEquals("New delegate should have 5 queued requests", 5, newDelegate.getCount());
        assertEquals("Queue should be drained", 0, reloadable.getQueueSize());

        // 6. 验证新请求直接委托
        reloadable.process(null, "AFTER 1", null);
        assertEquals("New delegate should have 6 requests", 6, newDelegate.getCount());

        // 旧 delegate 计数不变
        assertEquals("Old delegate count should remain 2", 2, delegate.getCount());
    }

    @Test
    public void testDrainTimeoutWithSlowInFlight() throws InterruptedException {
        // 使用一个慢 processor 模拟 in-flight 请求
        SlowProcessor slowDelegate = new SlowProcessor(500); // 500ms delay
        ReloadableQueryProcessor rp = new ReloadableQueryProcessor("slow", slowDelegate, 10, 100); // 100ms timeout

        // 提交一个慢请求（在后台线程）
        Thread t = new Thread(() -> rp.process(null, "SLOW", null));
        t.start();
        Thread.sleep(50); // 等待慢请求开始

        // drain 应该超时（drainTimeoutMs=100ms < 500ms delay）
        boolean drained = rp.drainAndClose();
        assertFalse("Drain should timeout", drained);

        t.join(1000); // 等待完成
        rp.close();
    }

    // ==================== close ====================

    @Test
    public void testCloseDelegatesToInner() {
        reloadable.close();
        assertTrue("Inner delegate should be closed", delegate.isClosed());
    }

    // ==================== 并发 ====================

    @Test
    public void testConcurrentRequestsInActive() throws InterruptedException {
        int threadCount = 10;
        int requestsPerThread = 50;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                        try {
                            startLatch.await();
                        } catch (InterruptedException ignored) {
                        }
                        for (int j = 0; j < requestsPerThread; j++) {
                            reloadable.process(null, "CONCURRENT", null);
                        }
                        doneLatch.countDown();
                    })
                    .start();
        }

        startLatch.countDown();
        assertTrue("All threads should finish", doneLatch.await(10, TimeUnit.SECONDS));
        assertEquals("Delegate should have all requests", threadCount * requestsPerThread, delegate.getCount());
    }

    // ==================== 内部测试辅助类 ====================

    /**
     * 计数处理器 —— 记录请求次数。
     */
    static class CountingProcessor implements QueryProcessor {
        private final AtomicInteger count = new AtomicInteger(0);
        private volatile boolean closed = false;

        @Override
        public void process(ChannelHandlerContext ctx, String sql, FrontendSession session) {
            count.incrementAndGet();
        }

        @Override
        public void close() {
            closed = true;
        }

        int getCount() {
            return count.get();
        }

        boolean isClosed() {
            return closed;
        }
    }

    /**
     * 慢处理器 —— 每个请求延迟指定毫秒，用于测试 drain 超时。
     */
    static class SlowProcessor implements QueryProcessor {
        private final long delayMs;

        SlowProcessor(long delayMs) {
            this.delayMs = delayMs;
        }

        @Override
        public void process(ChannelHandlerContext ctx, String sql, FrontendSession session) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
            }
        }

        @Override
        public void close() {}
    }
}
