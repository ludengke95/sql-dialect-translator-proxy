package com.translator.proxy.metrics;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.translator.metrics.MetricsConfig;
import com.translator.metrics.MetricsHttpServer;

import io.prometheus.client.hotspot.DefaultExports;

/**
 * 指标模块生命周期管理器。
 *
 * <p>用法：
 * <pre>
 *   MetricsModule module = new MetricsModule();
 *   module.start(new MetricsConfig(true, 9090));
 *   // ... 应用运行期间，各组件直接使用静态指标类打点
 *   module.stop();
 * </pre>
 */
public class MetricsModule {

    private static final Logger log = LoggerFactory.getLogger(MetricsModule.class);

    private final MetricsConfig config;
    private MetricsHttpServer httpServer;
    private ScheduledExecutorService poolGaugeScheduler;

    /** 池 Gauge 刷新间隔（秒） */
    private static final int POOL_REFRESH_INTERVAL_SECONDS = 15;

    public MetricsModule(MetricsConfig config) {
        this.config = config != null ? config : new MetricsConfig(false, 0);
    }

    /**
     * 启动指标模块：初始化 JVM 默认导出、启动 HTTP 端点、启动连接池 Gauge 定期刷新。
     */
    public synchronized void start() {
        if (!config.isEnabled()) {
            log.info("Prometheus metrics are disabled");
            return;
        }

        log.info("Starting Prometheus metrics on port {}", config.getPort());

        // 1. 注册 JVM 默认指标（内存、GC、线程、类加载）
        DefaultExports.initialize();

        // 2. 启动 HTTP 指标端点
        httpServer = new MetricsHttpServer(config.getPort());
        httpServer.start();

        // 3. 启动连接池 Gauge 定期刷新
        poolGaugeScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sdt-pool-metrics-refresher");
            t.setDaemon(true);
            return t;
        });
        poolGaugeScheduler.scheduleAtFixedRate(
                HikariMetricsTrackerFactory::refreshPoolGauges,
                POOL_REFRESH_INTERVAL_SECONDS,
                POOL_REFRESH_INTERVAL_SECONDS,
                TimeUnit.SECONDS);

        log.info("Prometheus metrics module started successfully");
    }

    /**
     * 停止指标模块。
     */
    public synchronized void stop() {
        if (poolGaugeScheduler != null) {
            poolGaugeScheduler.shutdownNow();
            poolGaugeScheduler = null;
        }
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
        log.info("Prometheus metrics module stopped");
    }

    public MetricsConfig getConfig() {
        return config;
    }
}
