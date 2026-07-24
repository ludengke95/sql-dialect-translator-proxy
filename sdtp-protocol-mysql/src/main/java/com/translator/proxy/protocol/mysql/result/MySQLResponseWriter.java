package com.translator.proxy.protocol.mysql.result;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.translator.proxy.protocol.frontend.ResponseWriter;
import com.translator.proxy.protocol.mysql.codec.MySQLPacketEncoder;
import com.translator.proxy.protocol.mysql.constant.ServerStatus;
import com.translator.proxy.protocol.mysql.util.BufferUtils;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;

/**
 * MySQL 响应写入器 —— 将内部执行结果按 MySQL 线协议格式编码并写入 Netty Channel。
 *
 * <p>实现 {@link com.translator.proxy.protocol.frontend.ResponseWriter} 接口，
 * 将原 {@code AuthHandler.buildOkPacket/buildErrPacket} 和 {@code CommandHandler}
 * 中的 ColumnDef/EOF/TextRow 构造逻辑集中至此。
 */
public class MySQLResponseWriter implements ResponseWriter {

    private static final Logger log = LoggerFactory.getLogger(MySQLResponseWriter.class);

    @Override
    public void writeOk(
            byte sequenceNumber,
            ChannelHandlerContext ctx,
            long affectedRows,
            long lastInsertId,
            int statusFlags,
            int warnings,
            String info) {
        ByteBuf ok = buildOkPacket(ctx.alloc(), affectedRows, lastInsertId, statusFlags, warnings, info);
        ctx.writeAndFlush(new MySQLPacketEncoder.OutgoingPacket(ok, sequenceNumber));
    }

    @Override
    public io.netty.channel.ChannelFuture writeErr(
            byte sequenceNumber, ChannelHandlerContext ctx, int errorCode, String sqlState, String message) {
        ByteBuf err = buildErrPacket(ctx.alloc(), errorCode, sqlState, message);
        return ctx.writeAndFlush(new MySQLPacketEncoder.OutgoingPacket(err, sequenceNumber));
    }

