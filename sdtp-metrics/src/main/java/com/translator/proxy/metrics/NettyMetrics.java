package com.translator.proxy.metrics;

import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;

/**
 * Netty 传输层指标：读写字节数、活跃 Channel 数。
 */
public final class NettyMetrics {

    private NettyMetrics() {}

    /** Netty 读取字节总数 */
    public static final Counter BYTES_READ = Counter.build()
            .name("sdt_netty_bytes_read_total")
            .help("Total bytes read by Netty")
            .register();

    /** Netty 写出字节总数 */
    public static final Counter BYTES_WRITTEN = Counter.build()
            .name("sdt_netty_bytes_written_total")
            .help("Total bytes written by Netty")
            .register();

    /** 当前活跃 Channel 数 */
    public static final Gauge CHANNELS_ACTIVE = Gauge.build()
            .name("sdt_netty_channels_active")
            .help("Current number of active Netty channels")
            .register();

    // ==================== 便捷方法 ====================

    public static void recordBytesRead(long bytes) {
        BYTES_READ.inc(bytes);
    }

    public static void recordBytesWritten(long bytes) {
        BYTES_WRITTEN.inc(bytes);
    }

    public static void onChannelActive() {
        CHANNELS_ACTIVE.inc();
    }

    public static void onChannelInactive() {
        CHANNELS_ACTIVE.dec();
    }
}
