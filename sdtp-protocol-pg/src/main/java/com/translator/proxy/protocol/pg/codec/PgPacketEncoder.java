package com.translator.proxy.protocol.pg.codec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * PostgreSQL 协议封包器。
 *
 * <p>将 {@link PgMessage} 编码为 PG 线协议帧：
 * <pre>
 *   消息类型码（1 字节） + 长度（4 字节，含自身）+ payload
 * </pre>
 */
public class PgPacketEncoder extends MessageToByteEncoder<PgMessage> {

    private static final Logger log = LoggerFactory.getLogger(PgPacketEncoder.class);

    @Override
    protected void encode(ChannelHandlerContext ctx, PgMessage msg, ByteBuf out) {
        ByteBuf payload = msg.getPayload();
        int payloadLen = payload.readableBytes();

        try {
            out.writeByte(msg.getType());
            out.writeInt(4 + payloadLen);
            out.writeBytes(payload);
        } finally {
            payload.release();
        }

        if (log.isTraceEnabled()) {
            log.trace("Encoded PG message: type={}, len={}", (char) msg.getType(), 4 + payloadLen);
        }
    }
}
