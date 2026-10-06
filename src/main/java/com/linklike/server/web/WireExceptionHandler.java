package com.linklike.server.web;

import com.linklike.server.protocol.ErrorCodes;
import com.linklike.server.protocol.WireError;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 把业务异常转成官方 {@code SchoolException} 形状的错误体。
 *
 * <p>{@code WireController} 自己已经处理了 {@code /v1} 的异常（因为要在 {@code finally}
 * 里写请求日志），这个 advice 是兜底：其它端点或过滤链抛出的异常也返回同一种扁平结构，
 * 而不是 Spring 默认的 {@code /error} JSON —— 客户端只认前者。
 */
@RestControllerAdvice
public class WireExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(WireExceptionHandler.class);

    @ExceptionHandler(WireError.class)
    public ResponseEntity<Map<String, Object>> handleWireError(WireError error) {
        return ResponseEntity.status(error.status()).body(error.payload());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception error) {
        log.error("未预期的服务端错误", error);
        Map<String, Object> payload = ErrorCodes.payload(500,
                error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(), null);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(payload);
    }
}
