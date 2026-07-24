package com.translator.proxy.core.session;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.channel.Channel;

/**
 * 前端连接的会话上下文。
 * 与 Netty Channel 一对一绑定，维护连接生命周期内的状态。
 *
 * <p>关键状态：sequenceId（严格递增）、当前 database、字符集、事务状态。
 */
public class FrontendSession {

    private final Channel channel;
    private final long connectionId;
    private final AtomicInteger sequenceId;

    /** 当前选择的 database，null 表示未选择 */
    private volatile String database;

    /** 当前客户端字符集编码 */
    private volatile Charset charset;

    /** 是否在事务中 */
    private volatile boolean inTransaction;

    /** 是否自动提交 */
    private volatile boolean autoCommit;

    /** 服务端生成的 scramble（20 字节），用于认证校验 */
    private final byte[] scramble;

    public FrontendSession(Channel channel, long connectionId) {
        this.channel = channel;
        this.connectionId = connectionId;
        this.sequenceId = new AtomicInteger(0);
        this.charset = StandardCharsets.UTF_8;
        this.autoCommit = true;
        this.scramble = new byte[0]; // Phase 2 中由 HandshakeHandler 设置
    }

    private FrontendSession(Channel channel, long connectionId, byte[] scramble) {
        this.channel = channel;
        this.connectionId = connectionId;
        this.sequenceId = new AtomicInteger(0);
        this.charset = StandardCharsets.UTF_8;
        this.autoCommit = true;
        this.scramble = scramble;
    }

    /**
     * 创建携带 scramble 的会话。
     */
    public static FrontendSession create(Channel channel, long connectionId, byte[] scramble) {
        return new FrontendSession(channel, connectionId, scramble);
    }

    // ==================== Getters ====================

    public Channel getChannel() {
        return channel;
    }

    public long getConnectionId() {
        return connectionId;
    }

    public String getDatabase() {
        return database;
    }

    public Charset getCharset() {
        return charset;
    }

    public boolean isInTransaction() {
        return inTransaction;
    }

    public boolean isAutoCommit() {
        return autoCommit;
    }

    public byte[] getScramble() {
        return scramble;
    }

    // ==================== Sequence ID ====================

    /**
     * 获取当前 sequence ID 并自增（用于发送下一个包）。
     */
    public byte nextSequenceId() {
        return (byte) (sequenceId.getAndIncrement() & 0xFF);
    }

    /**
     * 重置 sequence ID 为指定值（如 COM_QUERY 开始时客户端会重置为 0）。
     */
    public void resetSequenceId(int value) {
        sequenceId.set(value & 0xFF);
    }

    // ==================== Setters ====================

    public void setDatabase(String database) {
        this.database = database;
    }

    public void setCharset(Charset charset) {
        this.charset = charset;
    }

    public void setInTransaction(boolean inTransaction) {
        this.inTransaction = inTransaction;
    }

    public void setAutoCommit(boolean autoCommit) {
        this.autoCommit = autoCommit;
    }

    @Override
    public String toString() {
        return "FrontendSession{id=" + connectionId
                + ", db=" + (database != null ? database : "(none)")
                + ", autoCommit=" + autoCommit + "}";
    }
}
