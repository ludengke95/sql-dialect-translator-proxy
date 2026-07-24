package com.translator.proxy.server.config;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.translator.proxy.backend.BackendEntry;

/**
 * ConfigWatcher 单元测试 —— 测试 TargetConfig → BackendEntry 转换和差异对比逻辑。
 */
public class ConfigWatcherTest {

    // ==================== toBackendEntry ====================

    @Test
    public void testToBackendEntryBasic() {
        ProxyConfig.TargetConfig tc = new ProxyConfig.TargetConfig();
        tc.setName("mydb");
        tc.setDialect("POSTGRESQL");
        tc.setJdbcUrl("jdbc:postgresql://localhost:5432/mydb");
        tc.setUsername("user");
        tc.setPassword("pass");
        tc.setMaxPoolSize(10);
        tc.setMinIdle(3);

        BackendEntry be = ConfigWatcher.toBackendEntry(tc);

        assertEquals("mydb", be.getName());
        assertEquals("POSTGRESQL", be.getDialect());
        assertEquals("jdbc:postgresql://localhost:5432/mydb", be.getJdbcUrl());
        assertEquals("user", be.getUsername());
        assertEquals("pass", be.getPassword());
        assertEquals(10, be.getMaxPoolSize());
        assertEquals(3, be.getMinIdle());
    }

    @Test
    public void testToBackendEntryWithTranslation() {
        ProxyConfig.TargetConfig tc = new ProxyConfig.TargetConfig();
        tc.setName("mydb");
        tc.setDialect("ORACLE");
        tc.setJdbcUrl("jdbc:oracle:thin:@localhost:1521:xe");

        ProxyConfig.TranslationConf tr = new ProxyConfig.TranslationConf();
        tr.setKeywordCase("LOWER");
        tr.setIdentifierCase("UPPER");
        tc.setTranslation(tr);

        BackendEntry be = ConfigWatcher.toBackendEntry(tc);

        assertEquals("LOWER", be.getKeywordCase());
        assertEquals("UPPER", be.getIdentifierCase());
    }

    @Test
    public void testToBackendEntryWithoutTranslation() {
        ProxyConfig.TargetConfig tc = new ProxyConfig.TargetConfig();
        tc.setName("mydb");
        tc.setDialect("MYSQL");
        tc.setJdbcUrl("jdbc:mysql://localhost:3306/mydb");

        BackendEntry be = ConfigWatcher.toBackendEntry(tc);

        assertNull(be.getKeywordCase());
        assertNull(be.getIdentifierCase());
    }

    // ==================== TargetConfig equals 验证 ====================

