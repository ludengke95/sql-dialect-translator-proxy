package com.translator.proxy.protocol.pg.command;

import org.junit.Assert;
import org.junit.Test;

import com.translator.proxy.protocol.pg.catalog.PgSystemCatalogProvider;

/**
 * PG 扩展查询与 SET 语句处理单元测试。
 */
public class ExtendedQueryTest {

    @Test
    public void testPgSystemCatalogCanHandleSet() {
        PgSystemCatalogProvider provider = new PgSystemCatalogProvider(null);
        Assert.assertTrue(provider.canHandle("SET extra_float_digits = 3"));
        Assert.assertTrue(provider.canHandle("  set client_encoding = 'utf8' "));
    }
}
