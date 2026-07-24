package com.translator.proxy.protocol.mysql.codec;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

import com.translator.proxy.protocol.mysql.util.BufferUtils;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

/**
 * MySQL 编解码器测试：验证 Encoder/Decoder 的往返正确性。
 */
public class MySQLPacketCodecTest {

    // ==================== Decoder 测试 ====================

    @Test
    public void testDecodeCompletePacket() {
        EmbeddedChannel channel = new EmbeddedChannel(new MySQLPacketDecoder());

        // 构造完整包：payload "Hello", seq=0
        String payload = "Hello";
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        ByteBuf in = Unpooled.buffer();
        in.writeMediumLE(payloadBytes.length); // 3 字节长度
        in.writeByte(0); // seq=0
        in.writeBytes(payloadBytes); // payload

        channel.writeInbound(in);

        MySQLPacketDecoder.RawMySQLPacket packet = channel.readInbound();
        assertNotNull("应该解码出一个包", packet);
        assertEquals("seq 应为 0", (byte) 0, packet.getSequenceId());
        assertEquals("payload 长度应匹配", payloadBytes.length, packet.getPayload().readableBytes());

        byte[] decoded = new byte[payloadBytes.length];
        packet.getPayload().readBytes(decoded);
        assertArrayEquals("payload 内容应正确", payloadBytes, decoded);

        packet.release();
        channel.finish();
    }

    @Test
    public void testDecodeMultiPacket() {
        EmbeddedChannel channel = new EmbeddedChannel(new MySQLPacketDecoder());

        // 构造两个连续包
        ByteBuf in = Unpooled.buffer();

        // 包 1: payload "AB", seq=0
        in.writeMediumLE(2);
        in.writeByte(0);
        in.writeBytes(new byte[] {'A', 'B'});

        // 包 2: payload "CDE", seq=1
        in.writeMediumLE(3);
        in.writeByte(1);
        in.writeBytes(new byte[] {'C', 'D', 'E'});

        channel.writeInbound(in);

        // 读包 1
        MySQLPacketDecoder.RawMySQLPacket p1 = channel.readInbound();
        assertNotNull(p1);
        assertEquals(0, p1.getSequenceId());
        assertEquals(2, p1.getPayload().readableBytes());
        p1.release();

        // 读包 2
        MySQLPacketDecoder.RawMySQLPacket p2 = channel.readInbound();
        assertNotNull(p2);
        assertEquals(1, p2.getSequenceId());
        assertEquals(3, p2.getPayload().readableBytes());
        p2.release();

        channel.finish();
    }

    @Test
    public void testDecodeIncompleteHeader() {
        EmbeddedChannel channel = new EmbeddedChannel(new MySQLPacketDecoder());

        // 只发 2 字节（不够 4 字节头）
        ByteBuf in = Unpooled.buffer();
        in.writeShort(0x0005);
        channel.writeInbound(in);

        // 应该没有任何包被解码
        assertNull(channel.readInbound());

        channel.finish();
    }

    @Test
    public void testDecodeIncompletePayload() {
        EmbeddedChannel channel = new EmbeddedChannel(new MySQLPacketDecoder());

        // 头发了 4 字节说 payload 有 10 字节，但实际只有 5 字节
        ByteBuf in = Unpooled.buffer();
        in.writeMediumLE(10);
        in.writeByte(0);
        in.writeBytes(new byte[] {1, 2, 3, 4, 5});
        channel.writeInbound(in);

        // payload 不完整，不应解码
        assertNull(channel.readInbound());

        // 补发剩余 5 字节
        ByteBuf more = Unpooled.buffer();
        more.writeBytes(new byte[] {6, 7, 8, 9, 10});
        channel.writeInbound(more);

        // 现在应该解码出来了
        MySQLPacketDecoder.RawMySQLPacket packet = channel.readInbound();
        assertNotNull(packet);
        assertEquals(10, packet.getPayload().readableBytes());
        packet.release();

        channel.finish();
    }

    // ==================== Encoder 测试 ====================

