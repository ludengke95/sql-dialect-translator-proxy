package com.translator.proxy.protocol.mysql.result;

import java.sql.Types;

import com.translator.proxy.protocol.frontend.TypeMapper;
import com.translator.proxy.protocol.mysql.constant.ColumnType;

/**
 * MySQL 类型映射器 —— 将 JDBC 类型映射为 MySQL ColumnType 常量。
 *
 * <p>实现 {@link TypeMapper} 接口，将原 sdtp-backend 中
 * {@code TypeMapper} 的 JDBC → MySQL ColumnType 映射逻辑复制至此。
 */
public class MySQLTypeMapper implements TypeMapper {

    /** MySQL 字符集编号：utf8mb4_general_ci */
    public static final int CHARSET_UTF8MB4 = 33;

    /** MySQL 字符集编号：binary */
    public static final int CHARSET_BINARY = 63;

    @Override
    public int jdbcToProtocolType(int jdbcType, String typeName) {
        return jdbcToMysqlStatic(jdbcType);
    }

    /**
     * 将 JDBC Types 常量映射为 MySQL ColumnType 常量（静态方法，兼容旧代码）。
     */
    public static int jdbcToMysqlStatic(int jdbcType) {
        switch (jdbcType) {
            case Types.TINYINT:
            case Types.BIT:
                return ColumnType.FIELD_TYPE_TINY;

            case Types.SMALLINT:
                return ColumnType.FIELD_TYPE_SHORT;

            case Types.INTEGER:
                return ColumnType.FIELD_TYPE_LONG;

            case Types.BIGINT:
                return ColumnType.FIELD_TYPE_LONGLONG;

            case Types.REAL:
            case Types.FLOAT:
                return ColumnType.FIELD_TYPE_FLOAT;

            case Types.DOUBLE:
                return ColumnType.FIELD_TYPE_DOUBLE;

            case Types.DECIMAL:
            case Types.NUMERIC:
                return ColumnType.FIELD_TYPE_NEWDECIMAL;

            case Types.CHAR:
                return ColumnType.FIELD_TYPE_STRING;

            case Types.VARCHAR:
            case Types.LONGVARCHAR:
            case Types.NVARCHAR:
            case Types.LONGNVARCHAR:
                return ColumnType.FIELD_TYPE_VAR_STRING;

            case Types.DATE:
                return ColumnType.FIELD_TYPE_DATE;

            case Types.TIME:
            case Types.TIME_WITH_TIMEZONE:
                return ColumnType.FIELD_TYPE_TIME;

            case Types.TIMESTAMP:
            case Types.TIMESTAMP_WITH_TIMEZONE:
                return ColumnType.FIELD_TYPE_TIMESTAMP;

            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
                return ColumnType.FIELD_TYPE_BLOB;

            case Types.BLOB:
                return ColumnType.FIELD_TYPE_LONG_BLOB;

            case Types.CLOB:
                return ColumnType.FIELD_TYPE_MEDIUM_BLOB;

            case Types.BOOLEAN:
                return ColumnType.FIELD_TYPE_TINY;

            case Types.NULL:
                return ColumnType.FIELD_TYPE_NULL;

            default:
                return ColumnType.FIELD_TYPE_VAR_STRING;
        }
    }

    /**
     * 根据 JDBC Types 推断显示的列长度。
     */
    public static int defaultColumnLengthStatic(int jdbcType) {
        switch (jdbcType) {
            case Types.TINYINT:
            case Types.BIT:
            case Types.BOOLEAN:
                return 1;
            case Types.SMALLINT:
                return 2;
            case Types.INTEGER:
                return 4;
            case Types.BIGINT:
                return 8;
            case Types.REAL:
            case Types.FLOAT:
                return 12;
            case Types.DOUBLE:
                return 22;
            case Types.DECIMAL:
            case Types.NUMERIC:
                return 65;
            case Types.DATE:
                return 10;
            case Types.TIME:
            case Types.TIME_WITH_TIMEZONE:
                return 8;
            case Types.TIMESTAMP:
            case Types.TIMESTAMP_WITH_TIMEZONE:
                return 19;
            default:
                return 255;
        }
    }

    /**
     * 判断 JDBC 类型是否应使用二进制字符集。
     */
    public static boolean isBinaryStatic(int jdbcType) {
        switch (jdbcType) {
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
            case Types.BLOB:
                return true;
            default:
                return false;
        }
    }
}
