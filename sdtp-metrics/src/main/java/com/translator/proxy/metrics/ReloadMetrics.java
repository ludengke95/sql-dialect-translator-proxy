package com.translator.proxy.metrics;

import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Histogram;

/**
 * 配置热更新相关指标。
 *
 * <p>Label 说明：
 * <ul>
 *   <li>{@code action} — reload 动作类型（add / remove / reload）</li>
 *   <li>{@code backend_name} — 后端名称</li>
 *   <li>{@code result} — drain 结果（ok / timeout）</li>
 *   <li>{@code reason} — 拒绝原因（full / timeout）</li>
 * </ul>
 */
public final class ReloadMetrics {

    private ReloadMetrics() {}

    /** 配置变更次数（按 action 和 backend_name 分 label） */
    public static final Counter RELOAD_TOTAL = Counter.build()
            .name("sdt_config_reload_total")
            .help("Total config reload actions by type")
            .labelNames("action", "backend_name")
            .register();

    /** 配置加载失败次数 */
    public static final Counter RELOAD_FAILURES = Counter.build()
            .name("sdt_config_reload_failures_total")
            .help("Total config reload failures (file parse error)")
            .register();

    /** reload drain 结果计数 */
    public static final Counter RELOAD_DRAIN = Counter.build()
            .name("sdt_config_reload_drain_total")
            .help("Total reload drain results")
            .labelNames("backend_name", "result")
            .register();

    /** reload 期间请求被拒绝的次数 */
    public static final Counter RELOAD_QUEUE_REJECTIONS = Counter.build()
            .name("sdt_config_reload_queue_rejections_total")
            .help("Total requests rejected during backend reload")
            .labelNames("backend_name", "reason")
            .register();

    /** 单次 reload 完整耗时（秒），按 action 和 backend_name 分 label */
    public static final Histogram RELOAD_DURATION = Histogram.build()
            .name("sdt_config_reload_duration_seconds")
            .help("Config reload duration in seconds")
            .labelNames("backend_name", "action")
            .buckets(0.01, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30)
            .register();

    /** 当前后端总数 */
    public static final Gauge BACKEND_COUNT = Gauge.build()
            .name("sdt_backend_count")
            .help("Current number of backend pools")
            .register();

    // ==================== 便捷方法 ====================

    public static void recordReload(String action, String backendName) {
        RELOAD_TOTAL.labels(action, backendName).inc();
    }

    public static void recordReloadFailure() {
        RELOAD_FAILURES.inc();
    }

    public static void recordDrain(String backendName, boolean ok) {
        RELOAD_DRAIN.labels(backendName, ok ? "ok" : "timeout").inc();
    }

    /** @param reason full（队列满）或 timeout（drain 超时时清空队列） */
    public static void recordQueueRejection(String backendName, String reason) {
        RELOAD_QUEUE_REJECTIONS.labels(backendName, reason).inc();
    }

    public static void observeDuration(String backendName, String action, double seconds) {
        RELOAD_DURATION.labels(backendName, action).observe(seconds);
    }

    /** 更新后端总数（通常在 addBackend / removeBackend 后调用） */
    public static void setBackendCount(int count) {
        BACKEND_COUNT.set(count);
    }
}
