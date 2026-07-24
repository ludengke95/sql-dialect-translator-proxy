package com.translator.proxy.backend;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.translator.proxy.core.handler.QueryProcessor;
import com.translator.proxy.core.session.FrontendSession;
import com.translator.proxy.metrics.ReloadMetrics;
import com.translator.proxy.protocol.mysql.codec.MySQLPacketEncoder;
import com.translator.proxy.protocol.mysql.result.MySQLResponseWriter;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

/**
 * 支持热 reload 的查询处理器包装器。
 *
 * <p>包装一个真实的 {@link QueryProcessor} delegate，
 * 通过状态机控制请求路由，实现后端配置变更时的优雅切换。
 *
 * <h3>状态机</h3>
 * <pre>
 *   ACTIVE ──drainAndClose()──▶ RELOADING ──activateNew()──▶ ACTIVE
 *      │                                                         │
 *      └──markDraining()──▶ DRAINING ──close()──▶ (removed)      │
 * </pre>
 *
 * <h3>各状态行为</h3>
 * <ul>
 *   <li><b>ACTIVE</b>：请求直接委托给内部 delegate 执行。</li>
 *   <li><b>RELOADING</b>：请求进入有界队列等待；队列满时返回
 *       MySQL ERR 1053（ER_SERVER_SHUTDOWN）。</li>
 *   <li><b>DRAINING</b>：直接拒绝请求，返回 ERR 1053。</li>
 * </ul>
 *
 * <p>线程安全：使用 AtomicReference 管理状态，AtomicInteger 跟踪 in-flight 请求数。
 */
public class ReloadableQueryProcessor implements QueryProcessor {

    private static final Logger log = LoggerFactory.getLogger(ReloadableQueryProcessor.class);

    /** 错误码：ER_SERVER_SHUTDOWN */
    private static final int ERR_SERVER_SHUTDOWN = 1053;

    private static final String SQL_STATE = "HY000";

    /** 状态枚举 */
    enum State {
        /** 正常运行，请求委托给 delegate */
        ACTIVE,
        /** 热 reload 中，新请求排队等待 */
        RELOADING,
        /** 后端被移除，拒绝所有请求 */
        DRAINING
    }

    /** 后端名称（日志用） */
    private final String backendName;

    /** 当前状态 */
    private final AtomicReference<State> state = new AtomicReference<>(State.ACTIVE);

    /** 被包装的真实处理器 */
    private volatile QueryProcessor delegate;

    /** reload 期间请求等待队列 */
    private final BlockingQueue<PendingRequest> pendingQueue;

    /** drain 等待超时（毫秒） */
    private final int drainTimeoutMs;

    /** 当前正在执行的请求数（含排队和错误写入的瞬时计数） */
    private final AtomicInteger inFlightCount = new AtomicInteger(0);

    /**
     * 创建包装器。
     *
     * @param backendName   后端名称
     * @param delegate      被包装的真实处理器
     * @param queueCapacity reload 期间请求队列容量
     * @param drainTimeoutMs drain 超时（毫秒）
     */
    public ReloadableQueryProcessor(
            String backendName, QueryProcessor delegate, int queueCapacity, int drainTimeoutMs) {
        this.backendName = backendName;
        this.delegate = delegate;
        this.pendingQueue = new LinkedBlockingQueue<>(queueCapacity);
        this.drainTimeoutMs = drainTimeoutMs;
    }

    // ==================== QueryProcessor 接口 ====================

    @Override
    public void process(ChannelHandlerContext ctx, String sql, FrontendSession session) {
        inFlightCount.incrementAndGet();
        try {
            State s = state.get();
            switch (s) {
                case ACTIVE:
                    delegate.process(ctx, sql, session);
                    break;
                case RELOADING:
                    enqueue(ctx, sql, session);
                    break;
                case DRAINING:
                    writeError(ctx, "Server shutdown in progress, backend '" + backendName + "' is being removed");
                    break;
                default:
                    writeError(ctx, "Unknown backend state for '" + backendName + "'");
                    break;
            }
        } finally {
            inFlightCount.decrementAndGet();
        }
    }

    @Override
    public void commit(ChannelHandlerContext ctx, FrontendSession session) throws Exception {
        QueryProcessor d = delegate;
        if (d != null) {
            d.commit(ctx, session);
        }
    }

    @Override
    public void rollback(ChannelHandlerContext ctx, FrontendSession session) throws Exception {
        QueryProcessor d = delegate;
        if (d != null) {
            d.rollback(ctx, session);
        }
    }

    @Override
    public void closeSessionConnection(ChannelHandlerContext ctx, FrontendSession session) {
        QueryProcessor d = delegate;
        if (d != null) {
            d.closeSessionConnection(ctx, session);
        }
    }

    @Override
    public void close() {
        QueryProcessor d = delegate;
        if (d != null) {
            d.close();
        }
    }

    // ==================== 生命周期管理（供 BackendPoolManager 调用） ====================

