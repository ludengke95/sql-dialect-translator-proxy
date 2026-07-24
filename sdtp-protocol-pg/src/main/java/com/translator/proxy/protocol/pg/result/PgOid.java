package com.translator.proxy.protocol.pg.result;

/**
 * PostgreSQL OID 类型常量。
 *
 * <p>用于 RowDescription 消息中的列类型 OID 字段。
 */
public final class PgOid {

    private PgOid() {}

    public static final int BOOL = 16;
    public static final int BYTEA = 17;
    public static final int CHAR = 18;
    public static final int NAME = 19;
    public static final int INT8 = 20;
    public static final int INT2 = 21;
    public static final int INT4 = 23;
    public static final int TEXT = 25;
    public static final int OID = 26;
    public static final int FLOAT4 = 700;
    public static final int FLOAT8 = 701;
    public static final int VARCHAR = 1043;
    public static final int DATE = 1082;
    public static final int TIME = 1083;
    public static final int TIMESTAMP = 1114;
    public static final int TIMESTAMPTZ = 1184;
    public static final int NUMERIC = 1700;
    public static final int UUID = 2950;
    public static final int JSON = 114;
    public static final int JSONB = 3802;
}
