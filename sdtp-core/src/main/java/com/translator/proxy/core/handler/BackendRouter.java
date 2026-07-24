package com.translator.proxy.core.handler;

import java.util.Collections;
import java.util.Set;

import com.translator.proxy.core.session.FrontendSession;

/**
 * 后端路由器接口。
 *
 * <p>根据会话中记录的数据库名称，解析出对应的后端 {@link QueryProcessor}。
 * sdtp-backend 模块中的 {@code BackendPoolManager} 实现此接口。
 */
public interface BackendRouter {

    /**
     * 根据会话信息解析出应使用的后端处理器。
     *
     * @param session 当前会话（含 database 信息）
     * @return 后端查询处理器，不应为 null
     */
    QueryProcessor resolve(FrontendSession session);

    /**
     * 获取所有已配置的后端名称集合。
     *
     * @return 后端名称集合（不可变视图），无后端时返回空集合
     */
    default Set<String> getBackendNames() {
        return Collections.emptySet();
    }
}
