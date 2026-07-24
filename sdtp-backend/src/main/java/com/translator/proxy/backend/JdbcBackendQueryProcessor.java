package com.translator.proxy.backend;

import java.sql.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.translator.metrics.BackendMetrics;
import com.translator.proxy.backend.mapper.ResultSetEncoder;
import com.translator.proxy.core.handler.QueryProcessor;
import com.translator.proxy.core.handler.SessionAttribute;
import com.translator.proxy.core.handler.SqlTranslationContext;
import com.translator.proxy.core.session.FrontendSession;
import com.translator.proxy.metrics.HikariMetricsTrackerFactory;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import io.netty.channel.ChannelHandlerContext;

/**
 * 基于 JDBC 的后端查询处理器。
 *
 * <p>实现 QueryProcessor 接口，管理 HikariCP 连接池，
 * 在独立线程中执行 SQL 并通过 ResultSetEncoder 将结果流式回传。
 *
 * <p>线程安全：连接池本身是线程安全的，每个查询从池中获取连接后独立执行。
 */
public class JdbcBackendQueryProcessor implements QueryProcessor {
    private static final Logger log = LoggerFactory.getLogger(JdbcBackendQueryProcessor.class);
    /** 专属的 SQL 翻译审计日志 Logger */
    private static final Logger transRecordLog = LoggerFactory.getLogger("sql-translate-record");

    private final HikariDataSource dataSource;
    /** 后端名称（用于指标打点） */
    private volatile String backendName = "unknown";

