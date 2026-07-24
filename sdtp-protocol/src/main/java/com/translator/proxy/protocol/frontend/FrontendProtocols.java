package com.translator.proxy.protocol.frontend;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SPI 协议加载工具类。
 *
 * <p>通过 Java 原生 {@link ServiceLoader} 加载所有 {@link FrontendProtocol} 实现。
 * 提供按协议 ID 查找和返回默认实现的能力。
 */
public final class FrontendProtocols {

    private static final Logger log = LoggerFactory.getLogger(FrontendProtocols.class);

    /** 延迟加载的协议列表 */
    private static volatile List<FrontendProtocol> protocols;

    private FrontendProtocols() {}

    /**
     * 加载所有注册的 {@link FrontendProtocol} 实现。
     *
     * @return 协议列表（不可变快照）
     */
    public static List<FrontendProtocol> loadAll() {
        if (protocols == null) {
            synchronized (FrontendProtocols.class) {
                if (protocols == null) {
                    List<FrontendProtocol> list = new ArrayList<FrontendProtocol>();
                    ServiceLoader<FrontendProtocol> loader = ServiceLoader.load(FrontendProtocol.class);
                    Iterator<FrontendProtocol> it = loader.iterator();
                    while (it.hasNext()) {
                        try {
                            FrontendProtocol protocol = it.next();
                            list.add(protocol);
                            log.info(
                                    "Loaded frontend protocol: {} (id={}, port={})",
                                    protocol.getClass().getName(),
                                    protocol.id(),
                                    protocol.defaultPort());
                        } catch (Exception e) {
                            log.warn("Failed to load frontend protocol implementation", e);
                        }
                    }
                    protocols = list;
                }
            }
        }
        return protocols;
    }

    /**
     * 按协议 ID 查找指定协议。
     *
     * @param protocolId 协议 ID，如 "MYSQL"、"POSTGRESQL"
     * @return 匹配的协议实例
     * @throws IllegalArgumentException 如果找不到对应协议
     */
    public static FrontendProtocol load(String protocolId) {
        if (protocolId == null || protocolId.isEmpty()) {
            throw new IllegalArgumentException("protocolId must not be null or empty");
        }

        for (FrontendProtocol protocol : loadAll()) {
            if (protocolId.equalsIgnoreCase(protocol.id())) {
                return protocol;
            }
        }

        throw new IllegalArgumentException(
                "No frontend protocol found for id='" + protocolId + "'. Available protocols: " + getAvailableIds());
    }

    /**
     * 返回第一个找到的协议实现（默认协议）。
     *
     * @return 默认协议实例
     * @throws IllegalArgumentException 如果没有任何协议实现
     */
    public static FrontendProtocol loadDefault() {
        List<FrontendProtocol> all = loadAll();
        if (all.isEmpty()) {
            throw new IllegalArgumentException("No frontend protocol implementations found on classpath. "
                    + "Ensure sdtp-protocol or sdtp-protocol-pg is on the classpath.");
        }
        return all.get(0);
    }

    /**
     * 获取所有已加载协议的 ID 列表（用于错误消息）。
     */
    private static List<String> getAvailableIds() {
        List<String> ids = new ArrayList<String>();
        for (FrontendProtocol p : loadAll()) {
            ids.add(p.id());
        }
        return ids;
    }
}
