package com.translator.proxy.protocol.pg.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * PostgreSQL MD5 认证算法。
 *
 * <p>PG MD5 认证：
 * <ol>
 *   <li>服务端发送 AuthenticationMD5Password 消息，携带 4 字节随机 salt</li>
 *   <li>客户端计算：md5(md5(password + username) + salt) 的十六进制表示</li>
 *   <li>服务端同样计算并比对</li>
 * </ol>
 */
public final class PgAuth {

    private PgAuth() {}

    /**
     * 验证客户端发来的 MD5 密码。
     *
     * @param username      用户名
     * @param password      明文密码
     * @param salt          4 字节随机 salt
     * @param clientResponse 客户端发来的十六进制字符串（"md5" + 32 hex）
     * @return true 如果验证通过
     */
    public static boolean verify(String username, String password, byte[] salt, String clientResponse) {
        if (username == null || password == null || salt == null || clientResponse == null) {
            return false;
        }

        String expected = encode(username, password, salt);

        // 客户端可能发 "md5" + 32 hex 或直接 32 hex
        String normalized = clientResponse;
        if (normalized.startsWith("md5")) {
            normalized = normalized.substring(3);
        }

        return expected.equals(normalized);
    }

    /**
     * 计算 MD5 密码。
     *
     * <pre>
     *   token = md5(md5(password + username) + salt)
     * </pre>
     *
     * @param username 用户名
     * @param password 明文密码
     * @param salt     4 字节随机 salt
     * @return 32 字符十六进制字符串
     */
    public static String encode(String username, String password, byte[] salt) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");

            // inner = md5(password + username)
            byte[] innerInput = (password + username).getBytes(StandardCharsets.UTF_8);
            byte[] inner = md5.digest(innerInput);

            // outer = md5(hex(inner) + salt)
            String innerHex = bytesToHex(inner);
            byte[] outerInput = new byte[36];
            System.arraycopy(innerHex.getBytes(StandardCharsets.UTF_8), 0, outerInput, 0, 32);
            System.arraycopy(salt, 0, outerInput, 32, 4);
            byte[] outer = md5.digest(outerInput);

            return bytesToHex(outer);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }

    /**
     * 生成 4 字节随机 salt。
     */
    public static byte[] generateSalt() {
        byte[] salt = new byte[4];
        for (int i = 0; i < 4; i++) {
            salt[i] = (byte) (Math.random() * 256);
        }
        return salt;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