    private JdbcBackendQueryProcessor(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }
    /**
     * 工厂方法：根据配置创建处理器。
     */
    public static JdbcBackendQueryProcessor create(
            String jdbcUrl, String username, String password, int maxPoolSize, int minIdle) {
        return create(null, jdbcUrl, username, password, maxPoolSize, minIdle);
    }
    /**
     * 工厂方法：根据配置创建处理器（带后端名称，用于指标打点）。
     */
    public static JdbcBackendQueryProcessor create(
            String backendName, String jdbcUrl, String username, String password, int maxPoolSize, int minIdle) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setUsername(username);
        hikariConfig.setPassword(password);
        hikariConfig.setMaximumPoolSize(maxPoolSize);
        hikariConfig.setMinimumIdle(minIdle);
        hikariConfig.setConnectionTimeout(10000); // 10s
        hikariConfig.setIdleTimeout(600000); // 10min
        hikariConfig.setMaxLifetime(1800000); // 30min
        // 关键：为了支持事务写操作，默认为非只读
        hikariConfig.setReadOnly(false);
        hikariConfig.setAutoCommit(true);
        // 设置连接池名称（用于 HikariMetricsTracker 的 pool_name label）
        if (backendName != null) {
            hikariConfig.setPoolName(backendName);
        }
        // 注册 HikariCP MetricsTracker
        hikariConfig.setMetricsTrackerFactory(new HikariMetricsTrackerFactory());
        HikariDataSource ds = new HikariDataSource(hikariConfig);
        log.info("HikariCP pool created: jdbcUrl={}, maxPoolSize={}", jdbcUrl, maxPoolSize);
        JdbcBackendQueryProcessor processor = new JdbcBackendQueryProcessor(ds);
        if (backendName != null) {
            processor.backendName = backendName;
        }
        return processor;
    }

    @Override
    public void process(ChannelHandlerContext ctx, String sql, FrontendSession session) {
        String queryType = BackendMetrics.classifyQueryType(sql);
        BackendMetrics.recordQuery(backendName, queryType);
        long startNanos = System.nanoTime();
        // 1. 判断是否处于事务上下文中
        boolean isTx = !session.isAutoCommit() || session.isInTransaction();
        Connection conn = null;
        boolean isNewConnection = false;

        // 获取并清除 SQL 翻译审计上下文
        SqlTranslationContext transCtx =
                ctx.channel().attr(SessionAttribute.SQL_CONTEXT_KEY).getAndSet(null);

        try {
            if (isTx) {
                conn = ctx.channel().attr(SessionAttribute.BACKEND_CONN_KEY).get();
                if (conn == null) {
                    conn = dataSource.getConnection();
                    conn.setAutoCommit(false);
                    ctx.channel().attr(SessionAttribute.BACKEND_CONN_KEY).set(conn);
                    isNewConnection = true;
                }
            } else {
                conn = dataSource.getConnection();
                isNewConnection = true;
            }
            try (Statement stmt = createStatement(conn)) {
                boolean isResultSet = stmt.execute(sql);
                if (isResultSet) {
                    try (ResultSet rs = stmt.getResultSet()) {
                        ResultSetEncoder.encodeAndWrite(ctx, rs, new ResultSetEncoder.SeqGenerator(), backendName);
                    }
                } else {
                    int updateCount = stmt.getUpdateCount();
                    ResultSetEncoder.encodeEmpty(ctx, Math.max(updateCount, 0), 0);
                    BackendMetrics.observeAffectedRows(backendName, Math.max(updateCount, 0));
                }
            }
            double elapsed = (System.nanoTime() - startNanos) / 1_000_000_000.0;
            BackendMetrics.recordQueryDuration(backendName, elapsed);

            // 记录成功执行审计日志
            logSqlTranslationRecord(ctx, transCtx, true, null);
        } catch (SQLException e) {
            log.error("SQL execution failed: {}", formatSqlForLog(sql), e);
            String sqlState = e.getSQLState() != null ? e.getSQLState() : "HY000";
            BackendMetrics.recordError(backendName, sqlState);
            double elapsed = (System.nanoTime() - startNanos) / 1_000_000_000.0;
            BackendMetrics.recordQueryDuration(backendName, elapsed);
            writeError(ctx, e);

            // 记录执行错误审计日志
            logSqlTranslationRecord(ctx, transCtx, false, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error executing SQL: {}", formatSqlForLog(sql), e);
            BackendMetrics.recordError(backendName, "HY000");
            double elapsed = (System.nanoTime() - startNanos) / 1_000_000_000.0;
            BackendMetrics.recordQueryDuration(backendName, elapsed);
            writeError(ctx, 1105, "HY000", e.getMessage());

            // 记录其它异常审计日志
            logSqlTranslationRecord(ctx, transCtx, false, e.getMessage());
        } finally {
            // 2. 关键：非事务连接即用即走，执行完必须立刻关闭释放；事务连接不在此处关闭，保留在 Channel 属性中
            if (!isTx && conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    log.error("Failed to close non-tx connection", e);
                }
            }
        }
    }
    /**
     * 设置后端名称（用于指标打点）。
     */
    public void setBackendName(String backendName) {
        this.backendName = backendName;
    }
    /**
     * 创建 Statement 并配置流式读取。
     */
    private Statement createStatement(Connection conn) throws SQLException {
        Statement stmt = conn.createStatement();
        // 设置 fetchSize 实现流式读取（JDBC 标准方式）
        // PostgreSQL 需要 setAutoCommit(false) 才生效，但参数化查询不受影响。
        // 对于大结果集，驱动内部会用游标分批获取。
        stmt.setFetchSize(1000);
        return stmt;
    }
    // ==================== 错误处理 ====================
    private void writeError(ChannelHandlerContext ctx, SQLException e) {
        int errorCode = e.getErrorCode();
        String sqlState = e.getSQLState();
        String message = e.getMessage();
        writeError(
                ctx,
                errorCode != 0 ? errorCode : 1105,
                sqlState != null ? sqlState : "HY000",
                message != null ? message : "Unknown SQL error");
    }

    private void writeError(ChannelHandlerContext ctx, int errorCode, String sqlState, String message) {
        ResultSetEncoder.writeError(ctx, errorCode, sqlState, message);
    }

    @Override
    public void commit(ChannelHandlerContext ctx, FrontendSession session) throws SQLException {
        Connection conn = ctx.channel().attr(SessionAttribute.BACKEND_CONN_KEY).get();
        if (conn != null) {
            try {
                log.debug("Committing physical connection: {}", conn);
                conn.commit();
            } finally {
                cleanupConnection(ctx, conn);
            }
        }
    }

    @Override
    public void rollback(ChannelHandlerContext ctx, FrontendSession session) throws SQLException {
        Connection conn = ctx.channel().attr(SessionAttribute.BACKEND_CONN_KEY).get();
        if (conn != null) {
            try {
                log.debug("Rolling back physical connection: {}", conn);
                conn.rollback();
            } finally {
                cleanupConnection(ctx, conn);
            }
        }
    }

    @Override
    public void closeSessionConnection(ChannelHandlerContext ctx, FrontendSession session) {
        Connection conn = ctx.channel().attr(SessionAttribute.BACKEND_CONN_KEY).get();
        if (conn != null) {
            try {
                log.warn("Force closing session bound connection due to inactive/exception");
                conn.rollback();
            } catch (SQLException e) {
                log.error("Failed to rollback during force close", e);
            } finally {
                cleanupConnection(ctx, conn);
            }
        }
    }

    private void cleanupConnection(ChannelHandlerContext ctx, Connection conn) {
        try {
            if (!conn.isClosed()) {
                conn.setAutoCommit(true); // 还原为自动提交，以归还连接池
                conn.close(); // 归还连接池
            }
        } catch (SQLException e) {
            log.error("Error cleaning up session connection", e);
        } finally {
            ctx.channel().attr(SessionAttribute.BACKEND_CONN_KEY).set(null);
            // FrontendSession no longer tracks activeTxBackend
        }
    }
    /**
     * 关闭连接池。
     */
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("HikariCP pool closed.");
        }
    }

    private void logSqlTranslationRecord(
            ChannelHandlerContext ctx, SqlTranslationContext transCtx, boolean success, String errorMsg) {
        if (transCtx == null) {
            return;
        }

        String ip = getClientIp(ctx);
        String db = this.backendName;
        String successStr = String.valueOf(success);

        // 格式化 SQL：去除换行符与连续空白，以便单行日志正则解析
        String srcSql = formatSqlForLog(transCtx.getOriginalSql());
        String destSql = formatSqlForLog(transCtx.getTranslatedSql());
        String err = errorMsg != null ? formatSqlForLog(errorMsg) : "none";

        transRecordLog.info(
                "[SQL_TRANS_RECORD] ip={} | db={} | success={} | src_sql={} | dest_sql={} | error={}",
                ip,
                db,
                successStr,
                srcSql,
                destSql,
                err);
    }

    private String formatSqlForLog(String sql) {
        if (sql == null) {
            return "";
        }
        return sql.replaceAll("[\\r\\n\\s]+", " ").trim();
    }

    private String getClientIp(ChannelHandlerContext ctx) {
        if (ctx != null && ctx.channel() != null && ctx.channel().remoteAddress() != null) {
            java.net.SocketAddress addr = ctx.channel().remoteAddress();
            if (addr instanceof java.net.InetSocketAddress) {
                return ((java.net.InetSocketAddress) addr).getAddress().getHostAddress();
            }
            return addr.toString();
        }
        return "unknown";
    }
}
