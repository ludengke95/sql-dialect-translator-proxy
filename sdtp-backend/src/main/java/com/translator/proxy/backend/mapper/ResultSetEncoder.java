package com.translator.proxy.backend.mapper;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.translator.metrics.BackendMetrics;
import com.translator.proxy.core.handler.SessionAttribute;
import com.translator.proxy.core.session.FrontendSession;
import com.translator.proxy.protocol.frontend.ResponseWriter;
import com.translator.proxy.protocol.frontend.TypeMapper;
import com.translator.proxy.protocol.mysql.codec.MySQLPacketEncoder;
import com.translator.proxy.protocol.mysql.constant.ServerStatus;
import com.translator.proxy.protocol.mysql.result.MySQLResponseWriter;
import com.translator.proxy.protocol.mysql.util.BufferUtils;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

/**
 * JDBC ResultSet → 线协议包编码器（SPI 可插拔版）。
 *
 * <p>通过 {@link TypeMapper} 和 {@link ResponseWriter} 接口解耦协议细节。
 * 默认使用 MySQL 协议实现（向后兼容），可通过 SPI 切换为其他协议。
 *
 * <p>协议结果集包序列：
 * <pre>
 *   ColumnCount → N*ColumnDef → EOF → N*Row → EOF/OK
 * </pre>
 */
public final class ResultSetEncoder {
    private static final Logger log = LoggerFactory.getLogger(ResultSetEncoder.class);
    /** 单次 flush 的最大行数（用于大结果集分批 flush） */
    private static final int FLUSH_BATCH_SIZE = 100;

    /** SPI 类型映射器（可注入） */
    private static volatile TypeMapper typeMapper = null;

    /** SPI 响应写入器（可注入） */
    private static volatile ResponseWriter responseWriter = null;

    private ResultSetEncoder() {}

    /**
     * 注入 SPI 类型映射器。不设置时使用默认 MySQL 映射。
     */
    public static void setTypeMapper(TypeMapper mapper) {
        typeMapper = mapper;
    }

    /**
     * 注入 SPI 响应写入器。不设置时使用默认 MySQL 响应格式。
     */
    public static void setResponseWriter(ResponseWriter writer) {
        responseWriter = writer;
    }

