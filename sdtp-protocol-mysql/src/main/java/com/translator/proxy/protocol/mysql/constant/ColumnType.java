package com.translator.proxy.protocol.mysql.constant;

/**
 * MySQL 协议列类型常量（用于 ColumnDefinition41 包中的 columnType 字段）。
 */
public final class ColumnType {

    private ColumnType() {}

    public static final int FIELD_TYPE_DECIMAL = 0x00;
    public static final int FIELD_TYPE_TINY = 0x01;
    public static final int FIELD_TYPE_SHORT = 0x02;
    public static final int FIELD_TYPE_LONG = 0x03;
    public static final int FIELD_TYPE_FLOAT = 0x04;
    public static final int FIELD_TYPE_DOUBLE = 0x05;
    public static final int FIELD_TYPE_NULL = 0x06;
    public static final int FIELD_TYPE_TIMESTAMP = 0x07;
    public static final int FIELD_TYPE_LONGLONG = 0x08;
    public static final int FIELD_TYPE_INT24 = 0x09;
    public static final int FIELD_TYPE_DATE = 0x0A;
    public static final int FIELD_TYPE_TIME = 0x0B;
    public static final int FIELD_TYPE_DATETIME = 0x0C;
    public static final int FIELD_TYPE_YEAR = 0x0D;
    public static final int FIELD_TYPE_NEWDATE = 0x0E;
    public static final int FIELD_TYPE_VARCHAR = 0x0F;
    public static final int FIELD_TYPE_BIT = 0x10;
    public static final int FIELD_TYPE_NEWDECIMAL = 0xF6;
    public static final int FIELD_TYPE_ENUM = 0xF7;
    public static final int FIELD_TYPE_SET = 0xF8;
    public static final int FIELD_TYPE_TINY_BLOB = 0xF9;
    public static final int FIELD_TYPE_MEDIUM_BLOB = 0xFA;
    public static final int FIELD_TYPE_LONG_BLOB = 0xFB;
    public static final int FIELD_TYPE_BLOB = 0xFC;
    public static final int FIELD_TYPE_VAR_STRING = 0xFD;
    public static final int FIELD_TYPE_STRING = 0xFE;
    public static final int FIELD_TYPE_GEOMETRY = 0xFF;
}
