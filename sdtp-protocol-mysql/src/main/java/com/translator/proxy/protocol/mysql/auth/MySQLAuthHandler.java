package com.translator.proxy.protocol.mysql.auth;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.translator.metrics.ConnectionMetrics;
import com.translator.proxy.core.handler.BackendRouter;
import com.translator.proxy.core.handler.SessionAttribute;
import com.translator.proxy.core.session.FrontendSession;
import com.translator.proxy.protocol.mysql.codec.MySQLPacketDecoder;
import com.translator.proxy.protocol.mysql.command.MySQLCommandHandler;
import com.translator.proxy.protocol.mysql.constant.CapabilityFlags;
import com.translator.proxy.protocol.mysql.result.MySQLResponseWriter;
import com.translator.proxy.protocol.mysql.util.BufferUtils;
import com.translator.proxy.protocol.mysql.util.MySQLAuth;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.EventExecutorGroup;

/**
 * MySQL 认证处理器 —— 处理客户端发来的 HandshakeResponse41 和 AuthSwitchResponse。
 *
 * <p>流程：
 * <ol>
 *   <li>服务端声明 mysql_native_password（HandshakeV10）</li>
 *   <li>客户端请求 mysql_native_password → 直接 SHA-1 验证 → OK</li>
 *   <li>客户端请求 caching_sha2_password → AuthSwitch 到 mysql_native_password → 验证 → OK</li>
 * </ol>
 *
 * <p>此类将原 sdtp-core 中的 {@code AuthHandler} 逻辑完整迁移至 sdtp-protocol 模块。
 */
