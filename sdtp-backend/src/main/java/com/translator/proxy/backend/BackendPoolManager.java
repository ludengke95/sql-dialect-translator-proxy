package com.translator.proxy.backend;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.translator.core.DialectType;
import com.translator.core.config.TranslationConfig;
import com.translator.proxy.core.handler.BackendRouter;
import com.translator.proxy.core.handler.QueryProcessor;
import com.translator.proxy.core.session.FrontendSession;
import com.translator.proxy.metrics.ReloadMetrics;

/**
 * 多后端连接池管理器。
 *
 * <p>根据配置的 backends 列表初始化多个 HikariCP 连接池，
 * 每个后端有一个 {@link ReloadableQueryProcessor}（内含翻译装饰器）。
 * 实现 {@link BackendRouter} 接口，按会话中记录的 database 名称路由。
 *
 * <p>支持动态增/删/改后端：{@link #addBackend(BackendEntry)}、
 * {@link #removeBackend(String)}、{@link #reloadBackend(BackendEntry)}。
 */
public class BackendPoolManager implements BackendRouter {

    private static final Logger log = LoggerFactory.getLogger(BackendPoolManager.class);

    /** 后端名称 → ReloadableQueryProcessor 映射 */
    private final ConcurrentMap<String, ReloadableQueryProcessor> processorMap = new ConcurrentHashMap<>();

    /** 默认后端（列表中的第一个） */
    private volatile QueryProcessor defaultProcessor;

    /** 全局默认翻译配置（后端未指定时使用） */
    private final TranslationConfig defaultTranslationConfig;

    /** reload 请求队列容量 */
    private final int reloadQueueCapacity;

    /** reload drain 超时（毫秒） */
    private final int reloadDrainTimeoutMs;

    /**
     * 创建管理器并初始化所有后端连接池（兼容旧接口，使用默认 reload 参数）。
     *
     * @param backends                后端配置列表
     * @param defaultTranslationConfig 全局默认翻译配置
     */
    public BackendPoolManager(List<BackendEntry> backends, TranslationConfig defaultTranslationConfig) {
        this(backends, defaultTranslationConfig, 1000, 30000);
    }

    /**
     * 创建管理器并初始化所有后端连接池。
     *
     * @param backends                后端配置列表
     * @param defaultTranslationConfig 全局默认翻译配置
     * @param reloadQueueCapacity     reload 请求队列容量
     * @param reloadDrainTimeoutMs    reload drain 超时（毫秒）
     */
    public BackendPoolManager(
            List<BackendEntry> backends,
            TranslationConfig defaultTranslationConfig,
            int reloadQueueCapacity,
            int reloadDrainTimeoutMs) {
        this.defaultTranslationConfig = defaultTranslationConfig;
        this.reloadQueueCapacity = reloadQueueCapacity;
        this.reloadDrainTimeoutMs = reloadDrainTimeoutMs;

        for (BackendEntry be : backends) {
            String name = be.getName();
            if (name == null || name.isEmpty()) {
                log.warn("Skipping backend with empty name");
                continue;
            }

            ReloadableQueryProcessor rp = createReloadableProcessor(be);
            processorMap.put(name, rp);
            log.info(
                    "Backend '{}': {} ({}), pool={}, kw={}, id={}",
                    name,
                    be.getJdbcUrl(),
                    be.getDialect(),
                    be.getMaxPoolSize(),
                    resolveTranslationConfig(be).getKeywordCase(),
                    resolveTranslationConfig(be).getIdentifierCase());
        }

        if (!processorMap.isEmpty()) {
            defaultProcessor = processorMap.values().iterator().next();
            log.info("Default backend: '{}'", processorMap.keySet().iterator().next());
        } else {
            log.warn("No backends configured, using NOOP processor");
            defaultProcessor = QueryProcessor.NOOP;
        }
        // 初始化后更新后端总数指标
        ReloadMetrics.setBackendCount(processorMap.size());
    }

    // ==================== BackendRouter 接口 ====================

    @Override
    public QueryProcessor resolve(FrontendSession session) {
        String database = session.getDatabase();
        if (database != null && processorMap.containsKey(database)) {
            return processorMap.get(database);
        }
        return defaultProcessor;
    }

    public QueryProcessor getProcessor(String databaseName) {
        if (databaseName != null && processorMap.containsKey(databaseName)) {
            return processorMap.get(databaseName);
        }
        return defaultProcessor;
    }

    public QueryProcessor getDefaultProcessor() {
        return defaultProcessor;
    }

    @Override
    public Set<String> getBackendNames() {
        return processorMap.keySet();
    }

    // ==================== 动态管理 ====================

    /**
     * 新增一个后端。
     *
     * @param be 后端配置
     * @return true 表示新增成功；false 表示 name 已存在（不会覆盖）
     */
    public boolean addBackend(BackendEntry be) {
        String name = be.getName();
        if (name == null || name.isEmpty()) {
            log.warn("Cannot add backend with empty name");
            return false;
        }

        ReloadableQueryProcessor rp = createReloadableProcessor(be);
        ReloadableQueryProcessor existing = processorMap.putIfAbsent(name, rp);
        if (existing != null) {
            log.warn("Backend '{}' already exists, use reloadBackend() to update", name);
            rp.close(); // 释放刚创建的连接池
            return false;
        }

        // 如果是第一个后端，更新 defaultProcessor
        if (processorMap.size() == 1) {
            defaultProcessor = rp;
        }

        log.info("Backend '{}' added: {} ({})", name, be.getJdbcUrl(), be.getDialect());
        return true;
    }

