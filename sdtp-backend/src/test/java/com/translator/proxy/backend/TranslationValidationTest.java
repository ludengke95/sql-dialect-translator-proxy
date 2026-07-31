package com.translator.proxy.backend;

import static org.junit.Assert.*;

import java.sql.Connection;
import java.sql.Statement;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.Before;
import org.junit.Test;

import com.translator.core.DialectType;
import com.translator.core.config.TranslationConfig;
import com.translator.proxy.backend.metadata.BackendMetadataProvider;
import com.translator.proxy.core.handler.QueryProcessor;

public class TranslationValidationTest {

    private JdbcDataSource dataSource;

    @Before
    public void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:test_trans_val_db;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS orders");
            stmt.execute("CREATE TABLE orders (id INT PRIMARY KEY, user_id INT, amount DECIMAL(10,2))");
        }
    }

    @Test
    public void testMetadataProviderBinding() {
        TranslationConfig config = new TranslationConfig()
                .withEnableValidation(true)
                .withValidationMode(TranslationConfig.ValidationMode.WARN);

        TranslationQueryProcessor processor = new TranslationQueryProcessor(
                QueryProcessor.NOOP, DialectType.MYSQL, DialectType.POSTGRESQL, config, "test_backend");

        BackendMetadataProvider provider = new BackendMetadataProvider(dataSource);
        processor.setMetadataProvider(provider);

        assertEquals(provider, processor.getMetadataProvider());
    }

    @Test
    public void testBackendPoolManagerValidationIntegration() {
        BackendEntry be = new BackendEntry();
        be.setName("h2_test");
        be.setDialect("POSTGRESQL");
        be.setJdbcUrl("jdbc:h2:mem:test_trans_val_db;DB_CLOSE_DELAY=-1");
        be.setUsername("sa");
        be.setPassword("");
        be.setEnableValidation(true);
        be.setValidationMode("WARN");
        be.setMaxTables(50);

        TranslationConfig defaultConfig = new TranslationConfig();
        BackendPoolManager poolManager = new BackendPoolManager(
                java.util.Collections.singletonList(be), defaultConfig);

        QueryProcessor processor = poolManager.getProcessor("h2_test");
        assertNotNull(processor);
        assertTrue(processor instanceof ReloadableQueryProcessor);

        poolManager.close();
    }
}
