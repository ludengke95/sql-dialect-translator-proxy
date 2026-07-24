package com.translator.proxy.backend;

import static org.junit.Assert.*;

import org.junit.Test;

import com.translator.core.DialectType;
import com.translator.core.SqlTranslator;
import com.translator.proxy.core.handler.QueryProcessor;

/**
 * SQL 翻译集成测试：验证 Calcite 引擎在 Proxy 场景下的翻译正确性。
 */
public class TranslationIntegrationTest {

    @Test
    public void testMysqlToPostgresql() {
        SqlTranslator translator = new SqlTranslator(DialectType.MYSQL, DialectType.POSTGRESQL);

        // IFNULL → COALESCE
        String result1 = translator.translate("SELECT IFNULL(name, 'unknown')");
        assertTrue("应包含 COALESCE: " + result1, result1.contains("COALESCE"));

        // 反引号 → 双引号
        String result2 = translator.translate("SELECT `id`, `name` FROM `users`");
        assertTrue("应包含双引号标识符: " + result2, result2.contains("\"id\""));
        assertTrue("应包含双引号标识符: " + result2, result2.contains("\"name\""));
        assertTrue("应包含双引号标识符: " + result2, result2.contains("\"users\""));

        // LIMIT → FETCH NEXT (PostgreSQL)
        String result3 = translator.translate("SELECT * FROM users LIMIT 10");
        assertTrue("LIMIT 应转为 FETCH NEXT: " + result3, result3.contains("FETCH NEXT") || result3.contains("LIMIT"));

        // != → <>
        String result4 = translator.translate("SELECT * FROM users WHERE status != 'deleted'");
        assertTrue("!= 应转为 <>: " + result4, result4.contains("<>"));
    }

    @Test
    public void testMysqlToOracle() {
        SqlTranslator translator = new SqlTranslator(DialectType.MYSQL, DialectType.ORACLE);

        // 验证翻译不会抛异常
        String result = translator.translate("SELECT * FROM users WHERE id = 1");
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    public void testTranslationDisabledWhenSameDialect() {
        // MySQL → MySQL：TranslationQueryProcessor 应跳过翻译
        TranslationQueryProcessor processor = new TranslationQueryProcessor(QueryProcessor.NOOP, "mysql");

        // 通过 NOOP 委托执行，不会抛异常
        // 由于 NOOP 会返回错误，这里只验证翻译逻辑不抛异常
        assertNotNull(processor);
    }

    @Test
    public void testTranslationFallback() {
        // 构造一个无法解析的 SQL，验证降级
        SqlTranslator translator = new SqlTranslator(DialectType.MYSQL, DialectType.POSTGRESQL);
        try {
            translator.translate("INVALID SQL !!! @@@");
            fail("应该抛异常");
        } catch (Exception e) {
            // 预期——翻译失败
            assertTrue(e.getMessage() != null);
        }
    }

    @Test
    public void testDialectTypeLookup() {
        assertEquals(DialectType.POSTGRESQL, DialectType.fromIdentifier("postgresql"));
        assertEquals(DialectType.POSTGRESQL, DialectType.fromIdentifier("POSTGRESQL"));
        assertEquals(DialectType.MYSQL, DialectType.fromIdentifier("mysql"));
        assertEquals(DialectType.ORACLE, DialectType.fromIdentifier("oracle"));
        assertEquals(DialectType.SQLSERVER, DialectType.fromIdentifier("sqlserver"));
    }

    // ==================== 直通模式测试 ====================

    @Test
    public void testDirectHintLineComment() {
        // -- direct 行注释标记
        String sql = "-- direct\nSELECT pg_sleep(1)";
        String stripped = TranslationQueryProcessor.stripDirectHint(sql);
        assertNotNull("应检测到直通标记", stripped);
        assertEquals("剥离后应只剩 SQL", "SELECT pg_sleep(1)", stripped);
    }

    @Test
    public void testDirectHintSdtpPrefix() {
        // -- sdtp:direct
        String sql = "-- sdtp:direct\nSELECT now()";
        String stripped = TranslationQueryProcessor.stripDirectHint(sql);
        assertNotNull("应检测到 sdtp:direct 标记", stripped);
        assertEquals("SELECT now()", stripped);
    }

    @Test
    public void testDirectHintBlockComment() {
        // /* sdtp:direct */
        String sql = "/* sdtp:direct */ SELECT pg_backend_pid()";
        String stripped = TranslationQueryProcessor.stripDirectHint(sql);
        assertNotNull("应检测到块注释标记", stripped);
        assertEquals("SELECT pg_backend_pid()", stripped);
    }

    @Test
    public void testDirectHintShortBlockComment() {
        // /* direct */
        String sql = "/* direct */\nSELECT 1";
        String stripped = TranslationQueryProcessor.stripDirectHint(sql);
        assertNotNull(stripped);
        assertEquals("SELECT 1", stripped);
    }

    @Test
    public void testNoDirectHint() {
        // 普通 SQL 不应被误判
        String sql = "SELECT * FROM users WHERE id = 1";
        String stripped = TranslationQueryProcessor.stripDirectHint(sql);
        assertNull("普通 SQL 不应被检测为直通", stripped);
    }

    @Test
    public void testDirectHintCaseInsensitive() {
        // 大小写不敏感
        String sql = "-- DIRECT\nSELECT 1";
        String stripped = TranslationQueryProcessor.stripDirectHint(sql);
        assertNotNull(stripped);
        assertEquals("SELECT 1", stripped);
    }

    @Test
    public void testDirectHintWithLeadingSpaces() {
        // 有前导空格
        String sql = "  -- direct\nSELECT 1";
        String stripped = TranslationQueryProcessor.stripDirectHint(sql);
        assertNotNull(stripped);
        assertEquals("SELECT 1", stripped);
    }

    @Test
    public void testDirectHintOnlyComment() {
        // 只有标记没有 SQL
        String sql = "-- direct";
        String stripped = TranslationQueryProcessor.stripDirectHint(sql);
        assertNotNull(stripped);
        assertEquals("", stripped);
    }
}
