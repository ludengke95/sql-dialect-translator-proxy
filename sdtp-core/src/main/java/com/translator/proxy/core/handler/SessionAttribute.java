package com.translator.proxy.core.handler;

import java.sql.Connection;

import com.translator.proxy.core.session.FrontendSession;

import io.netty.util.AttributeKey;

/**
 * Netty Channel 属性键常量。
 */
public final class SessionAttribute {
    private SessionAttribute() {}
    /** FrontendSession 在 Channel 上的 AttributeKey */
    public static final AttributeKey<FrontendSession> SESSION_KEY = AttributeKey.valueOf("frontendSession");
    /** 绑定在 Channel 上的物理 JDBC 连接对象 */
    public static final AttributeKey<Connection> BACKEND_CONN_KEY = AttributeKey.valueOf("backendConnection");
    /** SQL 翻译审计上下文 */
    public static final AttributeKey<SqlTranslationContext> SQL_CONTEXT_KEY =
            AttributeKey.valueOf("sqlTranslationContext");
}
