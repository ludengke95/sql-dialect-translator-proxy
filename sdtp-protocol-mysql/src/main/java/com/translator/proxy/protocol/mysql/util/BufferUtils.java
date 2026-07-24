package com.translator.proxy.protocol.mysql.util;

import java.nio.charset.StandardCharsets;

import io.netty.buffer.ByteBuf;

/**
 * MySQL 协议字节操作工具类：长度编码整数/字符串读写、小端序辅助、null-bitmap 等。
 */
public final class BufferUtils {

    private BufferUtils() {}

    /** NULL 值的协议标记字节 */
    public static final byte NULL_MARKER = (byte) 0xFB;

    // ==================== Length-Encoded Integer ====================

    /**
     * 写入长度编码整数（Length-Encoded Integer）。
     * <pre>
     *   0-250:     1 byte
     *   251-65535: 0xFC + 2 bytes LE
     *   65536-16777215: 0xFD + 3 bytes LE
     *   >16777215: 0xFE + 8 bytes LE
     * </pre>
     */
    public static void writeLengthEncodedInt(ByteBuf buf, long value) {
        if (value < 251) {
            buf.writeByte((int) value);
        } else if (value < 65536) {
            buf.writeByte(0xFC);
            buf.writeShortLE((int) value);
        } else if (value < 16777216) {
            buf.writeByte(0xFD);
            buf.writeMediumLE((int) value);
        } else {
            buf.writeByte(0xFE);
            buf.writeLongLE(value);
        }
    }

    /**
     * 从缓冲区读取长度编码整数。返回读取的值。
     */
    public static long readLengthEncodedInt(ByteBuf buf) {
        int first = buf.readUnsignedByte();
        if (first < 251) {
            return first;
        } else if (first == 0xFC) {
            return buf.readUnsignedShortLE();
        } else if (first == 0xFD) {
            return buf.readUnsignedMediumLE();
        } else if (first == 0xFE) {
            return buf.readLongLE();
        } else {
            // 0xFB = NULL, 0xFF = error
            return first;
        }
    }

    /**
     * 计算长度编码整数所占的字节数（只用于预分配，不能完全信任；写入时用 writeLengthEncodedInt）。
     */
    public static int lengthEncodedIntSize(long value) {
        if (value < 251) return 1;
        if (value < 65536) return 3;
        if (value < 16777216) return 4;
        return 9;
    }

    // ==================== Length-Encoded String ====================

    /**
     * 写入长度编码字符串（先写长度编码整数，再写 UTF-8 字节内容）。
     */
    public static void writeLengthEncodedString(ByteBuf buf, String value) {
        if (value == null) {
            buf.writeByte(NULL_MARKER);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeLengthEncodedInt(buf, bytes.length);
        buf.writeBytes(bytes);
    }

    /**
     * 读取长度编码字符串，返回 UTF-8 解码后的字符串。
     */
    public static String readLengthEncodedString(ByteBuf buf) {
        long len = readLengthEncodedInt(buf);
        if (len == 0xFB) { // NULL marker (251 unsigned)
            return null;
        }
        if (len < 0) {
            throw new IllegalArgumentException("Negative length-encoded string length: " + len);
        }
        byte[] bytes = new byte[(int) len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ==================== Null-Terminated String ====================

    /**
     * 写入以 NUL (0x00) 结尾的字符串（MySQL 协议中大量使用）。
     */
    public static void writeNullTerminatedString(ByteBuf buf, String value) {
        buf.writeBytes(value.getBytes(StandardCharsets.UTF_8));
        buf.writeByte(0x00);
    }

    /**
     * 读取以 NUL (0x00) 结尾的字符串。若未找到 NUL 则安全读取至 ByteBuf 末尾。
     */
    public static String readNullTerminatedString(ByteBuf buf) {
        if (buf == null || buf.readableBytes() == 0) {
            return "";
        }
        int len = buf.bytesBefore((byte) 0x00);
        if (len < 0) {
            int remaining = buf.readableBytes();
            byte[] bytes = new byte[remaining];
            buf.readBytes(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        buf.skipBytes(1); // skip NUL
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ==================== Fixed-Length String ====================

    /**
     * 写入固定长度字符串（右侧补 NUL）。
     */
    public static void writeFixedLengthString(ByteBuf buf, String value, int length) {
        byte[] bytes = value != null ? value.getBytes(StandardCharsets.UTF_8) : new byte[0];
        int writeLen = Math.min(bytes.length, length);
        buf.writeBytes(bytes, 0, writeLen);
        for (int i = writeLen; i < length; i++) {
            buf.writeByte(0x00);
        }
    }

    /**
     * 读取固定长度字符串。
     */
    public static String readFixedLengthString(ByteBuf buf, int length) {
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        // trim trailing NULs
        int end = length;
        while (end > 0 && bytes[end - 1] == 0x00) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    // ==================== EOF-Length String ====================

    /**
     * 读取直到缓冲区末尾的所有剩余字节并解码为字符串。
     */
    public static String readEofString(ByteBuf buf) {
        int len = buf.readableBytes();
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
