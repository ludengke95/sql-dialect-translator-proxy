package com.translator.proxy.core.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.translator.proxy.metrics.NettyMetrics;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

/**
 * Netty 物理读写字节吞吐监控 Handler。
 */
public class NettyMetricsHandler extends ChannelDuplexHandler {

    private static final Logger log = LoggerFactory.getLogger(NettyMetricsHandler.class);

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof ByteBuf) {
            int bytes = ((ByteBuf) msg).readableBytes();
            if (log.isDebugEnabled()) {
                log.debug("NettyMetricsHandler read {} bytes", bytes);
            }
            NettyMetrics.recordBytesRead(bytes);
        } else {
            if (log.isDebugEnabled()) {
                log.debug(
                        "NettyMetricsHandler read msg of non-ByteBuf type: {}",
                        msg != null ? msg.getClass().getName() : "null");
            }
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof ByteBuf) {
            int bytes = ((ByteBuf) msg).readableBytes();
            if (log.isDebugEnabled()) {
                log.debug("NettyMetricsHandler wrote {} bytes", bytes);
            }
            NettyMetrics.recordBytesWritten(bytes);
        } else {
            if (log.isDebugEnabled()) {
                log.debug(
                        "NettyMetricsHandler wrote msg of non-ByteBuf type: {}",
                        msg != null ? msg.getClass().getName() : "null");
            }
        }
        super.write(ctx, msg, promise);
    }
}
