package com.linklike.server.wire;

import java.util.Map;

/**
 * 一条 {@code /v1/...} 路由的处理函数。
 *
 * <p>返回值是下发给客户端的内部数据，通常是 PascalCase 属性名的 {@code Map}，由 HTTP 层
 * 按 {@code wire_models.json} 编码成 snake_case 字段；少数接口（例如
 * {@code /v1/common/get_header_announs}）在下发结构的最外层就是数组，所以返回类型是
 * {@code Object}。返回 {@code null} 视为空对象。
 */
@FunctionalInterface
public interface WireHandler {

    Object handle(WireRequest request);
}
