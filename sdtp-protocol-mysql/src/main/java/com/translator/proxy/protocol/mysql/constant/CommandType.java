package com.translator.proxy.protocol.mysql.constant;

/**
 * MySQL 协议命令类型（客户端→服务端 Command Phase 的第一个字节）。
 */
public final class CommandType {

    private CommandType() {}

    /** 0x00: COM_SLEEP（内部使用，客户端不会发） */
    public static final int COM_SLEEP = 0x00;
    /** 0x01: COM_QUIT — 关闭连接 */
    public static final int COM_QUIT = 0x01;
    /** 0x02: COM_INIT_DB — 切换数据库 */
    public static final int COM_INIT_DB = 0x02;
    /** 0x03: COM_QUERY — 执行 SQL 查询 */
    public static final int COM_QUERY = 0x03;
    /** 0x04: COM_FIELD_LIST — 获取表的字段列表 */
    public static final int COM_FIELD_LIST = 0x04;
    /** 0x05: COM_CREATE_DB — 创建数据库 */
    public static final int COM_CREATE_DB = 0x05;
    /** 0x06: COM_DROP_DB — 删除数据库 */
    public static final int COM_DROP_DB = 0x06;
    /** 0x07: COM_REFRESH — 刷新 */
    public static final int COM_REFRESH = 0x07;
    /** 0x0E: COM_PING — 心跳检测 */
    public static final int COM_PING = 0x0E;
    /** 0x0F: COM_STATISTICS — 获取统计信息 */
    public static final int COM_STATISTICS = 0x0F;
    /** 0x11: COM_STMT_PREPARE — 预编译语句 */
    public static final int COM_STMT_PREPARE = 0x16;
    /** 0x17: COM_STMT_EXECUTE — 执行预编译语句 */
    public static final int COM_STMT_EXECUTE = 0x17;
    /** 0x19: COM_STMT_CLOSE — 关闭预编译语句 */
    public static final int COM_STMT_CLOSE = 0x19;
    /** 0x1A: COM_STMT_RESET — 重置预编译语句 */
    public static final int COM_STMT_RESET = 0x1A;
    /** 0x1B: COM_SET_OPTION — 设置选项 */
    public static final int COM_SET_OPTION = 0x1B;
    /** 0x1C: COM_STMT_FETCH — 获取预编译语句结果行 */
    public static final int COM_STMT_FETCH = 0x1C;
    /** 0x1F: COM_RESET_CONNECTION — 重置连接 */
    public static final int COM_RESET_CONNECTION = 0x1F;

    /**
     * 返回命令类型的可读名称，用于日志。
     */
    public static String nameOf(int command) {
        switch (command) {
            case COM_SLEEP:
                return "COM_SLEEP";
            case COM_QUIT:
                return "COM_QUIT";
            case COM_INIT_DB:
                return "COM_INIT_DB";
            case COM_QUERY:
                return "COM_QUERY";
            case COM_FIELD_LIST:
                return "COM_FIELD_LIST";
            case COM_CREATE_DB:
                return "COM_CREATE_DB";
            case COM_DROP_DB:
                return "COM_DROP_DB";
            case COM_REFRESH:
                return "COM_REFRESH";
            case COM_PING:
                return "COM_PING";
            case COM_STATISTICS:
                return "COM_STATISTICS";
            case COM_STMT_PREPARE:
                return "COM_STMT_PREPARE";
            case COM_STMT_EXECUTE:
                return "COM_STMT_EXECUTE";
            case COM_STMT_CLOSE:
                return "COM_STMT_CLOSE";
            case COM_STMT_RESET:
                return "COM_STMT_RESET";
            case COM_SET_OPTION:
                return "COM_SET_OPTION";
            case COM_STMT_FETCH:
                return "COM_STMT_FETCH";
            case COM_RESET_CONNECTION:
                return "COM_RESET_CONNECTION";
            default:
                return "UNKNOWN(0x" + Integer.toHexString(command) + ")";
        }
    }
}