    /**
     * 进入 RELOADING 状态，等待 in-flight 请求完成，关闭旧 delegate。
     *
     * <p>调用后必须紧接着调用 {@link #activateNew(QueryProcessor)}。
     *
     * @return true 表示 drain 成功完成；false 表示超时（旧 delegate 仍被强行关闭）
     */
    public boolean drainAndClose() {
        log.info("Backend '{}': starting drain (timeout={}ms)", backendName, drainTimeoutMs);
        state.set(State.RELOADING);

        // 等待 in-flight 请求完成
        long deadline = System.currentTimeMillis() + drainTimeoutMs;
        while (inFlightCount.get() > 0) {
            if (System.currentTimeMillis() >= deadline) {
                log.warn(
                        "Backend '{}': drain timeout after {}ms, forcing close ({} in-flight)",
                        backendName,
                        drainTimeoutMs,
                        inFlightCount.get());
                break;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Backend '{}': drain interrupted", backendName);
                break;
            }
        }

        boolean drained = inFlightCount.get() <= 0;
        log.info(
                "Backend '{}': drain {} ({} remaining in-flight)",
                backendName,
                drained ? "complete" : "timeout",
                inFlightCount.get());
        ReloadMetrics.recordDrain(backendName, drained);

        // 关闭旧连接池
        QueryProcessor old = delegate;
        delegate = null;
        if (old != null) {
            old.close();
        }
        // 清空队列中残留的请求（返回错误）
        drainQueueWithError();
        return drained;
    }

    /**
     * 设置新的 delegate 并切换回 ACTIVE 状态，
     * 然后逐一处理队列中等待的请求。
     *
     * @param newDelegate 新创建的查询处理器
     */
    public void activateNew(QueryProcessor newDelegate) {
        Objects.requireNonNull(newDelegate, "newDelegate must not be null");
        this.delegate = newDelegate;
        state.set(State.ACTIVE);
        log.info("Backend '{}': new delegate activated, draining {} queued requests", backendName, pendingQueue.size());

        // 处理队列中的请求
        PendingRequest pr;
        while ((pr = pendingQueue.poll()) != null) {
            inFlightCount.incrementAndGet();
            try {
                newDelegate.process(pr.ctx, pr.sql, pr.session);
            } catch (Exception e) {
                log.error("Backend '{}': error processing queued request: {}", backendName, e.getMessage());
                writeError(pr.ctx, "Backend reload completed but request failed: " + e.getMessage());
            } finally {
                inFlightCount.decrementAndGet();
            }
        }
        log.info("Backend '{}': queued requests drained", backendName);
    }

    /**
     * 标记为 DRAINING，拒绝所有新请求，供后端被移除时使用。
     * 调用者应在之后调用 {@link #close()} 释放资源。
     */
    public void markDraining() {
        state.set(State.DRAINING);
        log.info("Backend '{}': marked as DRAINING", backendName);
    }

    /**
     * 获取当前状态。
     */
    public State getState() {
        return state.get();
    }

    /**
     * 获取队列中等待的请求数。
     */
    public int getQueueSize() {
        return pendingQueue.size();
    }

    // ==================== 内部辅助 ====================

    /**
     * 将请求放入等待队列；队列满时返回错误。
     */
    private void enqueue(ChannelHandlerContext ctx, String sql, FrontendSession session) {
        PendingRequest pr = new PendingRequest(ctx, sql, session);
        if (!pendingQueue.offer(pr)) {
            log.warn(
                    "Backend '{}': reload queue full (capacity={}), rejecting request",
                    backendName,
                    pendingQueue.size() + pendingQueue.remainingCapacity());
            ReloadMetrics.recordQueueRejection(backendName, "full");
            writeError(
                    ctx, "Server shutdown in progress, backend '" + backendName + "' is reloading, please retry later");
        } else {
            log.debug("Backend '{}': request queued (queue size={})", backendName, pendingQueue.size());
        }
    }

    /**
     * 清空队列中的请求，每个返回错误（drain 超时时使用）。
     */
    private void drainQueueWithError() {
        PendingRequest pr;
        int count = 0;
        while ((pr = pendingQueue.poll()) != null) {
            writeError(
                    pr.ctx,
                    "Server shutdown in progress, backend '" + backendName + "' reload timed out, please retry");
            count++;
        }
        if (count > 0) {
            log.warn("Backend '{}': rejected {} queued requests due to drain timeout", backendName, count);
            ReloadMetrics.recordQueueRejection(backendName, "timeout");
        }
    }

    /**
     * 向客户端写入 MySQL ERR 包。
     */
    private void writeError(ChannelHandlerContext ctx, String message) {
        try {
            ByteBuf err = MySQLResponseWriter.buildErrPacket(ctx.alloc(), ERR_SERVER_SHUTDOWN, SQL_STATE, message);
            ctx.writeAndFlush(new MySQLPacketEncoder.OutgoingPacket(err, (byte) 1));
        } catch (Exception e) {
            log.error("Backend '{}': failed to write error packet", backendName, e);
        }
    }

    // ==================== 内部类 ====================

    /**
     * 等待处理的请求。
     */
    private static class PendingRequest {
        final ChannelHandlerContext ctx;
        final String sql;
        final FrontendSession session;

        PendingRequest(ChannelHandlerContext ctx, String sql, FrontendSession session) {
            this.ctx = ctx;
            this.sql = sql;
            this.session = session;
        }
    }
}
