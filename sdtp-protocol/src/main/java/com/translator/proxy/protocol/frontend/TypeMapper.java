package com.translator.proxy.protocol.frontend;

/**
 * 类型映射器接口 —— 将 JDBC 类型映射为协议特定的列类型标识。
 */
public interface TypeMapper {

    /**
     * 将 JDBC 类型（{@link java.sql.Types} 常量）映射为协议列类型。
     *
     * @param jdbcType JDBC 类型常量
     * @param typeName JDBC 类型名称（用于辅助判断）
     * @return 协议列类型标识（MySQL: ColumnType 常量, PG: OID）
     */
    int jdbcToProtocolType(int jdbcType, String typeName);
}
