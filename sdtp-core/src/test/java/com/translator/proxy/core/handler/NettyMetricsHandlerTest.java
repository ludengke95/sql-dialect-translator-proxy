package com.translator.proxy.core.handler;

import static org.junit.Assert.*;

import org.junit.Test;

import com.translator.proxy.metrics.NettyMetrics;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

public class NettyMetricsHandlerTest {

    @Test
    public void testMetricsCount() {
        double readBefore = NettyMetrics.BYTES_READ.get();
        double writeBefore = NettyMetrics.BYTES_WRITTEN.get();

        EmbeddedChannel channel = new EmbeddedChannel(new NettyMetricsHandler());

        // 测试读取
        ByteBuf in = Unpooled.copiedBuffer(new byte[] {1, 2, 3, 4, 5});
        channel.writeInbound(in);

        // 测试写入
        ByteBuf out = Unpooled.copiedBuffer(new byte[] {6, 7, 8});
        channel.writeOutbound(out);

        double readAfter = NettyMetrics.BYTES_READ.get();
        double writeAfter = NettyMetrics.BYTES_WRITTEN.get();

        assertEquals(5, (int) (readAfter - readBefore));
        assertEquals(3, (int) (writeAfter - writeBefore));

        channel.finish();
    }
}
