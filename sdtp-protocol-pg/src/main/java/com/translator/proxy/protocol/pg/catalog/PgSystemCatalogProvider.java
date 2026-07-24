package com.translator.proxy.protocol.pg.catalog;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.translator.proxy.core.handler.BackendRouter;
import com.translator.proxy.core.session.FrontendSession;
import com.translator.proxy.protocol.frontend.SystemCatalogProvider;
import com.translator.proxy.protocol.pg.codec.PgMessage;
import com.translator.proxy.protocol.pg.codec.PgWire;
import com.translator.proxy.protocol.pg.result.PgOid;
import com.translator.proxy.protocol.pg.result.PgResponseWriter;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

/**
 * PostgreSQL 系统目录提供者 —— 模拟 pg_catalog 相关查询。
 *
 * <p>提供 PG 版本、字符集等系统变量，拦截常见的系统查询。
 */
public class PgSystemCatalogProvider implements SystemCatalogProvider {

    private static final Logger log = LoggerFactory.getLogger(PgSystemCatalogProvider.class);

    /** 后端路由器（用于 pg_database 枚举返回后端名） */
    private final BackendRouter backendRouter;

    private static final Pattern PG_DATABASE =
            Pattern.compile("^\\s*SELECT\\s+.*\\bFROM\\s+PG_DATABASE\\b", Pattern.CASE_INSENSITIVE);

    private final PgResponseWriter responseWriter = new PgResponseWriter();

    public PgSystemCatalogProvider(BackendRouter backendRouter) {
        this.backendRouter = backendRouter;
    }

    @Override
    public boolean canHandle(String sql) {
        if (sql == null) {
            return false;
        }
        String upper = sql.trim().toUpperCase();
        return upper.startsWith("SET ")
                || upper.startsWith("SHOW ")
                || upper.contains("VERSION()")
                || upper.contains("CURRENT_SCHEMA")
                || upper.contains("CURRENT_DATABASE")
                || upper.contains("PG_GET_KEYWORDS")
                || isPgDatabaseQuery(sql);
    }

    private static boolean isPgDatabaseQuery(String sql) {
        return sql != null && PG_DATABASE.matcher(sql.trim()).find();
    }

    @Override
    public void handleQuery(ChannelHandlerContext ctx, String sql, FrontendSession session) {
        if (sql == null || sql.trim().isEmpty()) {
            sendEmptyResult(ctx, "SELECT");
            return;
        }

        String upper = sql.trim().toUpperCase();

        if (upper.startsWith("SET ")) {
            // SET 语句：按照 PG 线协议标准返回 CommandComplete("SET")
            responseWriter.sendCommandComplete(ctx, "SET");
            return;
        }

        if (upper.contains("PG_GET_KEYWORDS")) {
            sendSingleValueResult(ctx, "string_agg", "select,from,where,insert,update,delete,table,into,values");
            return;
        }

        if (upper.startsWith("SELECT VERSION()") || upper.contains("VERSION()")) {
            sendSingleValueResult(ctx, "version", "PostgreSQL 14.8 (SDT Proxy)");
            return;
        }

        if (upper.startsWith("SELECT CURRENT_SCHEMA") || upper.contains("CURRENT_SCHEMA")) {
            String schema = session != null && session.getDatabase() != null ? session.getDatabase() : "public";
            sendSingleValueResult(ctx, "current_schema", schema);
            return;
        }

        if (upper.startsWith("SELECT CURRENT_DATABASE") || upper.contains("CURRENT_DATABASE")) {
            String db = session != null && session.getDatabase() != null ? session.getDatabase() : "";
            sendSingleValueResult(ctx, "current_database", db);
            return;
        }

        if (upper.startsWith("SHOW ")) {
            String varName = upper.substring(5).trim();
            String value = getVariables().get(varName.toLowerCase());
            if (value == null) value = "";
            sendSingleValueResult(ctx, varName.toLowerCase(), value);
            return;
        }

        if (isPgDatabaseQuery(sql)) {
            sendDatabasesResult(ctx, backendRouter.getBackendNames());
            return;
        }

        // 默认：返回空结果
        sendEmptyResult(ctx, "SELECT");
    }

