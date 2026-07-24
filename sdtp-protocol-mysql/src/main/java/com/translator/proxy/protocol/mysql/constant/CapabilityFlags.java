package com.translator.proxy.protocol.mysql.constant;

/**
 * MySQL 协议 Capability Flags（客户端/服务端能力协商标志位）。
 * 参考 MySQL 官方文档：Protocol::CapabilityFlags
 */
public final class CapabilityFlags {

    private CapabilityFlags() {}

    public static final int CLIENT_LONG_PASSWORD = 0x00000001;
    public static final int CLIENT_FOUND_ROWS = 0x00000002;
    public static final int CLIENT_LONG_FLAG = 0x00000004;
    public static final int CLIENT_CONNECT_WITH_DB = 0x00000008;
    public static final int CLIENT_NO_SCHEMA = 0x00000010;
    public static final int CLIENT_COMPRESS = 0x00000020;
    public static final int CLIENT_ODBC = 0x00000040;
    public static final int CLIENT_LOCAL_FILES = 0x00000080;
    public static final int CLIENT_IGNORE_SPACE = 0x00000100;
    public static final int CLIENT_PROTOCOL_41 = 0x00000200;
    public static final int CLIENT_INTERACTIVE = 0x00000400;
    public static final int CLIENT_SSL = 0x00000800;
    public static final int CLIENT_IGNORE_SIGPIPE = 0x00001000;
    public static final int CLIENT_TRANSACTIONS = 0x00002000;
    public static final int CLIENT_RESERVED = 0x00004000;
    public static final int CLIENT_SECURE_CONNECTION = 0x00008000;
    public static final int CLIENT_MULTI_STATEMENTS = 0x00010000;
    public static final int CLIENT_MULTI_RESULTS = 0x00020000;
    public static final int CLIENT_PS_MULTI_RESULTS = 0x00040000;
    public static final int CLIENT_PLUGIN_AUTH = 0x00080000;
    public static final int CLIENT_CONNECT_ATTRS = 0x00100000;
    public static final int CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA = 0x00200000;
    public static final int CLIENT_CAN_HANDLE_EXPIRED_PASSWORDS = 0x00400000;
    public static final int CLIENT_SESSION_TRACK = 0x00800000;
    public static final int CLIENT_DEPRECATE_EOF = 0x01000000;

    /** Proxy 服务端支持的默认能力集 */
    public static final int SERVER_DEFAULT_CAPABILITIES = CLIENT_LONG_PASSWORD
            | CLIENT_FOUND_ROWS
            | CLIENT_LONG_FLAG
            | CLIENT_CONNECT_WITH_DB
            | CLIENT_NO_SCHEMA
            | CLIENT_PROTOCOL_41
            | CLIENT_TRANSACTIONS
            | CLIENT_SECURE_CONNECTION
            | CLIENT_MULTI_STATEMENTS
            | CLIENT_MULTI_RESULTS
            | CLIENT_PS_MULTI_RESULTS
            | CLIENT_PLUGIN_AUTH;
    // 注意：不开启 CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA（使用 1 字节长度格式，兼容性更好）
    // 不开启 CLIENT_DEPRECATE_EOF，使用传统 EOF 格式兼容所有客户端
}
