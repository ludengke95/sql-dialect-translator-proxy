package com.translator.proxy.protocol.pg.codec;

import io.netty.buffer.ByteBuf;

/**
 * PG 原始消息 —— 解码中间产物。
 *
 * <p>在 Startup 阶段（无消息类型码），type 为 0；
 * 在 Normal 阶段，type 为消息类型码。
 */
public class PgRawMessage {

    private final byte type;
    private final int length;
    private final ByteBuf payload;

    /** Startup 阶段（无类型码） */
    public static final byte TYPE_STARTUP = 0;

    public PgRawMessage(byte type, int length, ByteBuf payload) {
        this.type = type;
        this.length = length;
        this.payload = payload;
    }

    public byte getType() {
        return type;
    }

    public int getLength() {
        return length;
    }

    public ByteBuf getPayload() {
        return payload;
    }

    /**
     * 是否为 Startup 消息。
     */
    public boolean isStartup() {
        return type == TYPE_STARTUP;
    }

    public void release() {
        if (payload != null) {
            payload.release();
        }
    }

    @Override
    public String toString() {
        if (isStartup()) {
            return "PgRawMessage{type=Startup, len=" + length + "}";
        }
        return "PgRawMessage{type=" + (char) type + ", len=" + length + "}";
    }
}