    /**
     * 删除一个后端。优雅 drain 后关闭连接池。
     *
     * @param name 后端名称
     * @return true 表示删除成功；false 表示 name 不存在
     */
    public boolean removeBackend(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }

        ReloadableQueryProcessor rp = processorMap.remove(name);
        if (rp == null) {
            log.warn("Backend '{}' not found, cannot remove", name);
            return false;
        }

        log.info("Backend '{}': starting removal...", name);
        rp.markDraining();

        // 等待 in-flight 请求完成
        long deadline = System.currentTimeMillis() + reloadDrainTimeoutMs;
        while (rp.getQueueSize() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        rp.close();
        updateDefaultProcessor();
        log.info("Backend '{}' removed", name);
        return true;
    }

    /**
     * 热 reload 一个后端：drain 旧连接池 → 按新配置创建连接池 → 重新激活。
     *
     * @param be 新的后端配置（name 必须与已有后端匹配）
     * @return true 表示 reload 成功；false 表示 name 不存在
     */
    public boolean reloadBackend(BackendEntry be) {
        String name = be.getName();
        if (name == null || name.isEmpty()) {
            return false;
        }

        ReloadableQueryProcessor rp = processorMap.get(name);
        if (rp == null) {
            log.warn("Backend '{}' not found, cannot reload. Use addBackend() instead.", name);
            return false;
        }

        log.info("Backend '{}': starting reload...", name);

        // 1. Drain 并关闭旧 delegate
        boolean drained = rp.drainAndClose();

        // 2. 创建新 delegate
        QueryProcessor newDelegate = createInnerProcessor(be);

        // 3. 激活新 delegate
        rp.activateNew(newDelegate);

        // 4. 如果是最初的默认后端，可能需要更新 defaultProcessor
        updateDefaultProcessor();

        log.info("Backend '{}' reloaded (drained={}): {} ({})", name, drained, be.getJdbcUrl(), be.getDialect());
        return true;
    }

    /**
     * 关闭所有连接池。
     */
    public void close() {
        for (ReloadableQueryProcessor rp : processorMap.values()) {
            rp.close();
        }
        log.info("All backend pools closed");
    }

    // ==================== 内部辅助 ====================

    /**
     * 根据 BackendEntry 创建带翻译装饰器的真实 QueryProcessor。
     */
    private QueryProcessor createInnerProcessor(BackendEntry be) {
        // 创建原始 JDBC 后端处理器
        JdbcBackendQueryProcessor jdbcProcessor = JdbcBackendQueryProcessor.create(
                be.getName(),
                be.getJdbcUrl(),
                be.getUsername(),
                be.getPassword(),
                be.getMaxPoolSize(),
                be.getMinIdle());

        TranslationConfig tc = resolveTranslationConfig(be);

        // 如果后端 dialect 不是 MYSQL，按 MYSQL -> targetDialect 转换
        if (be.getDialect() != null && !be.getDialect().equalsIgnoreCase("MYSQL")) {
            return new TranslationQueryProcessor(jdbcProcessor, be.getDialect(), tc, be.getName());
        } else {
            // 前端是 POSTGRESQL 且后端是 MYSQL 时，开启 POSTGRESQL -> MYSQL 改写翻译
            return new TranslationQueryProcessor(
                    jdbcProcessor, DialectType.POSTGRESQL, DialectType.MYSQL, tc, be.getName());
        }
    }

    /**
     * 创建完整的 ReloadableQueryProcessor（内含翻译装饰器链）。
     */
    private ReloadableQueryProcessor createReloadableProcessor(BackendEntry be) {
        QueryProcessor inner = createInnerProcessor(be);
        return new ReloadableQueryProcessor(be.getName(), inner, reloadQueueCapacity, reloadDrainTimeoutMs);
    }

    /**
     * 解析后端翻译配置：优先后端自带，否则全局默认。
     */
    private TranslationConfig resolveTranslationConfig(BackendEntry be) {
        if (be.getKeywordCase() != null || be.getIdentifierCase() != null) {
            String kw = be.getKeywordCase() != null
                    ? be.getKeywordCase()
                    : defaultTranslationConfig.getKeywordCase().name();
            String id = be.getIdentifierCase() != null
                    ? be.getIdentifierCase()
                    : defaultTranslationConfig.getIdentifierCase().name();
            return new TranslationConfig()
                    .withKeywordCase(TranslationConfig.KeywordCase.valueOf(kw))
                    .withIdentifierCase(TranslationConfig.IdentifierCase.valueOf(id));
        }
        return defaultTranslationConfig;
    }

    /**
     * 更新 defaultProcessor：指向列表中第一个（若有），否则 NOOP。
     */
    private void updateDefaultProcessor() {
        if (!processorMap.isEmpty()) {
            defaultProcessor = processorMap.values().iterator().next();
            log.info(
                    "Default backend updated to: '{}'",
                    processorMap.keySet().iterator().next());
        } else {
            defaultProcessor = QueryProcessor.NOOP;
            log.warn("No backends remaining, default processor is NOOP");
        }
    }
}