    /**
     * 发送单行单列结果。
     */
    private void sendSingleValueResult(ChannelHandlerContext ctx, String colName, String value) {
        // RowDescription (1 column, text type)
        ByteBuf rowDesc = ctx.alloc().buffer(64);
        rowDesc.writeShort(1);
        PgWire.cstr(rowDesc, colName);
        rowDesc.writeInt(0); // table OID
        rowDesc.writeShort(0); // attr num
        rowDesc.writeInt(PgOid.TEXT); // type OID
        rowDesc.writeShort(-1); // type size
        rowDesc.writeInt(-1); // type modifier
        rowDesc.writeShort(0); // format
        ctx.write(new PgMessage(PgWire.MSG_ROW_DESCRIPTION, rowDesc));

        // DataRow
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        ByteBuf dataRow = ctx.alloc().buffer(4 + bytes.length);
        dataRow.writeShort(1);
        dataRow.writeInt(bytes.length);
        dataRow.writeBytes(bytes);
        ctx.write(new PgMessage(PgWire.MSG_DATA_ROW, dataRow));

        // CommandComplete
        responseWriter.sendCommandComplete(ctx, "SELECT 1");
    }

    /**
     * 发送空结果集。
     */
    private void sendEmptyResult(ChannelHandlerContext ctx, String tag) {
        // RowDescription with 0 columns
        ByteBuf rowDesc = ctx.alloc().buffer(2);
        rowDesc.writeShort(0);
        ctx.write(new PgMessage(PgWire.MSG_ROW_DESCRIPTION, rowDesc));

        responseWriter.sendCommandComplete(ctx, tag + " 0");
    }

    /**
     * 发送 pg_database 枚举结果（与 MySQL SHOW DATABASES 对称，返回后端名列表）。
     */
    private void sendDatabasesResult(ChannelHandlerContext ctx, Set<String> names) {
        // RowDescription (1 column: datname, text type)
        ByteBuf rowDesc = ctx.alloc().buffer(64);
        rowDesc.writeShort(1);
        PgWire.cstr(rowDesc, "datname");
        rowDesc.writeInt(0); // table OID
        rowDesc.writeShort(0); // attr num
        rowDesc.writeInt(PgOid.TEXT); // type OID
        rowDesc.writeShort(-1); // type size
        rowDesc.writeInt(-1); // type modifier
        rowDesc.writeShort(0); // format
        ctx.write(new PgMessage(PgWire.MSG_ROW_DESCRIPTION, rowDesc));

        for (String name : names) {
            byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
            ByteBuf dataRow = ctx.alloc().buffer(4 + bytes.length);
            dataRow.writeShort(1);
            dataRow.writeInt(bytes.length);
            dataRow.writeBytes(bytes);
            ctx.write(new PgMessage(PgWire.MSG_DATA_ROW, dataRow));
        }

        responseWriter.sendCommandComplete(ctx, "SELECT " + names.size());
    }

    @Override
    public Map<String, String> getVariables() {
        Map<String, String> vars = new HashMap<String, String>();
        vars.put("server_version", "14.8");
        vars.put("server_encoding", "UTF8");
        vars.put("client_encoding", "UTF8");
        vars.put("standard_conforming_strings", "on");
        vars.put("integer_datetimes", "on");
        vars.put("datestyle", "ISO, MDY");
        vars.put("timezone", "UTC");
        vars.put("max_connections", "100");
        vars.put("max_identifier_length", "63");
        vars.put("max_index_keys", "32");
        vars.put("block_size", "8192");
        vars.put("lc_collate", "en_US.UTF-8");
        vars.put("lc_ctype", "en_US.UTF-8");
        return vars;
    }
}
