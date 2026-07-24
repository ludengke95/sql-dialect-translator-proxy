package com.translator.proxy.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zaxxer.hikari.metrics.IMetricsTracker;
import com.zaxxer.hikari.metrics.MetricsTrackerFactory;
import com.zaxxer.hikari.metrics.PoolStats;

import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Histogram;

/**
 * HikariCP 连接池指标桥接。
 *
 * <p>实现 {@link MetricsTrackerFactory} 和 {@link IMetricsTracker}，
 * 将 HikariCP 连接池事件映射为 Prometheus 指标。
 *
 * <p>Label 说明：
 * <ul>
 *   <li>{@code pool_name} — 连接池名称（对应 BackendEntry.name）</li>
 * </ul>
 */
public final class HikariMetricsTrackerFactory implements MetricsTrackerFactory {

    private static final Logger log = LoggerFactory.getLogger(HikariMetricsTrackerFactory.class);

    // ==================== Prometheus Collectors ====================

    /** 当前活跃连接数 */
    static final Gauge ACTIVE_CONNECTIONS = Gauge.build()
            .name("sdt_pool_active_connections")
            .help("Current active connections in the pool")
            .labelNames("pool_name")
            .register();

    /** 当前空闲连接数 */
    static final Gauge IDLE_CONNECTIONS = Gauge.build()
            .name("sdt_pool_idle_connections")
            .help("Current idle connections in the pool")
            .labelNames("pool_name")
            .register();

    /** 等待获取连接的线程数 */
    static final Gauge PENDING_CONNECTIONS = Gauge.build()
            .name("sdt_pool_pending_connections")
            .help("Current threads pending for a connection")
            .labelNames("pool_name")
            .register();

    /** 连接池当前总连接数 */
    static final Gauge TOTAL_CONNECTIONS = Gauge.build()
            .name("sdt_pool_total_connections")
            .help("Current total connections in the pool")
            .labelNames("pool_name")
            .register();

    /** 获取连接超时次数 */
    static final Counter CONNECTION_TIMEOUT = Counter.build()
            .name("sdt_pool_connection_timeout_total")
            .help("Total connection timeout events")
            .labelNames("pool_name")
            .register();

    /** 物理连接创建耗时（秒） */
    static final Histogram CONNECTION_CREATION = Histogram.build()
            .name("sdt_pool_connection_creation_duration_seconds")
            .help("Physical connection creation duration")
            .labelNames("pool_name")
            .buckets(0.01, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10)
            .register();

    /** 连接获取耗时（秒） */
    static final Histogram CONNECTION_ACQUIRE = Histogram.build()
            .name("sdt_pool_connection_acquire_duration_seconds")
            .help("Connection acquire duration")
            .labelNames("pool_name")
            .buckets(0.0001, 0.0005, 0.001, 0.005, 0.01, 0.05, 0.1, 0.5, 1)
            .register();

    /** 连接使用时长（秒） */
    static final Histogram CONNECTION_USAGE = Histogram.build()
            .name("sdt_pool_connection_usage_duration_seconds")
            .help("Connection usage duration")
            .labelNames("pool_name")
            .buckets(0.001, 0.005, 0.01, 0.05, 0.1, 0.5, 1, 5, 10, 30, 60)
            .register();

    // ==================== PoolStats 注册表（供定期更新 Gauge） ====================

    private static final Map<String, PoolStats> POOL_STATS_MAP = new ConcurrentHashMap<>();

    /** 注册一个连接池的 PoolStats 引用（由 BackendPoolManager 调用） */
    public static void registerPoolStats(String poolName, PoolStats poolStats) {
        POOL_STATS_MAP.put(poolName, poolStats);
        log.debug("Registered pool stats for '{}'", poolName);
    }

    /** 注销连接池 */
    public static void unregisterPoolStats(String poolName) {
        POOL_STATS_MAP.remove(poolName);
    }

    /**
     * 刷新所有已注册连接池的 Gauge 指标。
     * 应由 MetricsModule 定期调用（如每 15 秒）。
     */
    public static void refreshPoolGauges() {
        for (Map.Entry<String, PoolStats> entry : POOL_STATS_MAP.entrySet()) {
            String poolName = entry.getKey();
            PoolStats stats = entry.getValue();
            try {
                ACTIVE_CONNECTIONS.labels(poolName).set(stats.getActiveConnections());
                IDLE_CONNECTIONS.labels(poolName).set(stats.getIdleConnections());
                PENDING_CONNECTIONS.labels(poolName).set(stats.getPendingThreads());
                TOTAL_CONNECTIONS.labels(poolName).set(stats.getTotalConnections());
            } catch (Exception e) {
                log.debug("Failed to refresh pool gauge for '{}': {}", poolName, e.getMessage());
            }
        }
    }

    // ==================== MetricsTrackerFactory 实现 ====================

    @Override
    public IMetricsTracker create(String poolName, PoolStats poolStats) {
        registerPoolStats(poolName, poolStats);
        return new Tracker(poolName);
    }

    // ==================== IMetricsTracker 实现 ====================

    private static class Tracker implements IMetricsTracker {

        private final String poolName;

        Tracker(String poolName) {
            this.poolName = poolName;
        }

        @Override
        public void recordConnectionCreatedMillis(long connectionCreatedMillis) {
            CONNECTION_CREATION.labels(poolName).observe(connectionCreatedMillis / 1000.0);
        }

        @Override
        public void recordConnectionAcquiredNanos(long elapsedAcquiredNanos) {
            CONNECTION_ACQUIRE.labels(poolName).observe(elapsedAcquiredNanos / 1_000_000_000.0);
        }

        @Override
        public void recordConnectionUsageMillis(long elapsedBorrowedMillis) {
            CONNECTION_USAGE.labels(poolName).observe(elapsedBorrowedMillis / 1000.0);
        }

        @Override
        public void recordConnectionTimeout() {
            CONNECTION_TIMEOUT.labels(poolName).inc();
        }
    }
}