    @Test
    public void testTargetConfigEqualsSame() {
        ProxyConfig.TargetConfig a = createTargetConfig("db1", "POSTGRESQL", "jdbc:postgresql://localhost:5432/db1");
        ProxyConfig.TargetConfig b = createTargetConfig("db1", "POSTGRESQL", "jdbc:postgresql://localhost:5432/db1");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void testTargetConfigEqualsDifferentJdbcUrl() {
        ProxyConfig.TargetConfig a = createTargetConfig("db1", "POSTGRESQL", "jdbc:postgresql://oldhost:5432/db1");
        ProxyConfig.TargetConfig b = createTargetConfig("db1", "POSTGRESQL", "jdbc:postgresql://newhost:5432/db1");

        assertNotEquals(a, b);
    }

    @Test
    public void testTargetConfigEqualsDifferentPoolSize() {
        ProxyConfig.TargetConfig a = createTargetConfig("db1", "POSTGRESQL", "jdbc:postgresql://localhost:5432/db1");
        a.setMaxPoolSize(10);

        ProxyConfig.TargetConfig b = createTargetConfig("db1", "POSTGRESQL", "jdbc:postgresql://localhost:5432/db1");
        b.setMaxPoolSize(20);

        assertNotEquals(a, b);
    }

    @Test
    public void testTargetConfigEqualsDifferentTranslation() {
        ProxyConfig.TargetConfig a = createTargetConfig("db1", "POSTGRESQL", "jdbc:postgresql://localhost:5432/db1");
        ProxyConfig.TranslationConf trA = new ProxyConfig.TranslationConf();
        trA.setKeywordCase("UPPER");
        a.setTranslation(trA);

        ProxyConfig.TargetConfig b = createTargetConfig("db1", "POSTGRESQL", "jdbc:postgresql://localhost:5432/db1");
        ProxyConfig.TranslationConf trB = new ProxyConfig.TranslationConf();
        trB.setKeywordCase("LOWER");
        b.setTranslation(trB);

        assertNotEquals(a, b);
    }

    @Test
    public void testTargetConfigNotEqualsDifferentName() {
        ProxyConfig.TargetConfig a = createTargetConfig("db1", "POSTGRESQL", "jdbc:postgresql://localhost:5432/db1");
        ProxyConfig.TargetConfig b = createTargetConfig("db2", "POSTGRESQL", "jdbc:postgresql://localhost:5432/db1");

        assertNotEquals(a, b);
    }

    // ==================== 差异场景验证 ====================

    /**
     * 模拟：新增 backend
     */
    @Test
    public void testDiffNewBackend() {
        List<ProxyConfig.TargetConfig> oldList =
                Arrays.asList(createTargetConfig("db1", "POSTGRESQL", "jdbc:pg://host1/db1"));
        List<ProxyConfig.TargetConfig> newList = Arrays.asList(
                createTargetConfig("db1", "POSTGRESQL", "jdbc:pg://host1/db1"),
                createTargetConfig("db2", "MYSQL", "jdbc:mysql://host2/db2"));

        // db2 在 new 中存在，old 中不存在 → 应新增
        assertTrue("db1 should exist in old", findByPath(oldList, "db1"));
        assertTrue("db1 should exist in new", findByPath(newList, "db1"));
        assertFalse("db2 should not exist in old", findByPath(oldList, "db2"));
        assertTrue("db2 should exist in new", findByPath(newList, "db2"));
    }

    /**
     * 模拟：删除 backend
     */
    @Test
    public void testDiffRemovedBackend() {
        List<ProxyConfig.TargetConfig> oldList = Arrays.asList(
                createTargetConfig("db1", "POSTGRESQL", "jdbc:pg://host1/db1"),
                createTargetConfig("db2", "MYSQL", "jdbc:mysql://host2/db2"));
        List<ProxyConfig.TargetConfig> newList =
                Arrays.asList(createTargetConfig("db1", "POSTGRESQL", "jdbc:pg://host1/db1"));

        // db2 在 old 中存在，new 中不存在 → 应删除
        assertTrue("db2 should exist in old", findByPath(oldList, "db2"));
        assertFalse("db2 should not exist in new", findByPath(newList, "db2"));
    }

    /**
     * 模拟：backend 配置变更（同名但不同配置）
     */
    @Test
    public void testDiffChangedBackend() {
        ProxyConfig.TargetConfig oldTc = createTargetConfig("db1", "POSTGRESQL", "jdbc:pg://oldhost/db1");
        oldTc.setMaxPoolSize(10);

        ProxyConfig.TargetConfig newTc = createTargetConfig("db1", "POSTGRESQL", "jdbc:pg://newhost/db1");
        newTc.setMaxPoolSize(20);

        // 同名但不 equals
        assertEquals("Names should match", oldTc.getName(), newTc.getName());
        assertNotEquals("Configs should differ", oldTc, newTc);
    }

    /**
     * 模拟：backend 配置不变（同名且相同配置）—— 应跳过
     */
    @Test
    public void testDiffUnchangedBackend() {
        ProxyConfig.TargetConfig oldTc = createTargetConfig("db1", "POSTGRESQL", "jdbc:pg://host1/db1");
        oldTc.setMaxPoolSize(10);

        ProxyConfig.TargetConfig newTc = createTargetConfig("db1", "POSTGRESQL", "jdbc:pg://host1/db1");
        newTc.setMaxPoolSize(10);

        // 同名且 equals
        assertEquals("Names should match", oldTc.getName(), newTc.getName());
        assertEquals("Configs should be equal", oldTc, newTc);
    }

    // ==================== 辅助方法 ====================

    private ProxyConfig.TargetConfig createTargetConfig(String name, String dialect, String jdbcUrl) {
        ProxyConfig.TargetConfig tc = new ProxyConfig.TargetConfig();
        tc.setName(name);
        tc.setDialect(dialect);
        tc.setJdbcUrl(jdbcUrl);
        tc.setUsername("user");
        tc.setPassword("pass");
        tc.setMaxPoolSize(10);
        tc.setMinIdle(2);
        return tc;
    }

    private boolean findByPath(List<ProxyConfig.TargetConfig> list, String name) {
        for (ProxyConfig.TargetConfig tc : list) {
            if (name.equals(tc.getName())) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void testConfigLoaderMaxAllowedPacket() throws Exception {
        String yaml = "proxy:\n" + "  port: 3306\n"
                + "  max-allowed-packet: 123456\n"
                + "  auth:\n"
                + "    user: root\n"
                + "    password: proxy_password\n";
        java.io.File tempFile = java.io.File.createTempFile("proxy-config-test", ".yml");
        tempFile.deleteOnExit();
        try (java.io.FileWriter writer = new java.io.FileWriter(tempFile)) {
            writer.write(yaml);
        }

        ProxyConfig config = ConfigLoader.loadFromFileOrNull(tempFile.getAbsolutePath());
        assertNotNull(config);
        assertEquals(3306, config.getPort());
        assertEquals(123456, config.getMaxAllowedPacket());
    }
}
