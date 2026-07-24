package com.translator.proxy.core.handler;

/**
 * SQL 翻译上下文实体，用于在 Netty 管道处理器间共享翻译前后的 SQL。
 */
public class SqlTranslationContext {
    private final String originalSql;
    private final String translatedSql;

    public SqlTranslationContext(String originalSql, String translatedSql) {
        this.originalSql = originalSql;
        this.translatedSql = translatedSql;
    }

    public String getOriginalSql() {
        return originalSql;
    }

    public String getTranslatedSql() {
        return translatedSql;
    }
}
