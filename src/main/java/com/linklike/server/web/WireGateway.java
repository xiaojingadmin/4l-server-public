package com.linklike.server.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linklike.server.config.ServerProperties;
import com.linklike.server.protocol.WireError;
import com.linklike.server.service.PlayerContext;
import com.linklike.server.service.PlayerStore;
import com.linklike.server.wire.WireHandler;
import com.linklike.server.wire.WireRequest;
import com.linklike.server.wire.WireRouter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code /v1} 请求的分发入口 —— 对应 Python 版 {@code wire_api.dispatch}。
 *
 * <p>职责与 Python 版一致：规范化路径、套用别名、校验 {@code X-Api-Key}、用 Bearer 装载玩家、
 * 查表找到 handler、在<b>一个事务里</b>执行。
 *
 * <p><b>落库由 handler 自己负责</b>（对应 Python 里各分支显式调用 {@code store.save_player(player)}）。
 * 这里不做自动保存，原因有两个：一是与 Python 版逐行对应，便于核对行为；二是登录 / 注册
 * 这类「本次请求刚创建账号」的分支里，新建的 {@link PlayerContext} 并不是本方法装载的那个，
 * 自动保存反而会漏掉它。
 */
@Service
public class WireGateway {

    private final WireRouter router;
    private final PlayerStore store;
    private final ObjectMapper mapper;
    private final ServerProperties config;

    public WireGateway(WireRouter router, PlayerStore store, ObjectMapper mapper,
                       ServerProperties config) {
        this.router = router;
        this.store = store;
        this.mapper = mapper;
        this.config = config;
    }

    /** 一次分发的产物：下发数据与本次装载的玩家（未认证时为 null）。 */
    public record Result(Map<String, Object> body, PlayerContext player) {
    }

    /** 该路径是否有显式 handler（含显式返回 501 的写接口）。 */
    public boolean supports(String path) {
        return router.supports(RequestPaths.canonical(path));
    }

    @Transactional
    public Result dispatch(String path, Map<String, Object> body, Map<String, String> headers) {
        String canonical = RequestPaths.canonical(path);

        String expected = config.getApiKey();
        if (expected != null && !"off".equals(expected)
                && !expected.equals(headers.get("x-api-key"))) {
            throw new WireError(401, "Invalid API key");
        }

        WireHandler handler = router.resolve(canonical);
        if (handler == null) {
            throw WireError.unimplemented();
        }

        PlayerContext player = loadPlayer(headers);
        WireRequest request = new WireRequest(canonical, body, headers, player, store, mapper, config);
        Map<String, Object> result = handler.handle(request);
        return new Result(result == null ? new LinkedHashMap<>() : result, player);
    }

    private PlayerContext loadPlayer(Map<String, String> headers) {
        String token = bearerToken(headers.get("authorization"));
        return token == null ? null : store.loadByToken(token);
    }

    /** 从 {@code Authorization: Bearer <token>} 里取出令牌；不匹配时返回 null。 */
    public static String bearerToken(String authorization) {
        if (authorization == null
                || !authorization.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
            return null;
        }
        String token = authorization.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    /** 已注册的路径数量，启动日志用。 */
    public int routeCount() {
        return router.size();
    }
}
