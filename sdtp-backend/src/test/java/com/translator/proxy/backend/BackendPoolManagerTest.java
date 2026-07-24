package com.translator.proxy.backend;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.translator.core.config.TranslationConfig;
import com.translator.proxy.core.session.FrontendSession;

/**
 * BackendPoolManager 单元测试 —— 测试动态增删改。
 */
public class BackendPoolManagerTest {

    private BackendPoolManager manager;
    private TranslationConfig defaultTr;

    @Before
    public void setUp() {
        defaultTr = new TranslationConfig()
                .withKeywordCase(TranslationConfig.KeywordCase.UPPER)
                .withIdentifierCase(TranslationConfig.IdentifierCase.LOWER);
    }

    @After
    public void tearDown() {
        if (manager != null) {
            manager.close();
        }
    }

    // ==================== 构造函数 ====================

    @Test
    public void testConstructorEmptyBackends() {
        manager = new BackendPoolManager(Collections.<BackendEntry>emptyList(), defaultTr);

        Set<String> names = manager.getBackendNames();
        assertTrue("Should be empty", names.isEmpty());
        assertNotNull("Default processor should not be null", manager.getDefaultProcessor());
    }

    @Test
    public void testConstructorSkipsEmptyName() {
        BackendEntry emptyName = new BackendEntry("", "POSTGRESQL", "jdbc:h2:mem:skip", "sa", "", 5, 1);

        manager = new BackendPoolManager(Arrays.asList(emptyName), defaultTr);

        assertTrue("Should skip empty name", manager.getBackendNames().isEmpty());
    }

    @Test
    public void testConstructorWithValidBackend() {
        BackendEntry be = new BackendEntry("h2db", "MYSQL", "jdbc:h2:mem:constr", "sa", "", 5, 1);

        manager = new BackendPoolManager(Arrays.asList(be), defaultTr);

        assertEquals("Should have 1 backend", 1, manager.getBackendNames().size());
        assertTrue("Should contain 'h2db'", manager.getBackendNames().contains("h2db"));
    }

    // ==================== 路由 ====================

    @Test
    public void testResolveReturnsDefaultForUnknownDatabase() {
        BackendEntry be = new BackendEntry("h2db", "MYSQL", "jdbc:h2:mem:route1", "sa", "", 5, 1);
        manager = new BackendPoolManager(Arrays.asList(be), defaultTr);

        FrontendSession session = createSession(null);
        assertNotNull("Should resolve to default", manager.resolve(session));

        session = createSession("nonexistent");
        assertNotNull("Should resolve to default for unknown db", manager.resolve(session));
    }

    @Test
    public void testResolveReturnsNamedBackend() {
        BackendEntry h2db = new BackendEntry("h2db", "MYSQL", "jdbc:h2:mem:route2_h2db", "sa", "", 5, 1);
        BackendEntry pgdb = new BackendEntry("pgdb", "MYSQL", "jdbc:h2:mem:route2_pgdb", "sa", "", 5, 1);
        manager = new BackendPoolManager(Arrays.asList(h2db, pgdb), defaultTr);

        FrontendSession session = createSession("pgdb");
        assertNotNull("Should resolve to pgdb", manager.resolve(session));

        // 不是同一个实例（包装在 ReloadableQueryProcessor 中），但应该非空
        assertNotNull("Should not be null", manager.getProcessor("pgdb"));
        assertNotNull("Should not be null", manager.getProcessor("h2db"));
    }

    // ==================== 动态添加 ====================

    @Test
    public void testAddBackend() {
        manager = new BackendPoolManager(Collections.<BackendEntry>emptyList(), defaultTr);

        BackendEntry newBe = new BackendEntry("newdb", "MYSQL", "jdbc:h2:mem:add1", "sa", "", 5, 1);

        boolean added = manager.addBackend(newBe);
        assertTrue("Should add successfully", added);
        assertEquals("Should have 1 backend", 1, manager.getBackendNames().size());
        assertTrue("Should contain 'newdb'", manager.getBackendNames().contains("newdb"));
    }

