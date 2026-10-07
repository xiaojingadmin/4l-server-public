package com.linklike.server.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linklike.server.protocol.ErrorCodes;
import com.linklike.server.protocol.WireError;
import com.linklike.server.service.PlayerContext;
import com.linklike.server.service.ResourceVersionService;
import com.linklike.server.wire.WireRouter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * 真实客户端的 {@code /v1/...} 入口 —— 对应 Python 版 {@code server.Handler._handle} 里
 * 处理 {@code /v1/} 的那一段。
 *
 * <p>传输层行为逐条对齐 Python 版：
 * <ul>
 *   <li>只接受 POST；{@code GET_QUERY_PATHS} 允许 GET，{@code PUT_JSON_PATHS} 允许 PUT，
 *       否则 405 {@code POST required}；
 *   <li>{@code Transfer-Encoding} 一律 501（本地不实现分块上传）；
 *   <li>请求体上限 1 MiB，必须是 UTF-8 JSON 对象；
 *   <li>成功时按状态码决定是否附带游戏响应头：只有 2xx 才带，错误响应不带；
 *   <li>无论成败都在最后写一份脱敏请求日志。
 * </ul>
 */
@RestController
public class WireController {

    private static final Logger log = LoggerFactory.getLogger(WireController.class);

    private final WireGateway gateway;
    private final GameHeaders gameHeaders;
    private final ResourceVersionService resourceVersions;
    private final RequestLogService requestLog;
    private final ObjectMapper mapper;

    public WireController(WireGateway gateway, GameHeaders gameHeaders,
                          ResourceVersionService resourceVersions, RequestLogService requestLog,
                          ObjectMapper mapper) {
        this.gateway = gateway;
        this.gameHeaders = gameHeaders;
        this.resourceVersions = resourceVersions;
        this.requestLog = requestLog;
        this.mapper = mapper;
    }

    @RequestMapping(value = "/v1/**",
            method = {RequestMethod.POST, RequestMethod.GET, RequestMethod.PUT,
                    RequestMethod.PATCH, RequestMethod.DELETE})
    public ResponseEntity<Map<String, Object>> handle(HttpServletRequest http) {
        Map<String, String> headers = lowercaseHeaders(http);
        String method = http.getMethod();
        String rawPath = http.getRequestURI();
        String path = WireRouter.normalize(rawPath);

        int bodyBytes = 0;
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> response = new LinkedHashMap<>();
        Map<String, String> responseHeaders = new LinkedHashMap<>();
        int status = 200;
        // Python 在方法检查之前就记录该路径是否有 handler，错误响应里也保留这个信息。
        boolean routeImplemented = gateway.supports(path);

        try {
            // Python 用的是真值判断（`if self.headers.get("Transfer-Encoding")`），
            // 所以值为空串时按「没有这个头」处理。
            String transferEncoding = http.getHeader("Transfer-Encoding");
            if (transferEncoding != null && !transferEncoding.isEmpty()) {
                status = 501;
                response = ErrorCodes.payload(501, "Transfer-Encoding is not supported", null);
            } else {
                // 先读请求体、再判方法：Python 的 _read_body 在方法检查之前，所以
                // 「非 POST + 坏的请求体」是 400 而不是 405。
                BodyRead read = readBody(http);
                bodyBytes = read.bodyBytes();
                body = read.body();
                if (!methodAllowed(method, path)) {
                    status = 405;
                    response = ErrorCodes.payload(405, "POST required", null);
                } else {
                    // 少量官方接口用 GET + query string，参数在 query 里而不是请求体里。
                    if ("GET".equals(method) && body.isEmpty()) {
                        body = decodeQuery(http.getQueryString());
                    }
                    WireGateway.Result result = gateway.dispatch(path, body, headers);
                    response = result.body();
                    responseHeaders = successHeaders(result.player(), path);
                }
            }
        } catch (WireError e) {
            status = e.status();
            response = e.payload();
        } catch (RuntimeException e) {
            status = 500;
            response = ErrorCodes.payload(500, String.valueOf(e.getMessage()), null);
            log.error("处理 {} {} 失败", method, rawPath, e);
        } finally {
            requestLog.log(method, rawPath, headers, body, bodyBytes, routeImplemented, status,
                    responseHeaders, response);
        }

        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(new MediaType("application", "json", java.nio.charset.StandardCharsets.UTF_8));
        responseHeaders.forEach(httpHeaders::set);
        return ResponseEntity.status(status).headers(httpHeaders).body(response);
    }

