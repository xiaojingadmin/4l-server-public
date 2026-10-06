package com.linklike.server.wire;

/** 业务模块注册自己的路由，对应 Python 版各 {@code wire_*.py} 的 {@code ROUTES} + {@code dispatch}。 */
public interface WireModule {

    /** 把本模块处理的路径注册到路由表。 */
    void register(WireRouter router);
}