    @Test
    public void testEncodeSinglePacket() {
        EmbeddedChannel channel = new EmbeddedChannel(new MySQLPacketEncoder());

        ByteBuf payload = Unpooled.copiedBuffer("Hello", StandardCharsets.UTF_8);
        channel.writeOutbound(new MySQLPacketEncoder.OutgoingPacket(payload, (byte) 2));

        ByteBuf encoded = channel.readOutbound();
        assertNotNull("应该编码出一个包", encoded);

        // 验证头
        int len = encoded.readUnsignedMediumLE();
        assertEquals("长度应等于 payload 长度", 5, len);
        assertEquals("seq 应为 2", (byte) 2, encoded.readByte());

        // 验证 payload
        byte[] payloadBytes = new byte[5];
        encoded.readBytes(payloadBytes);
        assertArrayEquals("payload 内容应正确", "Hello".getBytes(StandardCharsets.UTF_8), payloadBytes);

        encoded.release();
        channel.finish();
    }

    // ==================== 往返测试 ====================

    @Test
    public void testRoundTrip() {
        EmbeddedChannel serverChannel = new EmbeddedChannel(new MySQLPacketDecoder(), new MySQLPacketEncoder());

        // 模拟客户端发送包
        ByteBuf clientIn = Unpooled.buffer();
        clientIn.writeMediumLE(3);
        clientIn.writeByte(5);
        clientIn.writeBytes(new byte[] {10, 20, 30});
        serverChannel.writeInbound(clientIn);

        // 服务端解码
        MySQLPacketDecoder.RawMySQLPacket decoded = serverChannel.readInbound();
        assertNotNull(decoded);
        assertEquals(5, decoded.getSequenceId());

        // 服务端构造响应包
        ByteBuf response = Unpooled.buffer();
        response.writeBytes(new byte[] {99, 98, 97});
        serverChannel.writeOutbound(new MySQLPacketEncoder.OutgoingPacket(response, (byte) 6));

        // 读取编码后的输出
        ByteBuf encoded = serverChannel.readOutbound();
        assertNotNull(encoded);
        int respLen = encoded.readUnsignedMediumLE();
        assertEquals(3, respLen);
        assertEquals(6, encoded.readByte());
        byte[] respPayload = new byte[3];
        encoded.readBytes(respPayload);
        assertArrayEquals(new byte[] {99, 98, 97}, respPayload);

        decoded.release();
        encoded.release();
        serverChannel.finish();
    }

    @Test
    public void testEncodeEmptyPayload() {
        EmbeddedChannel channel = new EmbeddedChannel(new MySQLPacketEncoder());

        ByteBuf empty = Unpooled.buffer(0);
        channel.writeOutbound(new MySQLPacketEncoder.OutgoingPacket(empty, (byte) 0));

        ByteBuf encoded = channel.readOutbound();
        assertNotNull(encoded);
        assertEquals(0, encoded.readUnsignedMediumLE()); // payload 长度 = 0
        assertEquals(0, encoded.readByte()); // seq = 0
        assertEquals(0, encoded.readableBytes()); // 没有多余数据

        encoded.release();
        channel.finish();
    }

    // ==================== 大包切片与重组测试 ====================

