package com.translator.proxy.protocol.frontend;

import com.translator.proxy.core.session.FrontendSession;

import io.netty.channel.ChannelHandlerContext;

/**
 * 会话级语句处理器接口 —— 用于拦截处理 SET、USE 等会话级语句。
 */
public interface SessionStatementHandler {

    /**
     * 判断是否能处理该 SQL。
     *
     * @param sql 原始 SQL
     * @return true 如果可以处理
     */
    boolean canHandle(String sql);

    /**
     * 处理会话级语句。
     *
     * @param ctx     Netty 上下文
     * @param sql     原始 SQL
     * @param session 当前会话
     */
    void handle(ChannelHandlerContext ctx, String sql, FrontendSession session);
}
