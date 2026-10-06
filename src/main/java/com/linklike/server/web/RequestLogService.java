package com.linklike.server.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linklike.server.config.ServerProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 每个请求写一份脱敏 JSON 日志 —— 移植自 Python 版 {@code server.Handler._log_request}。
 *
 * <p>日志用于对照真实客户端的请求行为排查问题，因此<b>保留协议结构</b>（字段名、嵌套形状、
 * 原始日期时间），只把认证材料替换成 {@code [REDACTED]}。
 *
 * <p>写日志失败不能影响请求结果，所以所有 IO 异常只记录、不上抛。
 */
@Service
public class RequestLogService {

    private static final Logger log = LoggerFactory.getLogger(RequestLogService.class);

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter RECORD_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 需要脱敏的键名，比较时先去掉 {@code -} 与 {@code _} 再转小写，
     * 因此 {@code Authorization}、{@code X-Api-Key}、{@code XApiKey} 都会命中。
     */
    private static final Set<String> SENSITIVE = Set.of(
            "authorization", "xapikey", "cookie", "setcookie", "sessiontoken",
            "idtoken", "devicespecificid", "pushdevicetoken", "password");

    private static final String REDACTED = "[REDACTED]";

    private final ServerProperties config;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    public RequestLogService(ServerProperties config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
    }

    /**
     * 写一条请求记录。
     *
     * @param bodyBytes 实际读取到的请求体字节数
     * @param routeImplemented 该路径是否有显式 handler（用于区分「未实现」与「实现里拒绝」）
     */
    public void log(String method, String path, Map<String, String> headers, Object body,
                    int bodyBytes, boolean routeImplemented, int status,
                    Map<String, String> responseHeaders, Object response) {
        if (!config.isLogRequests()) {
            return;
        }
        try {
            Path directory = Path.of(config.getLogDir());
            Files.createDirectories(directory);
            String fileName = FILE_STAMP.format(LocalDateTime.now()) + "-"
                    + HexFormat.of().formatHex(randomBytes(3)) + ".json";
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("time", RECORD_TIME.format(LocalDateTime.now()));
            record.put("method", method);
            record.put("path", path);
            record.put("headers", redact(headers));
            record.put("body", serialize(redact(body)));
            record.put("body_bytes", bodyBytes);
            record.put("route_implemented", routeImplemented);
            record.put("status", status);
            record.put("response_game_headers", responseHeaders == null ? Map.of() : responseHeaders);
            record.put("response", redact(response));
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(record);
            Files.writeString(directory.resolve(fileName), json, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            // 日志是辅助手段，失败不能改变请求结果。
            log.warn("写请求日志失败: {}", e.getMessage());
        }
    }

    /** 递归脱敏：认证材料换成 {@code [REDACTED]}，其余结构原样保留。 */
    @SuppressWarnings("unchecked")
    public Object redact(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                result.put(key, isSensitive(key) ? REDACTED : redact(entry.getValue()));
            }
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) {
                result.add(redact(item));
            }
            return result;
        }
        return value;
    }

    private static boolean isSensitive(String key) {
        String normalized = key.replace("-", "").replace("_", "").toLowerCase();
        return SENSITIVE.contains(normalized);
    }

    private String serialize(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException e) {
            return String.valueOf(value);
        }
    }

    private byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        random.nextBytes(bytes);
        return bytes;
    }
}
