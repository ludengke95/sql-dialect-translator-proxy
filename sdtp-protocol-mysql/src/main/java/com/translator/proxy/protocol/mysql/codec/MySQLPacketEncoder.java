package com.translator.proxy.protocol.mysql.codec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * MySQL 协议封包器。
 *
 * <p>将 payload ByteBuf 封装为 MySQL 线协议包：
 * <pre>
 *   写入 3 字节小端序 payload 长度 + 1 字节 sequence ID + payload
 * </pre>
 */
public class MySQLPacketEncoder extends MessageToByteEncoder<MySQLPacketEncoder.OutgoingPacket> {

    private static final Logger log = LoggerFactory.getLogger(MySQLPacketEncoder.class);

    private static final int MAX_PAYLOAD_LENGTH = 0x00FFFFFF;

    @Override
    protected void encode(ChannelHandlerContext ctx, OutgoingPacket packet, ByteBuf out) {
        ByteBuf payload = packet.payload;
        byte sequenceId = packet.sequenceId;
        int totalLen = payload.readableBytes();

        try {
            if (totalLen < MAX_PAYLOAD_LENGTH) {
                // 普通小包，直接写入
                out.writeMediumLE(totalLen);
                out.writeByte(sequenceId);
                out.writeBytes(payload);
            } else {
                // 超大包分片写出
                int remaining = totalLen;
                byte seq = sequenceId;

                while (remaining >= MAX_PAYLOAD_LENGTH) {
                    out.writeMediumLE(MAX_PAYLOAD_LENGTH);
                    out.writeByte(seq++);
                    out.writeBytes(payload, MAX_PAYLOAD_LENGTH); // 写入一部分并推进 readerIndex
                    remaining -= MAX_PAYLOAD_LENGTH;
                }

                // 写入最后一个包（即使 remaining 为 0 也必须写入，作为大包的终止符）
                out.writeMediumLE(remaining);
                out.writeByte(seq);
                if (remaining > 0) {
                    out.writeBytes(payload, remaining);
                }

                if (log.isDebugEnabled()) {
                    log.debug("Large packet split finished. Sent {} fragments", (seq - sequenceId + 1));
                }
            }
        } finally {
            // 释放 payload（MessageToByteEncoder 只释放 OutgoingPacket 结构，我们需要释放它持有的 ByteBuf）
            payload.release();
        }

        if (log.isTraceEnabled()) {
            log.trace("Encoded packet: seq={}, payloadLen={}", sequenceId, totalLen);
        }
    }

    /**
     * 待发送的 MySQL 包（payload + sequenceId）。
     * <p>调用者负责管理 payload ByteBuf 的生命周期（通常 writeAndFlush 后 Netty 会自动 release）。
     */
    public static class OutgoingPacket {
        private final ByteBuf payload;
        private final byte sequenceId;

        public OutgoingPacket(ByteBuf payload, byte sequenceId) {
            this.payload = payload;
            this.sequenceId = sequenceId;
        }

        public ByteBuf getPayload() {
            return payload;
        }

        public byte getSequenceId() {
            return sequenceId;
        }
    }
}
