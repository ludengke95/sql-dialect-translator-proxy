package com.translator.proxy.protocol.mysql.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.translator.metrics.ConnectionMetrics;
import com.translator.proxy.core.handler.BackendRouter;
import com.translator.proxy.core.handler.SessionAttribute;
import com.translator.proxy.core.session.FrontendSession;
import com.translator.proxy.metrics.CommandMetrics;
import com.translator.proxy.metrics.NettyMetrics;
import com.translator.proxy.protocol.mysql.auth.MySQLAuthHandler;
import com.translator.proxy.protocol.mysql.catalog.MySQLSystemCatalogProvider;
import com.translator.proxy.protocol.mysql.codec.MySQLPacketDecoder;
import com.translator.proxy.protocol.mysql.codec.MySQLPacketEncoder;
import com.translator.proxy.protocol.mysql.constant.CommandType;
import com.translator.proxy.protocol.mysql.result.MySQLResponseWriter;
import com.translator.proxy.protocol.mysql.util.BufferUtils;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * MySQL 命令处理器 —— 认证完成后处理客户端各类 MySQL 命令。
 *
 * <p>支持的命令：
 * <ul>
 *   <li>COM_QUERY (0x03) — SQL 查询，分发到系统变量拦截或后端执行</li>
 *   <li>COM_PING (0x0E) — 心跳</li>
 *   <li>COM_QUIT (0x01) — 断开连接</li>
 *   <li>COM_INIT_DB (0x02) — 切换数据库</li>
 *   <li>COM_FIELD_LIST (0x04) — 表字段列表（暂返回错误）</li>
 * </ul>
 *
 * <p>此类将原 sdtp-core 中的 {@code CommandHandler} 逻辑完整迁移至 sdtp-protocol 模块。
 * OK/ERR/ColumnDef/TextRow/EOF 包构造委托给 {@link MySQLResponseWriter}，
 * 系统变量查询委托给 {@link MySQLSystemCatalogProvider}。
 */
