package com.translator.proxy.server;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.translator.core.config.TranslationConfig;
import com.translator.metrics.MetricsConfig;
import com.translator.proxy.backend.BackendEntry;
import com.translator.proxy.backend.BackendPoolManager;
import com.translator.proxy.backend.mapper.ResultSetEncoder;
import com.translator.proxy.core.handler.BackendRouter;
import com.translator.proxy.core.handler.NettyMetricsHandler;
import com.translator.proxy.metrics.MetricsModule;
import com.translator.proxy.protocol.frontend.AuthConfig;
import com.translator.proxy.protocol.frontend.FrontendProtocol;
import com.translator.proxy.protocol.frontend.FrontendProtocols;
import com.translator.proxy.server.config.ConfigLoader;
import com.translator.proxy.server.config.ConfigWatcher;
import com.translator.proxy.server.config.ProxyConfig;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;

/**
 * SDT Proxy 启动引导类（SPI 多协议版）。
 *
 * <p>启动时通过 {@link FrontendProtocols} SPI 加载前端协议，
 * 初始化 {@link BackendPoolManager} 管理多个后端连接池，
 * 将 {@link BackendRouter} 注入命令处理器实现按数据库名路由。
 *
 * <p>启动后通过 {@link ConfigWatcher} 监听配置文件变更，
 * 实现 backends 列表的热更新。
 */
public class ProxyBootstrap {

    private static final Logger log = LoggerFactory.getLogger(ProxyBootstrap.class);

    private final ProxyConfig config;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private DefaultEventLoopGroup bizExecutorGroup;
    private BackendPoolManager backendPoolManager;
    private MetricsModule metricsModule;
    private ConfigWatcher configWatcher;

    /** 通过 SPI 加载的前端协议实例 */
    private FrontendProtocol frontendProtocol;

    public ProxyBootstrap(ProxyConfig config) {
        this.config = config;
    }

