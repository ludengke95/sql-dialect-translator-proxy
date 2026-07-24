package com.translator.proxy.protocol.mysql.catalog;

import static org.junit.Assert.*;

import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.translator.proxy.protocol.mysql.codec.MySQLPacketEncoder;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;

/**
 * SystemVariableInterceptor 内联后的行为级回归测试。
 *
 * <p>覆盖 {@link MySQLSystemCatalogProvider} 的公开 API：
 * {@code canHandle}/{@code isSetStatement}/{@code extractUseDatabase}/
 * {@code getVariables}/{@code setSystemVariable}，以及通过 {@code EmbeddedChannel}
 * 轻量验证 {@code handleQuery} 的 Netty 写入路径。
 *
 * <p>注意：变量表为进程级 static 单例，{@code setSystemVariable} 会全局生效，
 * 因此在 {@link #tearDown()} 中还原 {@code max_allowed_packet} 初始值，避免污染其他用例。
 */
public class MySQLSystemCatalogProviderTest {

    /** max_allowed_packet 的初始值（来自 SYSTEM_VARIABLES 静态初始化） */
    private static final String MAX_PACKET_DEFAULT = "16777216";

    private MySQLSystemCatalogProvider provider;

    @Before
    public void setUp() {
        provider = new MySQLSystemCatalogProvider();
    }

    @After
    public void tearDown() {
        // 还原共享 static 变量表，避免污染其他用例 / 测试类
        MySQLSystemCatalogProvider.setSystemVariable("max_allowed_packet", MAX_PACKET_DEFAULT);
    }

    // ==================== canHandle ====================

    @Test
    public void testCanHandleSelectVersion() {
        assertTrue("SELECT @@version 应被拦截", provider.canHandle("SELECT @@version"));
    }

    @Test
    public void testCanHandleSelectVersionWithLimit() {
        assertTrue("SELECT @@version LIMIT 1 应被拦截", provider.canHandle("SELECT @@version LIMIT 1"));
    }

    @Test
    public void testCanHandleShowVariablesLike() {
        assertTrue("SHOW VARIABLES LIKE 'autocommit' 应被拦截", provider.canHandle("SHOW VARIABLES LIKE 'autocommit'"));
    }

    @Test
    public void testCanHandleSelectDatabase() {
        assertTrue("SELECT DATABASE() 应被拦截", provider.canHandle("SELECT DATABASE()"));
    }

    @Test
    public void testCanHandleShowWarnings() {
        assertTrue("SHOW WARNINGS 应被拦截", provider.canHandle("SHOW WARNINGS"));
    }

    @Test
    public void testCanHandleMultiColumnSystemVariables() {
        assertTrue("多列系统变量查询应被拦截", provider.canHandle("SELECT @@session.tx_isolation AS a, @@autocommit AS b"));
    }

    @Test
    public void testCanHandleConnectionCommentPrefixStripped() {
        // 连接建立时 MySQL Connector/J 8.0.33 会带上版本注释前缀，应被正确剥离后识别
        String sql = "/* mysql-connector-j-8.0.33 ... */ SELECT @@version";
        assertTrue("带连接注释前缀的 SELECT @@version 应被拦截", provider.canHandle(sql));
    }

    @Test
    public void testCanHandleSetStatement() {
        assertTrue("SET autocommit=1 经 isSetStatement 应被拦截", provider.canHandle("SET autocommit=1"));
    }

    @Test
    public void testCanHandleUseDatabase() {
        assertTrue("USE mydb 经 extractUseDatabase 应被拦截", provider.canHandle("USE mydb"));
    }

    @Test
    public void testCanHandlePlainSelectOneFalse() {
        assertFalse("普通 SELECT 1 不应被拦截", provider.canHandle("SELECT 1"));
    }

    @Test
    public void testCanHandlePlainSelectFromTableFalse() {
        assertFalse("普通 SELECT * FROM t 不应被拦截", provider.canHandle("SELECT * FROM t"));
    }

    // ==================== isSetStatement ====================

    @Test
    public void testIsSetStatementTrue() {
        assertTrue("SET autocommit=1 是 SET 语句", provider.isSetStatement("SET autocommit=1"));
    }

    @Test
    public void testIsSetStatementFalse() {
        assertFalse("SELECT 1 不是 SET 语句", provider.isSetStatement("SELECT 1"));
    }

    // ==================== extractUseDatabase ====================

    @Test
    public void testExtractUseDatabaseSimple() {
        assertEquals("mydb", provider.extractUseDatabase("USE mydb"));
    }