public class MySQLCommandHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(MySQLCommandHandler.class);

    /** SHOW DATABASES / SHOW SCHEMAS [LIKE 'xxx'] */
    private static final Pattern SHOW_DATABASES = Pattern.compile(
            "^\\s*SHOW\\s+(DATABASES|SCHEMAS)(?:\\s+LIKE\\s+'([^']*)')?\\s*$", Pattern.CASE_INSENSITIVE);

    private static final Pattern SET_AUTOCOMMIT_PATTERN = Pattern.compile(
            "^\\s*SET\\s+(?:@@(?:session\\.)?)?autocommit\\s*=\\s*(\\d)\\s*$", Pattern.CASE_INSENSITIVE);

    private static final Pattern BEGIN_PATTERN =
            Pattern.compile("^\\s*(?:BEGIN|START\\s+TRANSACTION)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMIT_PATTERN = Pattern.compile("^\\s*COMMIT\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROLLBACK_PATTERN = Pattern.compile("^\\s*ROLLBACK\\s*$", Pattern.CASE_INSENSITIVE);

    /** 后端路由器（多后端模式下按数据库名路由，由握手链路构造注入） */
    private final BackendRouter backendRouter;

    /** 系统目录提供者 */
    private final MySQLSystemCatalogProvider systemCatalog = new MySQLSystemCatalogProvider();

    /** 响应写入器 */
    private final MySQLResponseWriter responseWriter = new MySQLResponseWriter();

    /**
     * 构造命令处理器。
     *
     * @param backendRouter 后端路由器，按会话 database 解析对应后端 QueryProcessor
     */
    public MySQLCommandHandler(BackendRouter backendRouter) {
        this.backendRouter = backendRouter;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof MySQLPacketDecoder.RawMySQLPacket)) {
            ctx.fireChannelRead(msg);
            return;
        }
        MySQLPacketDecoder.RawMySQLPacket raw = (MySQLPacketDecoder.RawMySQLPacket) msg;
        ByteBuf payload = raw.getPayload();
        try {
            int command = payload.readUnsignedByte();
            String cmdName = CommandType.nameOf(command);
            log.debug("Command: {} (seq={})", cmdName, raw.getSequenceId());
            CommandMetrics.recordCommand(cmdName);

            FrontendSession session =
                    ctx.channel().attr(SessionAttribute.SESSION_KEY).get();
            switch (command) {
                case CommandType.COM_QUERY:
                    handleQuery(ctx, payload, session);
                    break;
                case CommandType.COM_PING:
                    handlePing(ctx);
                    break;
                case CommandType.COM_QUIT:
                    handleQuit(ctx);
                    break;
                case CommandType.COM_INIT_DB:
                    handleInitDb(ctx, payload, session);
                    break;
                case CommandType.COM_FIELD_LIST:
                    handleFieldList(ctx, payload);
                    break;
                default:
                    handleUnsupported(ctx, CommandType.nameOf(command));
                    break;
            }
        } catch (Exception e) {
            log.error("Error handling command", e);
            CommandMetrics.recordError();
            responseWriter.writeErr((byte) 1, ctx, 1105, "HY000", "Internal error: " + e.getMessage());
        } finally {
            raw.release();
        }
    }

    // ==================== COM_QUERY ====================

    private void handleQuery(ChannelHandlerContext ctx, ByteBuf payload, FrontendSession session) {
        String sql = payload.toString(StandardCharsets.UTF_8);
        log.debug("SQL: {}", sql);

        // 1. 检查是否 USE 语句
        String useDb = systemCatalog.extractUseDatabase(sql);
        if (useDb != null) {
            if (!useDb.equalsIgnoreCase(session.getDatabase())) {
                rollbackActiveTransaction(ctx, session);
            }
            session.setDatabase(useDb);
            int statusFlags = MySQLAuthHandler.getStatusFlags(session);
            responseWriter.writeOk((byte) 1, ctx, 0, 0, statusFlags, 0, "");
            return;
        }

        // 2. 检查是否 SHOW DATABASES
        if (isShowDatabases(sql)) {
            handleShowDatabases(ctx, sql, session);
            return;
        }

        // 3. 拦截事务控制语句
        String trimmedSql = sql.trim();
        Matcher autocommitMatcher = SET_AUTOCOMMIT_PATTERN.matcher(trimmedSql);
        if (autocommitMatcher.matches()) {
            int val = Integer.parseInt(autocommitMatcher.group(1));
            boolean targetAutoCommit = (val == 1);
            try {
                if (session.isAutoCommit() != targetAutoCommit) {
                    if (!session.isAutoCommit() && targetAutoCommit) {
                        com.translator.proxy.core.handler.QueryProcessor processor = resolveProcessor(session);
                        processor.commit(ctx, session);
                    }
                    session.setAutoCommit(targetAutoCommit);
                }
                int statusFlags = MySQLAuthHandler.getStatusFlags(session);
                responseWriter.writeOk((byte) 1, ctx, 0, 0, statusFlags, 0, "");
            } catch (Exception e) {
                log.error("Failed to set autocommit", e);
                responseWriter.writeErr((byte) 1, ctx, 1105, "HY000", "Failed to set autocommit: " + e.getMessage());
            }
            return;
        }

        if (BEGIN_PATTERN.matcher(trimmedSql).matches()) {
            session.setInTransaction(true);
            int statusFlags = MySQLAuthHandler.getStatusFlags(session);
            responseWriter.writeOk((byte) 1, ctx, 0, 0, statusFlags, 0, "");
            return;
        }

        if (COMMIT_PATTERN.matcher(trimmedSql).matches()) {
            try {
                session.setInTransaction(false);
                com.translator.proxy.core.handler.QueryProcessor processor = resolveProcessor(session);
                processor.commit(ctx, session);
                int statusFlags = MySQLAuthHandler.getStatusFlags(session);
                responseWriter.writeOk((byte) 1, ctx, 0, 0, statusFlags, 0, "");
            } catch (Exception e) {
                log.error("Commit failed", e);
                responseWriter.writeErr((byte) 1, ctx, 1105, "HY000", "Commit failed: " + e.getMessage());
            }
            return;
        }

        if (ROLLBACK_PATTERN.matcher(trimmedSql).matches()) {
            try {
                session.setInTransaction(false);
                com.translator.proxy.core.handler.QueryProcessor processor = resolveProcessor(session);
                processor.rollback(ctx, session);
                int statusFlags = MySQLAuthHandler.getStatusFlags(session);
                responseWriter.writeOk((byte) 1, ctx, 0, 0, statusFlags, 0, "");
            } catch (Exception e) {
                log.error("Rollback failed", e);
                responseWriter.writeErr((byte) 1, ctx, 1105, "HY000", "Rollback failed: " + e.getMessage());
            }
            return;
        }

        // 4. 检查 SET 语句
        if (systemCatalog.isSetStatement(sql)) {
            log.debug("Handling SET statement locally: {}", sql);
            CommandMetrics.recordSystemVarInterception();
            int statusFlags = MySQLAuthHandler.getStatusFlags(session);
            responseWriter.writeOk((byte) 1, ctx, 0, 0, statusFlags, 0, "");
            return;
        }

        // 5. 系统变量查询
        if (systemCatalog.canHandle(sql)) {
            log.debug("Intercepted system variable query: {}", sql);
            CommandMetrics.recordSystemVarInterception();
            systemCatalog.handleQuery(ctx, sql, session);
            return;
        }

        // 6. 转发到后端
        com.translator.proxy.core.handler.QueryProcessor processor = resolveProcessor(session);
        processor.process(ctx, sql, session);
    }

    private com.translator.proxy.core.handler.QueryProcessor resolveProcessor(FrontendSession session) {
        return backendRouter.resolve(session);
    }

    // ==================== SHOW DATABASES ====================

    private boolean isShowDatabases(String sql) {
        return sql != null && SHOW_DATABASES.matcher(sql.trim()).matches();
    }

    private void handleShowDatabases(ChannelHandlerContext ctx, String sql, FrontendSession session) {
        Set<String> names = backendRouter.getBackendNames();

        Matcher m = SHOW_DATABASES.matcher(sql.trim());
        if (m.matches()) {
            String likePattern = m.group(2);
            if (likePattern != null && !likePattern.isEmpty()) {
                Pattern likeRegex = sqlLikeToRegex(likePattern);
                List<String> filtered = new ArrayList<String>();
                for (String name : names) {
                    if (likeRegex.matcher(name).matches()) {
                        filtered.add(name);
                    }
                }
                names = new LinkedHashSet<String>(filtered);
            }
        }

        int statusFlags = MySQLAuthHandler.getStatusFlags(session);
        writeShowDatabasesResult(ctx, names, statusFlags);
    }

    private void writeShowDatabasesResult(ChannelHandlerContext ctx, Set<String> names, int statusFlags) {
        List<String> sorted = new ArrayList<String>(names);

        ByteBuf colCountBuf = ctx.alloc().buffer(2);
        BufferUtils.writeLengthEncodedInt(colCountBuf, 1);
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(colCountBuf, (byte) 1));
        byte seq = 2;

        ctx.write(new MySQLPacketEncoder.OutgoingPacket(
                MySQLResponseWriter.buildColumnDef(ctx.alloc(), "def", "", "Database", "Database", 255, 0xFD, 33),
                seq++));

        ctx.write(new MySQLPacketEncoder.OutgoingPacket(MySQLResponseWriter.buildEof(ctx.alloc(), statusFlags), seq++));

        for (String name : sorted) {
            ByteBuf row = MySQLResponseWriter.buildTextRow(ctx.alloc(), new String[] {name});
            ctx.write(new MySQLPacketEncoder.OutgoingPacket(row, seq++));
        }

        ctx.write(new MySQLPacketEncoder.OutgoingPacket(MySQLResponseWriter.buildEof(ctx.alloc(), statusFlags), seq));
        ctx.flush();
    }

    private static Pattern sqlLikeToRegex(String likePattern) {
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < likePattern.length(); i++) {
            char c = likePattern.charAt(i);
            if (c == '%') {
                sb.append(".*");
            } else if (c == '_') {
                sb.append('.');
            } else if (c == '\\' && i + 1 < likePattern.length()) {
                sb.append(Pattern.quote(String.valueOf(likePattern.charAt(i + 1))));
                i++;
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        sb.append("$");
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
    }

    // ==================== COM_PING / COM_QUIT ====================

    private void handlePing(ChannelHandlerContext ctx) {
        int statusFlags = 0;
        FrontendSession session =
                ctx.channel().attr(SessionAttribute.SESSION_KEY).get();
        if (session != null) {
            statusFlags = MySQLAuthHandler.getStatusFlags(session);
        }
        responseWriter.writeOk((byte) 1, ctx, 0, 0, statusFlags, 0, "");
    }

    private void handleQuit(ChannelHandlerContext ctx) {
        log.debug("Client sent COM_QUIT, closing");
        ctx.close();
    }

    // ==================== COM_INIT_DB ====================

    private void handleInitDb(ChannelHandlerContext ctx, ByteBuf payload, FrontendSession session) {
        String dbName = BufferUtils.readEofString(payload);
        log.debug("COM_INIT_DB: {}", dbName);
        if (dbName != null && !dbName.equalsIgnoreCase(session.getDatabase())) {
            rollbackActiveTransaction(ctx, session);
        }
        session.setDatabase(dbName);
        int statusFlags = MySQLAuthHandler.getStatusFlags(session);
        responseWriter.writeOk((byte) 1, ctx, 0, 0, statusFlags, 0, "");
    }

    private void rollbackActiveTransaction(ChannelHandlerContext ctx, FrontendSession session) {
        Connection conn = ctx.channel().attr(SessionAttribute.BACKEND_CONN_KEY).get();
        if (conn != null) {
            log.info("Implicit rollback of active transaction due to database switch");
            try {
                com.translator.proxy.core.handler.QueryProcessor processor = resolveProcessor(session);
                processor.rollback(ctx, session);
            } catch (Exception e) {
                log.error("Failed to implicitly rollback transaction during database switch", e);
            }
        }
    }

    // ==================== COM_FIELD_LIST / Unsupported ====================

    private void handleFieldList(ChannelHandlerContext ctx, ByteBuf payload) {
        responseWriter.writeErr((byte) 1, ctx, 1105, "HY000", "COM_FIELD_LIST not supported");
    }

    private void handleUnsupported(ChannelHandlerContext ctx, String cmdName) {
        responseWriter.writeErr((byte) 1, ctx, 1105, "HY000", "Command not supported: " + cmdName);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (isConnectionReset(cause)) {
            log.debug(
                    "Client {} disconnected: {}",
                    ctx.channel().remoteAddress(),
                    cause.getMessage() != null ? cause.getMessage() : "connection reset");
        } else {
            log.error("Exception in MySQLCommandHandler", cause);
        }
        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        FrontendSession session =
                ctx.channel().attr(SessionAttribute.SESSION_KEY).get();
        if (session != null) {
            try {
                com.translator.proxy.core.handler.QueryProcessor processor = resolveProcessor(session);
                processor.closeSessionConnection(ctx, session);
            } catch (Exception e) {
                log.error("Error closing session bound connection in channelInactive", e);
            }
        }
        ConnectionMetrics.onDisconnect();
        NettyMetrics.onChannelInactive();
        ctx.fireChannelInactive();
    }

    private static boolean isConnectionReset(Throwable cause) {
        if (cause instanceof IOException) {
            String msg = cause.getMessage();
            return msg != null
                    && (msg.contains("reset by peer")
                            || msg.contains("connection reset")
                            || msg.contains("中止了一个已建立的连接")
                            || msg.contains("abort"));
        }
        return false;
    }
}
