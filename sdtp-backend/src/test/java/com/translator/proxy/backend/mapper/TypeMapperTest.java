package com.translator.proxy.backend.mapper;

import static org.junit.Assert.*;

import java.sql.Types;

import org.junit.Test;

import com.translator.proxy.protocol.mysql.constant.ColumnType;

/**
 * TypeMapper 测试：JDBC Types → MySQL ColumnType 映射正确性。
 */
public class TypeMapperTest {

    @Test
    public void testIntegerTypes() {
        assertEquals(ColumnType.FIELD_TYPE_TINY, TypeMapper.jdbcToMysql(Types.TINYINT));
        assertEquals(ColumnType.FIELD_TYPE_SHORT, TypeMapper.jdbcToMysql(Types.SMALLINT));
        assertEquals(ColumnType.FIELD_TYPE_LONG, TypeMapper.jdbcToMysql(Types.INTEGER));
        assertEquals(ColumnType.FIELD_TYPE_LONGLONG, TypeMapper.jdbcToMysql(Types.BIGINT));
    }

    @Test
    public void testFloatTypes() {
        assertEquals(ColumnType.FIELD_TYPE_FLOAT, TypeMapper.jdbcToMysql(Types.FLOAT));
        assertEquals(ColumnType.FIELD_TYPE_FLOAT, TypeMapper.jdbcToMysql(Types.REAL));
        assertEquals(ColumnType.FIELD_TYPE_DOUBLE, TypeMapper.jdbcToMysql(Types.DOUBLE));
    }

    @Test
    public void testDecimalTypes() {
        assertEquals(ColumnType.FIELD_TYPE_NEWDECIMAL, TypeMapper.jdbcToMysql(Types.DECIMAL));
        assertEquals(ColumnType.FIELD_TYPE_NEWDECIMAL, TypeMapper.jdbcToMysql(Types.NUMERIC));
    }

    @Test
    public void testStringTypes() {
        assertEquals(ColumnType.FIELD_TYPE_STRING, TypeMapper.jdbcToMysql(Types.CHAR));
        assertEquals(ColumnType.FIELD_TYPE_VAR_STRING, TypeMapper.jdbcToMysql(Types.VARCHAR));
        assertEquals(ColumnType.FIELD_TYPE_VAR_STRING, TypeMapper.jdbcToMysql(Types.LONGVARCHAR));
    }

    @Test
    public void testDateTimeTypes() {
        assertEquals(ColumnType.FIELD_TYPE_DATE, TypeMapper.jdbcToMysql(Types.DATE));
        assertEquals(ColumnType.FIELD_TYPE_TIME, TypeMapper.jdbcToMysql(Types.TIME));
        assertEquals(ColumnType.FIELD_TYPE_TIMESTAMP, TypeMapper.jdbcToMysql(Types.TIMESTAMP));
    }

    @Test
    public void testBooleanType() {
        assertEquals(ColumnType.FIELD_TYPE_TINY, TypeMapper.jdbcToMysql(Types.BOOLEAN));
    }

    @Test
    public void testDefaultToVarString() {
        // 不认识的类型默认映射到 VAR_STRING
        assertEquals(ColumnType.FIELD_TYPE_VAR_STRING, TypeMapper.jdbcToMysql(Types.OTHER));
        assertEquals(ColumnType.FIELD_TYPE_VAR_STRING, TypeMapper.jdbcToMysql(Types.JAVA_OBJECT));
    }

    @Test
    public void testBinaryTypeIsBinary() {
        assertTrue(TypeMapper.isBinary(Types.BINARY));
        assertTrue(TypeMapper.isBinary(Types.VARBINARY));
        assertTrue(TypeMapper.isBinary(Types.BLOB));
        assertFalse(TypeMapper.isBinary(Types.VARCHAR));
        assertFalse(TypeMapper.isBinary(Types.INTEGER));
    }

    @Test
    public void testDefaultColumnLength() {
        assertEquals(1, TypeMapper.defaultColumnLength(Types.TINYINT));
        assertEquals(2, TypeMapper.defaultColumnLength(Types.SMALLINT));
        assertEquals(4, TypeMapper.defaultColumnLength(Types.INTEGER));
        assertEquals(8, TypeMapper.defaultColumnLength(Types.BIGINT));
        assertEquals(10, TypeMapper.defaultColumnLength(Types.DATE));
        assertEquals(19, TypeMapper.defaultColumnLength(Types.TIMESTAMP));
        assertEquals(255, TypeMapper.defaultColumnLength(Types.VARCHAR));
    }
}