    @Override
    public void writeColumnDef(ChannelHandlerContext ctx, java.sql.ResultSetMetaData metaData, int columnIndex)
            throws java.sql.SQLException {
        ByteBuf colDef = buildColumnDef(ctx, metaData, columnIndex);
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(colDef, (byte) 0));
    }

    @Override
    public void writeEof(ChannelHandlerContext ctx, int statusFlags) {
        ByteBuf eof = buildEof(ctx.alloc(), statusFlags);
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(eof, (byte) 0));
    }

    @Override
    public void writeTextRow(ChannelHandlerContext ctx, java.sql.ResultSet rs, int columnCount)
            throws java.sql.SQLException {
        ByteBuf row = buildTextRow(ctx.alloc(), rs, columnCount);
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(row, (byte) 0));
    }

    @Override
    public int writeResultSet(ChannelHandlerContext ctx, java.sql.ResultSet rs) throws java.sql.SQLException {
        java.sql.ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        byte seq = 1;
        ByteBuf colCount = ctx.alloc().buffer(9);
        BufferUtils.writeLengthEncodedInt(colCount, columnCount);
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(colCount, seq++));
        int statusFlags = ServerStatus.SERVER_STATUS_AUTOCOMMIT;
        for (int i = 1; i <= columnCount; i++) {
            ByteBuf colDef = buildColumnDef(ctx, meta, i);
            ctx.write(new MySQLPacketEncoder.OutgoingPacket(colDef, seq++));
        }
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(buildEof(ctx.alloc(), statusFlags), seq++));
        int rowCount = 0;
        while (rs.next()) {
            ByteBuf row = buildTextRow(ctx.alloc(), rs, columnCount);
            ctx.write(new MySQLPacketEncoder.OutgoingPacket(row, seq++));
            rowCount++;
        }
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(buildEof(ctx.alloc(), statusFlags), seq++));
        ctx.flush();
        return rowCount;
    }

    // ==================== 辅助写入方法 ====================

    /**
     * 发送 AuthSwitchRequest 包。
     */
    public void writeAuthSwitchRequest(ChannelHandlerContext ctx, byte[] scramble, String pluginName) {
        ByteBuf buf = ctx.alloc().buffer(64);
        buf.writeByte(0xFE);
        BufferUtils.writeNullTerminatedString(buf, pluginName);
        buf.writeBytes(scramble);
        buf.writeByte(0x00);
        ctx.writeAndFlush(new MySQLPacketEncoder.OutgoingPacket(buf, (byte) 2));
    }

    /**
     * 发送 AuthMoreData fast auth success 包。
     */
    public void writeAuthMoreDataFastSuccess(ChannelHandlerContext ctx, byte seq) {
        ByteBuf buf = ctx.alloc().buffer(2);
        buf.writeByte(0x01);
        buf.writeByte(0x03);
        ctx.writeAndFlush(new MySQLPacketEncoder.OutgoingPacket(buf, seq));
    }

    /**
     * 发送空结果集（Column Count = 0 + OK）。
     */
    public void encodeEmpty(ChannelHandlerContext ctx, long affectedRows, long lastInsertId, int statusFlags) {
        ByteBuf ok = ctx.alloc().buffer(16);
        ok.writeByte(0x00);
        BufferUtils.writeLengthEncodedInt(ok, affectedRows);
        BufferUtils.writeLengthEncodedInt(ok, lastInsertId);
        ok.writeShortLE(statusFlags);
        ok.writeShortLE(0);
        ctx.writeAndFlush(new MySQLPacketEncoder.OutgoingPacket(ok, (byte) 1));
    }

    // ==================== Packet Builders ====================

    /**
     * 构造 OK 包。
     */
    public static ByteBuf buildOkPacket(
            ByteBufAllocator alloc, long affectedRows, long lastInsertId, int statusFlags, int warnings, String info) {
        ByteBuf buf = alloc.buffer(32);
        buf.writeByte(0x00);
        BufferUtils.writeLengthEncodedInt(buf, affectedRows);
        BufferUtils.writeLengthEncodedInt(buf, lastInsertId);
        buf.writeShortLE(statusFlags);
        buf.writeShortLE(warnings);
        if (info != null && !info.isEmpty()) {
            BufferUtils.writeLengthEncodedString(buf, info);
        }
        return buf;
    }

    /**
     * 构造 ERR 包。
     */
    public static ByteBuf buildErrPacket(ByteBufAllocator alloc, int errorCode, String sqlState, String message) {
        ByteBuf buf = alloc.buffer(64);
        buf.writeByte(0xFF);
        buf.writeShortLE(errorCode);
        buf.writeByte('#');
        if (sqlState != null && sqlState.length() == 5) {
            buf.writeBytes(sqlState.getBytes(StandardCharsets.UTF_8));
        } else {
            buf.writeBytes("HY000".getBytes(StandardCharsets.UTF_8));
        }
        if (message != null) {
            buf.writeBytes(message.getBytes(StandardCharsets.UTF_8));
        }
        return buf;
    }

    /**
     * 构造 ColumnDefinition41 包。
     */
    public static ByteBuf buildColumnDef(
            ByteBufAllocator alloc,
            String catalog,
            String schema,
            String table,
            String name,
            int columnLength,
            int columnType,
            int charset) {
        ByteBuf buf = alloc.buffer(64);
        BufferUtils.writeLengthEncodedString(buf, "def");
        BufferUtils.writeLengthEncodedString(buf, schema);
        BufferUtils.writeLengthEncodedString(buf, table);
        BufferUtils.writeLengthEncodedString(buf, table);
        BufferUtils.writeLengthEncodedString(buf, name);
        BufferUtils.writeLengthEncodedString(buf, name);
        buf.writeByte(0x0C);
        buf.writeShortLE(charset);
        buf.writeIntLE(columnLength);
        buf.writeByte(columnType);
        buf.writeShortLE(0);
        buf.writeByte(0);
        buf.writeZero(2);
        return buf;
    }

    /**
     * 构造 EOF 包。
     */
    public static ByteBuf buildEof(ByteBufAllocator alloc, int statusFlags) {
        ByteBuf buf = alloc.buffer(5);
        buf.writeByte(0xFE);
        buf.writeShortLE(0);
        buf.writeShortLE(statusFlags);
        return buf;
    }

    /**
     * 构造文本协议行数据包。
     */
    public static ByteBuf buildTextRow(ByteBufAllocator alloc, String[] values) {
        ByteBuf buf = alloc.buffer(256);
        for (String val : values) {
            if (val == null) {
                buf.writeByte(0xFB);
            } else {
                BufferUtils.writeLengthEncodedString(buf, val);
            }
        }
        return buf;
    }

    /**
     * 从 JDBC ResultSetMetaData 构造 ColumnDefinition41 包。
     */
    private ByteBuf buildColumnDef(ChannelHandlerContext ctx, java.sql.ResultSetMetaData meta, int colIndex)
            throws java.sql.SQLException {
        ByteBuf buf = ctx.alloc().buffer(128);
        String schema = meta.getSchemaName(colIndex);
        String table = meta.getTableName(colIndex);
        String name = meta.getColumnLabel(colIndex);
        String orgName = meta.getColumnName(colIndex);
        int jdbcType = meta.getColumnType(colIndex);
        int mysqlType = MySQLTypeMapper.jdbcToMysqlStatic(jdbcType);
        int colLen = meta.getColumnDisplaySize(colIndex);
        if (colLen <= 0) {
            colLen = MySQLTypeMapper.defaultColumnLengthStatic(jdbcType);
        }
        int charset = MySQLTypeMapper.isBinaryStatic(jdbcType)
                ? MySQLTypeMapper.CHARSET_BINARY
                : MySQLTypeMapper.CHARSET_UTF8MB4;
        int decimals = meta.getScale(colIndex);

        BufferUtils.writeLengthEncodedString(buf, "def");
        BufferUtils.writeLengthEncodedString(buf, schema != null ? schema : "");
        BufferUtils.writeLengthEncodedString(buf, table != null ? table : "");
        BufferUtils.writeLengthEncodedString(buf, table != null ? table : "");
        BufferUtils.writeLengthEncodedString(buf, name);
        BufferUtils.writeLengthEncodedString(buf, orgName != null ? orgName : name);
        buf.writeByte(0x0C);
        buf.writeShortLE(charset);
        buf.writeIntLE(Math.min(colLen, Integer.MAX_VALUE));
        buf.writeByte(mysqlType);
        buf.writeShortLE(0);
        buf.writeByte(decimals);
        buf.writeZero(2);
        return buf;
    }

    /**
     * 从 JDBC ResultSet 构造文本协议行数据包。
     */
    private ByteBuf buildTextRow(ByteBufAllocator alloc, java.sql.ResultSet rs, int columnCount)
            throws java.sql.SQLException {
        ByteBuf buf = alloc.buffer(256);
        for (int i = 1; i <= columnCount; i++) {
            String value = rs.getString(i);
            if (rs.wasNull() || value == null) {
                buf.writeByte(0xFB);
            } else {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                BufferUtils.writeLengthEncodedInt(buf, bytes.length);
                buf.writeBytes(bytes);
            }
        }
        return buf;
    }
}