    @Test
    public void testDecodeLargePacketReassembly() {
        EmbeddedChannel channel = new EmbeddedChannel(new MySQLPacketDecoder());

        // 逻辑包大小设计：总计约 17MB 的包，第一部分 16MB-1，第二部分 1MB
        int firstLen = 16777215; // MAX_PAYLOAD_LENGTH
        int secondLen = 1024 * 1024; // 1MB

        // 使用 64KB 数组作为共享切片源，防止内存溢出 (OOM)
        int chunkSize = 65536;
        ByteBuf sliceA = Unpooled.directBuffer(chunkSize);
        for (int i = 0; i < chunkSize; i++) {
            sliceA.writeByte('A');
        }
        ByteBuf sliceB = Unpooled.directBuffer(chunkSize);
        for (int i = 0; i < chunkSize; i++) {
            sliceB.writeByte('B');
        }

        try {
            // 组装第一物理包的 4 字节头部 [Len=16MB-1, Seq=0]
            ByteBuf header1 = Unpooled.buffer(4);
            header1.writeMediumLE(firstLen);
            header1.writeByte(0); // seq=0

            // 使用 CompositeByteBuf 组合 payload，避免分配 16MB 连续内存
            CompositeByteBuf payload1 = channel.alloc().compositeBuffer();
            int remaining = firstLen;
            while (remaining > 0) {
                int toAdd = Math.min(remaining, chunkSize);
                payload1.addComponent(true, sliceA.retainedSlice(0, toAdd));
                remaining -= toAdd;
            }

            CompositeByteBuf in1 = channel.alloc().compositeBuffer();
            in1.addComponent(true, header1);
            in1.addComponent(true, payload1);

            channel.writeInbound(in1);

            // 此时应该还不能解码出完整逻辑包
            assertNull("在第一个分包收到后，不应该分发完整包", channel.readInbound());

            // 组装第二物理包的 4 字节头部 [Len=1MB, Seq=1]
            ByteBuf header2 = Unpooled.buffer(4);
            header2.writeMediumLE(secondLen);
            header2.writeByte(1); // seq=1

            // 同样用 CompositeByteBuf 组合第二包的 payload
            CompositeByteBuf payload2 = channel.alloc().compositeBuffer();
            remaining = secondLen;
            while (remaining > 0) {
                int toAdd = Math.min(remaining, chunkSize);
                payload2.addComponent(true, sliceB.retainedSlice(0, toAdd));
                remaining -= toAdd;
            }

            CompositeByteBuf in2 = channel.alloc().compositeBuffer();
            in2.addComponent(true, header2);
            in2.addComponent(true, payload2);

            channel.writeInbound(in2);

            // 此时大包重组完成，应该能解码出来
            MySQLPacketDecoder.RawMySQLPacket packet = channel.readInbound();
            assertNotNull("大包拼装完成后，应该成功解码", packet);
            assertEquals("大包最后的 sequenceId 应为 1", (byte) 1, packet.getSequenceId());
            assertEquals(
                    "组装后的 payload 长度应为 17MB",
                    firstLen + secondLen,
                    packet.getPayload().readableBytes());

            // 验证前半部分和后半部分数据是否完好
            byte[] verifyFirst = new byte[100];
            packet.getPayload().readBytes(verifyFirst);
            for (byte b : verifyFirst) {
                assertEquals('A', b);
            }

            packet.getPayload().readerIndex(firstLen);
            byte[] verifySecond = new byte[100];
            packet.getPayload().readBytes(verifySecond);
            for (byte b : verifySecond) {
                assertEquals('B', b);
            }

            packet.release();
        } finally {
            // 确保测试结束彻底释放共享切片
            sliceA.release();
            sliceB.release();
            channel.finish();
        }
    }

    @Test
    public void testDecodeLargePacketOOMProtection() {
        EmbeddedChannel channel = new EmbeddedChannel(new MySQLPacketDecoder());

        int firstLen = 16777215; // 16MB - 1
        int chunkSize = 65536;
        ByteBuf sliceA = Unpooled.directBuffer(chunkSize);
        for (int i = 0; i < chunkSize; i++) {
            sliceA.writeByte('A');
        }

        try {
            // 连续发送 5 个 16MB 的满包，总大小将达到 80MB，超出 64MB 的上限
            for (int i = 0; i < 5; i++) {
                ByteBuf header = Unpooled.buffer(4);
                header.writeMediumLE(firstLen);
                header.writeByte(i); // seq 连续递增

                CompositeByteBuf payload = channel.alloc().compositeBuffer();
                int remaining = firstLen;
                while (remaining > 0) {
                    int toAdd = Math.min(remaining, chunkSize);
                    payload.addComponent(true, sliceA.retainedSlice(0, toAdd));
                    remaining -= toAdd;
                }

                CompositeByteBuf in = channel.alloc().compositeBuffer();
                in.addComponent(true, header);
                in.addComponent(true, payload);

                channel.writeInbound(in);
            }
            fail("当累计大小超过 64MB 限制时，应该抛出 DecoderException 异常");
        } catch (io.netty.handler.codec.DecoderException e) {
            assertTrue("异常消息中应说明超限原因", e.getMessage().contains("exceeds maxAllowedPacketSize limit"));
        } finally {
            sliceA.release();
            channel.finish();
        }
    }

