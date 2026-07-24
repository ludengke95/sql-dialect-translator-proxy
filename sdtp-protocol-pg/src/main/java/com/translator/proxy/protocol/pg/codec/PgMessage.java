package com.translator.proxy.protocol.pg.codec;

import io.netty.buffer.ByteBuf;

/**
 * PG 消息封装 —— 编码后的完整协议消息。
 *
 * <p>包含消息类型码（1 字节）和 payload。
 * 编码器负责添加 4 字节长度前缀。
 */
public class PgMessage {

    private final byte type;
    private final ByteBuf payload;

    public PgMessage(byte type, ByteBuf payload) {
        this.type = type;
        this.payload = payload;
    }

    public byte getType() {
        return type;
    }

    public ByteBuf getPayload() {
        return payload;
    }

    /**
     * 释放 payload 持有的引用。
     */
    public void release() {
        if (payload != null) {
            payload.release();
        }
    }

    @Override
    public String toString() {
        return "PgMessage{type=" + (char) type + ", payloadLen=" + (payload != null ? payload.readableBytes() : 0)
                + "}";
    }
}
