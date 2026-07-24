package com.translator.proxy.protocol.pg.auth;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.translator.proxy.core.handler.BackendRouter;
import com.translator.proxy.core.session.FrontendSession;
import com.translator.proxy.protocol.frontend.AuthConfig;
import com.translator.proxy.protocol.pg.codec.PgMessage;
import com.translator.proxy.protocol.pg.codec.PgRawMessage;
import com.translator.proxy.protocol.pg.codec.PgWire;
import com.translator.proxy.protocol.pg.command.PgCommandHandler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.EventExecutorGroup;

/**
 * PostgreSQL 握手处理器 —— 处理 StartupMessage 和 MD5 认证。
 *
 * <p>流程：
 * <ol>
 *   <li>接收 StartupMessage（协议版本 3.0，用户/数据库参数）</li>
 *   <li>发送 AuthenticationMD5Password（'R' + int32(5) + salt）</li>
 *   <li>接收密码响应（'p' 消息，payload 为 "md5" + 32 hex）</li>
 *   <li>调用 PgAuth 验证，发送 AuthenticationOk</li>
 *   <li>发送 ParameterStatus 消息 + BackendKeyData + ReadyForQuery</li>
 *   <li>认证成功后 pipeline 切换到 PgCommandHandler</li>
 * </ol>
 */