    public void start() throws InterruptedException {
        // 1. 通过 SPI 加载前端协议
        String protocolId = config.getFrontendProtocol();
        if (protocolId == null || protocolId.isEmpty()) {
            protocolId = "MYSQL";
        }
        try {
            frontendProtocol = FrontendProtocols.load(protocolId);
        } catch (IllegalArgumentException e) {
            log.error("Failed to load frontend protocol '{}'", protocolId);
            throw e;
        }
        log.info(
                "Using frontend protocol: {} (id={}, port={})",
                frontendProtocol.getClass().getSimpleName(),
                frontendProtocol.id(),
                frontendProtocol.defaultPort());

        // 将连接级参数（最大包大小）下发给前端协议，由其自行决定如何应用：
        // MySQL 会写入自身系统变量表，PG 等无此概念则忽略。
        // 这样通用启动类不再直接依赖 MySQL 专属的 MySQLSystemCatalogProvider。
        frontendProtocol.applyMaxPacketSize(config.getMaxAllowedPacket());

        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        // todo 这个地方取最大值比较好，还是用累计求和比较好
        int bizThreads = 4;
        for (ProxyConfig.TargetConfig tcBackend : config.getBackends()) {
            bizThreads = Math.max(bizThreads, tcBackend.getMaxPoolSize());
        }
        bizExecutorGroup = new DefaultEventLoopGroup(bizThreads);

        // 构建翻译配置（全局默认）
        ProxyConfig.TranslationConf trc = config.getTranslation();
        TranslationConfig defaultTranslationConfig = new TranslationConfig()
                .withKeywordCase(TranslationConfig.KeywordCase.valueOf(trc.getKeywordCase()))
                .withIdentifierCase(TranslationConfig.IdentifierCase.valueOf(trc.getIdentifierCase()));

        // 将 ProxyConfig.TargetConfig 转换为 BackendEntry 列表
        List<BackendEntry> backends = new ArrayList<BackendEntry>();
        for (ProxyConfig.TargetConfig tc : config.getBackends()) {
            backends.add(ConfigWatcher.toBackendEntry(tc));
        }

        // 初始化多后端连接池管理器
        backendPoolManager = new BackendPoolManager(
                backends, defaultTranslationConfig, config.getReloadQueueCapacity(), config.getReloadDrainTimeoutMs());

        // 注入前端协议的结果集编码器（SPI 可插拔）：
        // PG 等前端按各自线协议编码结果；MySQL 前端沿用内置默认实现（responseWriter 保持 null）。
        if (!"MYSQL".equalsIgnoreCase(frontendProtocol.id())) {
            ResultSetEncoder.setResponseWriter(frontendProtocol.newResponseWriter());
            ResultSetEncoder.setTypeMapper(frontendProtocol.newTypeMapper());
        }

        // 初始化指标模块
        ProxyConfig.MetricsConf mc = config.getMetrics();
        if (mc.isEnabled()) {
            int metricsPort = mc.getPort();
            if (metricsPort <= 0) {
                metricsPort = config.getPort() + 10000;
            }
            MetricsConfig metricsConfig = new MetricsConfig(mc.isEnabled(), metricsPort);
            metricsModule = new MetricsModule(metricsConfig);
            metricsModule.start();
        }

        // 创建 AuthConfig 适配器
        final AuthConfig authConfigAdapter = new ProxyAuthConfig(config);

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline pipeline = ch.pipeline();

                            // todo 这个地方addLast，最后一个是最新执行的么，bizExecutorGroup为什么给handshakeHandler
                            pipeline.addLast("nettyMetrics", new NettyMetricsHandler());
                            pipeline.addLast("decoder", frontendProtocol.newDecoder(config.getMaxAllowedPacket()));
                            pipeline.addLast("encoder", frontendProtocol.newEncoder());

                            pipeline.addLast("idleHandler", new IdleStateHandler(28800, 28800, 0));

                            pipeline.addLast(
                                    "handshakeHandler",
                                    frontendProtocol.newHandshakeHandler(
                                            authConfigAdapter, bizExecutorGroup, backendPoolManager));
                        }
                    });

            // 使用配置端口或协议默认端口
            int port = config.getPort();
            if (port <= 0 || port == 3306) {
                port = frontendProtocol.defaultPort();
            }

            ChannelFuture future = bootstrap.bind(port).sync();
            log.info("========================================");
            log.info("  SDT Proxy started (frontend: {}) on port {}", frontendProtocol.id(), port);
            log.info(
                    "  Backends: {} configured",
                    backendPoolManager.getBackendNames().size());
            for (String name : backendPoolManager.getBackendNames()) {
                log.info("    - {}", name);
            }
            log.info("  Auth: user={}", config.getAuth().getUser());
            log.info("  Biz threads: {}", bizThreads);
            log.info(
                    "  Reload: queue={}, drainTimeout={}ms, debounce={}ms",
                    config.getReloadQueueCapacity(),
                    config.getReloadDrainTimeoutMs(),
                    config.getReloadDebounceMs());
            log.info("========================================");

            // 启动配置文件监听器
            startConfigWatcher();

            future.channel().closeFuture().sync();
        } finally {
            shutdown();
        }
    }

    /**
     * 启动配置文件监听器。
     */
    private void startConfigWatcher() {
        String configPath = ConfigLoader.resolveConfigPath();
        if (configPath == null) {
            log.info("No file-based config to watch (using classpath or defaults), config watcher disabled");
            return;
        }

        configWatcher = new ConfigWatcher(configPath, config.getReloadDebounceMs(), backendPoolManager, config);
        configWatcher.start();
    }

    public void shutdown() {
        if (metricsModule != null) {
            metricsModule.stop();
        }
        if (configWatcher != null) {
            configWatcher.stop();
        }
        if (backendPoolManager != null) {
            backendPoolManager.close();
        }
        if (bizExecutorGroup != null) {
            bizExecutorGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        log.info("SDT Proxy stopped.");
    }

    public static void main(String[] args) throws Exception {
        ProxyConfig config = ConfigLoader.load();
        ProxyBootstrap bootstrap = new ProxyBootstrap(config);
        bootstrap.start();
    }

    /**
     * todo 只有一个实现，还需要抽象么？这个地方为什么要抽象出AuthConfig？
     * AuthConfig 适配器 —— 从 ProxyConfig 获取认证信息，
     * 实现 {@link com.translator.proxy.protocol.frontend.AuthConfig} 接口。
     */
    private static class ProxyAuthConfig implements AuthConfig {

        private final ProxyConfig proxyConfig;

        ProxyAuthConfig(ProxyConfig proxyConfig) {
            this.proxyConfig = proxyConfig;
        }

        @Override
        public String getUsername() {
            return proxyConfig.getAuth().getUser();
        }

        @Override
        public String getPassword() {
            return proxyConfig.getAuth().getPassword();
        }
    }
}