    @Test
    public void testExtractUseDatabaseBackticks() {
        assertEquals("other", provider.extractUseDatabase("use `other`"));
    }

    @Test
    public void testExtractUseDatabaseNonUseReturnsNull() {
        assertNull("非 USE 语句应返回 null", provider.extractUseDatabase("select 1"));
    }

    // ==================== getVariables ====================

    @Test
    public void testGetVariablesContainsExpectedKeysAndValues() {
        Map<String, String> vars = provider.getVariables();
        assertNotNull("getVariables() 不应返回 null", vars);
        assertEquals("version 值应为 5.7.38-proxy", "5.7.38-proxy", vars.get("version"));
        assertEquals("autocommit 值应为 1", "1", vars.get("autocommit"));
        assertTrue("应包含 max_allowed_packet", vars.containsKey("max_allowed_packet"));
    }

    @Test
    public void testGetVariablesReturnsSnapshotCopy() {
        Map<String, String> first = provider.getVariables();
        assertNotNull(first);

        // 1) 返回的快照必须是不可修改副本：对返回值 put/clear 会抛 UOE，源不会被污染
        try {
            first.put("snapshot_test_key", "x");
            first.clear();
            fail("getVariables() 返回的快照应为不可修改副本");
        } catch (UnsupportedOperationException expected) {
            // 期望：返回的是不可修改快照，无法从外部污染源
        }

        // 2) 第二次调用仍返回原始值，证明源未被污染（快照语义）
        Map<String, String> second = provider.getVariables();
        assertEquals("5.7.38-proxy", second.get("version"));
        assertEquals("1", second.get("autocommit"));

        // 3) 每次调用返回的是独立副本对象（不同引用），证明是防御性拷贝
        assertNotSame("getVariables() 应返回新的快照副本而非共享引用", first, second);
    }

    // ==================== setSystemVariable（全局生效） ====================

    @Test
    public void testSetSystemVariableGlobalEffect() {
        // 模拟 ProxyBootstrap.applyMaxPacketSize 的全局注入语义
        MySQLSystemCatalogProvider.setSystemVariable("max_allowed_packet", "999");
        assertEquals(
                "setSystemVariable 后全局变量应生效", "999", provider.getVariables().get("max_allowed_packet"));
    }

    // ==================== handleQuery（Netty 写入路径，EmbeddedChannel 轻量验证） ====================

    /**
     * 获取一个真实的 {@link ChannelHandlerContext}：往 {@link EmbeddedChannel} 塞一个
     * 无操作的入站 handler，取其 context。该 context 的 {@code write} 会沿 pipeline 反向
     * 抵达 head → unsafe，最终写入 EmbeddedChannel 的 outbound 缓冲（可被 {@code readOutbound} 读取）。
     */
    private ChannelHandlerContext newTestContext(EmbeddedChannel channel, ChannelInboundHandlerAdapter sink) {
        return channel.pipeline().context(sink);
    }

    private int countOutgoingPackets(EmbeddedChannel channel) {
        int count = 0;
        Object out;
        while ((out = channel.readOutbound()) != null) {
            assertTrue("写出对象应为 OutgoingPacket", out instanceof MySQLPacketEncoder.OutgoingPacket);
            ((MySQLPacketEncoder.OutgoingPacket) out).getPayload().release();
            count++;
        }
        return count;
    }

    @Test
    public void testHandleQueryWritesColumnDefRowEofForSelectVersion() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ChannelInboundHandlerAdapter sink = new ChannelInboundHandlerAdapter();
        channel.pipeline().addLast(sink);
        ChannelHandlerContext ctx = newTestContext(channel, sink);

        // SELECT @@version → 单列结果：列数(1) + 列定义(1) + EOF(1) + 数据行(1) + 最终 EOF(1)
        provider.handleQuery(ctx, "SELECT @@version", null);

        assertEquals("SELECT @@version 应写出 5 个 MySQL 包", 5, countOutgoingPackets(channel));
        channel.finish();
    }

    @Test
    public void testHandleQueryWritesEmptyResultSetForShowWarnings() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ChannelInboundHandlerAdapter sink = new ChannelInboundHandlerAdapter();
        channel.pipeline().addLast(sink);
        ChannelHandlerContext ctx = newTestContext(channel, sink);

        // SHOW WARNINGS → 三列空结果集：列数(1) + 列定义(3) + EOF(1) + 最终 EOF(1)（无数据行）
        provider.handleQuery(ctx, "SHOW WARNINGS", null);

        assertEquals("SHOW WARNINGS 应写出 6 个 MySQL 包（三列空结果集）", 6, countOutgoingPackets(channel));
        channel.finish();
    }
}
