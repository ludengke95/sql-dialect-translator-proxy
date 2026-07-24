package com.translator.proxy.protocol.pg.result;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.translator.proxy.protocol.frontend.ResponseWriter;
import com.translator.proxy.protocol.pg.codec.PgMessage;
import com.translator.proxy.protocol.pg.codec.PgWire;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

/**
 * PostgreSQL 响应写入器 —— 将 JDBC ResultSet 编码为 PG 协议消息。
 *
 * <p>实现 {@link ResponseWriter} 接口，将结果编码为：
 * <pre>
 *   RowDescription → N*DataRow → CommandComplete → ReadyForQuery
 * </pre>
 */
public class PgResponseWriter implements ResponseWriter {

    private static final Logger log = LoggerFactory.getLogger(PgResponseWriter.class);

    private final PgTypeMapper typeMapper = new PgTypeMapper();

    @Override
    public void writeOk(
            byte sequenceNumber,
            ChannelHandlerContext ctx,
            long affectedRows,
            long lastInsertId,
            int statusFlags,
            int warnings,
            String info) {
        // PG 用 CommandComplete 代替 OK，ReadyForQuery 由 CommandHandler 统一管理
        // sequenceNumber 在 PG 协议中无对应语义，忽略
        String tag = "";
        if (info != null && !info.isEmpty()) {
            tag = info;
        } else if (affectedRows >= 0) {
            tag = "OK " + affectedRows;
        } else {
            tag = "OK";
        }
        sendCommandComplete(ctx, tag);
    }

    @Override
    public io.netty.channel.ChannelFuture writeErr(
            byte sequenceNumber, ChannelHandlerContext ctx, int errorCode, String sqlState, String message) {
        // sequenceNumber 在 PG 协议中无对应语义，忽略
        sendError(ctx, "ERROR", sqlState, message);
        ByteBuf payload = ctx.alloc().buffer(1);
        payload.writeByte(PgWire.TXN_IDLE);
        return ctx.writeAndFlush(new PgMessage(PgWire.MSG_READY_FOR_QUERY, payload));
    }

    @Override
    public void writeColumnDef(ChannelHandlerContext ctx, ResultSetMetaData metaData, int columnIndex)
            throws SQLException {
        // PG 使用 RowDescription 消息（一次发送所有列定义）
        throw new UnsupportedOperationException(
                "PG protocol uses RowDescription for all columns at once. Use writeRowDescription instead.");
    }

    @Override
    public void writeEof(ChannelHandlerContext ctx, int statusFlags) {
        // PG 不使用 EOF，空实现
    }

    @Override
    public void writeTextRow(ChannelHandlerContext ctx, ResultSet rs, int columnCount) throws SQLException {
        // PG 使用 DataRow 消息
        sendDataRow(ctx, rs, columnCount);
    }

    @Override
    public int writeResultSet(ChannelHandlerContext ctx, ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        writeRowDescription(ctx, meta);
        int columnCount = meta.getColumnCount();
        int rowCount = 0;
        while (rs.next()) {
            sendDataRow(ctx, rs, columnCount);
            rowCount++;
        }
        String tag = "SELECT " + rowCount;
        sendCommandComplete(ctx, tag);
        ctx.flush();
        return rowCount;
    }

    // ==================== PG 特定的写入方法 ====================

    /**
     * 写入 RowDescription 消息（所有列的元数据）。
     */
    public void writeRowDescription(ChannelHandlerContext ctx, ResultSetMetaData meta) throws SQLException {
        int columnCount = meta.getColumnCount();
        ByteBuf payload = ctx.alloc().buffer(128);
        payload.writeShort(columnCount);

        for (int i = 1; i <= columnCount; i++) {
            String colName = meta.getColumnLabel(i);
            if (colName == null || colName.trim().isEmpty()) {
                colName = meta.getColumnName(i);
            }
            if (colName == null || colName.trim().isEmpty()) {
                colName = "col_" + i;
            }

            int jdbcType = meta.getColumnType(i);
            String typeName = meta.getColumnTypeName(i);
            int pgOid = typeMapper.jdbcToProtocolType(jdbcType, typeName);
            int typeSize = PgTypeMapper.getTypeSize(pgOid);

            int colLen = meta.getColumnDisplaySize(i);
            int typeModifier = (colLen > 0) ? (colLen + 4) : -1;

            PgWire.cstr(payload, colName);
            payload.writeInt(0); // table OID
            payload.writeShort(0); // column attribute number
            payload.writeInt(pgOid); // data type OID
            payload.writeShort(typeSize); // type size (定长为字节数，变长为 -1)
            payload.writeInt(typeModifier); // type modifier
            payload.writeShort(0); // format code (0 = text)
        }

        ctx.write(new PgMessage(PgWire.MSG_ROW_DESCRIPTION, payload));
    }

    /**
     * 发送 CommandComplete 消息。
     */
    public void sendCommandComplete(ChannelHandlerContext ctx, String tag) {
        ByteBuf payload = ctx.alloc().buffer(64);
        PgWire.cstr(payload, tag);
        ctx.write(new PgMessage(PgWire.MSG_COMMAND_COMPLETE, payload));
    }

    /**
     * 发送 ReadyForQuery 消息。
     */
    public void sendReadyForQuery(ChannelHandlerContext ctx, byte txnStatus) {
        ByteBuf payload = ctx.alloc().buffer(1);
        payload.writeByte(txnStatus);
        ctx.writeAndFlush(new PgMessage(PgWire.MSG_READY_FOR_QUERY, payload));
    }

    /**
     * 发送 ErrorResponse 消息。
     */
    public void sendError(ChannelHandlerContext ctx, String severity, String code, String message) {
        ByteBuf payload = ctx.alloc().buffer(128);
        PgWire.cstr(payload, "S");
        PgWire.cstr(payload, severity);
        PgWire.cstr(payload, "C");
        PgWire.cstr(payload, code);
        PgWire.cstr(payload, "M");
        PgWire.cstr(payload, message != null ? message : "unknown error");
        payload.writeByte(0x00);
        ctx.write(new PgMessage(PgWire.MSG_ERROR_RESPONSE, payload));
    }

    /**
     * 发送 DataRow 消息。
     */
    public void sendDataRow(ChannelHandlerContext ctx, ResultSet rs, int columnCount) throws SQLException {
        ByteBuf payload = ctx.alloc().buffer(256);
        payload.writeShort(columnCount);

        for (int i = 1; i <= columnCount; i++) {
            String value = rs.getString(i);
            if (rs.wasNull() || value == null) {
                payload.writeInt(-1); // NULL
            } else {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                payload.writeInt(bytes.length);
                payload.writeBytes(bytes);
            }
        }

        ctx.write(new PgMessage(PgWire.MSG_DATA_ROW, payload));
    }

    /**
     * 发送空查询响应。
     */
    public void sendEmptyQuery(ChannelHandlerContext ctx) {
        ByteBuf payload = ctx.alloc().buffer(0);
        ctx.write(new PgMessage(PgWire.MSG_EMPTY_QUERY_RESPONSE, payload));
    }
}
