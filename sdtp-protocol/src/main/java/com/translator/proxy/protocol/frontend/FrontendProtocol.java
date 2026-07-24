package com.translator.proxy.protocol.frontend;

import com.translator.proxy.core.handler.BackendRouter;

import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.ByteToMessageDecoder;

/**
 * SPI 主接口 —— 前端协议抽象。
 *
 * <p>每个协议实现模块（如 sdtp-protocol 的 MySQL、sdtp-protocol-pg 的 PostgreSQL）
 * 通过 Java {@link java.util.ServiceLoader} 注册其实现，sdtp-server 在启动时加载。
 *
 * <p>该接口定义了协议所需的全部工厂方法，包括编解码器、握手认证、
 * 命令处理、响应写入、类型映射和系统目录查询。
 */
public interface FrontendProtocol {

    /**
     * 协议唯一标识符，如 "MYSQL"、"POSTGRESQL"。
     *
     * @return 协议 ID
     */
    String id();

    /**
     * 前端方言的源方言标识符，如 "MYSQL"、"POSTGRESQL"。
     *
     * @return 源方言标识符
     */
    String getSourceDialect();

    /**
     * 创建协议拆包器（{@link ByteToMessageDecoder}）。
     *
     * @param maxPacketSize 最大允许包大小，用于防 OOM 保护
     * @return 协议拆包器实例
     */
    ByteToMessageDecoder newDecoder(long maxPacketSize);

    /**
     * 创建协议封包器（{@link ChannelHandler}）。
     *
     * @return 协议封包器实例
     */
    ChannelHandler newEncoder();

    /**
     * 创建握手处理器。
     *
     * @param authConfig 认证配置
     * @param executor   业务线程池（用于 pipeline 中 handler 的线程绑定）
     * @return 握手处理器实例
     */
    ChannelHandler newHandshakeHandler(AuthConfig authConfig, EventLoopGroup executor, BackendRouter backendRouter);

    /**
     * 创建响应写入器。
     *
     * @return 响应写入器实例
     */
    ResponseWriter newResponseWriter();

    /**
     * 创建类型映射器。
     *
     * @return 类型映射器实例
     */
    TypeMapper newTypeMapper();

    /**
     * 创建系统目录提供者。
     *
     * @return 系统目录提供者实例
     */
    SystemCatalogProvider newSystemCatalog();

    /**
     * 协议启动时的连接级参数注入钩子（可选）。
     *
     * <p>通用启动类（sdtp-server）在加载前端协议后调用一次，将全局配置中的
     * 连接级参数（如最大包大小）下发给协议自身，由各协议决定如何应用，
     * 从而避免通用层耦合任何具体协议的专属逻辑。默认空实现。
     *
     * <p>注意：参数使用基本类型而非 {@code ProxyConfig}，是为了保持
     * sdtp-protocol 模块对 sdtp-server 的无反向依赖。
     *
     * @param maxPacketSize 最大允许包大小（字节），用于解码器 OOM 防护及协议相关默认值
     */
    default void applyMaxPacketSize(long maxPacketSize) {}

    /**
     * 协议默认监听端口。
     *
     * @return 默认端口号（MySQL: 3306, PostgreSQL: 5432）
     */
    int defaultPort();
}
