package com.translator.proxy.server.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Proxy 配置模型 —— 支持多后端数据库实例。
 *
 * <p>客户端通过 JDBC URL 中的 database 名称或 &#64;64;USE db_name 选择后端。
 * 启动时加载全部后端连接池，按 {@link TargetConfig#getName()} 索引。
 */
public class ProxyConfig {

    private int port = 7788;

    /** MySQL 协议单包最大限制，默认 64MB */
    private int maxAllowedPacket = 67108864;

    /** 热 reload 请求队列容量 */
    private int reloadQueueCapacity = 1000;

    /** 热 reload drain 超时（毫秒） */
    private int reloadDrainTimeoutMs = 30000;

    /** 文件变化防抖间隔（毫秒） */
    private int reloadDebounceMs = 500;

    private AuthConfig auth = new AuthConfig();
    private List<TargetConfig> backends = new ArrayList<>();
    private TranslationConf translation = new TranslationConf(); // 全局默认值
    private MetricsConf metrics = new MetricsConf(); // 指标暴露配置

    /** 前端协议标识符，默认 "MYSQL"，可通过 YAML 的 frontend-protocol 键配置 */
    private String frontendProtocol = "MYSQL";

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getMaxAllowedPacket() {
        return maxAllowedPacket;
    }

    public void setMaxAllowedPacket(int maxAllowedPacket) {
        this.maxAllowedPacket = maxAllowedPacket;
    }

    public int getReloadQueueCapacity() {
        return reloadQueueCapacity;
    }

    public void setReloadQueueCapacity(int reloadQueueCapacity) {
        this.reloadQueueCapacity = reloadQueueCapacity;
    }

    public int getReloadDrainTimeoutMs() {
        return reloadDrainTimeoutMs;
    }

    public void setReloadDrainTimeoutMs(int reloadDrainTimeoutMs) {
        this.reloadDrainTimeoutMs = reloadDrainTimeoutMs;
    }

    public int getReloadDebounceMs() {
        return reloadDebounceMs;
    }

    public void setReloadDebounceMs(int reloadDebounceMs) {
        this.reloadDebounceMs = reloadDebounceMs;
    }

    public AuthConfig getAuth() {
        return auth;
    }

    public void setAuth(AuthConfig auth) {
        this.auth = auth;
    }

    public List<TargetConfig> getBackends() {
        return backends;
    }

    public void setBackends(List<TargetConfig> backends) {
        this.backends = backends;
    }

    public TranslationConf getTranslation() {
        return translation;
    }

    public void setTranslation(TranslationConf translation) {
        this.translation = translation;
    }

    public MetricsConf getMetrics() {
        return metrics;
    }

    public void setMetrics(MetricsConf metrics) {
        this.metrics = metrics;
    }

    public String getFrontendProtocol() {
        return frontendProtocol;
    }

    public void setFrontendProtocol(String frontendProtocol) {
        this.frontendProtocol = frontendProtocol;
    }

    // ==================== 内嵌配置类 ====================

    public static class AuthConfig {
        private String user = "root";
        private String password = "proxy_password";

        public String getUser() {
            return user;
        }

        public void setUser(String user) {
            this.user = user;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    /**
     * 单个后端数据库实例配置。
     * name 字段对应客户端连接时指定的数据库名称。
     */
    public static class TargetConfig {
        /** 后端名称，客户端通过 USE {@literal <name>} 或 JDBC URL database 连接 */
        private String name;

        /** 目标库方言类型，如 MYSQL, POSTGRESQL, ORACLE 等 */
        private String dialect = "POSTGRESQL";

        /** 目标库 JDBC URL */
        private String jdbcUrl = "jdbc:postgresql://localhost:5432/mydb";

        /** 目标库用户名 */
        private String username = "sdtpu";

        /** 目标库密码 */
        private String password = "pg_password";

        /** 连接池最大连接数 */
        private int maxPoolSize = 20;

        /** 连接池最小空闲连接数 */
        private int minIdle = 2;

        /** 翻译配置（可选，不设置时使用全局默认值） */
        private TranslationConf translation;

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

        public TranslationConf getTranslation() {
            return translation;
        }

        public void setTranslation(TranslationConf translation) {
            this.translation = translation;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TargetConfig)) return false;
            TargetConfig that = (TargetConfig) o;
            return maxPoolSize == that.maxPoolSize
                    && minIdle == that.minIdle
                    && Objects.equals(name, that.name)
                    && Objects.equals(dialect, that.dialect)
                    && Objects.equals(jdbcUrl, that.jdbcUrl)
                    && Objects.equals(username, that.username)
                    && Objects.equals(password, that.password)
                    && Objects.equals(translation, that.translation);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, dialect, jdbcUrl, username, password, maxPoolSize, minIdle, translation);
        }
    }

    /**
     * SQL 翻译配置。
     */
    public static class TranslationConf {
        private String keywordCase = "UPPER";
        private String identifierCase = "LOWER";

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
            if (!(o instanceof TranslationConf)) return false;
            TranslationConf that = (TranslationConf) o;
            return Objects.equals(keywordCase, that.keywordCase) && Objects.equals(identifierCase, that.identifierCase);
        }

        @Override
        public int hashCode() {
            return Objects.hash(keywordCase, identifierCase);
        }
    }

    /**
     * Prometheus 指标暴露配置。
     */
    public static class MetricsConf {
        private boolean enabled = true;
        /** 0 表示自动计算（proxy端口 + 10000），> 0 表示显式指定 */
        private int port = 0;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }
    }
}
