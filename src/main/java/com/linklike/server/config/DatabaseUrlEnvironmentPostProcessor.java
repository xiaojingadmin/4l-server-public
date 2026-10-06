package com.linklike.server.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * 让 {@code LINKLIKE_DATABASE_URL} / {@code LINKURA_DATABASE_URL} 既接受 JDBC 连接串，
 * 也接受 Python 版的 {@code mysql://user:pass@host:port/db} 写法。
 *
 * <p>Python 版的项目用 {@code --database-url} / {@code config.json} / 环境变量配置连接串，
 * 为了能直接沿用这些配置，这里在环境准备阶段就把 {@code mysql://} 形式转换好，
 * 并以最高优先级注入 {@code spring.datasource.*}。
 *
 * <p>已经显式给出账号（{@code LINKLIKE_DATABASE_USER}）或密码
 * （{@code LINKLIKE_DATABASE_PASSWORD}）时不覆盖它们，连接串里只提供其余部分。
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROPERTY_SOURCE_NAME = "linklike-database-url";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String raw = firstNonBlank(
                environment.getProperty("LINKLIKE_DATABASE_URL"),
                environment.getProperty("LINKURA_DATABASE_URL"));
        if (raw == null) {
            return;
        }

        DatabaseUrlSupport.Parsed parsed;
        try {
            parsed = DatabaseUrlSupport.parse(raw);
        } catch (IllegalArgumentException e) {
            // 明确失败，不要带着可疑连接串启动到一半再报错。
            throw new IllegalStateException("数据库连接串无效: " + e.getMessage(), e);
        }

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("spring.datasource.url", parsed.jdbcUrl());
        if (parsed.username() != null
                && isBlank(environment.getProperty("LINKLIKE_DATABASE_USER"))) {
            values.put("spring.datasource.username", parsed.username());
        }
        if (parsed.password() != null
                && isBlank(environment.getProperty("LINKLIKE_DATABASE_PASSWORD"))) {
            values.put("spring.datasource.password", parsed.password());
        }
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, values));
    }

    @Override
    public int getOrder() {
        // 早于 Spring Boot 的数据源自动配置，晚于 ConfigDataEnvironmentPostProcessor。
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (!isBlank(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
