package com.linklike.server.web;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * {@code /v1} 路由里的固定路径集合 —— 对应 Python 版 {@code server.py} 顶部的常量。
 *
 * <p>这些集合决定「哪些路径可以用非 POST 方法调用」「哪些路径不带玩家响应头」。
 * 移植时保持与 Python 版逐字一致：多一个路径会让客户端用错方法时得到 200，
 * 少一个会让合法的 GET/PUT 调用被 405 拒绝。
 */
public final class RequestPaths {

    private RequestPaths() {
    }

    /** 请求体上限 1 MiB（Python 版 {@code _read_body} 的同一限制）。 */
    public static final int MAX_BODY_BYTES = 1024 * 1024;

    /**
     * 登录本身只需要资源版本对。客户端此时还没有 {@code CommonApiCache}，
     * 因此这个路径的成功响应不带玩家级响应头。
     */
    public static final Set<String> LOGIN_ONLY_HEADER_PATHS = Set.of("/v1/user/login");

    /**
     * 客户端用 GET + query string 调用的路径（上游抓包核实过），
     * query 会被解码成 handler 的请求体。
     */
    public static final Set<String> GET_QUERY_PATHS = Set.of(
            "/v1/webview/school_idol_connect_post/get_theme_list",
            "/v1/webview/gacha/get_detail",
            "/v1/withstation/withstation_info",
            "/v1/withstation/prize",
            "/v1/circle/get_chat_log_list");

    /** 客户端（生成的客户端代码）用 PUT + JSON body 调用的路径。 */
    public static final Set<String> PUT_JSON_PATHS = Set.of("/v1/user/push/device");

    /**
     * 设备上实际抓到的 snake_case 路径 -> 旧原型拼写。
     *
     * <p>Python 版特意保留这些显式别名，而不是做全局「去掉下划线」的宽松匹配，
     * 以免把未知路径误判成已实现接口。
     */
    public static final Map<String, String> ALIASES = aliases();

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

    /** 把请求路径规范化并套用别名。 */
    public static String canonical(String path) {
        String normalized = com.linklike.server.wire.WireRouter.normalize(path);
        return ALIASES.getOrDefault(normalized, normalized);
    }
}