    /**
     * 将 ResultSet 流式编码并写入 Netty Channel。
     */
    public static void encodeAndWrite(ChannelHandlerContext ctx, ResultSet rs, SeqGenerator seqGen, String backendName)
            throws SQLException {
        if (responseWriter != null) {
            int rowCount = responseWriter.writeResultSet(ctx, rs);
            if (backendName != null) {
                BackendMetrics.observeResultRows(backendName, rowCount);
            }
            return;
        }
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        log.info("Encoding ResultSet: {} columns", columnCount);
        // 1. Column Count Packet [seq=1]
        ByteBuf colCount = ctx.alloc().buffer(9);
        BufferUtils.writeLengthEncodedInt(colCount, columnCount);
        log.debug("ColumnCount packet: {} bytes (seq={})", colCount.readableBytes(), seqGen.peek());
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(colCount, seqGen.next()));
        // 2. Column Definition Packets
        for (int i = 1; i <= columnCount; i++) {
            ByteBuf colDef = buildColumnDef(ctx, meta, i);
            log.debug(
                    "ColDef[{}] packet: {} bytes (seq={})",
                    meta.getColumnLabel(i),
                    colDef.readableBytes(),
                    seqGen.peek());
            ctx.write(new MySQLPacketEncoder.OutgoingPacket(colDef, seqGen.next()));
        }
        // 3. EOF after columns
        ByteBuf eof1 = buildEof(ctx);
        log.debug("EOF(after columns): {} bytes (seq={})", eof1.readableBytes(), seqGen.peek());
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(eof1, seqGen.next()));
        // 4. Row Packets
        int rowCount = 0;
        while (rs.next()) {
            ByteBuf row = buildTextRow(ctx, rs, columnCount, meta);
            if (rowCount == 0) {
                log.debug("First row packet: {} bytes (seq={})", row.readableBytes(), seqGen.peek());
            }
            ctx.write(new MySQLPacketEncoder.OutgoingPacket(row, seqGen.next()));
            rowCount++;
            if (rowCount % FLUSH_BATCH_SIZE == 0) {
                ctx.flush();
            }
        }
        log.info("Encoded {} rows", rowCount);
        if (backendName != null) {
            BackendMetrics.observeResultRows(backendName, rowCount);
        }
        // 5. Final EOF
        ByteBuf eof2 = buildEof(ctx);
        log.debug("EOF(after rows): {} bytes (seq={})", eof2.readableBytes(), seqGen.peek());
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(eof2, seqGen.next()));
        ctx.flush();
    }

    /**
     * 将 ResultSet 流式编码并写入 Netty Channel（便捷方法，不带指标打点）。
     */
    public static void encodeAndWrite(ChannelHandlerContext ctx, ResultSet rs, SeqGenerator seqGen)
            throws SQLException {
        encodeAndWrite(ctx, rs, seqGen, null);
    }

    /**
     * 发送空结果集（只有 Column Count = 0 + OK）。
     */
    public static void encodeEmpty(ChannelHandlerContext ctx, long affectedRows, long lastInsertId) {
        if (responseWriter != null) {
            responseWriter.writeOk((byte) 1, ctx, affectedRows, lastInsertId, getStatusFlags(ctx), 0, "");
            return;
        }
        // 默认 MySQL 格式（向后兼容）
        ByteBuf ok = ctx.alloc().buffer(16);
        ok.writeByte(0x00);
        BufferUtils.writeLengthEncodedInt(ok, affectedRows);
        BufferUtils.writeLengthEncodedInt(ok, lastInsertId);
        ok.writeShortLE(getStatusFlags(ctx));
        ok.writeShortLE(0);
        ctx.writeAndFlush(new MySQLPacketEncoder.OutgoingPacket(ok, (byte) 1));
    }

    /**
     * 协议无关的错误写入。
     *
     * <p>注入了 ResponseWriter 时委托给协议实现（如 PG ErrorResponse），
     * 否则回退为 MySQL ERR 包（向后兼容）。
     *
     * @param ctx          Netty 上下文
     * @param errorCode    错误码
     * @param sqlState     SQL 状态码
     * @param message      错误消息
     */
    public static void writeError(ChannelHandlerContext ctx, int errorCode, String sqlState, String message) {
        if (responseWriter != null) {
            responseWriter.writeErr((byte) 1, ctx, errorCode, sqlState, message);
            return;
        }
        ByteBuf err = MySQLResponseWriter.buildErrPacket(ctx.alloc(), errorCode, sqlState, message);
        ctx.writeAndFlush(new MySQLPacketEncoder.OutgoingPacket(err, (byte) 1));
    }

    // ==================== Column Definition Builder ====================

    private static ByteBuf buildColumnDef(ChannelHandlerContext ctx, ResultSetMetaData meta, int colIndex)
            throws SQLException {
        ByteBuf buf = ctx.alloc().buffer(128);
        String schema = meta.getSchemaName(colIndex);
        String table = meta.getTableName(colIndex);
        String name = meta.getColumnLabel(colIndex);
        String orgName = meta.getColumnName(colIndex);
        int jdbcType = meta.getColumnType(colIndex);
        String typeName = meta.getColumnTypeName(colIndex);

        // 使用 SPI TypeMapper 或默认本地映射
        int mysqlType;
        if (typeMapper != null) {
            mysqlType = typeMapper.jdbcToProtocolType(jdbcType, typeName);
        } else {
            mysqlType = com.translator.proxy.backend.mapper.TypeMapper.jdbcToMysql(jdbcType);
        }

        int colLen = meta.getColumnDisplaySize(colIndex);
        if (colLen <= 0) {
            colLen = com.translator.proxy.backend.mapper.TypeMapper.defaultColumnLength(jdbcType);
        }
        int charset = com.translator.proxy.backend.mapper.TypeMapper.isBinary(jdbcType)
                ? com.translator.proxy.backend.mapper.TypeMapper.CHARSET_BINARY
                : com.translator.proxy.backend.mapper.TypeMapper.CHARSET_UTF8MB4;
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

    // ==================== Text Row Builder ====================

    private static ByteBuf buildTextRow(
            ChannelHandlerContext ctx, ResultSet rs, int columnCount, ResultSetMetaData meta) throws SQLException {
        ByteBuf buf = ctx.alloc().buffer(256);
        for (int i = 1; i <= columnCount; i++) {
            String value = rs.getString(i);
            boolean wasNull = rs.wasNull();
            if (wasNull || value == null) {
                buf.writeByte(0xFB);
            } else {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                BufferUtils.writeLengthEncodedInt(buf, bytes.length);
                buf.writeBytes(bytes);
            }
        }
        return buf;
    }

    private static ByteBuf buildEof(ChannelHandlerContext ctx) {
        ByteBuf buf = ctx.alloc().buffer(5);
        buf.writeByte(0xFE);
        buf.writeShortLE(0);
        buf.writeShortLE(getStatusFlags(ctx));
        return buf;
    }

    private static int getStatusFlags(ChannelHandlerContext ctx) {
        FrontendSession session =
                ctx.channel().attr(SessionAttribute.SESSION_KEY).get();
        int flags = ServerStatus.SERVER_STATUS_AUTOCOMMIT;
        if (session != null) {
            flags = 0;
            if (session.isAutoCommit()) {
                flags |= ServerStatus.SERVER_STATUS_AUTOCOMMIT;
            }
            if (session.isInTransaction() || !session.isAutoCommit()) {
                flags |= ServerStatus.SERVER_STATUS_IN_TRANS;
            }
        }
        return flags;
    }

    // ==================== Sequence ID Generator ====================

    /**
     * Sequence ID 生成器（线程安全，从 1 开始每次递增）。
     */
    public static class SeqGenerator {
        private int seq;

        public SeqGenerator() {
            this.seq = 1;
        }

        public byte next() {
            return (byte) ((seq++) & 0xFF);
        }

        /** 查看下一个 seq 值（不递增），用于调试日志 */
        public byte peek() {
            return (byte) (seq & 0xFF);
        }
    }
}
