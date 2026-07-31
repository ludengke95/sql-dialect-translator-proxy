package com.translator.proxy.backend.metadata;

import static org.junit.Assert.*;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Set;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.Before;
import org.junit.Test;

import com.translator.core.metadata.TableMetadata;

public class BackendMetadataProviderTest {

    private JdbcDataSource dataSource;

    @Before
    public void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:test_meta_db;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS users");
            stmt.execute("CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(50), age INT)");
        }
    }

    @Test
    public void testGetTableNames() {
        BackendMetadataProvider provider = new BackendMetadataProvider(dataSource);
        Set<String> tableNames = provider.getTableNames();
        assertNotNull(tableNames);
        assertTrue("应包含 users 表（自适应包含原样与大小写）", tableNames.contains("USERS") || tableNames.contains("users"));
    }

    @Test
    public void testGetTableMetadata() {
        BackendMetadataProvider provider = new BackendMetadataProvider(dataSource);
        TableMetadata meta = provider.getTable("users");
        assertNotNull("应成功加载 users 表元数据", meta);
        assertEquals("users", meta.getTableName().toLowerCase());
        assertNotNull("应该包含列信息", meta.getColumns());
        assertEquals("应该包含 3 列", 3, meta.getColumns().size());
    }

    @Test
    public void testGetNonExistentTable() {
        BackendMetadataProvider provider = new BackendMetadataProvider(dataSource);
        TableMetadata meta = provider.getTable("not_exists");
        assertNull("不存在的表应返回 null", meta);
    }

    @Test
    public void testMaxTablesLimit() {
        BackendMetadataProvider provider = new BackendMetadataProvider(dataSource, 0);
        TableMetadata meta = provider.getTable("users");
        assertNull("当 maxTables 为 0 时拒绝加载新表列信息", meta);
    }
}
