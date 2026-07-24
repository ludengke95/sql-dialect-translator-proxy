package com.translator.proxy.protocol.mysql.auth;

import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.translator.metrics.ConnectionMetrics;
import com.translator.proxy.core.handler.BackendRouter;
import com.translator.proxy.core.handler.SessionAttribute;
import com.translator.proxy.core.session.FrontendSession;
import com.translator.proxy.metrics.NettyMetrics;
import com.translator.proxy.protocol.mysql.codec.MySQLPacketEncoder;
import com.translator.proxy.protocol.mysql.constant.CapabilityFlags;
import com.translator.proxy.protocol.mysql.util.BufferUtils;
import com.translator.proxy.protocol.mysql.util.MySQLAuth;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.EventExecutorGroup;

/**
 * MySQL 握手处理器 —— MySQL 连接建立后的第一个 Handler。
 *
 * <p>流程：
 * <ol>
 *   <li>生成 20 字节 scramble（认证挑战码）</li>
 *   <li>创建 FrontendSession 并绑定到 channel</li>
 *   <li>构造 HandshakeV10 包发送给客户端</li>
 *   <li>将自己从 pipeline 中移除，加入 MySQLAuthHandler</li>
 * </ol>
 *
 * <p>此类将原 sdtp-core 中的 {@code HandshakeHandler} 逻辑完整迁移至 sdtp-protocol 模块。
 */
public class MySQLHandshakeHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(MySQLHandshakeHandler.class);

    /** 伪装的服务端版本 */
    private static final String SERVER_VERSION = "5.7.38-proxy";

    /** 默认字符集 utf8_general_ci → collation id 33（注意：utf8mb4_general_ci 为 45） */
    private static final int CHARSET_UTF8 = 33;

    private static final AtomicLong CONNECTION_ID_GENERATOR = new AtomicLong(1);

    private final String authUser;
    private final String authPassword;
    private final EventExecutorGroup bizExecutorGroup;
    private final BackendRouter backendRouter;

    /**
     * 使用自定义账密和业务线程池。
     *
     * @param backendRouter 后端路由器（由启动层经 SPI 注入）
     */
    public MySQLHandshakeHandler(
            String authUser, String authPassword, EventExecutorGroup bizExecutorGroup, BackendRouter backendRouter) {
        this.authUser = authUser;
        this.authPassword = authPassword;
        this.bizExecutorGroup = bizExecutorGroup;
        this.backendRouter = backendRouter;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ConnectionMetrics.onConnect();
        NettyMetrics.onChannelActive();

        long connectionId = CONNECTION_ID_GENERATOR.getAndIncrement();

        byte[] scramble = MySQLAuth.generateScramble();

        FrontendSession session = FrontendSession.create(ctx.channel(), connectionId, scramble);
        ctx.channel().attr(SessionAttribute.SESSION_KEY).set(session);

        ByteBuf handshake = buildHandshakeV10(ctx.alloc(), connectionId, scramble);

        log.info("Sending handshake to {} (connectionId={})", ctx.channel().remoteAddress(), connectionId);
        log.debug("Handshake scramble (20 bytes): {}", MySQLAuth.bytesToHex(scramble));

        ctx.writeAndFlush(new MySQLPacketEncoder.OutgoingPacket(handshake, (byte) 0));

        ctx.pipeline()
                .replace(
                        this,
                        "authHandler",
                        new MySQLAuthHandler(authUser, authPassword, bizExecutorGroup, backendRouter));
    }

    /**
     * 构造 HandshakeV10 报文。
     */
    static ByteBuf buildHandshakeV10(ByteBufAllocator alloc, long connectionId, byte[] scramble) {
        ByteBuf buf = alloc.buffer(128);

        buf.writeByte(0x0A);

        BufferUtils.writeNullTerminatedString(buf, SERVER_VERSION);

        buf.writeIntLE((int) connectionId);

        buf.writeBytes(scramble, 0, 8);

        buf.writeByte(0x00);

        int capabilities = CapabilityFlags.SERVER_DEFAULT_CAPABILITIES;
        buf.writeShortLE(capabilities & 0xFFFF);

        buf.writeByte(CHARSET_UTF8);

        buf.writeShortLE(0x0002);

        buf.writeShortLE((capabilities >> 16) & 0xFFFF);

        buf.writeByte(21);

        buf.writeZero(10);

        buf.writeBytes(scramble, 8, 12);
        buf.writeByte(0x00);

        BufferUtils.writeNullTerminatedString(buf, "mysql_native_password");

        return buf;
    }
}
