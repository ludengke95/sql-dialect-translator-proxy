package com.translator.proxy.backend;

import java.util.Objects;

/**
 * 后端数据库实例配置条目。
 *
 * <p>由 {@code ProxyBootstrap} 从 {@code ProxyConfig.TargetConfig} 转换而来，
 * 传入 {@link BackendPoolManager} 初始化连接池。
 */
public class BackendEntry {

    private String name;
    private String dialect;
    private String jdbcUrl;
    private String username;
    private String password;
    private int maxPoolSize = 20;
    private int minIdle = 2;

    /** 翻译配置（可选，不设置时使用全局默认值） */
    private String keywordCase;

    private String identifierCase;

    public BackendEntry() {}

    public BackendEntry(
            String name,
            String dialect,
            String jdbcUrl,
            String username,
            String password,
            int maxPoolSize,
            int minIdle) {
        this.name = name;
        this.dialect = dialect;
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.maxPoolSize = maxPoolSize;
        this.minIdle = minIdle;
    }

    /**
     * 全参构造（含翻译配置）。
     */
    public BackendEntry(
            String name,
            String dialect,
            String jdbcUrl,
            String username,
            String password,
            int maxPoolSize,
            int minIdle,
            String keywordCase,
            String identifierCase) {
        this(name, dialect, jdbcUrl, username, password, maxPoolSize, minIdle);
        this.keywordCase = keywordCase;
        this.identifierCase = identifierCase;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDialect() {
        return dialect;
    }

    public void setDialect(String dialect) {
        this.dialect = dialect;
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public void setMaxPoolSize(int maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
    }

    public int getMinIdle() {
        return minIdle;
    }

    public void setMinIdle(int minIdle) {
        this.minIdle = minIdle;
    }

    public String getKeywordCase() {
        return keywordCase;
    }

    public void setKeywordCase(String keywordCase) {
        this.keywordCase = keywordCase;
    }

    public String getIdentifierCase() {
        return identifierCase;
    }

    public void setIdentifierCase(String identifierCase) {
        this.identifierCase = identifierCase;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BackendEntry)) return false;
        BackendEntry that = (BackendEntry) o;
        return maxPoolSize == that.maxPoolSize
                && minIdle == that.minIdle
                && Objects.equals(name, that.name)
                && Objects.equals(dialect, that.dialect)
                && Objects.equals(jdbcUrl, that.jdbcUrl)
                && Objects.equals(username, that.username)
                && Objects.equals(password, that.password)
                && Objects.equals(keywordCase, that.keywordCase)
                && Objects.equals(identifierCase, that.identifierCase);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                name, dialect, jdbcUrl, username, password, maxPoolSize, minIdle, keywordCase, identifierCase);
    }
}