public class MySQLAuthHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(MySQLAuthHandler.class);

    private final String expectedUser;
    private final String expectedPassword;
    private final EventExecutorGroup bizExecutorGroup;
    private final BackendRouter backendRouter;

    /** 是否在等待 AuthSwitchResponse（caching_sha2_password 第二步） */
    private boolean expectingAuthSwitchResponse;

    /** AuthSwitch 第二阶段使用的 scramble */
    private byte[] authSwitchScramble;

    /** HandshakeResponse41 中携带的 database 名（AuthSwitch 成功后设置） */
    private String pendingDatabase;

    /** 响应写入器 */
    private final MySQLResponseWriter responseWriter;

    public MySQLAuthHandler(
            String expectedUser,
            String expectedPassword,
            EventExecutorGroup bizExecutorGroup,
            BackendRouter backendRouter) {
        this.expectedUser = expectedUser;
        this.expectedPassword = expectedPassword;
        this.bizExecutorGroup = bizExecutorGroup;
        this.backendRouter = backendRouter;
        this.responseWriter = new MySQLResponseWriter();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof MySQLPacketDecoder.RawMySQLPacket)) {
            ctx.fireChannelRead(msg);
            return;
        }

        MySQLPacketDecoder.RawMySQLPacket raw = (MySQLPacketDecoder.RawMySQLPacket) msg;
        ByteBuf payload = raw.getPayload();

        try {
            FrontendSession session =
                    ctx.channel().attr(SessionAttribute.SESSION_KEY).get();
            if (session == null) {
                log.error("No session found for channel {}", ctx.channel());
                writeErrorAndClose(ctx, 1045, "HY000", "Internal error: no session", (byte) 2);
                return;
            }

            if (expectingAuthSwitchResponse) {
                handleAuthSwitchResponse(ctx, session, payload);
            } else {
                handleHandshakeResponse(ctx, session, payload);
            }
        } catch (Exception e) {
            log.error("Error processing auth", e);
            writeErrorAndClose(
                    ctx,
                    1047,
                    "HY000",
                    "Unknown error during authentication",
                    expectingAuthSwitchResponse ? (byte) 4 : (byte) 2);
        } finally {
            raw.release();
        }
    }

    // ==================== HandshakeResponse41 ====================

    private void handleHandshakeResponse(ChannelHandlerContext ctx, FrontendSession session, ByteBuf payload) {
        int savedReaderIndex = payload.readerIndex();
        byte[] rawBytes = new byte[payload.readableBytes()];
        payload.getBytes(savedReaderIndex, rawBytes);
        log.debug("HandshakeResponse41 raw ({} bytes): {}", rawBytes.length, MySQLAuth.bytesToHex(rawBytes));

        HandshakeResponse resp = parseHandshakeResponse41(payload);

        log.info(
                "Auth request from {}: user={}, db={}, plugin={} capFlags=0x{}",
                ctx.channel().remoteAddress(),
                resp.username,
                resp.database,
                resp.authPluginName != null ? resp.authPluginName : "(none)",
                Integer.toHexString(resp.capabilityFlags));

        if (!expectedUser.equals(resp.username)) {
            log.warn("Auth failed: unknown user '{}'", resp.username);
            ConnectionMetrics.onAuthFailure("wrong_user");
            writeErrorAndClose(ctx, 1045, "28000", "Access denied for user '" + resp.username + "'", (byte) 2);
            return;
        }

        String plugin = resp.authPluginName;

        if ("caching_sha2_password".equals(plugin)) {
            if (resp.authResponse.length > 0) {
                if (MySQLAuth.verifySha256(expectedPassword, session.getScramble(), resp.authResponse)) {
                    log.info("Auth success (sha256 fast) for user '{}'", resp.username);
                    applyDatabase(session, resp.database);
                    responseWriter.writeAuthMoreDataFastSuccess(ctx, (byte) 2);
                    responseWriter.writeOk((byte) 3, ctx, 0, 0, getStatusFlags(session), 0, "");
                    switchToCommandHandler(ctx);
                    return;
                }
                log.info("SHA-256 fast auth failed, falling back to AuthSwitch");
            } else {
                log.info("Client sent empty auth_response for caching_sha2_password, starting AuthSwitch");
            }

            pendingDatabase = resp.database;
            authSwitchScramble = MySQLAuth.generateScramble();
            writeAuthSwitchRequest(ctx, authSwitchScramble, "mysql_native_password");
            expectingAuthSwitchResponse = true;

        } else if ("mysql_native_password".equals(plugin) || plugin == null) {
            if (!MySQLAuth.verify(expectedPassword, session.getScramble(), resp.authResponse)) {
                log.warn("Auth failed (native): wrong password for user '{}'", resp.username);
                ConnectionMetrics.onAuthFailure("wrong_password");
                log.debug("Password: '{}'", expectedPassword);
                log.debug("Scramble: {}", MySQLAuth.bytesToHex(session.getScramble()));
                log.debug(
                        "Expected: {}",
                        MySQLAuth.bytesToHex(MySQLAuth.scramble411(expectedPassword, session.getScramble())));
                log.debug("Client:   {}", MySQLAuth.bytesToHex(resp.authResponse));
                writeErrorAndClose(
                        ctx,
                        1045,
                        "28000",
                        "Access denied for user '" + resp.username + "' (using password: YES)",
                        (byte) 2);
                return;
            }
            log.info("Auth success (native) for user '{}'", resp.username);
            ConnectionMetrics.onAuthSuccess("native");
            applyDatabase(session, resp.database);
            responseWriter.writeOk((byte) 2, ctx, 0, 0, getStatusFlags(session), 0, "");
            switchToCommandHandler(ctx);

        } else {
            log.warn("Auth failed: unsupported plugin '{}' from user '{}'", plugin, resp.username);
            ConnectionMetrics.onAuthFailure("unsupported_plugin");
            writeErrorAndClose(
                    ctx, 1045, "28000", "Authentication plugin '" + plugin + "' is not supported.", (byte) 2);
        }
    }

    private void applyDatabase(FrontendSession session, String database) {
        if (database != null && !database.isEmpty()) {
            session.setDatabase(database);
        }
    }

    // ==================== AuthSwitchResponse ====================

    private void handleAuthSwitchResponse(ChannelHandlerContext ctx, FrontendSession session, ByteBuf payload) {
        expectingAuthSwitchResponse = false;

        byte[] clientToken = new byte[payload.readableBytes()];
        payload.readBytes(clientToken);

        log.info("AuthSwitchResponse received ({} bytes)", clientToken.length);
        log.debug("AuthSwitch scramble: {}", MySQLAuth.bytesToHex(authSwitchScramble));

        if (!MySQLAuth.verify(expectedPassword, authSwitchScramble, clientToken)) {
            log.warn("Auth failed (native auth switch): wrong password");
            log.debug(
                    "Expected: {}", MySQLAuth.bytesToHex(MySQLAuth.scramble411(expectedPassword, authSwitchScramble)));
            log.debug("Client:   {}", MySQLAuth.bytesToHex(clientToken));
            writeErrorAndClose(
                    ctx, 1045, "28000", "Access denied for user '" + expectedUser + "' (using password: YES)", (byte)
                            4);
            return;
        }

        log.info("Auth success (sha256) for user '{}'", expectedUser);
        ConnectionMetrics.onAuthSuccess("sha256");
        applyDatabase(session, pendingDatabase);
        pendingDatabase = null;
        authSwitchScramble = null;

        responseWriter.writeOk((byte) 4, ctx, 0, 0, getStatusFlags(session), 0, "");
        switchToCommandHandler(ctx);
    }

    private void switchToCommandHandler(ChannelHandlerContext ctx) {
        if (bizExecutorGroup != null) {
            ctx.pipeline()
                    .addAfter(
                            bizExecutorGroup, "authHandler", "commandHandler", new MySQLCommandHandler(backendRouter));
            ctx.pipeline().remove(this);
        } else {
            ctx.pipeline().replace(this, "commandHandler", new MySQLCommandHandler(backendRouter));
        }
    }

    // ==================== Packet Writers ====================

    private void writeAuthSwitchRequest(ChannelHandlerContext ctx, byte[] scramble, String pluginName) {
        responseWriter.writeAuthSwitchRequest(ctx, scramble, pluginName);
    }

    private void writeErrorAndClose(
            ChannelHandlerContext ctx, int errorCode, String sqlState, String message, byte seq) {
        responseWriter
                .writeErr(seq, ctx, errorCode, sqlState, message)
                .addListener(io.netty.channel.ChannelFutureListener.CLOSE);
    }

    // ==================== HandshakeResponse41 解析 ====================

    static HandshakeResponse parseHandshakeResponse41(ByteBuf payload) {
        HandshakeResponse resp = new HandshakeResponse();

        resp.capabilityFlags = (int) payload.readUnsignedIntLE();
        resp.maxPacketSize = payload.readIntLE();
        resp.characterSet = payload.readUnsignedByte();
        payload.skipBytes(23);
        resp.username = BufferUtils.readNullTerminatedString(payload);

        boolean usePluginAuthLenenc =
                (resp.capabilityFlags & CapabilityFlags.CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA) != 0;
        boolean useSecureConnection = (resp.capabilityFlags & CapabilityFlags.CLIENT_SECURE_CONNECTION) != 0;

        if (usePluginAuthLenenc) {
            long len = BufferUtils.readLengthEncodedInt(payload);
            if (len > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Auth response too long: " + len);
            }
            resp.authResponse = new byte[(int) len];
            payload.readBytes(resp.authResponse);
        } else if (useSecureConnection) {
            int len = payload.readUnsignedByte();
            resp.authResponse = new byte[len];
            payload.readBytes(resp.authResponse);
        } else {
            resp.authResponse = BufferUtils.readNullTerminatedString(payload).getBytes(StandardCharsets.UTF_8);
        }

        if ((resp.capabilityFlags & CapabilityFlags.CLIENT_CONNECT_WITH_DB) != 0 && payload.readableBytes() > 0) {
            resp.database = BufferUtils.readNullTerminatedString(payload);
        }
        if ((resp.capabilityFlags & CapabilityFlags.CLIENT_PLUGIN_AUTH) != 0 && payload.readableBytes() > 0) {
            resp.authPluginName = BufferUtils.readNullTerminatedString(payload);
        }

        return resp;
    }

    /**
     * 获取会话状态标志。
     */
    public static int getStatusFlags(FrontendSession session) {
        if (session == null) {
            return com.translator.proxy.protocol.mysql.constant.ServerStatus.SERVER_STATUS_AUTOCOMMIT;
        }
        int flags = 0;
        if (session.isAutoCommit()) {
            flags |= com.translator.proxy.protocol.mysql.constant.ServerStatus.SERVER_STATUS_AUTOCOMMIT;
        }
        if (session.isInTransaction() || !session.isAutoCommit()) {
            flags |= com.translator.proxy.protocol.mysql.constant.ServerStatus.SERVER_STATUS_IN_TRANS;
        }
        return flags;
    }

    static class HandshakeResponse {
        int capabilityFlags;
        int maxPacketSize;
        int characterSet;
        String username;
        byte[] authResponse;
        String database;
        String authPluginName;
    }
}
