package com.linklike.server.wire;

import com.linklike.server.protocol.WireError;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * {@code path.rstrip('/').lower()} 的匹配方式一致，再套用 {@link #ALIASES} 里的显式别名
 * （对应 Python {@code wire_api.ALIASES}）。未注册的路径返回 501，不伪装成成功。
 *
 * <p>构建顺序：先让所有 {@link WireModule} 注册自己实现的路由，再把
 * {@link RouteSnapshot} 里「Python 版支持但 Java 版还没移植」的路径显式挂成 501。
 * 这样请求日志里的 {@code route_implemented} 与 Python 版一致 —— 客户端看到的行为
 * 同样是 501，但排查时能区分「没实现」和「路径不存在」。
 */
@Component
public class WireRouter {

    private static final Logger log = LoggerFactory.getLogger(WireRouter.class);

    /**
     * 设备上实际抓到的 snake_case 路径 -> 旧原型拼写。
     *
     * <p>Python 版特意保留这些显式别名，而不是做全局「去掉下划线」的宽松匹配，
     * 以免把未知路径误判成已实现接口。
     */
    public static final Map<String, String> ALIASES = aliases();

    private final Map<String, WireHandler> routes = new LinkedHashMap<>();

    public WireRouter(List<WireModule> modules, RouteSnapshot snapshot) {
        for (WireModule module : modules) {
            module.register(this);
        }
        int implemented = routes.size();
        int pending = registerPending(snapshot);
        log.info("已注册 {} 条 /v1 路由（已实现 {}，显式 501 {}），Python 版支持 {} 条",
                routes.size(), implemented, pending, snapshot.paths().size());
        verifyCoverage(snapshot);
    }

    private static Map<String, String> aliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("/v1/register/get_terms", "/v1/register/getterms");
        aliases.put("/v1/register/approve_terms", "/v1/register/approveterms");
        aliases.put("/v1/register/set_approve_terms", "/v1/register/setapproveterms");
        aliases.put("/v1/register/set_new_user", "/v1/register/setnewuser");
        aliases.put("/v1/register/set_user_data", "/v1/register/setuserdata");
        aliases.put("/v1/home/get_home", "/v1/home/gethome");
        return Map.copyOf(aliases);
    }

    /** 规范化：小写 + 去掉尾部斜杠（对应 Python 的 {@code path.rstrip('/').lower()}）。 */
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

    /** 规范化并套用别名，得到路由表的键。 */
    public static String canonical(String path) {
        String normalized = normalize(path);
        return ALIASES.getOrDefault(normalized, normalized);
    }

    public WireRouter register(String path, WireHandler handler) {
        String key = canonical(path);
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
        return routes.get(canonical(path));
    }

    public boolean supports(String path) {
        return routes.containsKey(canonical(path));
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

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 把快照里还没注册的路径挂成显式 501，返回新挂上的条数。 */
    private int registerPending(RouteSnapshot snapshot) {
        int added = 0;
        for (String path : snapshot.paths()) {
            // 与 register 一致地套用别名，否则别名的两种拼写会在表里各占一条、其中一条永远
            // 命中不到，启动日志里的条数也会虚高。
            if (routes.putIfAbsent(canonical(path), request -> {
                throw WireError.unimplemented();
            }) == null) {
                added++;
            }
        }
        return added;
    }

    /**
     * 反向校验：Java 注册了、但 Python 的 {@code SUPPORTED} 里没有的路径，属于移植时写错
     * 路径名（客户端下发的 {@code route_implemented} 会与 Python 版不一致），启动时就说出来。
     */
    private void verifyCoverage(RouteSnapshot snapshot) {
        Set<String> unknown = new LinkedHashSet<>(routes.keySet());
        unknown.removeAll(snapshot.paths());
        if (!unknown.isEmpty()) {
            log.warn("以下 {} 条路由不在 Python 版 SUPPORTED 里，可能是路径写错：{}",
                    unknown.size(), unknown);
        }
    }
}
