package com.linklike.server.wire;

import java.util.Map;

/**
 * 一条 {@code /v1/...} 路由的处理函数。
 *
 * <p>返回值是下发给客户端的内部数据（PascalCase 属性名），由 HTTP 层按
 * {@code wire_models.json} 编码成 snake_case 字段。返回 {@code null} 视为空对象。
 */
@FunctionalInterface
public interface WireHandler {

    Map<String, Object> handle(WireRequest request);
}
