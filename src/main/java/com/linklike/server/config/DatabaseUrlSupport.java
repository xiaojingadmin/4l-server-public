package com.linklike.server.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Python 版格式数据库连接串（{@code mysql://user:pass@host:port/db}）到 JDBC 连接串的转换。
 *
 * <p>Python 版用 {@code private_server/config.json} 的 {@code database_url} 或环境变量
 * {@code LINKURA_DATABASE_URL} 提供连接串，格式是 SQLAlchemy 风格的
 * {@code mysql://root@127.0.0.1:3306/linkura_5_1_0}。为了能直接沿用原项目的配置，
 * Java 版同时接受这种写法和标准的 {@code jdbc:mariadb://...}。
 *
 * <p>连接串优先级（与 Python 版一致）：显式的 {@code spring.datasource.*} >
 * {@code LINKLIKE_DATABASE_URL} / {@code LINKURA_DATABASE_URL} > 代码默认值。
 */
public final class DatabaseUrlSupport {

    private DatabaseUrlSupport() {
    }

    /**
     * 拆分 {@code mysql://[user[:pass]@]host[:port]/db[?query]}。
     *
     * <p>用户名与密码都允许省略（{@code mysql://root@host/db}、{@code mysql://host/db}），
     * 也允许密码为空（{@code mysql://root:@host/db}）。密码里的 {@code @} 需要写成 {@code %40}，
     * 与 SQLAlchemy 的处理方式一致。
     */
    private static final Pattern PYTHON_URL = Pattern.compile(
            "mysql://"
                    + "(?:(?<user>[^:@/]*)(?::(?<pass>[^@/]*))?@)?"
                    + "(?<host>[^:/@?]+)"
                    + "(?::(?<port>\\d+))?"
                    + "/(?<database>[^?]+)"
                    + "(?:\\?(?<query>.*))?");

    /** 连接信息：JDBC URL 与可选的账号密码覆盖。 */
    public record Parsed(String jdbcUrl, String username, String password) {
    }

    public static boolean isPythonUrl(String url) {
        return url != null && url.startsWith("mysql://");
    }

    /** 把 Python 版连接串转成 JDBC 连接串；已经是 JDBC 连接串时原样返回。 */
    public static Parsed parse(String url) {
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("database url must not be empty");
        }
        if (!isPythonUrl(url)) {
            return new Parsed(url, null, null);
        }
        Matcher matcher = PYTHON_URL.matcher(url);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "database url must look like mysql://user:pass@host:port/database");
        }
        String host = matcher.group("host");
        String port = matcher.group("port");
        String database = matcher.group("database");
        // 连接参数按 MariaDB 驱动的要求用 & 分隔（Java 的 URL 解析要求转义单个 &）。
        Map<String, String> params = new LinkedHashMap<>();
        String query = matcher.group("query");
        if (query != null && !query.isEmpty()) {
            for (String pair : query.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int split = pair.indexOf('=');
                if (split < 0) {
                    params.put(pair, "");
                } else {
                    params.put(pair.substring(0, split), pair.substring(split + 1));
                }
            }
        }
        params.putIfAbsent("useUnicode", "true");
        params.putIfAbsent("characterEncoding", "utf8");

        StringBuilder jdbc = new StringBuilder("jdbc:mariadb://").append(host);
        if (port != null && !port.isEmpty()) {
            jdbc.append(':').append(port);
        }
        jdbc.append('/').append(database);
        jdbc.append('?');
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) {
                jdbc.append('&');
            }
            first = false;
            jdbc.append(entry.getKey());
            if (!entry.getValue().isEmpty()) {
                jdbc.append('=').append(entry.getValue());
            }
        }

        String username = matcher.group("user");
        String password = matcher.group("pass");
        return new Parsed(jdbc.toString(),
                username == null || username.isEmpty() ? null : username,
                password);
    }
}
