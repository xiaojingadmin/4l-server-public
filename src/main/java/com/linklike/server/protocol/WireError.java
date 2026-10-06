package com.linklike.server.protocol;

import java.util.Map;

/** 业务校验失败的统一异常；由 HTTP 层转成官方形状的错误体。 */
public class WireError extends RuntimeException {

    private final int status;
    private final String errorCode;

    public WireError(int status, String message) {
        this(status, message, null);
    }

    public WireError(int status, String message, String errorCode) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public int status() {
        return status;
    }

    public String errorCode() {
        return errorCode;
    }

    public Map<String, Object> payload() {
        return ErrorCodes.payload(status, getMessage(), errorCode);
    }

    // ---- 常用构造 ----

    public static WireError badRequest(String message) {
        return new WireError(400, message);
    }

    public static WireError unauthorized(String message) {
        return new WireError(401, message);
    }

    public static WireError forbidden(String message) {
        return new WireError(403, message);
    }

    public static WireError notFound(String message) {
        return new WireError(404, message);
    }

    public static WireError conflict(String message) {
        return new WireError(409, message);
    }

    /** 本地没有可核实结算规则、宁可拒绝也不返回假成功的接口。 */
    public static WireError unimplemented() {
        return new WireError(501, "Local endpoint not implemented");
    }
}