    @Test
    public void testAddBackendDuplicateNameReturnsFalse() {
        BackendEntry be = new BackendEntry("dupdb", "MYSQL", "jdbc:h2:mem:dup1", "sa", "", 5, 1);
        manager = new BackendPoolManager(Arrays.asList(be), defaultTr);

        // 尝试添加同名
        BackendEntry dup = new BackendEntry("dupdb", "MYSQL", "jdbc:h2:mem:dup2", "sa", "", 5, 1);
        boolean added = manager.addBackend(dup);
        assertFalse("Should not add duplicate name", added);
        assertEquals("Should still have 1 backend", 1, manager.getBackendNames().size());
    }

    @Test
    public void testAddBackendEmptyNameReturnsFalse() {
        manager = new BackendPoolManager(Collections.<BackendEntry>emptyList(), defaultTr);

        BackendEntry emptyName = new BackendEntry("", "MYSQL", "jdbc:h2:mem:empty", "sa", "", 1, 1);

        boolean added = manager.addBackend(emptyName);
        assertFalse("Should not add empty name", added);
        assertTrue("Should remain empty", manager.getBackendNames().isEmpty());
    }

    // ==================== 动态删除 ====================

    @Test
    public void testRemoveBackend() {
        BackendEntry be = new BackendEntry("rmdb", "MYSQL", "jdbc:h2:mem:rm1", "sa", "", 5, 1);
        manager = new BackendPoolManager(Arrays.asList(be), defaultTr);
        assertEquals("Should have 1 backend", 1, manager.getBackendNames().size());

        boolean removed = manager.removeBackend("rmdb");
        assertTrue("Should remove successfully", removed);
        assertTrue("Should be empty", manager.getBackendNames().isEmpty());
    }

    @Test
    public void testRemoveNonexistentBackendReturnsFalse() {
        manager = new BackendPoolManager(Collections.<BackendEntry>emptyList(), defaultTr);

        boolean removed = manager.removeBackend("nosuch");
        assertFalse("Should return false for nonexistent", removed);
    }

    // ==================== 动态 reload ====================

    @Test
    public void testReloadBackend() {
        BackendEntry be = new BackendEntry("reloaddb", "MYSQL", "jdbc:h2:mem:reload1", "sa", "", 5, 1);
        manager = new BackendPoolManager(Arrays.asList(be), defaultTr);

        // 修改配置
        BackendEntry updated = new BackendEntry("reloaddb", "POSTGRESQL", "jdbc:h2:mem:reload2", "sa", "", 10, 3);

        boolean reloaded = manager.reloadBackend(updated);
        assertTrue("Should reload successfully", reloaded);
        assertEquals("Should still have 1 backend", 1, manager.getBackendNames().size());
    }

    @Test
    public void testReloadNonexistentBackendReturnsFalse() {
        manager = new BackendPoolManager(Collections.<BackendEntry>emptyList(), defaultTr);

        BackendEntry be = new BackendEntry("nosuch", "MYSQL", "jdbc:h2:mem:nosuch", "sa", "", 5, 1);
        boolean reloaded = manager.reloadBackend(be);
        assertFalse("Should return false for nonexistent", reloaded);
    }

    // ==================== close ====================

    @Test
    public void testCloseAll() {
        BackendEntry be1 = new BackendEntry("db1", "MYSQL", "jdbc:h2:mem:close1", "sa", "", 5, 1);
        BackendEntry be2 = new BackendEntry("db2", "MYSQL", "jdbc:h2:mem:close2", "sa", "", 5, 1);
        manager = new BackendPoolManager(Arrays.asList(be1, be2), defaultTr);

        manager.close();
        // close 不应抛异常，且不应影响 map（keySet 仍可访问，但 processor 已关闭）
        assertEquals(
                "Backend names should still be accessible",
                2,
                manager.getBackendNames().size());
    }

    // ==================== 辅助方法 ====================

    private FrontendSession createSession(String database) {
        return new FrontendSession(null, 1) {
            @Override
            public String getDatabase() {
                return database;
            }
        };
    }
}
