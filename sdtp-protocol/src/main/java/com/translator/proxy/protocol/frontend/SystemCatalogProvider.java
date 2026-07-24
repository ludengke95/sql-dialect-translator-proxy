package com.translator.proxy.protocol.frontend;

import java.util.Map;

import com.translator.proxy.core.session.FrontendSession;

import io.netty.channel.ChannelHandlerContext;

/**
 * 系统目录提供者接口 —— 模拟数据库系统表/系统变量查询，
 * 避免将 {@code SELECT @@xxx}、{@code SHOW VARIABLES} 等语句转发到目标库。
 */
public interface SystemCatalogProvider {

    /**
     * 判断是否能处理该 SQL（如系统变量查询、SHOW 语句等）。
     *
     * @param sql 原始 SQL
     * @return true 如果可以处理
     */
    boolean canHandle(String sql);

    /**
     * 处理系统目录查询，将结果直接写入 Channel。
     *
     * @param ctx     Netty 上下文
     * @param sql     原始 SQL
     * @param session 当前会话
     */
    void handleQuery(ChannelHandlerContext ctx, String sql, FrontendSession session);

    /**
     * 获取当前协议的所有系统变量及值。
     *
     * @return 变量名 → 变量值映射
     */
    Map<String, String> getVariables();
}