public class PgHandshakeHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(PgHandshakeHandler.class);

    private static final String SERVER_VERSION = "14.8";
    private static final String SERVER_ENCODING = "UTF8";

    private final String authUser;
    private final String authPassword;
    private final EventExecutorGroup bizExecutorGroup;
    private final BackendRouter backendRouter;

    private byte[] salt;
    private String clientUser;
    private String clientDatabase;

    public PgHandshakeHandler(AuthConfig authConfig, EventExecutorGroup bizExecutorGroup, BackendRouter backendRouter) {
        this.authUser = authConfig.getUsername();
        this.authPassword = authConfig.getPassword();
        this.bizExecutorGroup = bizExecutorGroup;
        this.backendRouter = backendRouter;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof PgRawMessage)) {
            // todo fireChannelRead是做什么的
            ctx.fireChannelRead(msg);
            return;
        }

        PgRawMessage raw = (PgRawMessage) msg;
        try {
            if (raw.isStartup()) {
                handleStartup(ctx, raw);
            } else if (raw.getType() == 'p') {
                handlePassword(ctx, raw);
            } else {
                log.debug("Ignoring message type: {} during handshake", (char) raw.getType());
            }
        } finally {
            raw.release();
        }
    }

    /**
     * 处理 StartupMessage。
     */
    private void handleStartup(ChannelHandlerContext ctx, PgRawMessage raw) {
        ByteBuf payload = raw.getPayload();

        int protocolVersion = payload.readInt();
        if (protocolVersion != PgWire.PROTOCOL_VERSION_3_0) {
            log.warn("Unsupported protocol version: {}.{}", protocolVersion >> 16, protocolVersion & 0xFFFF);
            sendError(ctx, "FATAL", "08P01", "Unsupported protocol version");
            ctx.close();
            return;
        }

        // 解析参数对（key\0value\0...\0）
        while (payload.readableBytes() > 1) {
            String key = readCstr(payload);
            if (key.isEmpty()) {
                break; // 空字符串表示参数列表结束
            }
            String value = readCstr(payload);

            if ("user".equals(key)) {
                clientUser = value;
            } else if ("database".equals(key)) {
                clientDatabase = value;
            }
        }

        log.info("Startup: user={}, database={}", clientUser, clientDatabase);

        // 验证用户名
        if (clientUser == null || !clientUser.equals(authUser)) {
            log.warn("Auth failed: unknown user '{}'", clientUser);
            sendError(ctx, "FATAL", "28000", "Authentication failed for user '" + clientUser + "'");
            ctx.close();
            return;
        }

        // 发送 AuthenticationMD5Password
        salt = PgAuth.generateSalt();
        sendAuthenticationMd5Password(ctx, salt);
    }

    /**
     * 处理密码响应。
     */
    private void handlePassword(ChannelHandlerContext ctx, PgRawMessage raw) {
        ByteBuf payload = raw.getPayload();
        // PG PasswordMessage 的密码字段是 NUL 终止串，复用 readCstr 剥离末尾的 0x00，
        // 否则尾随 NUL 会导致 PgAuth.verify 的 equals 比对恒为 false（MD5 认证必败）。
        String clientResponse = readCstr(payload);

        log.debug("Password response: {}", clientResponse);

        if (!PgAuth.verify(clientUser, authPassword, salt, clientResponse)) {
            log.warn("Auth failed: wrong password for user '{}'", clientUser);
            sendError(ctx, "FATAL", "28000", "Authentication failed for user '" + clientUser + "' (wrong password)");
            ctx.close();
            return;
        }

        log.info("Auth success for user '{}'", clientUser);

        // 创建 FrontendSession
        long connectionId = System.currentTimeMillis() % Integer.MAX_VALUE;
        FrontendSession session = new FrontendSession(ctx.channel(), connectionId);
        if (clientDatabase != null && !clientDatabase.isEmpty()) {
            session.setDatabase(clientDatabase);
        }
        ctx.channel()
                .attr(com.translator.proxy.core.handler.SessionAttribute.SESSION_KEY)
                .set(session);

        // 发送 AuthenticationOk
        sendAuthenticationOk(ctx);

        // 发送 ParameterStatus 消息
        sendParameterStatus(ctx, "server_version", SERVER_VERSION);
        sendParameterStatus(ctx, "server_encoding", SERVER_ENCODING);
        sendParameterStatus(ctx, "client_encoding", SERVER_ENCODING);
        sendParameterStatus(ctx, "DateStyle", "ISO, MDY");
        sendParameterStatus(ctx, "integer_datetimes", "on");
        sendParameterStatus(ctx, "standard_conforming_strings", "on");

        // 发送 BackendKeyData
        sendBackendKeyData(ctx, (int) connectionId, (int) (Math.random() * Integer.MAX_VALUE));

        // 发送 ReadyForQuery
        sendReadyForQuery(ctx, PgWire.TXN_IDLE);

        // 切换到命令分发器
        if (bizExecutorGroup != null) {
            ctx.pipeline()
                    .addAfter(
                            bizExecutorGroup,
                            "handshakeHandler",
                            "commandHandler",
                            new PgCommandHandler(backendRouter));
            ctx.pipeline().remove(this);
        } else {
            ctx.pipeline().replace(this, "commandHandler", new PgCommandHandler(backendRouter));
        }
    }

    // ==================== 消息发送方法 ====================

    private void sendAuthenticationMd5Password(ChannelHandlerContext ctx, byte[] salt) {
        ByteBuf payload = ctx.alloc().buffer(8);
        payload.writeInt(PgWire.AUTH_MD5_PASSWORD);
        payload.writeBytes(salt);
        ctx.writeAndFlush(new PgMessage(PgWire.MSG_AUTHENTICATION, payload));
    }

    private void sendAuthenticationOk(ChannelHandlerContext ctx) {
        ByteBuf payload = ctx.alloc().buffer(4);
        payload.writeInt(PgWire.AUTH_OK);
        ctx.writeAndFlush(new PgMessage(PgWire.MSG_AUTHENTICATION, payload));
    }

    private void sendParameterStatus(ChannelHandlerContext ctx, String name, String value) {
        ByteBuf payload = ctx.alloc().buffer(64);
        PgWire.cstr(payload, name);
        PgWire.cstr(payload, value);
        ctx.write(new PgMessage(PgWire.MSG_PARAMETER_STATUS, payload));
    }

    private void sendBackendKeyData(ChannelHandlerContext ctx, int pid, int secretKey) {
        ByteBuf payload = ctx.alloc().buffer(8);
        payload.writeInt(pid);
        payload.writeInt(secretKey);
        ctx.write(new PgMessage(PgWire.MSG_BACKEND_KEY_DATA, payload));
    }

    private void sendReadyForQuery(ChannelHandlerContext ctx, byte txnStatus) {
        ByteBuf payload = ctx.alloc().buffer(1);
        payload.writeByte(txnStatus);
        ctx.writeAndFlush(new PgMessage(PgWire.MSG_READY_FOR_QUERY, payload));
    }

    private void sendError(ChannelHandlerContext ctx, String severity, String code, String message) {
        ByteBuf payload = ctx.alloc().buffer(128);
        PgWire.cstr(payload, "S");
        PgWire.cstr(payload, severity);
        PgWire.cstr(payload, "C");
        PgWire.cstr(payload, code);
        PgWire.cstr(payload, "M");
        PgWire.cstr(payload, message);
        payload.writeByte(0x00); // 终止符
        ctx.writeAndFlush(new PgMessage(PgWire.MSG_ERROR_RESPONSE, payload));
    }

    // ==================== 辅助方法 ====================

    private static String readCstr(ByteBuf buf) {
        int len = buf.bytesBefore((byte) 0x00);
        if (len < 0) {
            return "";
        }
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        buf.skipBytes(1);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
