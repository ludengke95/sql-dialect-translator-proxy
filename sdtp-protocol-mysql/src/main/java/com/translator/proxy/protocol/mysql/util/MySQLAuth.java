package com.translator.proxy.protocol.mysql.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * MySQL 认证算法：`mysql_native_password`（SHA-1）和 `caching_sha2_password`（SHA-256）。
 *
 * <h3>mysql_native_password</h3>
 * <ol>
 *   <li>服务端生成 20 字节随机数（scramble）发给客户端</li>
 *   <li>客户端计算：SHA1(password) XOR SHA1(scramble + SHA1(SHA1(password)))</li>
 *   <li>客户端将 20 字节结果回传，服务端比对</li>
 * </ol>
 *
 * <h3>caching_sha2_password</h3>
 * <ol>
 *   <li>客户端计算：SHA256(password) XOR SHA256(scramble + SHA256(SHA256(password)))</li>
 *   <li>客户端将 32 字节结果回传</li>
 *   <li>服务端比对，成功后先发 AuthMoreData(0x03=fast_auth_success) 再发 OK</li>
 * </ol>
 */
public final class MySQLAuth {

    private MySQLAuth() {}

    /**
     * 生成 20 字节随机 scramble（Auth Plugin Data）。
     *
     * <p>scramble 取值范围为可打印 ASCII (0x21-0x7E)，避免 NUL 字节，
     * 同时兼容 MySQL JDBC 5.1.x 驱动将 seed 作为 String 处理的场景
     * （String 转换对非 ASCII 字节可能引入编码问题）。
     */
    public static byte[] generateScramble() {
        byte[] scramble = new byte[20];
        for (int i = 0; i < 20; i++) {
            // 使用可打印 ASCII 范围 0x21-0x7E（避免 NUL 和高位字节的字符集问题）
            int b = 0x21 + (int) (Math.random() * (0x7E - 0x21 + 1));
            scramble[i] = (byte) b;
        }
        return scramble;
    }

    /**
     * 服务端验证客户端发来的 token 是否正确。
     *
     * @param password     服务端存储的明文密码
     * @param scramble     服务端发出去的 20 字节 scramble（Auth Plugin Data）
     * @param clientToken  客户端回传的加密 token
     * @return true 如果验证通过
     */
    public static boolean verify(String password, byte[] scramble, byte[] clientToken) {
        if (password == null || scramble == null || clientToken == null) {
            return false;
        }
        byte[] expected = scramble411(password, scramble);
        if (expected.length != clientToken.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != clientToken[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 客户端计算 native_password 加密结果（用于测试验证）。
     *
     * @param password 明文密码
     * @param scramble 服务端 scramble（20 字节）
     * @return 加密后的 20 字节 token
     */
    public static byte[] scramble411(String password, byte[] scramble) {
        if (password == null || scramble == null) {
            return new byte[0];
        }
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");

            byte[] passwordBytes = password.getBytes(StandardCharsets.UTF_8);

            // stage1 = SHA1(password)
            byte[] stage1 = sha1.digest(passwordBytes);

            // stage2 = SHA1(stage1) = SHA1(SHA1(password))
            byte[] stage2 = sha1.digest(stage1);

            // stage3 = SHA1(scramble + stage2)
            sha1.reset();
            sha1.update(scramble);
            sha1.update(stage2);
            byte[] stage3 = sha1.digest();

            // result = stage1 XOR stage3
            byte[] result = new byte[stage1.length];
            for (int i = 0; i < result.length; i++) {
                result[i] = (byte) (stage1[i] ^ stage3[i]);
            }
            return result;

        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 algorithm not available", e);
        }
    }

    // ==================== caching_sha2_password (SHA-256) ====================

    /**
     * 服务端验证 caching_sha2_password 客户端 token（fast auth）。
     *
     * @param password     服务端存储的明文密码
     * @param scramble     服务端发出去的 20 字节 scramble
     * @param clientToken  客户端回传的加密 token（应为 32 字节）
     * @return true 如果验证通过
     */
    public static boolean verifySha256(String password, byte[] scramble, byte[] clientToken) {
        if (password == null || scramble == null || clientToken == null) {
            return false;
        }
        byte[] expected = scramble411Sha256(password, scramble);
        if (expected.length != clientToken.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != clientToken[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * caching_sha2_password fast auth token 计算（SHA-256 版本）。
     *
     * <pre>
     *   stage1 = SHA256(password)
     *   stage2 = SHA256(stage1)
     *   stage3 = SHA256(scramble + stage2)
     *   result = stage1 XOR stage3    （32 字节）
     * </pre>
     *
     * @param password 明文密码
     * @param scramble 服务端 scramble（20 字节）
     * @return 32 字节的 fast auth token
     */
    public static byte[] scramble411Sha256(String password, byte[] scramble) {
        if (password == null || scramble == null) {
            return new byte[0];
        }
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");

            byte[] passwordBytes = password.getBytes(StandardCharsets.UTF_8);

            // stage1 = SHA256(password)
            byte[] stage1 = sha256.digest(passwordBytes);

            // stage2 = SHA256(stage1) = SHA256(SHA256(password))
            byte[] stage2 = sha256.digest(stage1);

            // stage3 = SHA256(scramble + stage2)
            sha256.reset();
            sha256.update(scramble);
            sha256.update(stage2);
            byte[] stage3 = sha256.digest();

            // result = stage1 XOR stage3
            byte[] result = new byte[stage1.length];
            for (int i = 0; i < result.length; i++) {
                result[i] = (byte) (stage1[i] ^ stage3[i]);
            }
            return result;

        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    // ==================== Debug Helper ====================

    /**
     * 字节数组转十六进制字符串（调试用）。
     */
    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
