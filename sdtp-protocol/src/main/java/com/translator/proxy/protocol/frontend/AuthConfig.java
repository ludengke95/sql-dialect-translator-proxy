package com.translator.proxy.protocol.frontend;

/**
 * 认证配置接口 —— 提供前端协议认证所需的账号信息。
 */
public interface AuthConfig {

    /**
     * 获取认证用户名。
     *
     * @return 用户名
     */
    String getUsername();

    /**
     * 获取认证密码。
     *
     * @return 密码
     */
    String getPassword();
}
