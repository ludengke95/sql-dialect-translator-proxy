package com.translator.proxy.protocol.mysql.util;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * 测试 MySQLAuth 密码验证算法
 */
public class MySQLAuthTest {

    /**
     * 验证 scramble411 算法是否正确
     */
    @Test
    public void testScramble411() {
        String password = "123";
        byte[] scramble = MySQLAuth.generateScramble();

        // 计算token
        byte[] token = MySQLAuth.scramble411(password, scramble);

        // 验证token长度应该是20字节
        assertEquals(20, token.length);

        // 验证算法是否可以正确验证
        assertTrue(MySQLAuth.verify(password, scramble, token));

        System.out.println("Password: " + password);
        System.out.println("Scramble: " + MySQLAuth.bytesToHex(scramble));
        System.out.println("Token: " + MySQLAuth.bytesToHex(token));
    }

    /**
     * 验证密码不匹配时应该失败
     */
    @Test
    public void testWrongPassword() {
        String correctPassword = "123";
        String wrongPassword = "wrong";
        byte[] scramble = MySQLAuth.generateScramble();

        // 使用正确密码计算token
        byte[] correctToken = MySQLAuth.scramble411(correctPassword, scramble);

        // 使用错误密码验证应该失败
        assertFalse(MySQLAuth.verify(wrongPassword, scramble, correctToken));

        // 使用正确密码验证应该成功
        assertTrue(MySQLAuth.verify(correctPassword, scramble, correctToken));
    }

    /**
     * 手动验证算法（与真实 MySQL JDBC 驱动对比）
     */
    @Test
    public void testManualVerification() {
        // 使用与 Proxy Server 日志相同的密码和 scramble
        String password = "123";
        byte[] scramble = hexStringToByteArray("878f584c56e22e5c3008d4673b9204c59b7064a8");

        byte[] expectedToken = MySQLAuth.scramble411(password, scramble);

        System.out.println("Manual verification:");
        System.out.println("Password: " + password);
        System.out.println("Scramble: " + MySQLAuth.bytesToHex(scramble));
        System.out.println("Expected token: " + MySQLAuth.bytesToHex(expectedToken));

        // 从 Proxy Server 日志: Expected: 9f8c038fcded23c2fb0b40e0dceb81a1edac9b54
        System.out.println("Proxy Server Expected: 9f8c038fcded23c2fb0b40e0dceb81a1edac9b54");

        // 从 Proxy Server 日志: Client: 5c9ff7c066d63a311e06a6c2214e0fbeced0fffb
        System.out.println("MySQL JDBC Client: 5c9ff7c066d63a311e06a6c2214e0fbeced0fffb");

        // 验证 Expected token 是否匹配 Proxy Server 的计算
        assertEquals("9f8c038fcded23c2fb0b40e0dceb81a1edac9b54", MySQLAuth.bytesToHex(expectedToken));
    }

    /**
     * 十六进制字符串转 byte 数组
     */
    private byte[] hexStringToByteArray(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
