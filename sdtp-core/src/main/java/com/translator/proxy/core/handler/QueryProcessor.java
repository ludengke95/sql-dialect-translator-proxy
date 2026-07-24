package com.translator.proxy.core.handler;

import com.translator.proxy.core.session.FrontendSession;

import io.netty.channel.ChannelHandlerContext;

/**
 * 查询处理器接口 —— 处理前端 SQL 查询的核心 SPI。
 *
 * <p>实现类负责将 SQL 发送到后端执行，并将结果写入 Netty Channel。
 * 该接口定义在 sdtp-core 中，避免各模块间的循环依赖。
 */
public interface QueryProcessor {

    /** 空实现 —— 无后端配置时的默认处理器 */
    QueryProcessor NOOP = (ctx, sql, session) -> {
        // No backend configured, silently ignore
    };

    /**
     * 处理一条 SQL 查询。
     *
     * @param ctx     Netty 上下文
     * @param sql     原始 SQL（文本）
     * @param session 当前会话
     */
    void process(ChannelHandlerContext ctx, String sql, FrontendSession session);

    /**
     * 提交当前会话绑定的事务。
     */
    default void commit(ChannelHandlerContext ctx, FrontendSession session) throws Exception {}

    /**
     * 回滚当前会话绑定的事务。
     */
    default void rollback(ChannelHandlerContext ctx, FrontendSession session) throws Exception {}

    /**
     * 强制关闭并清理当前绑定的连接（异常断连时调用）。
     */
    default void closeSessionConnection(ChannelHandlerContext ctx, FrontendSession session) {}

    /**
     * 关闭后端连接池（默认空实现，实现类按需重写）。
     */
    default void close() {}
}