    @Test
    public void testEncodeLargePacketSplitting() {
        EmbeddedChannel channel = new EmbeddedChannel(new MySQLPacketEncoder());

        int firstLen = 16777215; // MAX_PAYLOAD_LENGTH
        int secondLen = 1024 * 1024; // 1MB
        int totalLen = firstLen + secondLen;

        int chunkSize = 65536;
        ByteBuf sliceA = Unpooled.directBuffer(chunkSize);
        for (int i = 0; i < chunkSize; i++) {
            sliceA.writeByte('A');
        }
        ByteBuf sliceB = Unpooled.directBuffer(chunkSize);
        for (int i = 0; i < chunkSize; i++) {
            sliceB.writeByte('B');
        }

        try {
            // 用 CompositeByteBuf 组合出 17MB 的大 payload 传给 OutgoingPacket
            CompositeByteBuf bigPayload = channel.alloc().compositeBuffer();
            int remaining = firstLen;
            while (remaining > 0) {
                int toAdd = Math.min(remaining, chunkSize);
                bigPayload.addComponent(true, sliceA.retainedSlice(0, toAdd));
                remaining -= toAdd;
            }
            remaining = secondLen;
            while (remaining > 0) {
                int toAdd = Math.min(remaining, chunkSize);
                bigPayload.addComponent(true, sliceB.retainedSlice(0, toAdd));
                remaining -= toAdd;
            }

            channel.writeOutbound(new MySQLPacketEncoder.OutgoingPacket(bigPayload, (byte) 5));

            // 从 channel 获取合并后的输出 ByteBuf
            ByteBuf out = channel.readOutbound();
            assertNotNull(out);

            // 1. 验证第一物理包头
            assertEquals("第一包的包长应为 16MB - 1", firstLen, out.readUnsignedMediumLE());
            assertEquals("第一包的 seq 应为 5", (byte) 5, out.readByte());
            // 验证第一物理包 payload
            byte[] firstVerify = new byte[100];
            out.readBytes(firstVerify);
            for (byte b : firstVerify) {
                assertEquals('A', b);
            }
            // 跳过第一包剩余部分
            out.skipBytes(firstLen - 100);

            // 2. 验证第二物理包头
            assertEquals("第二包的包长应为 1MB", secondLen, out.readUnsignedMediumLE());
            assertEquals("第二包的 seq 应为 6", (byte) 6, out.readByte());
            // 验证第二物理包 payload
            byte[] secondVerify = new byte[100];
            out.readBytes(secondVerify);
            for (byte b : secondVerify) {
                assertEquals('B', b);
            }
            // 跳过第二包剩余部分并验证已读完
            out.skipBytes(secondLen - 100);
            assertEquals("整个大包的所有字节应恰好被校验读完", 0, out.readableBytes());

            out.release();
        } finally {
            sliceA.release();
            sliceB.release();
            channel.finish();
        }
    }

    // ==================== BufferUtils 测试 ====================

    @Test
    public void testLengthEncodedInt() {
        // 测试 0-250 单字节
        ByteBuf buf = Unpooled.buffer();
        BufferUtils.writeLengthEncodedInt(buf, 0);
        BufferUtils.writeLengthEncodedInt(buf, 250);
        BufferUtils.writeLengthEncodedInt(buf, 251);
        BufferUtils.writeLengthEncodedInt(buf, 65535);
        BufferUtils.writeLengthEncodedInt(buf, 65536);

        assertEquals(0, BufferUtils.readLengthEncodedInt(buf));
        assertEquals(250, BufferUtils.readLengthEncodedInt(buf));
        assertEquals(251, BufferUtils.readLengthEncodedInt(buf));
        assertEquals(65535, BufferUtils.readLengthEncodedInt(buf));
        assertEquals(65536, BufferUtils.readLengthEncodedInt(buf));

        buf.release();
    }

    @Test
    public void testLengthEncodedString() {
        ByteBuf buf = Unpooled.buffer();
        BufferUtils.writeLengthEncodedString(buf, "Hello World");
        BufferUtils.writeLengthEncodedString(buf, null); // NULL 标记
        BufferUtils.writeLengthEncodedString(buf, ""); // 空串

        assertEquals("Hello World", BufferUtils.readLengthEncodedString(buf));
        assertNull(BufferUtils.readLengthEncodedString(buf));
        assertEquals("", BufferUtils.readLengthEncodedString(buf));

        buf.release();
    }

    @Test
    public void testNullTerminatedString() {
        ByteBuf buf = Unpooled.buffer();
        BufferUtils.writeNullTerminatedString(buf, "test_db");

        assertEquals("test_db", BufferUtils.readNullTerminatedString(buf));
        buf.release();
    }

    @Test
    public void testFixedLengthString() {
        ByteBuf buf = Unpooled.buffer();
        BufferUtils.writeFixedLengthString(buf, "root", 16); // 填到 16 字节

        byte[] raw = new byte[16];
        buf.readBytes(raw);
        assertEquals('r', raw[0]);
        assertEquals('o', raw[1]);
        assertEquals('o', raw[2]);
        assertEquals('t', raw[3]);
        // 剩余应为 NUL
        for (int i = 4; i < 16; i++) {
            assertEquals(0x00, raw[i]);
        }
        buf.release();
    }
}
