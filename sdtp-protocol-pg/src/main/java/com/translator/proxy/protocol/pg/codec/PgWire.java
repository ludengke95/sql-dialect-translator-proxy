package com.translator.proxy.protocol.pg.codec;

import java.nio.charset.StandardCharsets;

import io.netty.buffer.ByteBuf;

/**
 * PostgreSQL 线协议常量与辅助方法。
 *
 * <p>定义 PG 协议消息类型码、认证类型、SSL 码等常量，
 * 以及包构造所需的基础辅助方法（cstr、int32、int16）。
 */
public final class PgWire {

    private PgWire() {}

    // ==================== 消息类型码 ====================

    /** Authentication */
    public static final byte MSG_AUTHENTICATION = 'R';

    /** BackendKeyData */
    public static final byte MSG_BACKEND_KEY_DATA = 'K';

    /** ParameterStatus */
    public static final byte MSG_PARAMETER_STATUS = 'S';

    /** ReadyForQuery */
    public static final byte MSG_READY_FOR_QUERY = 'Z';

    /** Simple Query */
    public static final byte MSG_QUERY = 'Q';

    /** Parse */
    public static final byte MSG_PARSE = 'P';

    /** Bind */
    public static final byte MSG_BIND = 'B';

    /** Execute */
    public static final byte MSG_EXECUTE = 'E';

    /** Describe */
    public static final byte MSG_DESCRIBE = 'D';

    /** Close */
    public static final byte MSG_CLOSE = 'C';

    /** Flush */
    public static final byte MSG_FLUSH = 'H';

    /** Terminate */
    public static final byte MSG_TERMINATE = 'X';

    /** CommandComplete */
    public static final byte MSG_COMMAND_COMPLETE = 'C';

    /** RowDescription */
    public static final byte MSG_ROW_DESCRIPTION = 'T';

    /** DataRow */
    public static final byte MSG_DATA_ROW = 'D';

    /** EmptyQueryResponse */
    public static final byte MSG_EMPTY_QUERY_RESPONSE = 'I';

    /** ErrorResponse */
    public static final byte MSG_ERROR_RESPONSE = 'E';

    /** NoticeResponse */
    public static final byte MSG_NOTICE_RESPONSE = 'N';

    /** ParseComplete */
    public static final byte MSG_PARSE_COMPLETE = '1';

    /** BindComplete */
    public static final byte MSG_BIND_COMPLETE = '2';

    /** NoData */
    public static final byte MSG_NO_DATA = 'n';

    /** ParameterDescription */
    public static final byte MSG_PARAMETER_DESCRIPTION = 't';

    /** SSL Request 特殊码（协议版本 1234.5679） */
    public static final int SSL_REQUEST_CODE = 80877103;

    // ==================== 认证类型 ====================

    /** AuthenticationOk */
    public static final int AUTH_OK = 0;

    /** AuthenticationMD5Password */
    public static final int AUTH_MD5_PASSWORD = 5;

    /** AuthenticationCleartextPassword */
    public static final int AUTH_CLEARTEXT_PASSWORD = 3;

    // ==================== 事务状态 ====================

    /** Idle */
    public static final byte TXN_IDLE = 'I';

    /** In transaction block */
    public static final byte TXN_IN_TRANSACTION = 'T';

    /** In failed transaction block */
    public static final byte TXN_ERROR = 'E';

    // ==================== 协议版本 ====================

    /** PostgreSQL 协议版本 3.0 */
    public static final int PROTOCOL_VERSION_3_0 = 196608;

    /** Cancel 请求码 */
    public static final int CANCEL_REQUEST_CODE = 80877102;

    // ==================== 包构造辅助方法 ====================

    /**
     * 写入 C 风格字符串（NUL-terminated UTF-8）。
     */
    public static void cstr(ByteBuf buf, String value) {
        if (value != null) {
            buf.writeBytes(value.getBytes(StandardCharsets.UTF_8));
        }
        buf.writeByte(0x00);
    }

    /**
     * 写入 int32（4 字节大端序）。
     */
    public static void int32(ByteBuf buf, int value) {
        buf.writeInt(value);
    }

    /**
     * 写入 int16（2 字节大端序）。
     */
    public static void int16(ByteBuf buf, int value) {
        buf.writeShort(value);
    }

    /**
     * 构建完整的 PG 消息帧（类型码 + 长度 + payload）。
     *
     * <p>长度字段 = 4 (自身) + payload 可读字节数。
     */
    public static ByteBuf buildMessage(byte type, ByteBuf payload) {
        int payloadLen = payload.readableBytes();
        ByteBuf frame = payload.alloc().buffer(1 + 4 + payloadLen);
        frame.writeByte(type);
        frame.writeInt(4 + payloadLen);
        frame.writeBytes(payload);
        payload.release();
        return frame;
    }
}
