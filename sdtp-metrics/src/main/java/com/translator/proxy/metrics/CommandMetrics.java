package com.translator.proxy.metrics;

import io.prometheus.client.Counter;
import io.prometheus.client.Histogram;

/**
 * 命令层指标：各命令的计数、耗时、异常和系统变量拦截。
 *
 * <p>Label 说明：
 * <ul>
 *   <li>{@code command} — 命令类型（COM_QUERY / COM_PING / COM_QUIT / COM_INIT_DB 等）</li>
 * </ul>
 */
public final class CommandMetrics {

    private CommandMetrics() {}

    /** 命令总数（按 command 类型分 label） */
    public static final Counter TOTAL = Counter.build()
            .name("sdt_commands_total")
            .help("Total commands processed")
            .labelNames("command")
            .register();

    /** 命令处理耗时（秒），按 command 类型分 label */
    public static final Histogram DURATION = Histogram.build()
            .name("sdt_commands_duration_seconds")
            .help("Command processing duration in seconds")
            .labelNames("command")
            .buckets(0.0001, 0.0005, 0.001, 0.005, 0.01, 0.05, 0.1, 0.5, 1, 5, 10, 30)
            .register();

    /** 命令处理异常总数 */
    public static final Counter ERRORS = Counter.build()
            .name("sdt_commands_errors_total")
            .help("Total command processing errors")
            .register();

    /** 系统变量拦截次数（SET / SELECT @@xxx 本地处理） */
    public static final Counter SYSTEM_VAR_INTERCEPTIONS = Counter.build()
            .name("sdt_system_var_interceptions_total")
            .help("Total system variable interceptions (handled locally)")
            .register();

    // ==================== 便捷方法 ====================

    /** 记录命令计数 */
    public static void recordCommand(String command) {
        TOTAL.labels(command).inc();
    }

    /** 记录命令耗时 */
    public static void recordCommandDuration(String command, double seconds) {
        DURATION.labels(command).observe(seconds);
    }

    /** 记录命令异常 */
    public static void recordError() {
        ERRORS.inc();
    }

    /** 记录系统变量拦截 */
    public static void recordSystemVarInterception() {
        SYSTEM_VAR_INTERCEPTIONS.inc();
    }

    /**
     * 创建一个计时器，用于 Histogram 打点。
     * 用法：try (Timer t = CommandMetrics.startTimer("COM_QUERY")) { ... }
     */
    public static Timer startTimer(String command) {
        return new Timer(command);
    }

    /** 简单计时器（AutoCloseable） */
    public static class Timer implements AutoCloseable {
        private final String command;
        private final long startNanos;

        Timer(String command) {
            this.command = command;
            this.startNanos = System.nanoTime();
        }

        @Override
        public void close() {
            double seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
            DURATION.labels(command).observe(seconds);
        }
    }
}
