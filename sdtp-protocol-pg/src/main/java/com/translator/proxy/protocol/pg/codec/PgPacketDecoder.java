package com.translator.proxy.protocol.pg.codec;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

/**
 * PostgreSQL 协议拆包器。
 *
 * <p>处理两个阶段：
 * <ol>
 *   <li><b>Startup 阶段</b>：初始连接时，客户端发送长度前缀 + 协议版本号，
 *       无消息类型码。检测 SSL 请求（版本号 80877103）。</li>
 *   <li><b>Normal 阶段</b>：认证完成后，每条消息格式为
 *       消息类型码（1 字节） + 长度（4 字节，含自身） + payload。</li>
 * </ol>
 */
public class PgPacketDecoder extends ByteToMessageDecoder {

    private static final Logger log = LoggerFactory.getLogger(PgPacketDecoder.class);

    private final long maxPacketSize;
    private volatile boolean startupComplete;

    public PgPacketDecoder(long maxPacketSize) {
        this.maxPacketSize = maxPacketSize > 0 ? maxPacketSize : 16 * 1024 * 1024;
        this.startupComplete = false;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (!startupComplete) {
            decodeStartup(ctx, in, out);
        } else {
            decodeNormal(in, out);
        }
    }

    /**
     * Startup 阶段解码：长度前缀（4 字节大端序）+ 协议版本（4 字节）+ 参数对。
     */
    private void decodeStartup(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 4) {
            return;
        }

        in.markReaderIndex();
        int length = in.readInt();

        // 检测 SSL 请求（长度 8，协议版本 80877103）
        if (length == 8) {
            if (in.readableBytes() < 4) {
                in.resetReaderIndex();
                return;
            }
            int protocolVersion = in.readInt();
            if (protocolVersion == PgWire.SSL_REQUEST_CODE) {
                log.debug("SSL request received, rejecting (SSL not supported)");
                // 发送 'N' 拒绝 SSL
                ByteBuf response = ctx.alloc().buffer(1);
                response.writeByte('N');
                ctx.writeAndFlush(response);
                return;
            }
            // Cancel 请求
            if (protocolVersion == PgWire.CANCEL_REQUEST_CODE) {
                log.debug("Cancel request received");
                // Cancel 请求包含 pid 和 secret key，暂时忽略
                if (in.readableBytes() >= 8) {
                    in.skipBytes(8);
                }
                return;
            }
        }

        // StartupMessage: length + protocol version + parameters
        if (in.readableBytes() < length - 4) {
            in.resetReaderIndex();
            return;
        }

        ByteBuf payload = in.readRetainedSlice(length - 4);
        out.add(new PgRawMessage(PgRawMessage.TYPE_STARTUP, length, payload));

        startupComplete = true;
    }

    /**
     * Normal 阶段解码：类型码（1 字节）+ 长度（4 字节）+ payload。
     */
    private void decodeNormal(ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 5) {
            return;
        }

        in.markReaderIndex();
        byte type = in.readByte();
        int length = in.readInt();

        int payloadLen = length - 4;
        if (payloadLen < 0) {
            log.warn("Invalid message length: {}", length);
            return;
        }

        if (payloadLen > maxPacketSize) {
            log.error("Message too large: {} bytes (max: {})", payloadLen, maxPacketSize);
            in.resetReaderIndex();
            return;
        }

        if (in.readableBytes() < payloadLen) {
            in.resetReaderIndex();
            return;
        }

        ByteBuf payload = in.readRetainedSlice(payloadLen);
        out.add(new PgRawMessage(type, length, payload));
    }
}
