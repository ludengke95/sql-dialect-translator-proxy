package com.translator.proxy.protocol.frontend;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

import io.netty.channel.ChannelHandlerContext;

/**
 * 响应写入器接口 —— 将内部执行结果按协议格式编码并写入 Netty Channel。
 *
 * <p>不同协议（MySQL / PostgreSQL）对 OK、ERR、ColumnDef、EOF、TextRow
 * 的线协议格式不同，由各协议模块实现此接口。
 */
public interface ResponseWriter {

    /**
     * 写入 OK 包。
     *
     * @param sequenceNumber 协议序列号（MySQL 协议逐包递增，PG 协议忽略）
     * @param ctx            Netty 上下文
     * @param affectedRows   受影响行数
     * @param lastInsertId   最后插入 ID
     * @param statusFlags    状态标志位
     * @param warnings       警告数
     * @param info           附加信息（如 "Rows matched: 1  Changed: 1"）
     */
    void writeOk(
            byte sequenceNumber,
            ChannelHandlerContext ctx,
            long affectedRows,
            long lastInsertId,
            int statusFlags,
            int warnings,
            String info);

    /**
     * 写入 ERR 包。
     *
     * @param sequenceNumber 协议序列号（MySQL 协议逐包递增，PG 协议忽略）
     * @param ctx            Netty 上下文
     * @param errorCode      错误码
     * @param sqlState       SQL 状态码（5 字符）
     * @param message        错误消息
     * @return 写入操作的 ChannelFuture
     */
    io.netty.channel.ChannelFuture writeErr(
            byte sequenceNumber, ChannelHandlerContext ctx, int errorCode, String sqlState, String message);

    /**
     * 写入 ColumnDefinition 包。
     *
     * @param ctx         Netty 上下文
     * @param metaData    JDBC 结果集元数据
     * @param columnIndex 列索引（1-based）
     * @throws SQLException 如果 JDBC 元数据访问失败
     */
    void writeColumnDef(ChannelHandlerContext ctx, ResultSetMetaData metaData, int columnIndex) throws SQLException;

    /**
     * 写入 EOF 包（或 OK 包，用于标记列定义/行数据结束）。
     *
     * @param ctx         Netty 上下文
     * @param statusFlags 状态标志位
     */
    void writeEof(ChannelHandlerContext ctx, int statusFlags);

    /**
     * 写入文本协议行数据包。
     *
     * @param ctx         Netty 上下文
     * @param rs          JDBC 结果集（当前行）
     * @param columnCount 列数
     * @throws SQLException 如果 JDBC 数据访问失败
     */
    void writeTextRow(ChannelHandlerContext ctx, ResultSet rs, int columnCount) throws SQLException;

    /**
     * 写入完整结果集（按协议格式编码列定义 + 行数据 + 结束标记）。
     *
     * <p>不同协议的消息序列不同（MySQL: ColumnCount + ColumnDef + EOF + Rows + EOF；
     * PG: RowDescription + Rows + CommandComplete + ReadyForQuery），由各实现自行决定。
     *
     * @param ctx Netty 上下文
     * @param rs  JDBC 结果集（游标位于第一行之前）
     * @return 写入的行数
     * @throws SQLException 如果 JDBC 访问失败
     */
    int writeResultSet(ChannelHandlerContext ctx, ResultSet rs) throws SQLException;
}
