package com.linklike.server.wire;

import com.linklike.server.protocol.WireError;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code /v1/...} 路由表。
 *
 * <p>路径在注册与查找时统一小写并去掉尾部斜杠，与 Python 版
 * {@code path.rstrip('/').lower()} 的匹配方式一致。未注册的路径返回 501，
 * 不伪装成成功。
 */
@Component
public class WireRouter {

    private static final Logger log = LoggerFactory.getLogger(WireRouter.class);

    private final Map<String, WireHandler> routes = new LinkedHashMap<>();

    public WireRouter(List<WireModule> modules) {
        for (WireModule module : modules) {
            module.register(this);
        }
        log.info("已注册 {} 条 /v1 路由", routes.size());
    }

    public static String normalize(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.trim().toLowerCase(Locale.ROOT);
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    public WireRouter register(String path, WireHandler handler) {
        String key = normalize(path);
        if (routes.putIfAbsent(key, handler) != null) {
            throw new IllegalStateException("路由重复注册: " + key);
        }
        return this;
    }

    /** 同一处理函数挂多个路径（含别名与 GET 变体）。 */
    public WireRouter registerAll(Collection<String> paths, WireHandler handler) {
        for (String path : paths) {
            register(path, handler);
        }
        return this;
    }

    public WireHandler resolve(String path) {
        return routes.get(normalize(path));
    }

    public boolean supports(String path) {
        return routes.containsKey(normalize(path));
    }

    /** 显式 501 的写接口：有 handler，但 handler 直接拒绝。 */
    public WireRouter registerUnimplemented(Collection<String> paths) {
        return registerAll(paths, request -> {
            throw WireError.unimplemented();
        });
    }

    public Set<String> paths() {
        return Collections.unmodifiableSet(routes.keySet());
    }

    public int size() {
        return routes.size();
    }
}
