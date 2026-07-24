package com.translator.proxy.protocol.mysql.codec;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.DecoderException;

/**
 * MySQL 协议拆包器。
 *
 * <p>MySQL 线协议包格式（4 字节头）：
 * <pre>
 *   ┌──────────────┬──────────────┬───────────────────┐
 *   │ payload_len  │ sequence_id  │     payload       │
 *   │  3 bytes LE  │  1 byte      │  payload_len bytes│
 *   └──────────────┴──────────────┴───────────────────┘
 * </pre>
 *
 * <p>注意：如果一个 MySQL 包超过 16MB（0xFFFFFF），会被拆成多个子包（splitting），
 * 每个子包有独立的 4 字节头。此处通过 CompositeByteBuf 实现分包的零拷贝重组。
 */
public class MySQLPacketDecoder extends ByteToMessageDecoder {

    private static final Logger log = LoggerFactory.getLogger(MySQLPacketDecoder.class);

    /** MySQL 包最大 payload 长度（3 字节 = 16MB - 1） */
    private static final int MAX_PAYLOAD_LENGTH = 0x00FFFFFF;

    /** 最大允许包大小上限（用于防 OOM 保护） */
    private final int maxAllowedPacketSize;

    public MySQLPacketDecoder() {
        this(64 * 1024 * 1024); // 默认 64MB
    }

    public MySQLPacketDecoder(int maxAllowedPacketSize) {
        this.maxAllowedPacketSize = maxAllowedPacketSize;
    }

    /** 用于累积大包分片的复合缓冲区 */
    private CompositeByteBuf cumulation;

    /** 记录大逻辑包开始时的第一个分包序列号 */
    private byte initialSequenceId = -1;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        // 至少需要 4 字节头
        if (in.readableBytes() < 4) {
            return;
        }

        // 标记读指针，以便 payload 不完整时回退
        in.markReaderIndex();

        // 读取 3 字节小端序 payload 长度
        int payloadLength = in.readUnsignedMediumLE();
        // 读取 1 字节 sequence ID
        byte sequenceId = in.readByte();

        // 检查 payload 是否已完整到达
        if (in.readableBytes() < payloadLength) {
            in.resetReaderIndex();
            return;
        }

        // 读取 payload
        ByteBuf payload = in.readRetainedSlice(payloadLength);

        if (log.isTraceEnabled()) {
            log.trace("Decoded packet fragment: seq={}, payloadLen={}", sequenceId, payloadLength);
        }

        // 组装与累积逻辑
        if (cumulation == null) {
            if (payloadLength < MAX_PAYLOAD_LENGTH) {
                // 普通小包（未分片）：直接向下分发
                out.add(new RawMySQLPacket(payload, sequenceId));
            } else {
                // 大包的第一个分片到达：初始化 CompositeByteBuf 并记录初始序列号
                cumulation = ctx.alloc().compositeBuffer();
                cumulation.addComponent(true, payload);
                initialSequenceId = sequenceId;
                log.debug("First fragment of large packet arrived. seq={}", sequenceId);
            }
        } else {
            // 后续分片到达：验证序列号连续性，并拼接
            int expectedSeq = (initialSequenceId + cumulation.numComponents()) & 0xFF;
            if ((sequenceId & 0xFF) != expectedSeq) {
                payload.release();
                clearCumulation();
                throw new DecoderException(
                        "Sequence out of order. Expected: " + expectedSeq + ", Got: " + (sequenceId & 0xFF));
            }

            // 防内存暴增保护
            if (cumulation.readableBytes() + payloadLength > maxAllowedPacketSize) {
                payload.release();
                clearCumulation();
                throw new DecoderException(
                        "Packet size exceeds maxAllowedPacketSize limit of " + maxAllowedPacketSize + " bytes.");
            }

            cumulation.addComponent(true, payload);

            if (payloadLength < MAX_PAYLOAD_LENGTH) {
                // 收到最后一个分片（包括长度为 0 的空尾包）：拼接完成，向下分发
                log.debug(
                        "Last fragment of large packet arrived. Finished reassembly. Total size={}",
                        cumulation.readableBytes());
                out.add(new RawMySQLPacket(cumulation, sequenceId));
                cumulation = null;
                initialSequenceId = -1;
            } else {
                log.debug(
                        "Received subsequent fragment of large packet. Current total size={}",
                        cumulation.readableBytes());
            }
        }
    }

    private void clearCumulation() {
        if (cumulation != null) {
            cumulation.release();
            cumulation = null;
        }
        initialSequenceId = -1;
    }

    @Override
    protected void handlerRemoved0(ChannelHandlerContext ctx) throws Exception {
        clearCumulation();
        super.handlerRemoved0(ctx);
    }

    /**
     * 解码后的原始包（payload + sequenceId）。
     * 下游 Handler 根据 command 类型进一步解析具体报文。
     */
    public static class RawMySQLPacket {
        private final ByteBuf payload;
        private final byte sequenceId;

        public RawMySQLPacket(ByteBuf payload, byte sequenceId) {
            this.payload = payload;
            this.sequenceId = sequenceId;
        }

        public ByteBuf getPayload() {
            return payload;
        }

        public byte getSequenceId() {
            return sequenceId;
        }

        /**
         * 释放 payload 持有的 ByteBuf 引用。
         */
        public void release() {
            if (payload != null) {
                payload.release();
            }
        }

        @Override
        public String toString() {
            return "RawMySQLPacket{seq=" + sequenceId + ", len=" + (payload != null ? payload.readableBytes() : 0)
                    + "}";
        }
    }
}