    // ------------------------------------------------------------------
    // 传输层
    // ------------------------------------------------------------------

    private static boolean methodAllowed(String method, String path) {
        if ("POST".equals(method)) {
            return true;
        }
        if ("GET".equals(method)) {
            return RequestPaths.GET_QUERY_PATHS.contains(path);
        }
        if ("PUT".equals(method)) {
            return RequestPaths.PUT_JSON_PATHS.contains(path);
        }
        return false;
    }

    /** 成功响应头：会话级（资源版本、服务器时间）加玩家级；登录不下发玩家级。 */
    private Map<String, String> successHeaders(PlayerContext player, String path) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.putAll(gameHeaders.sessionHeaders(resourceVersions.currentSignature()));
        if (!RequestPaths.LOGIN_ONLY_HEADER_PATHS.contains(path) && player != null) {
            headers.putAll(gameHeaders.playerHeaders(player));
        }
        return headers;
    }

    /** 读取并校验请求体；与 Python 的 {@code _read_body} 返回同一种失败。 */
    @SuppressWarnings("unchecked")
    private BodyRead readBody(HttpServletRequest http) {
        int declared = http.getContentLength();
        if (declared > RequestPaths.MAX_BODY_BYTES) {
            throw WireError.badRequest("Request body exceeds 1 MiB");
        }
        byte[] raw;
        try (InputStream in = http.getInputStream()) {
            raw = in.readNBytes(RequestPaths.MAX_BODY_BYTES + 1);
        } catch (IOException e) {
            // 读取中断/被截断与 Python 的「Incomplete request body」同类，都按 400 处理。
            throw WireError.badRequest("Incomplete request body");
        }
        if (raw.length > RequestPaths.MAX_BODY_BYTES) {
            throw WireError.badRequest("Request body exceeds 1 MiB");
        }
        if (raw.length == 0) {
            return new BodyRead(new LinkedHashMap<>(), 0);
        }
        if (declared > 0 && raw.length != declared) {
            throw WireError.badRequest("Incomplete request body");
        }
        Object parsed;
        try {
            parsed = mapper.readValue(raw, Object.class);
        } catch (IOException e) {
            throw WireError.badRequest("Request body must be valid UTF-8 JSON");
        }
        if (!(parsed instanceof Map)) {
            throw WireError.badRequest("Request body must be a JSON object");
        }
        return new BodyRead((Map<String, Object>) parsed, raw.length);
    }

    /**
     * 把 {@code ?a=1&b=false} 解码成 {@code {a: 1, b: false}}；
     * 只有 {@code true}/{@code false} 与看起来像整数的值会被转换，其余保持字符串。
     */
    static Map<String, Object> decodeQuery(String query) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) {
            return body;
        }
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int split = pair.indexOf('=');
            String rawKey = split < 0 ? pair : pair.substring(0, split);
            String rawValue = split < 0 ? "" : pair.substring(split + 1);
            String key = urlDecode(rawKey);
            String value = urlDecode(rawValue);
            body.put(key, convertQueryValue(value));
        }
        return body;
    }

    private static Object convertQueryValue(String value) {
        if ("true".equals(value) || "false".equals(value)) {
            return Boolean.valueOf(value);
        }
        String digits = value.startsWith("-") ? value.substring(1) : value;
        if (!digits.isEmpty() && digits.chars().allMatch(Character::isDigit)) {
            try {
                return Integer.valueOf(value);
            } catch (NumberFormatException ignored) {
                return value;
            }
        }
        return value;
    }

    private static String urlDecode(String value) {
        return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Map<String, String> lowercaseHeaders(HttpServletRequest http) {
        Map<String, String> headers = new LinkedHashMap<>();
        java.util.Enumeration<String> names = http.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            String key = name.toLowerCase(java.util.Locale.ROOT);
            if (!headers.containsKey(key)) {
                headers.put(key, http.getHeader(name));
            }
        }
        return headers;
    }

    private record BodyRead(Map<String, Object> body, int bodyBytes) {
    }
}
